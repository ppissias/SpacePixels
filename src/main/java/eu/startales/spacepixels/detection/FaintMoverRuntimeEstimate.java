package eu.startales.spacepixels.detection;

import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.SkyMath;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.FaintMoverSearch;
import java.util.Arrays;
import java.util.Locale;

/** Approximate pre-run resource information from metadata; never selects the actual engine search domain. */
public final class FaintMoverRuntimeEstimate {
    /** Pre-run display assumption in pixels; the engine independently measures its actual seeing width. */
    public static final double ASSUMED_FWHM_PIXELS = 2.5;
    /** Names of the faint-mover search speeds 0, 1 and 2 ({@code faintMoverSearchSpeed}), as the settings show them. */
    public static final String[] SPEED_NAMES = {"Normal", "Fast", "Faster"};
    /**
     * Seconds per coarse addition at full resolution and binned, and per frame pixel prepared, with five search workers
     * on a 16-core desktop (measured 2026-11-13 on a 97-frame, 8-megapixel session). Fewer workers, as a small heap
     * allows, make the search slower.
     */
    private static final double FULL_ADD_SECONDS = 6e-11;
    private static final double BINNED_ADD_SECONDS = 7e-11;
    private static final double PREPARE_SECONDS = 1.5e-8;
    /**
     * The most first-pass trial motions the engine searches ({@code FaintMoverSearch.MAX_MOTIONS}); over it, a run skips
     * the search and says so.
     */
    public static final int MAX_MOTIONS = 100_000;

    /**
     * The trial motions the fastest motion needs for these frames and the fastest motion that stays within
     * {@link #MAX_MOTIONS}, in pixels per minute; estimated from the imported frames and the star size, so the kept
     * frames and the measured seeing of a run can move the limit a little.
     */
    public record MotionLimit(double motions, double highestSpeedPixelsPerMinute) {
        /** Whether the run will probably skip the search for too many trial motions. */
        public boolean exceeded() {
            return motions > MAX_MOTIONS;
        }
    }

    /**
     * Largest imported session's timing metadata; quality selection and measured seeing are not yet available.
     * {@code midMinutes} holds its mid-exposure times in minutes from its first frame, in time order.
     */
    public record ImportedSession(int frames, double spanMinutes, double halfSpanMinutes, String unavailableReason,
                                  double[] midMinutes) {
        /** Whether a finite, positive timing span supports display-only motion conversions. */
        public boolean hasSpan() {
            return unavailableReason == null && spanMinutes > 0 && Double.isFinite(spanMinutes)
                    && halfSpanMinutes > 0 && Double.isFinite(halfSpanMinutes);
        }
    }

    /** Prevents construction of this metadata-only presentation helper. */
    private FaintMoverRuntimeEstimate() { }

    /** Returns the first usable header's field-centre arcseconds/pixel, or NaN without a WCS; reads no image pixels. */
    public static double angularPixelScale(FitsFileInformation[] files) {
        if (files == null) return Double.NaN;
        for (var file : files) {
            if (file == null) continue;
            double scale = angularPixelScale(WcsCoordinateTransformer.fromHeader(file.getFitsHeader()),
                    file.getSizeWidth(), file.getSizeHeight());
            if (Double.isFinite(scale)) return scale;
        }
        return Double.NaN;
    }

    /** Converts an X-axis pixel at the field centre through the supplied WCS; this is a local angular-rate equivalent. */
    public static double angularPixelScale(WcsCoordinateTransformer wcs, int width, int height) {
        if (wcs == null || width <= 0 || height <= 0) return Double.NaN;
        var a = wcs.pixelToSky(width / 2.0, height / 2.0);
        var b = wcs.pixelToSky(width / 2.0 + 1, height / 2.0);
        if (a == null || b == null) return Double.NaN;
        double scale = SkyMath.separationArcsec(a.getRaDegrees(), a.getDecDegrees(), b.getRaDegrees(), b.getDecDegrees());
        return Double.isFinite(scale) && scale > 0 ? scale : Double.NaN;
    }

