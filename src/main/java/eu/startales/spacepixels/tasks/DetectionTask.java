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

import eu.startales.spacepixels.jobs.JobFailures;
import com.google.common.eventbus.EventBus;

import io.github.ppissias.jtransient.config.DetectionConfig;

import eu.startales.spacepixels.events.EngineProgressUpdateEvent;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

import eu.startales.spacepixels.events.DetectionFinishedEvent;
import eu.startales.spacepixels.events.DetectionStartedEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.util.ImageProcessing;

import java.io.File;
import java.util.logging.Level;

public class DetectionTask implements Runnable {
    private final EventBus eventBus;
    private final ImageProcessing preProcessing;

    // Hold the configuration
    private final DetectionConfig config;

    /** Runs the detection on the whole session and writes the report. */
    public DetectionTask(EventBus eventBus, ImageProcessing preProcessing, DetectionConfig config) {
        this.eventBus = eventBus;
        this.preProcessing = preProcessing;
        this.config = config;
    }

    @Override
    public void run() {
        try {
            // --- BATCH DETECTION ---

            // 1. Trigger the Progress Dialog NOW
            eventBus.post(new DetectionStartedEvent());

            // Define the Safety Valve Callback
            ImageProcessing.DetectionSafetyPrompt safetyPrompt = (summary) ->
                    summary.confirmReport(preProcessing.hasPlateSolvedFrame(), 50, null, config);

            // 2. Define the Progress Callback Bridge
            TransientEngineProgressListener progressListener = (percentage, message) -> {
                if (ApplicationWindow.OOM_FLAG) {
                    throw new OutOfMemoryError("Global OOM triggered");
                }
                // Instantly bridge the pure Java call to the Guava EventBus
                eventBus.post(new EngineProgressUpdateEvent(percentage, message));
            };

            // 3. Pass the progressListener down into preProcessing
            File exportDir = preProcessing.detectObjects(config, safetyPrompt, progressListener);

            if (exportDir == null) {
                eventBus.post(new DetectionFinishedEvent(null, false, false, "Report generation aborted by user due to high track count. Try raising your thresholds.", null, 0, 0, null));
            } else {
                eventBus.post(new EngineProgressUpdateEvent(100, "Batch detection complete!"));
                eventBus.post(new DetectionFinishedEvent(exportDir, true, false, null, null, 0, 0, null));
            }
        } catch (Throwable t) {
            // Out of memory leaves the thread for the application's handler, which closes SpacePixels with a message.
            String message = JobFailures.rethrowOutOfMemory(t);
            ApplicationWindow.logger.log(Level.SEVERE, "Detection failed", t);
            eventBus.post(new DetectionFinishedEvent(null, false, false, message, null, 0, 0, null));
        }
    }
}
