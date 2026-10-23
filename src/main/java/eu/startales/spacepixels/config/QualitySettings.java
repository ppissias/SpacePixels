package eu.startales.spacepixels.config;

import java.util.List;

/** Typed definitions for the existing quality controls; hidden engine fields remain unbound. */
public final class QualitySettings {
    public static final DetectionSetting<Double> STAR_COUNT_SIGMA_DEVIATION = new DetectionSetting<>(
            "starCountSigmaDeviation", "Star Count Drop Sigma", "sigma",
            "Rejects frames whose star count falls too far below the session median. Higher values are more tolerant.",
            true, Double.class, config -> config.starCountSigmaDeviation,
            (config, value) -> config.starCountSigmaDeviation = value);
    public static final DetectionSetting<Double> FWHM_SIGMA_DEVIATION = new DetectionSetting<>(
            "fwhmSigmaDeviation", "FWHM Spike Sigma", "sigma",
            "Rejects frames whose median FWHM rises too far above the session median. Higher values are more tolerant.",
            true, Double.class, config -> config.fwhmSigmaDeviation,
            (config, value) -> config.fwhmSigmaDeviation = value);
    public static final DetectionSetting<Double> ECCENTRICITY_SIGMA_DEVIATION = new DetectionSetting<>(
            "eccentricitySigmaDeviation", "Eccentricity Spike Sigma", "sigma",
            "Rejects frames whose star shapes become too elongated compared with the session median. Higher values are more tolerant.",
            true, Double.class, config -> config.eccentricitySigmaDeviation,
            (config, value) -> config.eccentricitySigmaDeviation = value);
    public static final DetectionSetting<Boolean> ENABLE_BRIGHT_STAR_ECCENTRICITY_FILTER = new DetectionSetting<>(
            "enableBrightStarEccentricityFilter", "Enable Bright-Star Eccentricity Filter", "",
            "Runs an extra rejection gate using only the brightest quality stars, which is useful for catching tracking error or wind that shows up most clearly on high-SNR stars.",
            true, Boolean.class, config -> config.enableBrightStarEccentricityFilter,
            (config, value) -> config.enableBrightStarEccentricityFilter = value);
    public static final DetectionSetting<Double> BRIGHT_STAR_ECCENTRICITY_SIGMA_DEVIATION = new DetectionSetting<>(
            "brightStarEccentricitySigmaDeviation", "Bright-Star Eccentricity Sigma", "sigma",
            "Rejects frames whose bright-star median eccentricity rises too far above the session median. Higher values are more tolerant.",
            true, Double.class, config -> config.brightStarEccentricitySigmaDeviation,
            (config, value) -> config.brightStarEccentricitySigmaDeviation = value);
    public static final DetectionSetting<Double> BACKGROUND_SIGMA_DEVIATION = new DetectionSetting<>(
            "backgroundSigmaDeviation", "Background Deviation Sigma", "sigma",
            "Rejects frames whose background level deviates too much from the session median. Higher values are more tolerant.",
            true, Double.class, config -> config.backgroundSigmaDeviation,
            (config, value) -> config.backgroundSigmaDeviation = value);
    public static final DetectionSetting<Double> MIN_BACKGROUND_DEVIATION_ADU = new DetectionSetting<>(
            "minBackgroundDeviationADU", "Min Background Deviation (ADU)", "ADU",
            "Smallest allowed sky-level shift, in ADU, even when the measured session variation is tiny. The allowed shift is also never smaller than the typical sky noise of one frame.",
            true, Double.class, config -> config.minBackgroundDeviationADU,
            (config, value) -> config.minBackgroundDeviationADU = value);

    /** Prevents constructing the fixed page definitions. */
    private QualitySettings() {}

    /** Returns the immutable definitions in the existing page order. */
    public static List<DetectionSetting<?>> all() {
        return List.of(STAR_COUNT_SIGMA_DEVIATION, FWHM_SIGMA_DEVIATION, ECCENTRICITY_SIGMA_DEVIATION, ENABLE_BRIGHT_STAR_ECCENTRICITY_FILTER, BRIGHT_STAR_ECCENTRICITY_SIGMA_DEVIATION, BACKGROUND_SIGMA_DEVIATION, MIN_BACKGROUND_DEVIATION_ADU);
    }
}
