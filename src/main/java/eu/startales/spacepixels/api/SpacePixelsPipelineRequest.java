/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.api;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;

import java.io.File;
import java.util.Objects;

/**
 * Immutable request object for running the public SpacePixels detection pipeline API.
 * <p>
 * A request always includes the input directory and may optionally supply a base detection
 * configuration, an Auto-Tune profile, input-preparation behavior, report generation, and a
 * progress listener.
 */
public final class SpacePixelsPipelineRequest {
    private final File inputDirectory;
    private final DetectionConfig detectionConfig;
    private final JTransientAutoTuner.AutoTuneProfile autoTuneProfile;
    private final int autoTuneMaxCandidateFrames;
    private final InputPreparationMode inputPreparationMode;
    private final boolean generateReport;
    private final SpacePixelsProgressListener progressListener;

    private SpacePixelsPipelineRequest(Builder builder) {
        this.inputDirectory = builder.inputDirectory;
        this.detectionConfig = builder.detectionConfig == null ? null : builder.detectionConfig.clone();
        this.autoTuneProfile = builder.autoTuneProfile;
        this.autoTuneMaxCandidateFrames = SpacePixelsDetectionProfile.normalizeAutoTuneMaxCandidateFrames(builder.autoTuneMaxCandidateFrames);
        this.inputPreparationMode = builder.inputPreparationMode;
        this.generateReport = builder.generateReport;
        this.progressListener = builder.progressListener;
    }

    /**
     * Creates a request builder for the supplied input directory.
     *
     * @param inputDirectory directory containing the source FITS or XISF sequence
     * @return request builder
     */
    public static Builder builder(File inputDirectory) {
        return new Builder(inputDirectory);
    }

    /**
     * Returns the original input directory supplied by the caller.
     *
     * @return input directory
     */
    public File getInputDirectory() {
        return inputDirectory;
    }

    /**
     * Returns a defensive copy of the base detection configuration, or {@code null} to use the
     * default {@link DetectionConfig}.
     *
     * @return base detection configuration, or {@code null}
     */
    public DetectionConfig getDetectionConfig() {
        return detectionConfig == null ? null : detectionConfig.clone();
    }

    /**
     * Returns the optional Auto-Tune profile to apply before running the pipeline.
     *
     * @return Auto-Tune profile, or {@code null} when Auto-Tune is disabled
     */
    public JTransientAutoTuner.AutoTuneProfile getAutoTuneProfile() {
        return autoTuneProfile;
    }

    /**
     * Returns the maximum number of candidate frames that Auto-Tune may sample.
     *
     * @return normalized Auto-Tune candidate-frame limit
     */
    public int getAutoTuneMaxCandidateFrames() {
        return autoTuneMaxCandidateFrames;
    }

    /**
     * Returns the requested input-preparation behavior.
     *
     * @return input-preparation mode
     */
    public InputPreparationMode getInputPreparationMode() {
        return inputPreparationMode;
    }

    /**
     * Returns whether the standard HTML report and export assets should be generated.
     *
     * @return {@code true} to export the report, {@code false} to return only in-memory results
     */
    public boolean isGenerateReport() {
        return generateReport;
    }

    /**
     * Returns the optional progress listener.
     *
     * @return progress listener, or {@code null}
     */
    public SpacePixelsProgressListener getProgressListener() {
        return progressListener;
    }

    /**
     * Builder for {@link SpacePixelsPipelineRequest}.
     */
    public static final class Builder {
        private final File inputDirectory;
        private DetectionConfig detectionConfig;
        private JTransientAutoTuner.AutoTuneProfile autoTuneProfile;
        private int autoTuneMaxCandidateFrames = SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES;
        private InputPreparationMode inputPreparationMode = InputPreparationMode.FAIL_IF_NOT_READY;
        private boolean generateReport = true;
        private SpacePixelsProgressListener progressListener;

        private Builder(File inputDirectory) {
            this.inputDirectory = Objects.requireNonNull(inputDirectory, "inputDirectory");
        }

        /**
         * Sets the base detection configuration.
         *
         * @param detectionConfig configuration to clone and use as the pipeline baseline;
         *                        {@code null} uses a new default {@link DetectionConfig}
         * @return this builder
         */
        public Builder detectionConfig(DetectionConfig detectionConfig) {
            this.detectionConfig = detectionConfig;
            return this;
        }

        /**
         * Enables Auto-Tune with the supplied profile.
         *
         * @param autoTuneProfile Auto-Tune profile, or {@code null} to disable Auto-Tune
         * @return this builder
         */
        public Builder autoTuneProfile(JTransientAutoTuner.AutoTuneProfile autoTuneProfile) {
            this.autoTuneProfile = autoTuneProfile;
            return this;
        }

        /**
         * Sets the maximum number of frames to consider when building the Auto-Tune candidate pool.
         *
         * @param autoTuneMaxCandidateFrames requested candidate-frame limit
         * @return this builder
         */
        public Builder autoTuneMaxCandidateFrames(int autoTuneMaxCandidateFrames) {
            this.autoTuneMaxCandidateFrames = autoTuneMaxCandidateFrames;
            return this;
        }

        /**
         * Sets whether the API should reject unsupported input formats or normalize them first.
         *
         * @param inputPreparationMode input-preparation mode
         * @return this builder
         */
        public Builder inputPreparationMode(InputPreparationMode inputPreparationMode) {
            this.inputPreparationMode = Objects.requireNonNull(inputPreparationMode, "inputPreparationMode");
            return this;
        }

        /**
         * Sets whether the standard HTML report should be exported.
         *
         * @param generateReport {@code true} to export the report, {@code false} for raw results only
         * @return this builder
         */
        public Builder generateReport(boolean generateReport) {
            this.generateReport = generateReport;
            return this;
        }

        /**
         * Sets an optional progress listener.
         *
         * @param progressListener listener that receives coarse pipeline progress updates
         * @return this builder
         */
        public Builder progressListener(SpacePixelsProgressListener progressListener) {
            this.progressListener = progressListener;
            return this;
        }

        /**
         * Builds an immutable pipeline request.
         *
         * @return pipeline request
         */
        public SpacePixelsPipelineRequest build() {
            return new SpacePixelsPipelineRequest(this);
        }
    }
}
