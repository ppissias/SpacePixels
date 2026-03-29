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

import com.google.common.eventbus.EventBus;
import eu.startales.spacepixels.events.EngineProgressUpdateEvent;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import eu.startales.spacepixels.events.AutoTuneFinishedEvent;
import eu.startales.spacepixels.events.AutoTuneStartedEvent;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.util.AutoTuneCandidatePoolBuilder;
import eu.startales.spacepixels.util.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import java.util.List;
import java.util.logging.Level;

public class AutoTuneTask implements Runnable {

    private final EventBus eventBus;
    private final FitsFileInformation[] filesInfo;
    private final DetectionConfig baseConfig;
    private final int autoTuneMaxCandidateFrames;
    private final JTransientAutoTuner.AutoTuneProfile profile;

    public AutoTuneTask(EventBus eventBus,
                        FitsFileInformation[] filesInfo,
                        DetectionConfig baseConfig,
                        int autoTuneMaxCandidateFrames,
                        JTransientAutoTuner.AutoTuneProfile profile) {
        this.eventBus = eventBus;
        this.filesInfo = filesInfo;
        this.baseConfig = baseConfig;
        this.autoTuneMaxCandidateFrames = autoTuneMaxCandidateFrames;
        this.profile = profile;
    }

    @Override
    public void run() {
        eventBus.post(new AutoTuneStartedEvent());

        try {
            if (filesInfo == null || filesInfo.length < SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES) {
                throw new IllegalStateException("Not enough frames to run Auto-Tuning.");
            }

            ApplicationWindow.logger.info("Auto-Tuner evaluating up to " + autoTuneMaxCandidateFrames + " candidate frames from " + filesInfo.length + " inputs.");

            List<ImageFrame> candidateFrames = AutoTuneCandidatePoolBuilder.buildCandidatePool(
                    filesInfo,
                    baseConfig,
                    autoTuneMaxCandidateFrames,
                    (percentage, message) -> eventBus.post(new EngineProgressUpdateEvent(percentage, message)));

            ApplicationWindow.logger.info("Candidate pool ready. Passing " + candidateFrames.size() + " frames to AutoTuner.");

            TransientEngineProgressListener autoTuneListener = (enginePercent, message) -> {
                int scaledPercent = 50 + (int) ((enginePercent / 100.0f) * 50);
                eventBus.post(new EngineProgressUpdateEvent(scaledPercent, "Tuning: " + message));
            };

            eventBus.post(new EngineProgressUpdateEvent(50, "Starting mathematical tuning algorithms..."));

            JTransientAutoTuner.AutoTunerResult result = JTransientAutoTuner.tune(candidateFrames, baseConfig, profile, autoTuneListener);

            eventBus.post(new EngineProgressUpdateEvent(100, "Auto-Tuning complete!"));
            eventBus.post(new AutoTuneFinishedEvent(true, "Auto-Tuning completed successfully.", result));

        } catch (Exception ex) {
            ApplicationWindow.logger.log(Level.SEVERE, "Auto-Tuning failed", ex);
            eventBus.post(new AutoTuneFinishedEvent(false, ex.getMessage(), null));
        }
    }
}
