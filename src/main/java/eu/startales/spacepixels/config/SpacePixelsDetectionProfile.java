/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.config;

import io.github.ppissias.jtransient.config.DetectionConfig;

import java.util.Objects;

/**
 * SpacePixels-owned detection profile wrapper around JTransient's {@link DetectionConfig}.
 * Application choices include the auto-tuner candidate-pool limit and variable-star report presentation.
 */
public final class SpacePixelsDetectionProfile {

    public static final int DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES = 30;
    public static final int MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES = 4;

    private final DetectionConfig detectionConfig;
    private final int autoTuneMaxCandidateFrames;
    private final VariableStarReportOptions variableStarReportOptions;

    public SpacePixelsDetectionProfile(DetectionConfig detectionConfig, int autoTuneMaxCandidateFrames) {
        this(detectionConfig, autoTuneMaxCandidateFrames, VariableStarReportOptions.DEFAULT);
    }

    public SpacePixelsDetectionProfile(DetectionConfig detectionConfig, int autoTuneMaxCandidateFrames,
                                       VariableStarReportOptions variableStarReportOptions) {
        this.detectionConfig = Objects.requireNonNull(detectionConfig, "detectionConfig");
        this.autoTuneMaxCandidateFrames = normalizeAutoTuneMaxCandidateFrames(autoTuneMaxCandidateFrames);
        this.variableStarReportOptions = Objects.requireNonNull(variableStarReportOptions, "variableStarReportOptions");
    }

    public VariableStarReportOptions getVariableStarReportOptions() { return variableStarReportOptions; }

    public DetectionConfig getDetectionConfig() {
        return detectionConfig;
    }

    public int getAutoTuneMaxCandidateFrames() {
        return autoTuneMaxCandidateFrames;
    }

    public static int normalizeAutoTuneMaxCandidateFrames(int autoTuneMaxCandidateFrames) {
        return Math.max(MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES, autoTuneMaxCandidateFrames);
    }
}
