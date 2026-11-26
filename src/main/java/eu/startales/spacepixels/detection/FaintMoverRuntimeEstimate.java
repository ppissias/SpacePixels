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
     * The most first-pass trial motions the CPU engine searches ({@code FaintMoverSearch.MAX_MOTIONS}).
     */
    public static final int MAX_MOTIONS = FaintMoverSearch.MAX_MOTIONS;
    /** The most first-pass trial motions when running the coarse pass on OpenCL ({@code FaintMoverSearch.MAX_MOTIONS_GPU}). */
    public static final int MAX_MOTIONS_GPU = FaintMoverSearch.MAX_MOTIONS_GPU;
    /**
     * Safety fraction of the active motion cap used by automatic fastest-motion sizing so small shifts in measured
     * seeing or kept frame count do not push the run over the cap and skip the search.
     */
    public static final double AUTO_MAX_SPEED_MOTION_CAP_FRACTION = 0.90;

    /**
     * Automatic fastest motion for a session: {@link #POINT_TRAIL_FWHM} star widths per median exposure, clamped to
     * stay within {@link #AUTO_MAX_SPEED_MOTION_CAP_FRACTION} of the active motion cap when session timing is known.
     */
    public record AutoMaxSpeed(double speedPixelsPerMinute, double pointSpeedPixelsPerMinute,
                               boolean cappedByMotionLimit, int maxMotions) { }

    /**
     * Estimated runtime of the currently selected search speed compared against the configured time limit.
     * {@code likelyToExceed} is true when the central estimate itself exceeds the limit; {@code mayExceed} is true
     * when the upper range (2× the central estimate) exceeds the limit while the central estimate stays within it.
     */
    public record TimeAssessment(double estimatedSeconds, double lowSeconds, double highSeconds,
                                 double timeLimitSeconds, boolean likelyToExceed, boolean mayExceed) {
        /** Human-readable range string (0.5× to 2× the estimate) in minutes. */
        public String rangeText() {
            return minutes(estimatedSeconds);
        }
    }

    /**
     * The trial motions the fastest motion needs for these frames and the fastest motion that stays within
     * {@code maxMotions}, in pixels per minute; estimated from the imported frames and the star size, so the kept
     * frames and the measured seeing of a run can move the limit a little.
     */
    public record MotionLimit(double motions, double highestSpeedPixelsPerMinute, int maxMotions) {
        /** Preserves the 2-argument record constructor using the CPU motion cap. */
        public MotionLimit(double motions, double highestSpeedPixelsPerMinute) {
            this(motions, highestSpeedPixelsPerMinute, MAX_MOTIONS);
        }

        /** Whether the run will probably skip the search for too many trial motions. */
        public boolean exceeded() {
            return motions > maxMotions;
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
    public static final double POINT_TRAIL_FWHM = FaintMoverSearch.POINT_TRAIL_FWHM;
    /** Maximum per-exposure trail length, in star widths, intended for synthetic tracking. */
    public static final double MAX_TRAIL_FWHM = FaintMoverSearch.MAX_TRAIL_FWHM;

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
     * The trial motions and the highest fastest motion for these frames using the CPU cap; null when the
     * frames give no usable session span or fewer than 8 frames.
     */
    public static MotionLimit motionLimit(FitsFileInformation[] files, DetectionConfig config, double measuredStarSize) {
        return motionLimit(files, config, measuredStarSize, MAX_MOTIONS);
    }

    /**
     * The trial motions and the highest fastest motion for these frames against {@code maxMotions}; null when the
     * frames give no usable session span or fewer than 8 frames.
     */
    public static MotionLimit motionLimit(FitsFileInformation[] files, DetectionConfig config,
                                          double measuredStarSize, int maxMotions) {
        var session = importedSession(files);
        if (session.unavailableReason() != null || session.frames() < 8 || !session.hasSpan()) return null;
        double fwhm = Double.isFinite(measuredStarSize) && measuredStarSize > 0 ? measuredStarSize : ASSUMED_FWHM_PIXELS;
        double step = fwhm / session.halfSpanMinutes(), min = config.faintMoverMinDisplacementFwhm * fwhm / session.spanMinutes();
        double max = config.faintMoverMaxSpeedPixelsPerMinute;
        double motions = Math.PI * Math.max(0, max * max - min * min) / (step * step);
        return new MotionLimit(motions, Math.sqrt(maxMotions * step * step / Math.PI + min * min), maxMotions);
    }

    /**
     * Computes the automatic fastest motion ({@link #POINT_TRAIL_FWHM} star widths per median exposure) for these
     * frames, clamped to {@link #AUTO_MAX_SPEED_MOTION_CAP_FRACTION} of {@code maxMotions} when session timing is
     * usable so short-exposure sessions do not skip the search. Returns {@code null} without positive exposure times.
     */
    public static AutoMaxSpeed autoMaxSpeed(FitsFileInformation[] files, DetectionConfig config,
                                            double measuredStarSize, int maxMotions) {
        Trail trail = trail(files, config, measuredStarSize);
        if (trail == null) return null;
        double pointSpeed = trail.pointSpeedPixelsPerMinute();
        if (!Double.isFinite(pointSpeed) || pointSpeed <= 0) return null;
        int capMotions = Math.max(1, maxMotions);
        var session = importedSession(files);
        if (session.unavailableReason() == null && session.frames() >= 8 && session.hasSpan()) {
            double fwhm = Double.isFinite(measuredStarSize) && measuredStarSize > 0 ? measuredStarSize : ASSUMED_FWHM_PIXELS;
            double step = fwhm / session.halfSpanMinutes();
            double min = config.faintMoverMinDisplacementFwhm * fwhm / session.spanMinutes();
            double capSpeed = Math.sqrt(AUTO_MAX_SPEED_MOTION_CAP_FRACTION * capMotions * step * step / Math.PI + min * min);
            if (Double.isFinite(capSpeed) && capSpeed > 0 && pointSpeed > capSpeed) {
                return new AutoMaxSpeed(capSpeed, pointSpeed, true, capMotions);
            }
        }
        return new AutoMaxSpeed(pointSpeed, pointSpeed, false, capMotions);
    }

    /**
     * Assesses whether the currently selected search speed is likely to exceed {@code timeLimitSeconds}. Returns
     * {@code null} when timing is unavailable, fewer than 8 frames exist, or the motion cap itself is exceeded.
     */
    public static TimeAssessment timeAssessment(FitsFileInformation[] files, DetectionConfig config,
                                                double measuredStarSize, int maxMotions,
                                                double timeLimitSeconds, double gpuSpeedup) {
        var session = importedSession(files);
        if (session.unavailableReason() != null || session.frames() < 8 || !session.hasSpan()) return null;
        double fwhm = Double.isFinite(measuredStarSize) && measuredStarSize > 0 ? measuredStarSize : ASSUMED_FWHM_PIXELS;
        double maxSpeed = config.faintMoverMaxSpeedPixelsPerMinute;
        double step = fwhm / session.halfSpanMinutes();
        double min = config.faintMoverMinDisplacementFwhm * fwhm / session.spanMinutes();
        double motions = Math.PI * Math.max(0, maxSpeed * maxSpeed - min * min) / (step * step);
        if (motions > maxMotions) return null;
        int selected = Math.max(0, Math.min(SPEED_NAMES.length - 1, config.faintMoverSearchSpeed));
        double pixels = (double) files[0].getSizeWidth() * files[0].getSizeHeight();
        double coarseFactor = Double.isFinite(gpuSpeedup) && gpuSpeedup > 0 ? 1.0 / gpuSpeedup : 1.0;
        double seconds = estimateSeconds(selected, fwhm, session, maxSpeed, motions, pixels, coarseFactor);
        double low = seconds * 0.5;
        double high = seconds * 2.0;
        boolean hasLimit = Double.isFinite(timeLimitSeconds) && timeLimitSeconds > 0;
        boolean likely = hasLimit && seconds > timeLimitSeconds;
        boolean may = hasLimit && !likely && high > timeLimitSeconds;
        return new TimeAssessment(seconds, low, high, timeLimitSeconds, likely, may);
    }

    /**
     * Estimates the additional time of each search speed from headers and the star size using CPU defaults.
     */
    public static String describe(FitsFileInformation[] files, DetectionConfig config, double measuredStarSize) {
        return describe(files, config, measuredStarSize, MAX_MOTIONS, config.faintMoverMaxSeconds, Double.NaN);
    }

    /**
     * Estimates the additional time of each search speed from headers, star size, active motion limit and optional
     * measured GPU speedup, including a timeout warning when the selected speed is likely to exceed the limit.
     */
    public static String describe(FitsFileInformation[] files, DetectionConfig config, double measuredStarSize,
                                  int maxMotions, double timeLimitSeconds, double gpuSpeedup) {
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
        double coarseFactor = Double.isFinite(gpuSpeedup) && gpuSpeedup > 0 ? 1.0 / gpuSpeedup : 1.0;
        StringBuilder times = new StringBuilder();
        double[] speedSeconds = new double[SPEED_NAMES.length];
        for (int speed = 0; speed < SPEED_NAMES.length; speed++) {
            int[] shape = FaintMoverSearch.coarseSearchShape(speed, fwhm, session.midMinutes(), maxSpeed);
            double seconds = estimateSeconds(shape, n, motions, pixels, coarseFactor);
            speedSeconds[speed] = seconds;
            times.append(speed == 0 ? "" : " · ").append(SPEED_NAMES[speed]).append(' ').append(minutes(seconds));
            if (shape[0] > 1 || shape[1] < n) {
                times.append(" (").append(shape[0] > 1 ? shape[0] + "×" + shape[0] + " pixels" : "")
                        .append(shape[0] > 1 && shape[1] < n ? ", " : "")
                        .append(shape[1] < n ? shape[1] + " of " + n + " frames" : "").append(" in the first pass)");
            }
        }
        if (motions > maxMotions) {
            double highest = Math.sqrt(maxMotions * step * step / Math.PI + min * min);
            return String.format(Locale.ROOT, "<b>The run will probably skip the search:</b> needs about "
                            + "%,.0f trial motions (limit %,d). Lower fastest motion to ~%.2f px/min or less (%d frames, %.1f min, %s).",
                    motions, maxMotions, highest, n, span,
                    measured ? String.format(Locale.ROOT, "%.2f px the last detection run measured", fwhm)
                            : "assumed " + fwhm + " px star size");
        }
        int selected = Math.max(0, Math.min(SPEED_NAMES.length - 1, config.faintMoverSearchSpeed));
        double margin = Math.ceil(maxSpeed * halfSpan) + 16;
        double mib = Math.pow(512 + 2 * margin, 2) * (4 * n + 12) / (1024 * 1024);
        String base = String.format(Locale.ROOT,
                "Approximate extra time: %s. Selected: %s (limit %.1f min, ~%.0f MiB per search worker). "
                        + "Based on %d frames over %.1f min and %s (seeing, masks and kept frames vary actual time).",
                times, SPEED_NAMES[selected], timeLimitSeconds / 60, mib, n, span,
                measured ? String.format(Locale.ROOT, "%.2f px the last detection run measured", fwhm)
                        : "assumed " + fwhm + " px star size");
        double selectedSeconds = speedSeconds[selected];
        if (Double.isFinite(timeLimitSeconds) && timeLimitSeconds > 0) {
            if (selectedSeconds > timeLimitSeconds) {
                return base + String.format(Locale.ROOT,
                        "<br><b>Warning:</b> Estimated time (%s) is likely to exceed the %.1f min time limit; the search may stop early with partial coverage. "
                                + "Raise the time limit, use a faster Search speed, lower Fastest motion, or enable GPU acceleration.",
                        minutes(selectedSeconds), timeLimitSeconds / 60.0);
            }
            if (selectedSeconds * 2.0 > timeLimitSeconds) {
                return base + String.format(Locale.ROOT,
                        "<br><b>Note:</b> Estimated time (%s) may approach or exceed the %.1f min time limit depending on seeing and kept frames.",
                        minutes(selectedSeconds), timeLimitSeconds / 60.0);
            }
        }
        return base;
    }

    private static double estimateSeconds(int speed, double fwhm, ImportedSession session, double maxSpeed,
                                          double motions, double pixels, double coarseFactor) {
        int[] shape = FaintMoverSearch.coarseSearchShape(speed, fwhm, session.midMinutes(), maxSpeed);
        return estimateSeconds(shape, session.frames(), motions, pixels, coarseFactor);
    }

    private static double estimateSeconds(int[] shape, int frames, double motions, double pixels, double coarseFactor) {
        double coarseSeconds = (shape[0] == 1 ? FULL_ADD_SECONDS : BINNED_ADD_SECONDS) * motions * shape[1] * pixels
                / (shape[0] * shape[0]) * coarseFactor;
        return coarseSeconds + PREPARE_SECONDS * frames * pixels;
    }

    /** A time range from half to twice the estimate, in minutes. */
    private static String minutes(double seconds) {
        return seconds * 2 < 6 ? "under 0.1 min" : String.format(Locale.ROOT, "%.1f–%.1f min",
                Math.max(0.1, seconds / 120), Math.max(0.1, seconds / 30));
    }
}
