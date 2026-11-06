package eu.startales.spacepixels.config;

import java.util.List;

/** Typed definitions for the curated variable-star controls; other engine fields remain configurable in profiles. */
public final class VariableStarsSettings {
    public static final DetectionSetting<Boolean> ENABLE_VARIABLE_STAR_DETECTION = new DetectionSetting<>(
            "enableVariableStarDetection", "Enable Variable-Star Detection", "",
            "Run stationary-star photometry and variable-star detection after moving-object tracking.<br>Adds processing time proportional to stars x frames.",
            false, Boolean.class, config -> config.enableVariableStarDetection,
            (config, value) -> config.enableVariableStarDetection = value);
    public static final DetectionSetting<Double> VARIABLE_SCORE_SIGMA = new DetectionSetting<>(
            "variableScoreSigma", "Variability Score Threshold", "",
            "How far above same-brightness stars both the scatter score and the Stetson J score must be for a star to become a candidate.<br>Higher values give fewer, safer candidates.",
            true, Double.class, config -> config.variableScoreSigma,
            (config, value) -> config.variableScoreSigma = value);
    public static final DetectionSetting<Double> VARIABLE_MIN_AMPLITUDE_MAG = new DetectionSetting<>(
            "variableMinAmplitudeMag", "Min Amplitude (mag)", "mag",
            "Smallest brightness change (95th minus 5th percentile) reported as high confidence.<br>Also used by the peak-removal artifact check; changing it requires a new analysis. The Limited amplitude floor remains in the profile; Review rules checks their consistency.",
            true, Double.class, config -> config.variableMinAmplitudeMag,
            (config, value) -> config.variableMinAmplitudeMag = value);
    public static final DetectionSetting<Integer> VARIABLE_MIN_FRAMES = new DetectionSetting<>(
            "variableMinFrames", "Min Frames", "frames",
            "Fewest usable measurements a star needs to be scored, and fewest frames the session must keep after the photometry checks.",
            true, Integer.class, config -> config.variableMinFrames,
            (config, value) -> config.variableMinFrames = value);
    public static final DetectionSetting<Double> VARIABLE_MIN_SPAN_MINUTES = new DetectionSetting<>(
            "variableMinSpanMinutes", "Min Time Span (minutes)", "minutes",
            "Shortest time span of a candidate's measurements for a high-confidence result.<br>Ignored when the frames have no timestamps.",
            true, Double.class, config -> config.variableMinSpanMinutes,
            (config, value) -> config.variableMinSpanMinutes = value);
    public static final DetectionSetting<Integer> PHOTOMETRY_MAX_STARS = new DetectionSetting<>(
            "photometryMaxStars", "Max Stars Measured", "stars",
            "Most stars measured; 0 measures every usable star (recommended).<br>A cap picks stars evenly across the field and the brightness range. Saturated stars are always skipped.",
            true, Integer.class, config -> config.photometryMaxStars,
            (config, value) -> config.photometryMaxStars = value);
    public static final DetectionSetting<Double> PHOTOMETRY_MIN_SNR = new DetectionSetting<>(
            "photometryMinSnr", "Min Star SNR", "",
            "Stars fainter than this median signal-to-noise ratio are measured and exported, but not used or scored.<br>0 disables the cut.",
            true, Double.class, config -> config.photometryMinSnr,
            (config, value) -> config.photometryMinSnr = value);
    public static final DetectionSetting<Integer> VARIABLE_MIN_PERSISTENCE_FRAMES = new DetectionSetting<>(
            "variableMinPersistenceFrames", "Min Persistence (frames)", "frames",
            "Fewest consecutive frames that must deviate in the same direction. Rejects single-frame events.",
            true, Integer.class, config -> config.variableMinPersistenceFrames,
            (config, value) -> config.variableMinPersistenceFrames = value);
    public static final DetectionSetting<Double> VARIABLE_LOCAL_RADIUS_PIXELS = new DetectionSetting<>(
            "variableLocalRadiusPixels", "Local Comparison Radius (px)", "pixels",
            "Radius within which sufficiently precise nearby stars are checked for the same pattern.<br>Sparse fields can use the nearest comparison stars out to three times this radius.",
            true, Double.class, config -> config.variableLocalRadiusPixels,
            (config, value) -> config.variableLocalRadiusPixels = value);

    /** Prevents constructing the fixed page definitions. */
    private VariableStarsSettings() {}

    /** Returns the immutable definitions in the existing page order. */
    public static List<DetectionSetting<?>> all() {
        return List.of(ENABLE_VARIABLE_STAR_DETECTION, VARIABLE_SCORE_SIGMA, VARIABLE_MIN_AMPLITUDE_MAG, VARIABLE_MIN_FRAMES, VARIABLE_MIN_SPAN_MINUTES, VARIABLE_MIN_PERSISTENCE_FRAMES, PHOTOMETRY_MAX_STARS, PHOTOMETRY_MIN_SNR, VARIABLE_LOCAL_RADIUS_PIXELS);
    }
}