    /** Reads only headers to choose the most populated session, with gaps over two hours separating sessions. */
    public static ImportedSession importedSession(FitsFileInformation[] files) {
        if (files == null || files.length == 0) return new ImportedSession(0, 0, 0, "Import frames to estimate the additional search time.", new double[0]);
        double[] times = Arrays.stream(files).filter(java.util.Objects::nonNull)
                .filter(f -> f.getObservationTimestamp() != -1L)
                .mapToDouble(f -> f.getObservationTimestamp() + Math.max(0, f.getExposureDurationMillis()) / 2.0).sorted().toArray();
        if (times.length != files.length) return new ImportedSession(times.length, 0, 0, "Capture times are required; the engine will report why a search is skipped.", new double[0]);
        int start = 0, bestStart = 0, bestEnd = 0;
        for (int end = 1; end <= times.length; end++) {
            if (end == times.length || times[end] - times[end - 1] > 120 * 60000.0) {
                if (end - start > bestEnd - bestStart) { bestStart = start; bestEnd = end; }
                start = end;
            }
        }
        int n = bestEnd - bestStart;
        double mean = Arrays.stream(times, bestStart, bestEnd).average().orElse(0);
        double halfSpan = Math.max(mean - times[bestStart], times[bestEnd - 1] - mean) / 60000.0;
        double span = (times[bestEnd - 1] - times[bestStart]) / 60000.0;
        double[] minutes = new double[n];
        for (int k = 0; k < n; k++) minutes[k] = (times[bestStart + k] - times[bestStart]) / 60000.0;
        return new ImportedSession(n, span, halfSpan, null, minutes);
    }

    /** Trail length per frame, in star widths, up to which the search scores movers as points without losing much. */
    public static final double POINT_TRAIL_FWHM = 2.0;

    /**
     * How long the fastest motion trails in one frame: the median exposure in minutes, the star size used, the trail in
     * pixels, and the speeds in pixels per minute at which a frame trails {@link #POINT_TRAIL_FWHM} star widths.
     */
    public record Trail(double exposureMinutes, double starSizePixels, double trailPixels, double pointSpeedPixelsPerMinute) {
        /** The trail in star widths. */
        public double trailStarWidths() {
            return trailPixels / starSizePixels;
        }

        /** Whether the fastest motion trails longer than {@link #POINT_TRAIL_FWHM} star widths. */
        public boolean beyondPoint() {
            return trailStarWidths() > POINT_TRAIL_FWHM + 1e-9;
        }
    }

    /** The trail of the fastest motion in these frames; null without exposure times. */
    public static Trail trail(FitsFileInformation[] files, DetectionConfig config, double measuredStarSize) {
        if (files == null) return null;
        double[] exposures = Arrays.stream(files).filter(java.util.Objects::nonNull)
                .mapToDouble(f -> f.getExposureDurationMillis() / 60000.0).filter(m -> m > 0).sorted().toArray();
        if (exposures.length == 0) return null;
        double exposure = exposures[exposures.length / 2];
        double fwhm = Double.isFinite(measuredStarSize) && measuredStarSize > 0 ? measuredStarSize : ASSUMED_FWHM_PIXELS;
        return new Trail(exposure, fwhm, config.faintMoverMaxSpeedPixelsPerMinute * exposure, POINT_TRAIL_FWHM * fwhm / exposure);
    }

    /**
     * The trial motions and the highest fastest motion for these frames (see {@link MotionLimit}); null when the
     * frames give no usable session span or fewer than 8 frames.
     */
    public static MotionLimit motionLimit(FitsFileInformation[] files, DetectionConfig config, double measuredStarSize) {
        var session = importedSession(files);
        if (session.unavailableReason() != null || session.frames() < 8 || !session.hasSpan()) return null;
        double fwhm = Double.isFinite(measuredStarSize) && measuredStarSize > 0 ? measuredStarSize : ASSUMED_FWHM_PIXELS;
        double step = fwhm / session.halfSpanMinutes(), min = config.faintMoverMinDisplacementFwhm * fwhm / session.spanMinutes();
        double max = config.faintMoverMaxSpeedPixelsPerMinute;
        double motions = Math.PI * Math.max(0, max * max - min * min) / (step * step);
        return new MotionLimit(motions, Math.sqrt(MAX_MOTIONS * step * step / Math.PI + min * min));
    }

