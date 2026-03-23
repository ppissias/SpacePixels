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
import eu.startales.spacepixels.events.BatchStretchFinishedEvent;
import eu.startales.spacepixels.events.BatchStretchStartedEvent;
import eu.startales.spacepixels.events.EngineProgressUpdateEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.StretchAlgorithm;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

import java.util.logging.Level;

public class BatchStretchTask implements Runnable {
    private final EventBus eventBus;
    private final ImageProcessing preProcessing;
    private final int stretchFactor;
    private final int iterations;
    private final StretchAlgorithm algo;

    public BatchStretchTask(EventBus eventBus, ImageProcessing preProcessing,
                            int stretchFactor, int iterations, StretchAlgorithm algo) {
        this.eventBus = eventBus;
        this.preProcessing = preProcessing;
        this.stretchFactor = stretchFactor;
        this.iterations = iterations;
        this.algo = algo;
    }

    @Override
    public void run() {
        // 1. Tell the UI we are starting
        eventBus.post(new BatchStretchStartedEvent());

        try {
            ApplicationWindow.logger.info("Will stretch all images (color or mono)");

            TransientEngineProgressListener progressListener = (percentage, message) -> {
                eventBus.post(new EngineProgressUpdateEvent(percentage, message));
            };

            // 2. Perform the heavy lifting
            preProcessing.batchStretch(stretchFactor, iterations, algo, progressListener);

            // 3. Report Success
            eventBus.post(new BatchStretchFinishedEvent(true, null));
        } catch (Exception ex) {
            ApplicationWindow.logger.log(Level.SEVERE, "Cannot stretch images", ex);

            // 3. Report Failure
            eventBus.post(new BatchStretchFinishedEvent(false, ex.getMessage()));
        }
    }
}