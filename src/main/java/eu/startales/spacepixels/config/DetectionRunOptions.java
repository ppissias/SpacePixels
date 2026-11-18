package eu.startales.spacepixels.config;

import java.util.Objects;

/** Immutable run options; latitude/longitude retain decimal/cardinal syntax and optional altitude is in metres. */
public record DetectionRunOptions(String observatoryCode, String siteLatitude, String siteLongitude,
        String siteAltitude, VisualizationSettings visualization, int autoTuneMaxCandidateFrames,
        String gpuMode, String gpuDeviceId) {
    /** Retains the existing constructor for callers without a configured altitude or GPU preference. */
    public DetectionRunOptions(String observatoryCode, String siteLatitude, String siteLongitude,
            VisualizationSettings visualization, int autoTuneMaxCandidateFrames) {
        this(observatoryCode, siteLatitude, siteLongitude, "", visualization, autoTuneMaxCandidateFrames, "CPU", "");
    }
    /** Retains the existing 6-argument constructor for headless API callers without explicit GPU preferences. */
    public DetectionRunOptions(String observatoryCode, String siteLatitude, String siteLongitude,
            String siteAltitude, VisualizationSettings visualization, int autoTuneMaxCandidateFrames) {
        this(observatoryCode, siteLatitude, siteLongitude, siteAltitude, visualization, autoTuneMaxCandidateFrames, "CPU", "");
    }
    /** Normalizes absent site/GPU values and the existing tuning-limit minimum without reading preferences or globals. */
    public DetectionRunOptions {
        observatoryCode = Objects.requireNonNullElse(observatoryCode, "");
        siteLatitude = Objects.requireNonNullElse(siteLatitude, "");
        siteLongitude = Objects.requireNonNullElse(siteLongitude, "");
        siteAltitude = Objects.requireNonNullElse(siteAltitude, "");
        Objects.requireNonNull(visualization, "visualization");
        autoTuneMaxCandidateFrames = SpacePixelsDetectionProfile.normalizeAutoTuneMaxCandidateFrames(autoTuneMaxCandidateFrames);
        gpuMode = normalizeGpuMode(gpuMode);
        gpuDeviceId = Objects.requireNonNullElse(gpuDeviceId, "").trim();
    }

    /** Returns deterministic headless defaults with no configured observing site, GPU lookup or user-home cache. */
    public static DetectionRunOptions defaults() {
        return new DetectionRunOptions("", "", "", "", VisualizationSettings.defaults(),
                SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES, "CPU", "");
    }

    /**
     * Captures the observing site and GPU compute preferences of desktop/CLI preferences once at an admission
     * boundary, with the built-in visualization settings ({@link #withVisualization} replaces them); subsequent work
     * uses only these values.
     */
    public static DetectionRunOptions capture(AppConfig application, int tuningLimit) {
        AppConfig snapshot = application == null ? new AppConfig() : application.copy();
        return new DetectionRunOptions(snapshot.observatoryCode, snapshot.siteLat, snapshot.siteLong,
                snapshot.siteAltitude, VisualizationSettings.defaults(), tuningLimit,
                snapshot.gpuMode(), snapshot.gpuDeviceId());
    }

    /** Returns an independent site/compute adapter, excluding solver credentials and private executable paths. */
    public AppConfig applicationConfig() {
        AppConfig config = new AppConfig();
        config.observatoryCode = observatoryCode;
        config.siteLat = siteLatitude;
        config.siteLong = siteLongitude;
        config.siteAltitude = siteAltitude;
        config.gpuMode = gpuMode;
        config.gpuDeviceId = gpuDeviceId;
        return config;
    }

    /** Replaces rendering choices while retaining the captured site, GPU preferences and tuning limit. */
    public DetectionRunOptions withVisualization(VisualizationSettings settings) {
        return new DetectionRunOptions(observatoryCode, siteLatitude, siteLongitude, siteAltitude, settings,
                autoTuneMaxCandidateFrames, gpuMode, gpuDeviceId);
    }

    /** Replaces the requested tuning pool limit without modifying another request or process-wide defaults. */
    public DetectionRunOptions withAutoTuneMaxCandidateFrames(int limit) {
        return new DetectionRunOptions(observatoryCode, siteLatitude, siteLongitude, siteAltitude, visualization,
                limit, gpuMode, gpuDeviceId);
    }

    /** Replaces the requested GPU compute mode ({@code AUTO}, {@code GPU}, or {@code CPU}). */
    public DetectionRunOptions withGpuMode(String mode) {
        return new DetectionRunOptions(observatoryCode, siteLatitude, siteLongitude, siteAltitude, visualization,
                autoTuneMaxCandidateFrames, mode, gpuDeviceId);
    }

    /** Replaces the requested GPU device identifier. */
    public DetectionRunOptions withGpuDeviceId(String deviceId) {
        return new DetectionRunOptions(observatoryCode, siteLatitude, siteLongitude, siteAltitude, visualization,
                autoTuneMaxCandidateFrames, gpuMode, deviceId);
    }

    /** Replaces presentation-only variable-star choices without changing any detection setting. */
    public DetectionRunOptions withVariableStarReportOptions(VariableStarReportOptions options) {
        return withVisualization(visualization.withVariableStarReportOptions(options));
    }

    private static String normalizeGpuMode(String value) {
        if (value == null || value.isBlank()) {
            return "CPU";
        }
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "AUTO" -> "AUTO";
            case "GPU" -> "GPU";
            case "CPU" -> "CPU";
            default -> "CPU";
        };
    }
}
