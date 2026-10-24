package eu.startales.spacepixels.config;

import java.util.Objects;

/** Immutable application options captured before processing; site strings retain the existing decimal/cardinal syntax. */
public record DetectionRunOptions(String observatoryCode, String siteLatitude, String siteLongitude,
        VisualizationSettings visualization, int autoTuneMaxCandidateFrames) {
    /** Normalizes absent site values and the existing tuning-limit minimum without reading preferences or globals. */
    public DetectionRunOptions {
        observatoryCode = Objects.requireNonNullElse(observatoryCode, "");
        siteLatitude = Objects.requireNonNullElse(siteLatitude, "");
        siteLongitude = Objects.requireNonNullElse(siteLongitude, "");
        Objects.requireNonNull(visualization, "visualization");
        autoTuneMaxCandidateFrames = SpacePixelsDetectionProfile.normalizeAutoTuneMaxCandidateFrames(autoTuneMaxCandidateFrames);
    }

    /** Returns deterministic headless defaults with no configured observing site or user-home lookup. */
    public static DetectionRunOptions defaults() {
        return new DetectionRunOptions("", "", "", VisualizationSettings.defaults(),
                SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES);
    }

    /**
     * Captures the observing site of desktop/CLI preferences once at an admission boundary, with the built-in
     * visualization settings ({@link #withVisualization} replaces them); subsequent work uses only these values.
     */
    public static DetectionRunOptions capture(AppConfig application, int tuningLimit) {
        AppConfig snapshot = application == null ? new AppConfig() : application.copy();
        return new DetectionRunOptions(snapshot.observatoryCode, snapshot.siteLat, snapshot.siteLong,
                VisualizationSettings.defaults(), tuningLimit);
    }

    /** Returns an independent site/config adapter, excluding solver credentials and private executable paths. */
    public AppConfig applicationConfig() {
        AppConfig config = new AppConfig();
        config.observatoryCode = observatoryCode;
        config.siteLat = siteLatitude;
        config.siteLong = siteLongitude;
        return config;
    }

    /** Replaces rendering choices while retaining the captured site and tuning limit. */
    public DetectionRunOptions withVisualization(VisualizationSettings settings) {
        return new DetectionRunOptions(observatoryCode, siteLatitude, siteLongitude, settings, autoTuneMaxCandidateFrames);
    }

    /** Replaces the requested tuning pool limit without modifying another request or process-wide defaults. */
    public DetectionRunOptions withAutoTuneMaxCandidateFrames(int limit) {
        return new DetectionRunOptions(observatoryCode, siteLatitude, siteLongitude, visualization, limit);
    }

    /** Replaces presentation-only variable-star choices without changing any detection setting. */
    public DetectionRunOptions withVariableStarReportOptions(VariableStarReportOptions options) {
        return withVisualization(visualization.withVariableStarReportOptions(options));
    }
}
