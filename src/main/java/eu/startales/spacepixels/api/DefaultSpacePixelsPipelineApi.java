/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.api;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.util.AutoTuneCandidatePoolBuilder;
import eu.startales.spacepixels.util.DetectionInputPreparation;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * Default implementation of the public SpacePixels pipeline API.
 * <p>
 * This implementation performs optional input preparation, optional Auto-Tune optimization,
 * standard pipeline execution, and optional HTML report export.
 *
 * <p>Auto-Tune-enabled runs should not be executed concurrently in the same JVM. The current
 * implementation temporarily adjusts shared JTransient Auto-Tune state while a tuning run is in
 * progress.
 */
public final class DefaultSpacePixelsPipelineApi implements SpacePixelsPipelineApi {

    /**
     * {@inheritDoc}
     */
    @Override
    public SpacePixelsPipelineResult run(SpacePixelsPipelineRequest request) throws SpacePixelsPipelineException {
        Objects.requireNonNull(request, "request");

        try {
            DetectionConfig baseConfig = request.getDetectionConfig();
            if (baseConfig == null) {
                baseConfig = new DetectionConfig();
            }

            SpacePixelsProgressListener progressListener = request.getProgressListener();
            DetectionInputPreparation.PreparedDirectory preparedDirectory = DetectionInputPreparation.prepareInputDirectory(
                    request.getInputDirectory(),
                    request.getInputPreparationMode() == InputPreparationMode.AUTO_PREPARE_TO_16BIT_MONO,
                    (percentage, message) -> emitScaledProgress(progressListener, 0, 15, percentage, message));

            emitProgress(progressListener, 16, "Validating FITS metadata for pipeline execution...");
            ImageProcessing imageProcessing = ImageProcessing.getInstance(preparedDirectory.getPreparedInputDirectory());
            FitsFileInformation[] filesInfo = imageProcessing.getFitsfileInformationHeadless();

            JTransientAutoTuner.AutoTunerResult autoTuneResult = null;
            int pipelineStartPercent = 15;
            DetectionConfig pipelineBaseConfig = baseConfig.clone();
            if (request.getAutoTuneProfile() != null) {
                pipelineStartPercent = 35;
                autoTuneResult = runAutoTune(
                        filesInfo,
                        pipelineBaseConfig.clone(),
                        request.getAutoTuneMaxCandidateFrames(),
                        request.getAutoTuneProfile(),
                        (percentage, message) -> emitScaledProgress(progressListener, 15, 35, percentage, message));
                pipelineBaseConfig = autoTuneResult.optimizedConfig.clone();
            }

            int pipelineEndPercent = request.isGenerateReport() ? 90 : 100;
            final int finalPipelineStartPercent = pipelineStartPercent;
            final int finalPipelineEndPercent = pipelineEndPercent;
            ImageProcessing.PipelineExecutionData executionData = imageProcessing.runDetectionPipeline(
                    pipelineBaseConfig,
                    (percentage, message) -> emitScaledProgress(progressListener, finalPipelineStartPercent, finalPipelineEndPercent, percentage, message));

            File reportFile = null;
            File exportDirectory = null;
            if (request.isGenerateReport()) {
                emitProgress(progressListener, 90, "Exporting tracks and generating HTML report...");
                reportFile = imageProcessing.exportDetectionReport(executionData);
                if (reportFile == null) {
                    throw new SpacePixelsPipelineException("Pipeline completed without producing an export report.");
                }
                exportDirectory = reportFile.getParentFile();
                emitProgress(progressListener, 100, "Finished!");
            } else {
                emitProgress(progressListener, 100, "Pipeline finished.");
            }

            return new SpacePixelsPipelineResult(
                    request.getInputDirectory(),
                    preparedDirectory.getPreparedInputDirectory(),
                    preparedDirectory.isInputWasPrepared(),
                    executionData.getFilesInformation(),
                    baseConfig,
                    executionData.getEffectiveConfig(),
                    autoTuneResult != null,
                    request.getAutoTuneProfile(),
                    autoTuneResult == null ? null : autoTuneResult.telemetryReport,
                    executionData.getPipelineResult(),
                    exportDirectory,
                    reportFile);
        } catch (SpacePixelsPipelineException e) {
            throw e;
        } catch (Exception e) {
            throw new SpacePixelsPipelineException("SpacePixels pipeline execution failed: " + e.getMessage(), e);
        }
    }

    private static JTransientAutoTuner.AutoTunerResult runAutoTune(FitsFileInformation[] filesInfo,
                                                                   DetectionConfig baseConfig,
                                                                   int autoTuneMaxCandidateFrames,
                                                                   JTransientAutoTuner.AutoTuneProfile profile,
                                                                   SpacePixelsProgressListener progressListener) throws Exception {
        if (filesInfo == null || filesInfo.length < SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES) {
            throw new SpacePixelsPipelineException("Auto-Tune requires at least " + SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES +
                    " frames, but only " + (filesInfo == null ? 0 : filesInfo.length) + " were available.");
        }

        List<ImageFrame> candidateFrames = AutoTuneCandidatePoolBuilder.buildCandidatePool(
                filesInfo,
                baseConfig,
                autoTuneMaxCandidateFrames,
                (percentage, message) -> emitProgress(progressListener, percentage, message));

        emitProgress(progressListener, 50, "Starting mathematical tuning algorithms...");

        TransientEngineProgressListener autoTuneListener = (enginePercent, message) ->
                emitProgress(progressListener,
                        50 + (int) ((enginePercent / 100.0f) * 50),
                        "Tuning: " + message);

        int originalSampleSize = JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE;
        int effectiveSampleSize = Math.min(originalSampleSize, candidateFrames.size());

        JTransientAutoTuner.AutoTunerResult result;
        JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE = effectiveSampleSize;
        try {
            result = JTransientAutoTuner.tune(candidateFrames, baseConfig, profile, autoTuneListener);
        } finally {
            JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE = originalSampleSize;
        }

        if (result == null || !result.success || result.optimizedConfig == null) {
            throw new SpacePixelsPipelineException("Auto-Tune did not return an optimized configuration.");
        }

        if (result.optimizedConfig.growSigmaMultiplier > result.optimizedConfig.detectionSigmaMultiplier) {
            result.optimizedConfig.growSigmaMultiplier = result.optimizedConfig.detectionSigmaMultiplier;
        }

        emitProgress(progressListener, 100, "Auto-Tuning complete.");
        return result;
    }

    private static void emitScaledProgress(SpacePixelsProgressListener progressListener,
                                           int startPercent,
                                           int endPercent,
                                           int percentage,
                                           String message) {
        emitProgress(progressListener, scaleProgress(percentage, startPercent, endPercent), message);
    }

    private static int scaleProgress(int percentage, int startPercent, int endPercent) {
        int boundedPercent = Math.max(0, Math.min(100, percentage));
        return startPercent + (int) Math.round((boundedPercent / 100.0d) * (endPercent - startPercent));
    }

    private static void emitProgress(SpacePixelsProgressListener progressListener, int percentage, String message) {
        if (progressListener != null) {
            progressListener.onProgress(Math.max(0, Math.min(100, percentage)), message);
        }
    }
}
