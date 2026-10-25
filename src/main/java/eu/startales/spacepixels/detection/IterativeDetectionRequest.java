package eu.startales.spacepixels.detection;

import eu.startales.spacepixels.config.DetectionRunOptions;
import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.VisualizationSettings;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.config.DetectionConfigRules;

import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import java.io.File;
import java.util.Objects;
import eu.startales.spacepixels.skycatalog.SkySnapshot;

public record IterativeDetectionRequest(File directory, DetectionConfig config, AppConfig appConfig,
                                       VisualizationSettings visualization, IterativeDetectionPlan plan,
                                       AutoTuneProfile autoTuneProfile, int autoTuneFrames,
                                       boolean autoPrepare, boolean keepNegativeValues, int expectedFrameCount,
                                       SkySnapshot sky) {
    public IterativeDetectionRequest {
        Objects.requireNonNull(directory);
        config = DetectionConfigRules.snapshot(config, DetectionConfigRules.Operation.CONFIGURATION);
        appConfig = Objects.requireNonNull(appConfig).copy();
        Objects.requireNonNull(visualization);
        Objects.requireNonNull(plan);
        if (autoTuneFrames < 5) throw new IllegalArgumentException("Calibrated Auto-Tune needs a sample limit of at least 5 frames.");
        if (expectedFrameCount < 0) throw new IllegalArgumentException("Expected frame count cannot be negative.");
    }

    @Override public DetectionConfig config() { return config.clone(); }
    @Override public AppConfig appConfig() { return appConfig.copy(); }

    /** Returns the admitted application options without consulting runtime defaults during a pass. */
    public DetectionRunOptions runOptions() {
        return new DetectionRunOptions(appConfig.observatoryCode,
                appConfig.siteLat, appConfig.siteLong, visualization, autoTuneFrames);
    }
}
