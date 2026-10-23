package eu.startales.spacepixels.config;

import java.util.List;

/** Typed definitions for the eight Overview values managed by Auto-Tune and shared with frame previews. */
public final class CoreDetectionSettings {
    public static final DetectionSetting<Double> DETECTION_SIGMA_MULTIPLIER = new DetectionSetting<>(
            "detectionSigmaMultiplier", "Detection Sigma", "sigma",
            "Minimum brightness threshold for starting a new detection. Higher values reduce noise; lower values detect fainter objects.",
            false, Double.class, config -> config.detectionSigmaMultiplier,
            (config, value) -> config.detectionSigmaMultiplier = value);
    public static final DetectionSetting<Double> GROW_SIGMA_MULTIPLIER = new DetectionSetting<>(
            "growSigmaMultiplier", "Grow Sigma (Hysteresis)", "sigma",
            "Secondary threshold used to expand a detection after it starts during per-frame object detection. Lower values capture fainter edges; higher values keep detections tighter. It cannot exceed Detection Sigma. If it goes below Master Sigma, faint star wings detected in the frames may extend past the veto mask and leak as transients; the calibrated Auto-Tuner measures that trade-off.",
            false, Double.class, config -> config.growSigmaMultiplier,
            (config, value) -> config.growSigmaMultiplier = value);
    public static final DetectionSetting<Integer> MIN_DETECTION_PIXELS = new DetectionSetting<>(
            "minDetectionPixels", "Min Detection Pixels", "pixels",
            "Minimum blob size required for a detection to be kept. Higher values reject hot pixels and noise; lower values allow smaller sources.",
            false, Integer.class, config -> config.minDetectionPixels,
            (config, value) -> config.minDetectionPixels = value);
    public static final DetectionSetting<Double> MASTER_SIGMA_MULTIPLIER = new DetectionSetting<>(
            "masterSigmaMultiplier", "Master Sigma", "sigma",
            "Detection threshold used when building the master star map. Lower values mask more faint stars and halos; higher values create a smaller, cleaner mask. Stars are seeded at this value and grown down to Master Grow Sigma (or this value when that is 0). A master shallower than the per-frame Grow Sigma hides less sky but lets more star wings leak; the calibrated Auto-Tuner measures that trade-off.",
            false, Double.class, config -> config.masterSigmaMultiplier,
            (config, value) -> config.masterSigmaMultiplier = value);
    public static final DetectionSetting<Double> MASTER_GROW_SIGMA_MULTIPLIER = new DetectionSetting<>(
            "masterGrowSigmaMultiplier", "Master Grow Sigma", "sigma",
            "Threshold for growing master-map stars after they are seeded at Master Sigma. A lower value covers the faint wings of stars without adding noise islands; 0 uses Master Sigma (no hysteresis). Cannot exceed Master Sigma.",
            false, Double.class, config -> config.masterGrowSigmaMultiplier,
            (config, value) -> config.masterGrowSigmaMultiplier = value);
    public static final DetectionSetting<Integer> MASTER_MIN_DETECTION_PIXELS = new DetectionSetting<>(
            "masterMinDetectionPixels", "Master Min Pixels", "pixels",
            "Minimum size required for a source to be included in the master star map. Lower values include fainter stars.",
            false, Integer.class, config -> config.masterMinDetectionPixels,
            (config, value) -> config.masterMinDetectionPixels = value);
    public static final DetectionSetting<Double> MAX_MASK_OVERLAP_FRACTION = new DetectionSetting<>(
            "maxMaskOverlapFraction", "Max Mask Overlap Fraction", "",
            "Maximum fraction of a point footprint that may overlap the master veto mask before it is rejected as likely stellar residual contamination.",
            false, Double.class, config -> config.maxMaskOverlapFraction,
            (config, value) -> config.maxMaskOverlapFraction = value);
    public static final DetectionSetting<Double> MAX_STAR_JITTER = new DetectionSetting<>(
            "maxStarJitter", "Base Star Jitter Radius", "pixels",
            "Baseline radius under which detections are treated as stationary star jitter instead of true moving points. Higher values are more conservative around registration residuals.",
            false, Double.class, config -> config.maxStarJitter,
            (config, value) -> config.maxStarJitter = value);

    /** Prevents constructing the fixed Overview definitions. */
    private CoreDetectionSettings() {}

    /** Returns the immutable definitions in Overview display order. */
    public static List<DetectionSetting<?>> all() {
        return List.of(DETECTION_SIGMA_MULTIPLIER, GROW_SIGMA_MULTIPLIER, MIN_DETECTION_PIXELS, MASTER_SIGMA_MULTIPLIER, MASTER_GROW_SIGMA_MULTIPLIER, MASTER_MIN_DETECTION_PIXELS, MAX_MASK_OVERLAP_FRACTION, MAX_STAR_JITTER);
    }
}
