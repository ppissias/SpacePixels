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
import io.github.ppissias.jplatesolve.PlateSolveResult;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.util.ImagePreprocessing;
import eu.startales.spacepixels.events.SolveStartedEvent;
import eu.startales.spacepixels.events.SolveFinishedEvent;

import java.util.concurrent.Future;
import java.util.logging.Level;

public class PlateSolveTask implements Runnable {
    private final EventBus eventBus;
    private final ImagePreprocessing preProcessing;
    private final String filePath;
    private final int rowIndex;
    private final boolean useAstap;
    private final boolean useAstrometryNet;

    public PlateSolveTask(EventBus eventBus, ImagePreprocessing preProcessing, String filePath, int rowIndex, boolean useAstap, boolean useAstrometryNet) {
        this.eventBus = eventBus;
        this.preProcessing = preProcessing;
        this.filePath = filePath;
        this.rowIndex = rowIndex;
        this.useAstap = useAstap;
        this.useAstrometryNet = useAstrometryNet;
    }

    @Override
    public void run() {
        // 1. Tell the UI we are starting
        eventBus.post(new SolveStartedEvent(rowIndex));

        try {
            // 2. Do the heavy lifting (this blocks the background thread, which is fine)
            Future<PlateSolveResult> solveResultFuture = preProcessing.solve(filePath, useAstap, useAstrometryNet);
            PlateSolveResult result = solveResultFuture.get();

            if (result.isSuccess()) {
                preProcessing.writeSolveResults(filePath, result);
            }

            // 3. Tell the UI we are done and pass the result
            eventBus.post(new SolveFinishedEvent(rowIndex, result));

        } catch (Exception ex) {
            ApplicationWindow.logger.log(Level.SEVERE, "Cannot solve image", ex);
            // Post a failure event
            eventBus.post(new SolveFinishedEvent(rowIndex, null));
        }
    }
}