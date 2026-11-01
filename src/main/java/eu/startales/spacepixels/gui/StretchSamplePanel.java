/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.detection.TrackOverlay;
import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.DisplayImageRenderer;
import eu.startales.spacepixels.fits.DisplayStretch;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.session.ProcessingSession;
import io.github.ppissias.jtransient.core.DataArea;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.HierarchyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * The preview under Auto Stretch Black and White Sigma on the Report Visualization page: a few crops stretched with
 * the values on the page, each with the share of its pixels clipped to black and to white. After a detection run the
 * crops are around its first tracks, as the report shows them; before one they are picked from the middle frame of the
 * session: a quiet patch of sky, a busy star field and the brightest star. The crops are read on a worker once per
 * session and run, and only while the page is on screen; a change of the two values stretches them again on the EDT.
 */
final class StretchSamplePanel extends JPanel {

    /** The side of a crop in frame pixels, as the report's object-centred track crops. */
    static final int CROP_SIZE = 200;
    /** The most crops shown. */
    static final int MAX_SAMPLES = 3;
    /** How far from a peak its surroundings are taken, in frame pixels. */
    private static final int SURROUNDING_DISTANCE = 4;

    private static final String APPLIES_TO = " The two settings stretch the report's cutouts and animations, its"
            + " median-stack pictures, a run's live view and the Star Mask Explorer; whole-field pictures from the"
            + " enhanced stack have their own asinh stretch. Under each crop: the share of its pixels drawn black and"
            + " drawn white.";

    /** One crop and its title. */
    record Sample(String title, short[][] pixels) {
    }

    /** The crops of one source and the sentence that names it. */
    record Samples(String source, List<Sample> crops) {
    }

    /** The shares (0 to 1) of a crop's valid pixels at or below the black point and at or above the white point. */
    record Clipping(double black, double white) {
    }

    /** What the crops were read from: the session and the tracks of its last run, both compared by identity. */
    private record Source(ProcessingSession session, TrackOverlay tracks) {
    }

    private final SessionAccess sessions;
    private final WrappingHelpLabel caption = new WrappingHelpLabel(" ", 600);
    private final WrappingControlsPanel tilePanel = new WrappingControlsPanel(UiStyle.space(12), UiStyle.space(8));
    private final List<Tile> tiles = new ArrayList<>();
    private List<Sample> samples = List.of();
    private double blackSigma;
    private double whiteSigma;
    private Source requested;
    private SwingWorker<Samples, Void> worker;

