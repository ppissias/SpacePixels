package eu.startales.spacepixels.config;

import java.util.List;

/** Typed definitions for the existing slow movers controls; hidden engine fields remain unbound. */
public final class SlowMoversSettings {
    public static final DetectionSetting<Boolean> ENABLE_SLOW_MOVER_DETECTION = new DetectionSetting<>(
            "enableSlowMoverDetection", "Enable Slow-Mover Detection", "",
            "Look for elongated shapes in the maximum stack after poor-quality frames are removed.<br>Results are candidates for review, not confirmed tracks.",
            false, Boolean.class, config -> config.enableSlowMoverDetection,
            (config, value) -> config.enableSlowMoverDetection = value);
    public static final DetectionSetting<Double> MASTER_SLOW_MOVER_SIGMA_MULTIPLIER = new DetectionSetting<>(
            "masterSlowMoverSigmaMultiplier", "Slow-Mover Sigma", "sigma",
            "A pixel must rise this far above the estimated stack background to start a source.<br>Used on both stacks. Lower values find fainter sources but may add noise.",
            false, Double.class, config -> config.masterSlowMoverSigmaMultiplier,
            (config, value) -> config.masterSlowMoverSigmaMultiplier = value);
    public static final DetectionSetting<Double> MASTER_SLOW_MOVER_GROW_SIGMA_MULTIPLIER = new DetectionSetting<>(
            "masterSlowMoverGrowSigmaMultiplier", "Slow-Mover Grow Sigma", "sigma",
            "Neighboring pixels above this lower brightness threshold join a source.<br>Used on both stacks. Lower values enlarge shapes and may merge sources.<br>Cannot exceed Slow-Mover Sigma.",
            false, Double.class, config -> config.masterSlowMoverGrowSigmaMultiplier,
            (config, value) -> config.masterSlowMoverGrowSigmaMultiplier = value);
    public static final DetectionSetting<Integer> MASTER_SLOW_MOVER_MIN_PIXELS = new DetectionSetting<>(
            "masterSlowMoverMinPixels", "Slow-Mover Min Pixels", "pixels",
            "Fewest connected pixels required to keep a source in either stack.<br>Higher values reject small noise spots but may miss small sources.",
            false, Integer.class, config -> config.masterSlowMoverMinPixels,
            (config, value) -> config.masterSlowMoverMinPixels = value);
    public static final DetectionSetting<Double> SLOW_MOVER_MIN_AXIS_RATIO = new DetectionSetting<>(
            "slowMoverMinAxisRatio", "Min Axis Ratio", "",
            "Length divided by width of a candidate's detected shape; 1 is roughly round.<br>Raise this to reject rounder sources.",
            true, Double.class, config -> config.slowMoverMinAxisRatio,
            (config, value) -> config.slowMoverMinAxisRatio = value);
    public static final DetectionSetting<Double> SLOW_MOVER_MAX_AXIS_RATIO = new DetectionSetting<>(
            "slowMoverMaxAxisRatio", "Max Axis Ratio", "",
            "Largest allowed length-to-width ratio for a candidate.<br>Lower this to reject longer, thinner streaks. Cannot be below Min Axis Ratio.",
            true, Double.class, config -> config.slowMoverMaxAxisRatio,
            (config, value) -> config.slowMoverMaxAxisRatio = value);
    public static final DetectionSetting<Double> SLOW_MOVER_MIN_FILL_FACTOR = new DetectionSetting<>(
            "slowMoverMinFillFactor", "Min Fill Factor", "",
            "How solid the shape must be inside a rectangle aligned with it.<br>Fill factor is the fraction of that rectangle covered by detected pixels.<br>Higher values reject sparse, bent, or branched shapes. Zero disables it.",
            true, Double.class, config -> config.slowMoverMinFillFactor,
            (config, value) -> config.slowMoverMinFillFactor = value);
    public static final DetectionSetting<Double> SLOW_MOVER_MEDIAN_SUPPORT_OVERLAP_FRACTION = new DetectionSetting<>(
            "slowMoverMedianSupportOverlapFraction", "Median Mask Min Overlap", "",
            "Minimum share of candidate pixels also in the median-stack source mask.<br>0.20 requires 20% overlap; 0 does not require overlap.<br>Higher values require more persistence.",
            true, Double.class, config -> config.slowMoverMedianSupportOverlapFraction,
            (config, value) -> config.slowMoverMedianSupportOverlapFraction = value);
    public static final DetectionSetting<Double> SLOW_MOVER_MEDIAN_SUPPORT_MAX_OVERLAP_FRACTION = new DetectionSetting<>(
            "slowMoverMedianSupportMaxOverlapFraction", "Median Mask Max Overlap", "",
            "Largest share of candidate pixels allowed in the median-stack source mask.<br>0.80 rejects over 80% overlap. Lower values reject more stationary sources.<br>1 disables this limit.",
            true, Double.class, config -> config.slowMoverMedianSupportMaxOverlapFraction,
            (config, value) -> config.slowMoverMedianSupportMaxOverlapFraction = value);
    public static final DetectionSetting<Double> SLOW_MOVER_MIN_FRAME_SUPPORT = new DetectionSetting<>(
            "slowMoverMinFrameSupport", "Min Frame Support (%)", "percent",
            "Minimum share of usable frames with significant signal inside the candidate shape.<br>Raise to require evidence in more frames; zero disables this rejection.<br>Skipped if fewer than two usable frames. This does not confirm motion.",
            true, Double.class, config -> config.slowMoverMinFrameSupport,
            (config, value) -> config.slowMoverMinFrameSupport = value);
    public static final DetectionSetting<Double> SLOW_MOVER_MAX_STATIONARY_LIKELIHOOD = new DetectionSetting<>(
            "slowMoverMaxStationaryLikelihood", "Max Stationary Likelihood (%)", "percent",
            "Largest allowed share of supported frame positions clustered near one spot.<br>Lower to reject more stationary-looking candidates; 100 disables it.<br>Skipped if evidence is insufficient. This heuristic is not a probability.",
            true, Double.class, config -> config.slowMoverMaxStationaryLikelihood,
            (config, value) -> config.slowMoverMaxStationaryLikelihood = value);

    /** Prevents constructing the fixed page definitions. */
    private SlowMoversSettings() {}

    /** Returns the immutable definitions in the existing page order. */
    public static List<DetectionSetting<?>> all() {
        return List.of(ENABLE_SLOW_MOVER_DETECTION, MASTER_SLOW_MOVER_SIGMA_MULTIPLIER, MASTER_SLOW_MOVER_GROW_SIGMA_MULTIPLIER, MASTER_SLOW_MOVER_MIN_PIXELS, SLOW_MOVER_MIN_AXIS_RATIO, SLOW_MOVER_MAX_AXIS_RATIO, SLOW_MOVER_MIN_FILL_FACTOR, SLOW_MOVER_MEDIAN_SUPPORT_OVERLAP_FRACTION, SLOW_MOVER_MEDIAN_SUPPORT_MAX_OVERLAP_FRACTION, SLOW_MOVER_MIN_FRAME_SUPPORT, SLOW_MOVER_MAX_STATIONARY_LIKELIHOOD);
    }
}
