package eu.startales.spacepixels.util;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.util.reporting.ExportVisualizationSettings;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner.AutoTuneProfile;

import java.io.File;
import java.util.Objects;

public record IterativeDetectionRequest(File directory, DetectionConfig config, AppConfig appConfig,
                                       ExportVisualizationSettings visualization, IterativeDetectionPlan plan,
                                       AutoTuneProfile autoTuneProfile, int autoTuneFrames,
                                       boolean autoPrepare, boolean keepNegativeValues, int expectedFrameCount) {
    public IterativeDetectionRequest {
        Objects.requireNonNull(directory);
        config = Objects.requireNonNull(config).clone();
        java.util.List<String> problems = eu.startales.spacepixels.config.DetectionSettingsRules.problems(config);
        if (!problems.isEmpty()) throw new IllegalArgumentException("Invalid detection settings: " + problems.getFirst());
        appConfig = Objects.requireNonNull(appConfig).copy();
        Objects.requireNonNull(visualization);
        Objects.requireNonNull(plan);
        if (autoTuneFrames < 5) throw new IllegalArgumentException("Calibrated Auto-Tune needs a sample limit of at least 5 frames.");
        if (expectedFrameCount < 0) throw new IllegalArgumentException("Expected frame count cannot be negative.");
    }

    @Override public DetectionConfig config() { return config.clone(); }
    @Override public AppConfig appConfig() { return appConfig.copy(); }
}