    StretchSamplePanel(SessionAccess sessions, double blackSigma, double whiteSigma) {
        this.sessions = sessions;
        this.blackSigma = blackSigma;
        this.whiteSigma = whiteSigma;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(LEFT_ALIGNMENT);
        setBorder(new EmptyBorder(UiStyle.space(4), 0, UiStyle.space(12), 0));
        tilePanel.setAlignmentX(LEFT_ALIGNMENT);
        add(caption);
        add(Box.createVerticalStrut(UiStyle.space(6)));
        add(tilePanel);
        showCaption("Import frames to see these two settings on them here.");
        // Frames are read only for a page on screen, and again when the session or its last run has changed since.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                load();
            }
        });
    }

    /** Stretches the crops with these values. */
    void setSigmas(double black, double white) {
        blackSigma = black;
        whiteSigma = white;
        render();
    }

    /** The session or its last run changed: drops the crops, and reads new ones while the page is on screen. */
    void sourceChanged() {
        requested = null;
        if (worker != null) {
            worker.cancel(true);
            worker = null;
        }
        showSamples(List.of());
        showCaption(" ");
        if (isShowing()) {
            load();
        }
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    private void load() {
        ProcessingSession session = sessions.current();
        Source source = session == null ? null : new Source(session, session.getTrackOverlay());
        if (source != null && source.equals(requested)) {
            return;
        }
        requested = source;
        if (worker != null) {
            worker.cancel(true);
            worker = null;
        }
        showSamples(List.of());
        if (source == null) {
            showCaption("Import frames to see these two settings on them here.");
            return;
        }
        FitsFileInformation[] files = session.getFiles();
        TrackOverlay tracks = source.tracks();
        showCaption("Reading crops for the preview…");
        SwingWorker<Samples, Void> started = new SwingWorker<>() {
            @Override
            protected Samples doInBackground() throws Exception {
                Samples found = tracks == null ? null : trackSamples(files, tracks);
                return found != null ? found : frameSamples(files);
            }

            @Override
            protected void done() {
                if (worker != this) {
                    return;
                }
                worker = null;
                try {
                    Samples result = get();
                    showCaption(result.source() + APPLIES_TO);
                    showSamples(result.crops());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    showCaption("The preview could not read the frames: " + cause.getMessage());
                }
            }
        };
        worker = started;
        started.execute();
    }

    private void showCaption(String text) {
        caption.setText("<html>" + text + "</html>");
        revalidate();
    }

    private void showSamples(List<Sample> crops) {
        samples = crops;
        tiles.clear();
        tilePanel.removeAll();
        for (Sample sample : crops) {
            Tile tile = new Tile(sample.title());
            tiles.add(tile);
            tilePanel.add(tile);
        }
        render();
        tilePanel.revalidate();
        revalidate();
        repaint();
    }

    private void render() {
        for (int i = 0; i < tiles.size(); i++) {
            short[][] pixels = samples.get(i).pixels();
            double[] levels = DisplayImageRenderer.displayLevels(pixels, blackSigma, whiteSigma);
            tiles.get(i).show(DisplayImageRenderer.createDisplayImageAtLevels(pixels, levels[0], levels[1]),
                    clipping(pixels, levels[0], levels[1]));
        }
    }

    /**
     * Crops around the first tracks of the last run, one per track, in the order and with the names of the report.
     * Each crop is in one of its track's frames, a frame already chosen for an earlier track where it can, so that few
     * frames are read. Null when no track has a position in the session's frames.
     */
    static Samples trackSamples(FitsFileInformation[] files, TrackOverlay overlay) throws IOException {
        Map<String, Integer> frameByName = new HashMap<>();
        for (int i = 0; i < files.length; i++) {
            frameByName.put(new File(files[i].getFilePath()).getName(), i);
        }
        List<String> labels = new ArrayList<>();
        List<TrackOverlay.Point> points = new ArrayList<>();
        Map<String, List<Integer>> cropsByFrame = new LinkedHashMap<>();
        for (TrackOverlay.Track track : overlay.getTracks()) {
            if (points.size() == MAX_SAMPLES) {
                break;
            }
            List<TrackOverlay.Point> known = new ArrayList<>();
            for (TrackOverlay.Point point : track.points) {
                if (frameByName.containsKey(point.fileName)) {
                    known.add(point);
                }
            }
            if (known.isEmpty()) {
                continue;
            }
            TrackOverlay.Point chosen = known.get(known.size() / 2);
            for (TrackOverlay.Point point : known) {
                if (cropsByFrame.containsKey(point.fileName)) {
                    chosen = point;
                    break;
                }
            }
            cropsByFrame.computeIfAbsent(chosen.fileName, name -> new ArrayList<>()).add(points.size());
            labels.add(track.label);
            points.add(chosen);
        }
        if (points.isEmpty()) {
            return null;
        }
        Sample[] crops = new Sample[points.size()];
        for (Map.Entry<String, List<Integer>> frame : cropsByFrame.entrySet()) {
            int frameIndex = frameByName.get(frame.getKey());
            BlinkSequence.Level full = BlinkSequence.load(files[frameIndex]).getLevel(0);
            for (int crop : frame.getValue()) {
                TrackOverlay.Point point = points.get(crop);
                crops[crop] = new Sample(labels.get(crop) + " · frame " + (frameIndex + 1),
                        crop(full.channels[0], full.width, full.height,
                                (int) Math.round(point.x), (int) Math.round(point.y), CROP_SIZE));
            }
        }
        return new Samples("Crops around " + joined(labels) + " of the last detection run.", List.of(crops));
    }

    /** Crops picked from the middle frame of the session, before a detection run. */
    static Samples frameSamples(FitsFileInformation[] files) throws IOException {
        if (files.length == 0) {
            return new Samples("The session has no frames.", List.of());
        }
        int frameIndex = files.length / 2;
        BlinkSequence.Level full = BlinkSequence.load(files[frameIndex]).getLevel(0);
        return new Samples("Crops of frame " + (frameIndex + 1) + " (" + files[frameIndex].getFileName()
                + "); after a detection run, crops around its tracks.",
                picks(full.channels[0], full.width, full.height));
    }

    /**
     * Crops of a frame that show both ends of the stretch: a quiet patch of sky, a busy star field and the brightest
     * star. The frame is cut into crop-sized tiles, leaving out those with registration fill. A tile's stars are its
     * local peaks that stand five sky noises above their surroundings (the noise from the darker half of the pixels,
     * which stars do not reach), so the smooth halo of a galaxy or a bright star does not count as a star field. The
     * brightest star's tile has the most pixels at or above the 99.99th percentile (the maximum, when stars saturate),
     * so a single hot pixel does not win it.
     */
    static List<Sample> picks(short[] data, int width, int height) {
        int[] histogram = DisplayStretch.histogram(data);
        int firstValid = DataArea.FILL_THRESHOLD - Short.MIN_VALUE + 1;
        long valid = 0;
        for (int value = firstValid; value < histogram.length; value++) {
            valid += histogram[value];
        }
        if (valid == 0) {
            return List.of();
        }
        int sky = percentile(histogram, firstValid, valid, 0.5);
        int noise = Math.max(1, sky - percentile(histogram, firstValid, valid, 0.1587));
        int starLevel = sky + 5 * noise;
        int brightLevel = percentile(histogram, firstValid, valid, 0.9999);
        int tileWidth = Math.min(CROP_SIZE, width);
        int tileHeight = Math.min(CROP_SIZE, height);
        int columns = width / tileWidth;
        int rows = height / tileHeight;
        List<int[]> candidates = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int stars = 0;
                int bright = 0;
                boolean filled = false;
                for (int y = row * tileHeight; y < (row + 1) * tileHeight && !filled; y++) {
                    int offset = y * width;
                    for (int x = column * tileWidth; x < (column + 1) * tileWidth; x++) {
                        short raw = data[offset + x];
                        if (raw <= DataArea.FILL_THRESHOLD) {
                            filled = true;
                            break;
                        }
                        int value = raw - Short.MIN_VALUE;
                        if (value >= starLevel && isPeak(data, width, height, x, y)
                                && value - surroundings(data, width, height, x, y) >= 5 * noise) {
                            stars++;
                        }
                        if (value >= brightLevel) {
                            bright++;
                        }
                    }
                }
                if (!filled) {
                    candidates.add(new int[]{column, row, stars, bright});
                }
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        int[] brightest = candidates.get(0);
        for (int[] tile : candidates) {
            if (tile[3] > brightest[3] || (tile[3] == brightest[3] && tile[2] > brightest[2])) {
                brightest = tile;
            }
        }
        int[] busiest = null;
        int[] quietest = null;
        for (int[] tile : candidates) {
            if (tile == brightest) {
                continue;
            }
            if (busiest == null || tile[2] > busiest[2]) {
                busiest = tile;
            }
        }
        for (int[] tile : candidates) {
            if (tile == brightest || tile == busiest) {
                continue;
            }
            if (quietest == null || tile[2] < quietest[2]) {
                quietest = tile;
            }
        }
        List<Sample> picked = new ArrayList<>();
        if (quietest != null) {
            picked.add(tileSample("Quiet sky", quietest, data, width, tileWidth, tileHeight));
        }
        if (busiest != null) {
            picked.add(tileSample("Busy star field", busiest, data, width, tileWidth, tileHeight));
        }
        picked.add(tileSample("Brightest star", brightest, data, width, tileWidth, tileHeight));
        return picked;
    }

    /**
     * Whether the pixel is a local peak: above its neighbours before it (the row above and its left) and at least its
     * neighbours after it, so a flat top, such as a saturated star, counts once.
     */
    private static boolean isPeak(short[] data, int width, int height, int x, int y) {
        short value = data[y * width + x];
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx;
                int ny = y + dy;
                if ((dx == 0 && dy == 0) || nx < 0 || ny < 0 || nx >= width || ny >= height) {
                    continue;
                }
                short neighbour = data[ny * width + nx];
                boolean before = dy < 0 || (dy == 0 && dx < 0);
                if (before ? neighbour >= value : neighbour > value) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The mean of the eight pixels four pixels away from (x, y) along the rows, columns and diagonals, in unsigned ADU. */
    private static double surroundings(short[] data, int width, int height, int x, int y) {
        double sum = 0;
        for (int dy = -SURROUNDING_DISTANCE; dy <= SURROUNDING_DISTANCE; dy += SURROUNDING_DISTANCE) {
            for (int dx = -SURROUNDING_DISTANCE; dx <= SURROUNDING_DISTANCE; dx += SURROUNDING_DISTANCE) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                int nx = Math.max(0, Math.min(width - 1, x + dx));
                int ny = Math.max(0, Math.min(height - 1, y + dy));
                sum += data[ny * width + nx] - Short.MIN_VALUE;
            }
        }
        return sum / 8;
    }

    private static Sample tileSample(String title, int[] tile, short[] data, int width, int tileWidth, int tileHeight) {
        short[][] pixels = new short[tileHeight][tileWidth];
        for (int y = 0; y < tileHeight; y++) {
            System.arraycopy(data, (tile[1] * tileHeight + y) * width + tile[0] * tileWidth, pixels[y], 0, tileWidth);
        }
        return new Sample(title, pixels);
    }

    /** The smallest unsigned value at or above the {@code fraction} quantile of the valid pixels. */
    private static int percentile(int[] histogram, int firstValid, long valid, double fraction) {
        long target = (long) Math.ceil(fraction * valid);
        long running = 0;
        for (int value = firstValid; value < histogram.length; value++) {
            running += histogram[value];
            if (running >= target) {
                return value;
            }
        }
        return histogram.length - 1;
    }

    /**
     * A crop of {@code size} × {@code size} frame pixels centred on ({@code centerX}, {@code centerY}) and moved inside
     * the frame; smaller only along a side where the frame itself is.
     */
    static short[][] crop(short[] data, int width, int height, int centerX, int centerY, int size) {
        int cropWidth = Math.min(size, width);
        int cropHeight = Math.min(size, height);
        int startX = Math.max(0, Math.min(width - cropWidth, centerX - cropWidth / 2));
        int startY = Math.max(0, Math.min(height - cropHeight, centerY - cropHeight / 2));
        short[][] pixels = new short[cropHeight][cropWidth];
        for (int y = 0; y < cropHeight; y++) {
            System.arraycopy(data, (startY + y) * width + startX, pixels[y], 0, cropWidth);
        }
        return pixels;
    }

    /** The shares of the valid pixels of {@code crop} at or below {@code black} and at or above {@code white}, in unsigned ADU. */
    static Clipping clipping(short[][] crop, double black, double white) {
        long valid = 0;
        long dark = 0;
        long bright = 0;
        for (short[] row : crop) {
            for (short raw : row) {
                if (raw <= DataArea.FILL_THRESHOLD) {
                    continue;
                }
                int value = raw - Short.MIN_VALUE;
                valid++;
                if (value <= black) {
                    dark++;
                }
                if (value >= white) {
                    bright++;
                }
            }
        }
        return valid == 0 ? new Clipping(0, 0) : new Clipping((double) dark / valid, (double) bright / valid);
    }

    /** "T1", "T1 and ST1", "T1, T2 and ST1". */
    private static String joined(List<String> labels) {
        if (labels.size() == 1) {
            return labels.get(0);
        }
        return String.join(", ", labels.subList(0, labels.size() - 1)) + " and " + labels.get(labels.size() - 1);
    }

    /** One crop at one screen pixel per frame pixel (scaled with the interface size), its title and its clipping. */
    private static final class Tile extends JPanel {
        private final JLabel clipping = new JLabel(" ");
        private BufferedImage image;
        private final JComponent view = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(Color.BLACK);
                g2.fillRect(0, 0, getWidth(), getHeight());
                if (image != null) {
                    // Nearest neighbour, so the noise looks as it does in the report rather than smoothed.
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g2.drawImage(image, 0, 0, getWidth(), getHeight(), null);
                }
                g2.dispose();
            }
        };

        Tile(String title) {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            JLabel titleLabel = new JLabel(title);
            clipping.setForeground(UiStyle.helpColor());
            clipping.setToolTipText("Pixels at or below the black point are drawn black; at or above the white point, white.");
            for (JComponent part : new JComponent[]{view, titleLabel, clipping}) {
                part.setAlignmentX(LEFT_ALIGNMENT);
            }
            add(view);
            add(Box.createVerticalStrut(UiStyle.space(4)));
            add(titleLabel);
            add(clipping);
        }

        void show(BufferedImage stretched, Clipping clipped) {
            image = stretched;
            Dimension size = new Dimension(UiStyle.space(stretched.getWidth()), UiStyle.space(stretched.getHeight()));
            view.setPreferredSize(size);
            view.setMinimumSize(size);
            view.setMaximumSize(size);
            clipping.setText(String.format(Locale.ROOT, "black %.1f%% · white %.1f%%",
                    100 * clipped.black(), 100 * clipped.white()));
            view.revalidate();
            view.repaint();
        }
    }
}
