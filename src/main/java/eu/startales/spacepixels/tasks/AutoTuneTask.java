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

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.util.AutoTuneCandidatePoolBuilder;
import eu.startales.spacepixels.util.AutoTunerRunner;
import eu.startales.spacepixels.util.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

import java.util.List;
import java.util.logging.Logger;

/**
 * Runs the chosen Auto-Tuner on a pool of the session's frames. The result is the tuner's answer, which says itself
 * whether it found usable settings.
 */
public class AutoTuneTask implements Job<JTransientAutoTuner.AutoTunerResult> {

    private static final Logger LOGGER = Logger.getLogger(AutoTuneTask.class.getName());

    private final FitsFileInformation[] filesInfo;
    private final DetectionConfig baseConfig;
    private final int autoTuneMaxCandidateFrames;
    private final JTransientAutoTuner.AutoTuneProfile profile;
    private final AutoTunerRunner.Algorithm algorithm;

    public AutoTuneTask(FitsFileInformation[] filesInfo,
                        DetectionConfig baseConfig,
                        int autoTuneMaxCandidateFrames,
                        JTransientAutoTuner.AutoTuneProfile profile,
                        AutoTunerRunner.Algorithm algorithm) {
        this.filesInfo = filesInfo;
        this.baseConfig = baseConfig;
        this.autoTuneMaxCandidateFrames = autoTuneMaxCandidateFrames;
        this.profile = profile;
        this.algorithm = algorithm;
    }

    @Override
    public JobOutcome<JTransientAutoTuner.AutoTunerResult> run(JobContext context) throws Exception {
        if (filesInfo == null || filesInfo.length < SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES) {
            throw new IllegalStateException("Not enough frames to run Auto-Tuning.");
        }

        LOGGER.info("Auto-Tuner evaluating up to " + autoTuneMaxCandidateFrames + " candidate frames from " + filesInfo.length + " inputs.");

        List<ImageFrame> candidateFrames = AutoTuneCandidatePoolBuilder.buildCandidatePool(
                filesInfo,
                baseConfig,
                autoTuneMaxCandidateFrames,
                context::progress);

        LOGGER.info("Candidate pool ready. Passing " + candidateFrames.size() + " frames to AutoTuner.");

        TransientEngineProgressListener autoTuneListener = (enginePercent, message) -> {
            int scaledPercent = 50 + (int) ((enginePercent / 100.0f) * 50);
            context.progress(scaledPercent, "Tuning: " + message);
        };

        context.progress(50, "Starting mathematical tuning algorithms...");

        LOGGER.info("Auto-Tuner algorithm: " + algorithm + ", profile: " + profile);
        JTransientAutoTuner.AutoTunerResult result =
                AutoTunerRunner.run(candidateFrames, baseConfig, profile, algorithm, autoTuneListener);

        context.progress(100, "Auto-Tuning complete!");
        return JobOutcome.completed(result);
    }
}
