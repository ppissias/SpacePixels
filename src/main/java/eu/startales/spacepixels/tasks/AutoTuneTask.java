/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.tasks;

import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import io.github.ppissias.jtransient.engine.AutoTunerResult;
import io.github.ppissias.jtransient.execution.CancellationScope;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.detection.AutoTuneCandidatePoolBuilder;
import eu.startales.spacepixels.detection.AutoTunerRunner;
import eu.startales.spacepixels.detection.RunPreviewScope;
import eu.startales.spacepixels.detection.RunPreviewSink;
import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.fits.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

import java.util.List;
import java.util.logging.Logger;

/**
 * Runs the chosen Auto-Tuner on a pool of the session's frames. The result is the tuner's answer, which says itself
 * whether it found usable settings.
 */
public class AutoTuneTask implements Job<AutoTunerResult> {

    private static final Logger LOGGER = Logger.getLogger(AutoTuneTask.class.getName());

    private final FitsFileInformation[] filesInfo;
    private final DetectionConfig baseConfig;
    private final int autoTuneMaxCandidateFrames;
    private final AutoTuneProfile profile;
    private final List<io.github.ppissias.jtransient.engine.ExcludedArea> excludedAreas;
    private RunPreviewSink previews;
    private VisualizationSettings visualization = VisualizationSettings.defaults();

    public AutoTuneTask(FitsFileInformation[] filesInfo,
                        DetectionConfig baseConfig,
                        int autoTuneMaxCandidateFrames,
                        AutoTuneProfile profile) {
        this(filesInfo, baseConfig, autoTuneMaxCandidateFrames, profile, List.of());
    }

    /** A tune that measures without the session's excluded areas, captured at admission. */
    public AutoTuneTask(FitsFileInformation[] filesInfo, DetectionConfig baseConfig, int autoTuneMaxCandidateFrames,
                        AutoTuneProfile profile, List<io.github.ppissias.jtransient.engine.ExcludedArea> excludedAreas) {
        this.filesInfo = filesInfo;
        this.baseConfig = baseConfig;
        this.autoTuneMaxCandidateFrames = autoTuneMaxCandidateFrames;
        this.profile = profile;
        this.excludedAreas = List.copyOf(excludedAreas);
    }

    @Override
    public JobOutcome<AutoTunerResult> run(JobContext context) throws Exception {
        try (CancellationScope cancellation = CancellationScope.open(context::isCancelled);
             RunPreviewScope preview = previews == null ? null : RunPreviewScope.open(previews)) {
            CancellationScope.check();
            return runCancellable(context);
        }
    }

    /** Captures the job's live-view destination and immutable rendering choices before admission. */
    public AutoTuneTask showingPreviews(RunPreviewSink sink, VisualizationSettings settings) {
        previews = sink;
        visualization = settings;
        return this;
    }

    private JobOutcome<AutoTunerResult> runCancellable(JobContext context) throws Exception {
        if (filesInfo == null || filesInfo.length < SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES) {
            throw new IllegalStateException("Not enough frames to run Auto-Tuning.");
        }

        LOGGER.info("Auto-Tuner evaluating up to " + autoTuneMaxCandidateFrames + " candidate frames from " + filesInfo.length + " inputs.");

        List<ImageFrame> candidateFrames = AutoTuneCandidatePoolBuilder.buildCandidatePool(
                filesInfo,
                baseConfig,
                autoTuneMaxCandidateFrames,
                context::progress, visualization, excludedAreas);

        LOGGER.info("Candidate pool ready. Passing " + candidateFrames.size() + " frames to AutoTuner.");

        TransientEngineProgressListener autoTuneListener = (enginePercent, message) -> {
            int scaledPercent = 50 + (int) ((enginePercent / 100.0f) * 50);
            context.progress(scaledPercent, "Tuning: " + message);
        };

        context.progress(50, "Starting mathematical tuning algorithms...");

        LOGGER.info("Auto-Tuner profile: " + profile);
        AutoTunerResult result = AutoTunerRunner.run(candidateFrames, baseConfig, profile, autoTuneListener, visualization,
                excludedAreas);
        context.progress(100, "Auto-Tuning complete!");
        return JobOutcome.completed(result);
    }
}
