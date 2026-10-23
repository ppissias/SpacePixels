package eu.startales.spacepixels.config;

import java.util.List;

/** Typed definitions for the existing anomaly detection controls; hidden engine fields remain unbound. */
public final class AnomalyDetectionSettings {
    public static final DetectionSetting<Boolean> ENABLE_ANOMALY_RESCUE = new DetectionSetting<>(
            "enableAnomalyRescue", "Enable Anomaly Rescue", "",
            "Keeps single-frame anomaly rescue active for flashes and fragments that do not become full multi-frame tracks.",
            false, Boolean.class, config -> config.enableAnomalyRescue,
            (config, value) -> config.enableAnomalyRescue = value);
    public static final DetectionSetting<Double> ANOMALY_MIN_PEAK_SIGMA = new DetectionSetting<>(
            "anomalyMinPeakSigma", "Anomaly Min Peak Sigma", "sigma",
            "Minimum peak signal-to-noise required for a single-frame point source to be rescued as an anomaly. Higher values are stricter.",
            false, Double.class, config -> config.anomalyMinPeakSigma,
            (config, value) -> config.anomalyMinPeakSigma = value);
    public static final DetectionSetting<Integer> ANOMALY_MIN_PIXELS = new DetectionSetting<>(
            "anomalyMinPixels", "Anomaly Min Pixels", "pixels",
            "Minimum size required for a single-frame point source to be rescued. Higher values reject more hot pixels and cosmic rays.",
            true, Integer.class, config -> config.anomalyMinPixels,
            (config, value) -> config.anomalyMinPixels = value);
    public static final DetectionSetting<Double> ANOMALY_MIN_INTEGRATED_SIGMA = new DetectionSetting<>(
            "anomalyMinIntegratedSigma", "Anomaly Min Integrated Sigma", "sigma",
            "Minimum integrated signal-to-noise required for the broader single-frame anomaly rescue path. Higher values demand stronger total support from faint but larger flashes.",
            true, Double.class, config -> config.anomalyMinIntegratedSigma,
            (config, value) -> config.anomalyMinIntegratedSigma = value);
    public static final DetectionSetting<Integer> ANOMALY_MIN_INTEGRATED_PIXELS = new DetectionSetting<>(
            "anomalyMinIntegratedPixels", "Anomaly Min Integrated Pixels", "pixels",
            "Minimum footprint size required for the integrated-sigma anomaly path. Higher values reject small high-energy fragments from the broader rescue branch.",
            true, Integer.class, config -> config.anomalyMinIntegratedPixels,
            (config, value) -> config.anomalyMinIntegratedPixels = value);
    public static final DetectionSetting<Double> ANOMALY_MIN_PEAK_SIGMA_FLOOR = new DetectionSetting<>(
            "anomalyMinPeakSigmaFloor", "Anomaly Min Peak Sigma Floor", "sigma",
            "Safety floor for diffuse anomaly rescue. Even broader anomalies must retain at least some local prominence to avoid low-contrast mush.",
            true, Double.class, config -> config.anomalyMinPeakSigmaFloor,
            (config, value) -> config.anomalyMinPeakSigmaFloor = value);

    /** Prevents constructing the fixed page definitions. */
    private AnomalyDetectionSettings() {}

    /** Returns the immutable definitions in the existing page order. */
    public static List<DetectionSetting<?>> all() {
        return List.of(ENABLE_ANOMALY_RESCUE, ANOMALY_MIN_PEAK_SIGMA, ANOMALY_MIN_PIXELS, ANOMALY_MIN_INTEGRATED_SIGMA, ANOMALY_MIN_INTEGRATED_PIXELS, ANOMALY_MIN_PEAK_SIGMA_FLOOR);
    }
}
