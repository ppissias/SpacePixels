package eu.startales.spacepixels.config;

import java.util.List;

/** Typed definitions for the existing streak detection controls; hidden engine fields remain unbound. */
public final class StreakDetectionSettings {
    public static final DetectionSetting<Double> SINGLE_STREAK_MIN_PEAK_SIGMA = new DetectionSetting<>(
            "singleStreakMinPeakSigma", "Single Streak Min Peak Sigma", "sigma",
            "Minimum peak signal-to-noise required for a streak seen in only one frame. Helps reject elongated noise and interpolation artifacts.",
            false, Double.class, config -> config.singleStreakMinPeakSigma,
            (config, value) -> config.singleStreakMinPeakSigma = value);
    public static final DetectionSetting<Double> STREAK_MIN_ELONGATION = new DetectionSetting<>(
            "streakMinElongation", "Streak Min Elongation", "",
            "Minimum elongation required for a detected object to be treated as a streak candidate rather than a point source.",
            true, Double.class, config -> config.streakMinElongation,
            (config, value) -> config.streakMinElongation = value);
    public static final DetectionSetting<Integer> STREAK_MIN_PIXELS = new DetectionSetting<>(
            "streakMinPixels", "Streak Min Pixels", "pixels",
            "Minimum size required for an elongated detection to be accepted as a streak. Higher values reject thin artifacts.",
            true, Integer.class, config -> config.streakMinPixels,
            (config, value) -> config.streakMinPixels = value);
    public static final DetectionSetting<Double> ANGLE_TOLERANCE_DEGREES = new DetectionSetting<>(
            "angleToleranceDegrees", "Trajectory Angle Tolerance", "degrees",
            "For streak-based tracks, maximum allowed difference between the streak angle and the inferred track direction.",
            true, Double.class, config -> config.angleToleranceDegrees,
            (config, value) -> config.angleToleranceDegrees = value);
    public static final DetectionSetting<Double> STREAK_TIME_CONSISTENCY_TOLERANCE = new DetectionSetting<>(
            "streakTimeConsistencyTolerance", "Streak Time Consistency Tolerance", "",
            "When timestamps are available, this controls how much projected-speed variation the multi-frame streak linker tolerates between sampled frames. Higher values are more permissive for fragmented or faint streak trails.",
            true, Double.class, config -> config.streakTimeConsistencyTolerance,
            (config, value) -> config.streakTimeConsistencyTolerance = value);
    public static final DetectionSetting<Boolean> ENABLE_FAINT_STREAK_FRAGMENTS = new DetectionSetting<>(
            "enableFaintStreakFragments", "Assemble Faint Streak Fragments", "",
            "Joins faint pieces that line up in one frame onto an existing streak, including a connected chain a short way past its ends, and builds a new single-frame streak when elongated pieces line up over a long span. Rounder pieces can fill the gaps between those anchors. Pieces below the normal streak gates are included. The tolerance appears below when this is on.",
            true, Boolean.class, config -> config.enableFaintStreakFragments,
            (config, value) -> config.enableFaintStreakFragments = value);
    public static final DetectionSetting<Double> FAINT_STREAK_LINE_TOLERANCE = new DetectionSetting<>(
            "faintStreakLineTolerance", "Faint Streak Line Tolerance", "pixels",
            "How far, in pixels, a piece may sit from the streak line and still be joined. Lower values keep only pieces that sit tightly on the line.",
            true, Double.class, config -> config.faintStreakLineTolerance,
            (config, value) -> config.faintStreakLineTolerance = value);

    /** Prevents constructing the fixed page definitions. */
    private StreakDetectionSettings() {}

    /** Returns the immutable definitions in the existing page order. */
    public static List<DetectionSetting<?>> all() {
        return List.of(SINGLE_STREAK_MIN_PEAK_SIGMA, STREAK_MIN_ELONGATION, STREAK_MIN_PIXELS, ANGLE_TOLERANCE_DEGREES, STREAK_TIME_CONSISTENCY_TOLERANCE, ENABLE_FAINT_STREAK_FRAGMENTS, FAINT_STREAK_LINE_TOLERANCE);
    }
}
