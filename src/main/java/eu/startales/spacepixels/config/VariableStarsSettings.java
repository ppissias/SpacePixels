package eu.startales.spacepixels.config;

import java.util.List;

/** Typed definitions for the existing variable stars controls; hidden engine fields remain unbound. */
public final class VariableStarsSettings {
    public static final DetectionSetting<Boolean> ENABLE_VARIABLE_STAR_DETECTION = new DetectionSetting<>(
            "enableVariableStarDetection", "Enable Variable-Star Detection", "",
            "Run stationary-star photometry and variable-star detection after moving-object tracking.<br>Adds processing time proportional to stars x frames.",
            false, Boolean.class, config -> config.enableVariableStarDetection,
            (config, value) -> config.enableVariableStarDetection = value);
    public static final DetectionSetting<Double> VARIABLE_SCORE_SIGMA = new DetectionSetting<>(
            "variableScoreSigma", "Variability Score Sigma", "",
            "How far above same-brightness stars both the scatter score and the Stetson J score must be for a star to become a candidate.<br>Higher values give fewer, safer candidates.",
            true, Double.class, config -> config.variableScoreSigma,
            (config, value) -> config.variableScoreSigma = value);
    public static final DetectionSetting<Double> VARIABLE_MIN_AMPLITUDE_MAG = new DetectionSetting<>(
            "variableMinAmplitudeMag", "Min Amplitude (mag)", "mag",
            "Smallest brightness change (95th minus 5th percentile) reported as high confidence.",
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
    public static final DetectionSetting<Double> LINEARITY_MAX_CONCENTRATION_DRIFT = new DetectionSetting<>(
            "linearityMaxConcentrationDrift", "B: Max Concentration Drift", "",
            "Largest change of star concentration (flux in 0.7 x FWHM / flux in 2.5 x FWHM) from faint to bright stars inside the linear range.<br>Stretched data makes bright stars flatter.",
            true, Double.class, config -> config.linearityMaxConcentrationDrift,
            (config, value) -> config.linearityMaxConcentrationDrift = value);
    public static final DetectionSetting<Double> LINEARITY_MIN_RANGE_MAG = new DetectionSetting<>(
            "linearityMinRangeMag", "B: Min Linear Range (mag)", "mag",
            "Frames whose linear magnitude range is narrower than this are excluded.",
            true, Double.class, config -> config.linearityMinRangeMag,
            (config, value) -> config.linearityMinRangeMag = value);
    public static final DetectionSetting<Double> LINEARITY_MAX_FRAME_SLOPE = new DetectionSetting<>(
            "linearityMaxFrameSlope", "D: Max Response Slope (mag/mag)", "mag/mag",
            "Largest allowed difference in how bright and faint stars respond within one frame. Failing frames are excluded.",
            true, Double.class, config -> config.linearityMaxFrameSlope,
            (config, value) -> config.linearityMaxFrameSlope = value);
    public static final DetectionSetting<Double> LINEARITY_MAX_FAILING_FRAME_FRACTION = new DetectionSetting<>(
            "linearityMaxFailingFrameFraction", "B/D: Max Failing Frame Fraction", "",
            "Share of frames that may fail check B or D before the whole session is refused.",
            true, Double.class, config -> config.linearityMaxFailingFrameFraction,
            (config, value) -> config.linearityMaxFailingFrameFraction = value);
    public static final DetectionSetting<Double> VARIABLE_LIMITED_MIN_AMPLITUDE_MAG = new DetectionSetting<>(
            "variableLimitedMinAmplitudeMag", "Min Amplitude When Limited (mag)", "mag",
            "Smallest amplitude reported as high confidence when the linearity verdict is Limited.",
            true, Double.class, config -> config.variableLimitedMinAmplitudeMag,
            (config, value) -> config.variableLimitedMinAmplitudeMag = value);
    public static final DetectionSetting<Double> VARIABLE_AMPLITUDE_NOISE_FACTOR = new DetectionSetting<>(
            "variableAmplitudeNoiseFactor", "Amplitude Noise Factor", "",
            "The amplitude must also exceed this many times the expected scatter at the star's brightness.",
            true, Double.class, config -> config.variableAmplitudeNoiseFactor,
            (config, value) -> config.variableAmplitudeNoiseFactor = value);
    public static final DetectionSetting<Integer> VARIABLE_MIN_PERSISTENCE_FRAMES = new DetectionSetting<>(
            "variableMinPersistenceFrames", "Min Persistence (frames)", "frames",
            "Fewest consecutive frames that must deviate in the same direction. Rejects single-frame events.",
            true, Integer.class, config -> config.variableMinPersistenceFrames,
            (config, value) -> config.variableMinPersistenceFrames = value);
    public static final DetectionSetting<Double> VARIABLE_MIN_SPLIT_HALF_CORRELATION = new DetectionSetting<>(
            "variableMinSplitHalfCorrelation", "Min Split-Half Correlation", "",
            "Consecutive measurement pairs must agree at least this well. Rejects noise that only looks coherent.",
            true, Double.class, config -> config.variableMinSplitHalfCorrelation,
            (config, value) -> config.variableMinSplitHalfCorrelation = value);
    public static final DetectionSetting<Double> VARIABLE_MAX_APERTURE_AMPLITUDE_DIFFERENCE = new DetectionSetting<>(
            "variableMaxApertureAmplitudeDifference", "Max Aperture Amplitude Difference", "",
            "Amplitudes measured with small and large apertures may differ by at most this fraction. Rejects neighbour leakage and seeing effects.",
            true, Double.class, config -> config.variableMaxApertureAmplitudeDifference,
            (config, value) -> config.variableMaxApertureAmplitudeDifference = value);
    public static final DetectionSetting<Double> VARIABLE_MAX_SYSTEMATICS_CORRELATION = new DetectionSetting<>(
            "variableMaxSystematicsCorrelation", "Max Systematics Correlation", "",
            "Light curves correlating more than this with transparency, FWHM, local sky or registration offsets are compared with how strongly constant stars of similar brightness respond to the same systematic.<br>Correlation alone does not reject a star: real variables that brighten or fade steadily correlate with any steady drift.",
            true, Double.class, config -> config.variableMaxSystematicsCorrelation,
            (config, value) -> config.variableMaxSystematicsCorrelation = value);
    public static final DetectionSetting<Double> VARIABLE_LOCAL_RADIUS_PIXELS = new DetectionSetting<>(
            "variableLocalRadiusPixels", "Local Comparison Radius (px)", "pixels",
            "Radius within which sufficiently precise nearby stars are checked for the same pattern.",
            true, Double.class, config -> config.variableLocalRadiusPixels,
            (config, value) -> config.variableLocalRadiusPixels = value);
    public static final DetectionSetting<Double> VARIABLE_MAX_LOCAL_CORRELATION = new DetectionSetting<>(
            "variableMaxLocalCorrelation", "Max Local Correlation", "",
            "Largest allowed median correlation with nearby comparison stars. Rejects dust, dew and local gradients.",
            true, Double.class, config -> config.variableMaxLocalCorrelation,
            (config, value) -> config.variableMaxLocalCorrelation = value);

    /** Prevents constructing the fixed page definitions. */
    private VariableStarsSettings() {}

    /** Returns the immutable definitions in the existing page order. */
    public static List<DetectionSetting<?>> all() {
        return List.of(ENABLE_VARIABLE_STAR_DETECTION, VARIABLE_SCORE_SIGMA, VARIABLE_MIN_AMPLITUDE_MAG, VARIABLE_MIN_FRAMES, VARIABLE_MIN_SPAN_MINUTES, PHOTOMETRY_MAX_STARS, PHOTOMETRY_MIN_SNR, LINEARITY_MAX_CONCENTRATION_DRIFT, LINEARITY_MIN_RANGE_MAG, LINEARITY_MAX_FRAME_SLOPE, LINEARITY_MAX_FAILING_FRAME_FRACTION, VARIABLE_LIMITED_MIN_AMPLITUDE_MAG, VARIABLE_AMPLITUDE_NOISE_FACTOR, VARIABLE_MIN_PERSISTENCE_FRAMES, VARIABLE_MIN_SPLIT_HALF_CORRELATION, VARIABLE_MAX_APERTURE_AMPLITUDE_DIFFERENCE, VARIABLE_MAX_SYSTEMATICS_CORRELATION, VARIABLE_LOCAL_RADIUS_PIXELS, VARIABLE_MAX_LOCAL_CORRELATION);
    }
}
