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
 * The extra field tracked here is the caller-side auto-tuner candidate-pool limit.
 */
public final class SpacePixelsDetectionProfile {

    public static final int DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES = 20;
    public static final int MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES = 5;

    private final DetectionConfig detectionConfig;
    private final int autoTuneMaxCandidateFrames;

    public SpacePixelsDetectionProfile(DetectionConfig detectionConfig, int autoTuneMaxCandidateFrames) {
        this.detectionConfig = Objects.requireNonNull(detectionConfig, "detectionConfig");
        this.autoTuneMaxCandidateFrames = normalizeAutoTuneMaxCandidateFrames(autoTuneMaxCandidateFrames);
    }

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
