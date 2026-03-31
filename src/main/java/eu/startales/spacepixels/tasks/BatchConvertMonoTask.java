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
import eu.startales.spacepixels.events.BatchConvertFinishedEvent;
import eu.startales.spacepixels.events.BatchConvertStartedEvent;
import eu.startales.spacepixels.events.EngineProgressUpdateEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.StretchAlgorithm;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

import java.util.logging.Level;

public class BatchConvertMonoTask implements Runnable {
    private final EventBus eventBus;
    private final ImageProcessing preProcessing;
    private final boolean stretchEnabled;
    private final int stretchFactor;
    private final int iterations;
    private final StretchAlgorithm algo;

    public BatchConvertMonoTask(EventBus eventBus, ImageProcessing preProcessing,
                                boolean stretchEnabled, int stretchFactor,
                                int iterations, StretchAlgorithm algo) {
        this.eventBus = eventBus;
        this.preProcessing = preProcessing;
        this.stretchEnabled = stretchEnabled;
        this.stretchFactor = stretchFactor;
        this.iterations = iterations;
        this.algo = algo;
    }

    @Override
    public void run() {
        eventBus.post(new BatchConvertStartedEvent());

        try {
            ApplicationWindow.logger.info("Will batch convert all images to mono");

            TransientEngineProgressListener progressListener = (percentage, message) -> {
                eventBus.post(new EngineProgressUpdateEvent(percentage, message));
            };

            java.io.File generatedMonoDirectory = preProcessing.batchConvertToMono(stretchEnabled, stretchFactor, iterations, algo, progressListener);

            eventBus.post(new BatchConvertFinishedEvent(true, null, generatedMonoDirectory));
        } catch (Exception ex) {
            ApplicationWindow.logger.log(Level.SEVERE, "Cannot convert images", ex);
            eventBus.post(new BatchConvertFinishedEvent(false, ex.getMessage()));
        }
    }
}
