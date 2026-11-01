package eu.startales.spacepixels.detection;

import eu.startales.spacepixels.config.VisualizationSettings;
import io.github.ppissias.jtransient.engine.AutoTuneObserver;
import io.github.ppissias.jtransient.engine.AutoTuneSnapshot;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Renders requested tuner snapshots on the job or engine worker, never on the EDT. Regional pictures use a bounded
 * central detail so artificial stars remain visible. Only the detached RGB thumbnail reaches the shared sink;
 * no borrowed engine images, masks or detections survive the callback. There is no rendering queue or timer.
 */
public final class AutoTuneLiveView implements AutoTuneObserver {
    private static final int DETAIL_SIZE = 384;
    private static final Color TEST = new Color(45, 220, 255);
    private static final Color RECOVERED = new Color(70, 240, 100);
    private static final Color MISSED = new Color(255, 170, 50);
    private final RunPreviewSink sink;
    private final VisualizationSettings settings;

    /** Captures the admitted run's sink and immutable rendering settings; callbacks may come from joined workers. */
    public AutoTuneLiveView(RunPreviewSink sink, VisualizationSettings settings) {
        this.sink = sink;
        this.settings = settings;
    }

    /** Reserves the shared three-second slot before reading or copying any display pixels. */
    @Override public boolean wantsUpdate() { return sink.wantsPreview(false); }

    /** Removes a queued picture of the previous phase; displayed pictures stay until the next clock slot. */
    @Override public void phaseStarted(AutoTuneSnapshot.Kind kind) { sink.dropPending(); }

    /** Renders synchronously while borrowed data are valid, then hands over only a detached small image. */
    @Override public void update(AutoTuneSnapshot snapshot) { sink.preview(render(snapshot, settings)); }

    /** Makes a bounded picture and pixel-coordinate overlays without modifying any scientific input. */
    static RunPreview render(AutoTuneSnapshot snapshot, VisualizationSettings settings) {
        short[][] source = snapshot.image();
        boolean detail = snapshot.kind() != AutoTuneSnapshot.Kind.FRAME && snapshot.kind() != AutoTuneSnapshot.Kind.REGIONS;
        int width = detail ? Math.min(DETAIL_SIZE, source[0].length) : source[0].length;
        int height = detail ? Math.min(DETAIL_SIZE, source.length) : source.length;
        int left = (source[0].length - width) / 2, top = (source.length - height) / 2;
        short[][] pixels = source;
        if (detail) {
            pixels = new short[height][width];
            for (int y = 0; y < height; y++) System.arraycopy(source[top + y], left, pixels[y], 0, width);
        }
        BufferedImage gray = PreviewThumbnails.frame(pixels, settings.getAutoStretchBlackSigma(), settings.getAutoStretchWhiteSigma());
        BufferedImage image = new BufferedImage(gray.getWidth(), gray.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.drawImage(gray, 0, 0, null);
            double scaleX = image.getWidth() / (double) width, scaleY = image.getHeight() / (double) height;
            if (snapshot.mask() != null) {
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    if (!snapshot.mask().get((top + y) * source[0].length + left + x)) continue;
                    int xx = Math.min(image.getWidth() - 1, (int) (x * scaleX));
                    int yy = Math.min(image.getHeight() - 1, (int) (y * scaleY));
                    int rgb = image.getRGB(xx, yy);
                    image.setRGB(xx, yy, new Color((rgb >> 16 & 255) / 2 + 105,
                            (rgb >> 8 & 255) / 2 + 30, (rgb & 255) / 2 + 95).getRGB());
                }
            }
            graphics.setColor(TEST);
            for (int i = 0; i < snapshot.regions().size(); i++) {
                var region = snapshot.regions().get(i);
                int x = (int) Math.round(region.x() * scaleX), y = (int) Math.round(region.y() * scaleY);
                graphics.drawRect(x, y, (int) Math.round(region.size() * scaleX), (int) Math.round(region.size() * scaleY));
                graphics.drawString(Integer.toString(i + 1), x + 3, y + 12);
            }
            for (var marker : snapshot.markers()) {
                if (marker.x() < left || marker.x() >= left + width || marker.y() < top || marker.y() >= top + height) continue;
                graphics.setColor(switch (marker.mark()) {
                    case RECOVERED -> RECOVERED;
                    case MISSED -> MISSED;
                    case TEST, DETECTION -> TEST;
                });
                int x = (int) Math.round((marker.x() - left) * scaleX), y = (int) Math.round((marker.y() - top) * scaleY);
                graphics.drawOval(x - 5, y - 5, 10, 10);
            }
        } finally { graphics.dispose(); }
        String legend = switch (snapshot.kind()) {
            case TEST_SOURCES -> "Cyan: artificial test stars";
            case EXTRACTION -> "Artificial stars · green: extracted · orange: missed (before veto)";
            case RECOVERY -> "Artificial stars · green: recovered · orange: missed or vetoed";
            case NOISE -> "Cyan: noise-control survivors · pink: star mask";
            case MASK -> "Pink: star mask";
            default -> "";
        };
        return RunPreview.of(image, "Auto-Tune · " + snapshot.caption()
                + (detail ? " · central detail" : "") + (legend.isEmpty() ? "" : "\n" + legend));
    }
}
