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
import eu.startales.spacepixels.events.FitsImportFinishedEvent;
import eu.startales.spacepixels.events.FitsImportStartedEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImagePreprocessing;

import java.io.File;
import java.util.logging.Level;

public class FitsImportTask implements Runnable {
    private final EventBus eventBus;
    private final File directory;

    public FitsImportTask(EventBus eventBus, File directory) {
        this.eventBus = eventBus;
        this.directory = directory;
    }

    @Override
    public void run() {
        // 1. Tell the UI to lock up and show progress
        eventBus.post(new FitsImportStartedEvent());

        try {
            // 2. Do the heavy lifting
            ImagePreprocessing preProcessing = ImagePreprocessing.getInstance(directory);
            FitsFileInformation[] filesInfo = preProcessing.getFitsfileInformation();

            // 3. Post success with the extracted data
            eventBus.post(new FitsImportFinishedEvent(true, null, preProcessing, filesInfo));

        } catch (Exception e) {
            ApplicationWindow.logger.log(Level.SEVERE, "Error loading FITS files", e);
            // 3. Post failure
            eventBus.post(new FitsImportFinishedEvent(false, e.getMessage(), null, null));
        }
    }
}