    /**
     * Estimates the additional time of each search speed from headers and the star size: the size the last detection
     * run measured when given (finite and positive), otherwise {@link #ASSUMED_FWHM_PIXELS}. Quality selection is not
     * yet known.
     */
    public static String describe(FitsFileInformation[] files, DetectionConfig config, double measuredStarSize) {
        var session = importedSession(files);
        if (session.unavailableReason() != null) return session.unavailableReason();
        int n = session.frames();
        if (n < 8) return "At least 8 kept frames in one session are needed; quality control may reduce the available frames.";
        if (!session.hasSpan()) return "Different capture times are required to estimate the search.";
        boolean measured = Double.isFinite(measuredStarSize) && measuredStarSize > 0;
        double fwhm = measured ? measuredStarSize : ASSUMED_FWHM_PIXELS, span = session.spanMinutes(), halfSpan = session.halfSpanMinutes();
        double maxSpeed = config.faintMoverMaxSpeedPixelsPerMinute;
        double step = fwhm / halfSpan, min = config.faintMoverMinDisplacementFwhm * fwhm / span;
        double motions = Math.PI * Math.max(0, maxSpeed * maxSpeed - min * min) / (step * step);
        double pixels = (double) files[0].getSizeWidth() * files[0].getSizeHeight();
        StringBuilder times = new StringBuilder();
        for (int speed = 0; speed < SPEED_NAMES.length; speed++) {
            int[] shape = FaintMoverSearch.coarseSearchShape(speed, fwhm, session.midMinutes(), maxSpeed);
            double seconds = (shape[0] == 1 ? FULL_ADD_SECONDS : BINNED_ADD_SECONDS) * motions * shape[1] * pixels
                    / (shape[0] * shape[0]) + PREPARE_SECONDS * n * pixels;
            times.append(speed == 0 ? "" : " · ").append(SPEED_NAMES[speed]).append(' ').append(minutes(seconds));
            if (shape[0] > 1 || shape[1] < n) {
                times.append(" (").append(shape[0] > 1 ? shape[0] + "×" + shape[0] + " pixels" : "")
                        .append(shape[0] > 1 && shape[1] < n ? ", " : "")
                        .append(shape[1] < n ? shape[1] + " of " + n + " frames" : "").append(" in the first pass)");
            }
        }
        if (motions > MAX_MOTIONS) {
            double highest = Math.sqrt(MAX_MOTIONS * step * step / Math.PI + min * min);
            return String.format(Locale.ROOT, "<b>The run will probably skip the search:</b> the fastest motion needs about "
                            + "%,.0f trial motions for these frames, over the search's limit of %,d. Lower the fastest motion "
                            + "to about %.2f px/min or less. Based on %d imported frames over %.2f min and %s.",
                    motions, MAX_MOTIONS, highest, n, span,
                    measured ? String.format(Locale.ROOT, "the star size of %.2f px the last detection run measured", fwhm)
                            : "an assumed " + fwhm + " px star size (a detection run measures it)");
        }
        int selected = Math.max(0, Math.min(SPEED_NAMES.length - 1, config.faintMoverSearchSpeed));
        double margin = Math.ceil(maxSpeed * halfSpan) + 16;
        double mib = Math.pow(512 + 2 * margin, 2) * (4 * n + 12) / (1024 * 1024);
        return String.format(Locale.ROOT,
                "Approximate extra time: %s. Selected: %s; limit %.1f min. About %.0f MiB per search worker. "
                        + "Fast and Faster add pixels or consecutive frames in the first pass and can miss candidates close "
                        + "to the limits, Faster more; every candidate is still scored on all frames at full resolution. "
                        + "Based on %d imported frames over %.2f min and %s. "
                        + "Estimated minimum speed = %.2f FWHM × %s px / span = %.5f px/min. "
                        + "CPU, seeing, masks and kept frames change this estimate.",
                times, SPEED_NAMES[selected], config.faintMoverMaxSeconds / 60, mib, n, span,
                measured ? String.format(Locale.ROOT, "the star size of %.2f px the last detection run measured", fwhm)
                        : "an assumed " + fwhm + " px star size (a detection run measures it)",
                config.faintMoverMinDisplacementFwhm, String.format(Locale.ROOT, "%.2f", fwhm), min);
    }

    /** A time range from half to twice the estimate, in minutes. */
    private static String minutes(double seconds) {
        return seconds * 2 < 6 ? "under 0.1 min" : String.format(Locale.ROOT, "%.1f–%.1f min",
                Math.max(0.1, seconds / 120), Math.max(0.1, seconds / 30));
    }
}
