/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util.skycatalog;

import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.PlateSolutionCorrection;
import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.WcsSolutionResolver;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;
import nom.tam.fits.Fits;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fits a {@link PlateSolutionCorrection} for a session: in every frame, the Gaia stars (moved to the frame's date) are
 * matched to the detected stars, a polynomial correction of order 1 to 3 is fitted to all frames together (the order
 * is the lowest that does as well as the best on frames left out of the fit), and then a shift (or, with many stars,
 * a linear correction) per frame on top. The frames are read one at a time.
 */
public final class PlateCorrectionFitter {

    /** Receives what the fit is doing, for example "frame 3 of 33". */
    public interface Progress {
        void update(String text);
    }

    /** With fewer stars on the frame than this, deeper stars are fetched for the fit. */
    static final int ENOUGH_STARS = 300;
    /** A frame with fewer matched stars gets no correction of its own. */
    static final int MIN_FRAME_PAIRS = 20;
    /** Above this many matched stars, a frame gets a linear correction instead of a shift. */
    static final int LINEAR_FRAME_PAIRS = 300;
    static final int MAX_FRAMES = 300;
    private static final double SEARCH_RANGE = 80;
    private static final double MATCH_RADIUS = 4;
    private static final double[] DEEPER_STARS = {17, 18.5};

    private PlateCorrectionFitter() {
    }

    /**
     * Fits the correction of the session's header solution.
     *
     * @throws IOException          when there is no solution, too few stars match, or deeper stars cannot be fetched
     * @throws InterruptedException when the thread is interrupted, which cancels the fit
     */
    public static PlateSolutionCorrection fit(FitsFileInformation[] files, SkyCatalogue catalogue, SkyCatalogueClient client,
                                              Progress progress) throws IOException, InterruptedException {
        WcsSolutionResolver.ResolvedWcsSolution solution = WcsSolutionResolver.resolveUncorrected(null, files);
        if (solution == null) {
            throw new IOException("no frame has a plate solution in its FITS header.");
        }
        WcsCoordinateTransformer header = solution.getTransformer();
        int width = files[0].getSizeWidth();
        int height = files[0].getSizeHeight();

        List<SkyCatalogue.Star> stars = catalogue.stars;
        List<String> notes = new ArrayList<>();
        if (countOnFrame(stars, header, width, height) < ENOUGH_STARS) {
            for (double depth : DEEPER_STARS) {
                if (depth <= catalogue.starMagnitudeLimit) {
                    continue;
                }
                progress.update(String.format(Locale.US, "fetching stars to magnitude %.1f for the fit", depth));
                stars = client.fetchStars(catalogue.field, depth);
                notes.add(String.format(Locale.US, "Fitted to Gaia stars down to magnitude %.1f (the field has few brighter ones).", depth));
                if (countOnFrame(stars, header, width, height) >= ENOUGH_STARS) {
                    break;
                }
            }
        }

        List<FitsFileInformation> chosen = new ArrayList<>();
        double step = Math.max(1.0, files.length / (double) MAX_FRAMES);
        for (double i = 0; i < files.length; i += step) {
            chosen.add(files[(int) i]);
        }
        List<FramePairs> frames = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            FitsFileInformation file = chosen.get(i);
            progress.update("frame " + (i + 1) + " of " + chosen.size());
            short[][] data = readMono(new File(file.getFilePath()));
            if (data == null) {
                notes.add(file.getFileName() + ": not 16-bit data, left out.");
                continue;
            }
            List<SourceExtractor.DetectedObject> objects = SourceExtractor.extractSources(data, 5.0, 5, new DetectionConfig()).objects;
            FramePairs pairs = FramePairs.match(file.getFileName(), stars, header, objects, width, height, file.getObservationTimestamp());
            if (pairs.size() >= MIN_FRAME_PAIRS) {
                frames.add(pairs);
            } else {
                notes.add(file.getFileName() + ": only " + pairs.size() + " stars matched; it uses the session correction.");
            }
        }
        if (frames.isEmpty()) {
            throw new IOException("too few catalogue stars could be matched to the stars in the frames.");
        }
        progress.update("fitting");
        return fitPairs(frames, header.solutionKey(), width, height, notes);
    }

    /** The fit itself, from the matched stars of each frame. */
    static PlateSolutionCorrection fitPairs(List<FramePairs> frames, String solutionKey, int width, int height, List<String> notes)
            throws IOException {
        double cx = width / 2.0;
        double cy = height / 2.0;
        double scale = Math.max(width, height) / 2.0;

        // The order: fitted on half of the frames (or of the stars, with one frame), measured on the rest.
        List<double[]> trainFrom = new ArrayList<>(), trainBy = new ArrayList<>(), testFrom = new ArrayList<>(), testBy = new ArrayList<>();
        for (int f = 0; f < frames.size(); f++) {
            FramePairs frame = frames.get(f);
            for (int k = 0; k < frame.size(); k++) {
                boolean train = frames.size() > 1 ? f % 2 == 0 : k % 2 == 0;
                (train ? trainFrom : testFrom).add(frame.header.get(k));
                (train ? trainBy : testBy).add(frame.displacement(k));
            }
        }
        double[] testMedian = new double[4];
        double best = Double.MAX_VALUE;
        for (int order = 1; order <= 3; order++) {
            PlateSolutionCorrection.Polynomial trial = PolynomialFit.fit(trainFrom, trainBy, order, cx, cy, scale);
            testMedian[order] = trial == null ? Double.MAX_VALUE : median(residuals(testFrom, testBy, trial));
            best = Math.min(best, testMedian[order]);
        }
        if (best == Double.MAX_VALUE) {
            throw new IOException("too few stars matched for a fit.");
        }
        int order = 1;
        while (testMedian[order] > best + 0.02) {
            order++;
        }

        List<double[]> allFrom = new ArrayList<>(trainFrom);
        allFrom.addAll(testFrom);
        List<double[]> allBy = new ArrayList<>(trainBy);
        allBy.addAll(testBy);
        PlateSolutionCorrection.Polynomial global = PolynomialFit.fit(allFrom, allBy, order, cx, cy, scale);
        if (global == null) {
            throw new IOException("the fit failed.");
        }

        PlateSolutionCorrection correction = new PlateSolutionCorrection();
        correction.fittedAtUtc = Instant.now().toString();
        correction.solutionKey = solutionKey;
        correction.width = width;
        correction.height = height;
        correction.global = global;
        correction.notes = notes;
        List<Double> before = new ArrayList<>();
        List<Double> after = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (FramePairs frame : frames) {
            counts.add(frame.size());
            List<double[]> from = new ArrayList<>();
            List<double[]> by = new ArrayList<>();
            for (int k = 0; k < frame.size(); k++) {
                double[] h = frame.header.get(k);
                double[] g = global.delta(h[0], h[1]);
                from.add(new double[]{h[0] + g[0], h[1] + g[1]});
                double[] d = frame.displacement(k);
                by.add(new double[]{d[0] - g[0], d[1] - g[1]});
                before.add(Math.hypot(d[0], d[1]));
            }
            PlateSolutionCorrection.Polynomial own = PolynomialFit.fit(from, by,
                    frame.size() >= LINEAR_FRAME_PAIRS ? 1 : 0, cx, cy, scale);
            if (own != null) {
                correction.frames.put(frame.name, own);
            }
            after.addAll(residuals(from, by, own));
        }
        Collections.sort(before);
        Collections.sort(after);
        Collections.sort(counts);
        correction.beforeMedianPx = before.get(before.size() / 2);
        correction.beforeP90Px = before.get((int) (before.size() * 0.9));
        correction.afterMedianPx = after.get(after.size() / 2);
        correction.afterP90Px = after.get((int) (after.size() * 0.9));
        correction.starsPerFrame = counts.get(counts.size() / 2);
        correction.framesFitted = frames.size();
        notes.add(0, String.format(Locale.US, "Correction of order %d for the session%s.", order,
                correction.frames.isEmpty() ? "" : ", plus a small one per frame"));
        return correction;
    }

    private static List<Double> residuals(List<double[]> from, List<double[]> by, PlateSolutionCorrection.Polynomial fit) {
        List<Double> residuals = new ArrayList<>(from.size());
        for (int k = 0; k < from.size(); k++) {
            double[] d = fit == null ? new double[2] : fit.delta(from.get(k)[0], from.get(k)[1]);
            residuals.add(Math.hypot(by.get(k)[0] - d[0], by.get(k)[1] - d[1]));
        }
        return residuals;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.isEmpty() ? Double.MAX_VALUE : sorted.get(sorted.size() / 2);
    }

    private static int countOnFrame(List<SkyCatalogue.Star> stars, WcsCoordinateTransformer header, int width, int height) {
        int count = 0;
        for (SkyCatalogue.Star star : stars) {
            double[] p = header.skyToPixel(star.ra, star.dec);
            if (p != null && p[0] >= 0 && p[1] >= 0 && p[0] < width && p[1] < height) {
                count++;
            }
        }
        return count;
    }

    /** The frame's 16-bit data; for a colour frame its middle plane. Null for other data. */
    static short[][] readMono(File file) throws IOException {
        try (Fits fits = new Fits(file)) {
            Object kernel = fits.getHDU(0).getKernel();
            if (kernel instanceof short[][]) {
                return (short[][]) kernel;
            }
            if (kernel instanceof short[][][] && ((short[][][]) kernel).length > 0) {
                short[][][] planes = (short[][][]) kernel;
                return planes[planes.length / 2];
            }
            return null;
        } catch (Exception e) {
            throw new IOException("cannot read " + file.getName() + ": " + e.getMessage(), e);
        }
    }

    // ==========================================
    // MATCHING
    // ==========================================

    /** The catalogue stars of one frame matched to its detected stars. */
    static final class FramePairs {
        final String name;
        /** Where the header solution puts each star, and where it was detected. */
        final List<double[]> header = new ArrayList<>();
        final List<double[]> detected = new ArrayList<>();

        FramePairs(String name) {
            this.name = name;
        }

        int size() {
            return header.size();
        }

        double[] displacement(int k) {
            return new double[]{detected.get(k)[0] - header.get(k)[0], detected.get(k)[1] - header.get(k)[1]};
        }

        /**
         * Matches the stars: a first offset from voting over pairs of the brightest stars, then nearest neighbours
         * within a shrinking radius around positions predicted by a fit that is refined each time. Only stars without
         * a similarly bright neighbour are used, and each detection is used once.
         */
        static FramePairs match(String name, List<SkyCatalogue.Star> catalogue, WcsCoordinateTransformer solution,
                                List<SourceExtractor.DetectedObject> objects, int width, int height, long timestamp) {
            FramePairs result = new FramePairs(name);
            double years = timestamp > 0 ? (timestamp - SkyCatalogue.GAIA_EPOCH_MILLIS) / (365.25 * 86_400_000.0) : 0;
            List<double[]> stars = new ArrayList<>(); // x, y, G
            for (SkyCatalogue.Star star : catalogue) {
                double ra = star.ra;
                double dec = star.dec;
                if (years != 0 && star.pmra != null && star.pmdec != null) {
                    dec += star.pmdec * years / 3.6e6;
                    ra += star.pmra * years / 3.6e6 / Math.max(1e-6, Math.cos(Math.toRadians(star.dec)));
                }
                double[] p = solution.skyToPixel(ra, dec);
                if (p != null && p[0] >= 10 && p[1] >= 10 && p[0] <= width - 11 && p[1] <= height - 11) {
                    stars.add(new double[]{p[0], p[1], star.g});
                }
            }
            List<double[]> detections = new ArrayList<>(); // x, y, flux
            for (SourceExtractor.DetectedObject object : objects) {
                if (!object.isNoise && !object.isStreak) {
                    detections.add(new double[]{object.x, object.y, object.totalFlux});
                }
            }
            if (stars.size() < MIN_FRAME_PAIRS || detections.size() < MIN_FRAME_PAIRS) {
                return result;
            }

            // First offset: the most common displacement between the brightest stars and the brightest detections.
            stars.sort(Comparator.comparingDouble(s -> s[2]));
            List<double[]> bright = new ArrayList<>(detections);
            bright.sort((p, q) -> Double.compare(q[2], p[2]));
            Grid brightGrid = new Grid(bright.subList(0, Math.min(1500, bright.size())), 16);
            int range = (int) SEARCH_RANGE;
            int[][] votes = new int[2 * range + 1][2 * range + 1];
            for (double[] s : stars.subList(0, Math.min(300, stars.size()))) {
                for (double[] d : brightGrid.near(s[0], s[1], SEARCH_RANGE)) {
                    int dx = (int) Math.round(d[0] - s[0]) + range;
                    int dy = (int) Math.round(d[1] - s[1]) + range;
                    if (dx >= 0 && dy >= 0 && dx <= 2 * range && dy <= 2 * range) {
                        votes[dx][dy]++;
                    }
                }
            }
            int bestX = range;
            int bestY = range;
            int bestVotes = -1;
            for (int x = 1; x < 2 * range; x++) {
                for (int y = 1; y < 2 * range; y++) {
                    int sum = 0;
                    for (int i = -1; i <= 1; i++) {
                        for (int j = -1; j <= 1; j++) {
                            sum += votes[x + i][y + j];
                        }
                    }
                    if (sum > bestVotes) {
                        bestVotes = sum;
                        bestX = x;
                        bestY = y;
                    }
                }
            }
            double offsetX = bestX - range;
            double offsetY = bestY - range;

            Grid starGrid = new Grid(stars, 16);
            List<double[]> usable = new ArrayList<>();
            for (double[] s : stars) {
                if (s[2] < 8.5) {
                    continue; // likely saturated
                }
                boolean isolated = true;
                for (double[] o : starGrid.near(s[0], s[1], 8)) {
                    if (o != s && o[2] < s[2] + 2.5 && Math.hypot(o[0] - s[0], o[1] - s[1]) < 8) {
                        isolated = false;
                        break;
                    }
                }
                if (isolated) {
                    usable.add(s);
                }
            }

            Grid grid = new Grid(detections, 16);
            double cx = width / 2.0;
            double cy = height / 2.0;
            double scale = Math.max(width, height) / 2.0;
            PlateSolutionCorrection.Polynomial fit = null;
            double radius = 8;
            List<double[][]> pairs;
            for (int iteration = 0; iteration < 4; iteration++) {
                pairs = pairs(usable, grid, fit, offsetX, offsetY, radius);
                List<double[]> from = new ArrayList<>();
                List<double[]> by = new ArrayList<>();
                for (double[][] p : pairs) {
                    from.add(new double[]{p[0][0], p[0][1]});
                    by.add(new double[]{p[1][0] - p[0][0], p[1][1] - p[0][1]});
                }
                PlateSolutionCorrection.Polynomial next = PolynomialFit.fit(from, by, pairs.size() > 200 ? 2 : 1, cx, cy, scale);
                if (next == null) {
                    break;
                }
                fit = next;
                radius = iteration == 0 ? 5 : MATCH_RADIUS;
            }
            for (double[][] p : pairs(usable, grid, fit, offsetX, offsetY, MATCH_RADIUS)) {
                result.header.add(new double[]{p[0][0], p[0][1]});
                result.detected.add(new double[]{p[1][0], p[1][1]});
            }
            return result;
        }

        /** Each detection paired with the nearest predicted star position within the radius (the closest wins). */
        private static List<double[][]> pairs(List<double[]> stars, Grid grid, PlateSolutionCorrection.Polynomial fit,
                                              double offsetX, double offsetY, double radius) {
            Map<double[], double[][]> byDetection = new IdentityHashMap<>();
            Map<double[], Double> distances = new IdentityHashMap<>();
            for (double[] s : stars) {
                double px;
                double py;
                if (fit != null) {
                    double[] d = fit.delta(s[0], s[1]);
                    px = s[0] + d[0];
                    py = s[1] + d[1];
                } else {
                    px = s[0] + offsetX;
                    py = s[1] + offsetY;
                }
                double[] nearest = null;
                double nearestDistance = radius;
                for (double[] d : grid.near(px, py, radius)) {
                    double distance = Math.hypot(d[0] - px, d[1] - py);
                    if (distance <= nearestDistance) {
                        nearest = d;
                        nearestDistance = distance;
                    }
                }
                if (nearest != null && (!distances.containsKey(nearest) || nearestDistance < distances.get(nearest))) {
                    byDetection.put(nearest, new double[][]{s, nearest});
                    distances.put(nearest, nearestDistance);
                }
            }
            return new ArrayList<>(byDetection.values());
        }
    }

    /** Points in square cells, for neighbour lookups. */
    static final class Grid {
        private final Map<Long, List<double[]>> cells = new HashMap<>();
        private final double cell;

        Grid(List<double[]> points, double cell) {
            this.cell = cell;
            for (double[] p : points) {
                cells.computeIfAbsent(key((int) Math.floor(p[0] / cell), (int) Math.floor(p[1] / cell)), k -> new ArrayList<>()).add(p);
            }
        }

        private static long key(int x, int y) {
            return ((long) x << 32) ^ (y & 0xffffffffL);
        }

        List<double[]> near(double x, double y, double radius) {
            List<double[]> found = new ArrayList<>();
            int x0 = (int) Math.floor((x - radius) / cell);
            int x1 = (int) Math.floor((x + radius) / cell);
            int y0 = (int) Math.floor((y - radius) / cell);
            int y1 = (int) Math.floor((y + radius) / cell);
            for (int i = x0; i <= x1; i++) {
                for (int j = y0; j <= y1; j++) {
                    List<double[]> list = cells.get(key(i, j));
                    if (list != null) {
                        found.addAll(list);
                    }
                }
            }
            return found;
        }
    }
}
