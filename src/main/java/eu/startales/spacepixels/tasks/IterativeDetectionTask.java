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
import io.github.ppissias.jtransient.config.DetectionConfig;
import eu.startales.spacepixels.events.DetectionFinishedEvent;
import eu.startales.spacepixels.events.DetectionStartedEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.util.ImageProcessing;

import javax.swing.*;
import java.io.File;
import java.util.logging.Level;
import java.util.function.IntPredicate;

public class IterativeDetectionTask implements Runnable {
    private final EventBus eventBus;
    private final ImageProcessing preProcessing;
    private final DetectionConfig config;

    public IterativeDetectionTask(EventBus eventBus, ImageProcessing preProcessing, DetectionConfig config) {
        this.eventBus = eventBus;
        this.preProcessing = preProcessing;
        this.config = config;
    }

    @Override
    public void run() {
        eventBus.post(new DetectionStartedEvent());

        try {
            // Define the Safety Valve Callback
            IntPredicate safetyPrompt = (trackCount) -> {
                int SAFE_TRACK_LIMIT = 100; // Increased limit slightly since iterative runs aggregate tracks
                if (trackCount <= SAFE_TRACK_LIMIT) {
                    return true;
                }

                final boolean[] proceed = {false};
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        int choice = JOptionPane.showConfirmDialog(
                                null,
                                "The iterative engine found a very high number of combined tracks (" + trackCount + ").\n\n" +
                                        "Generating the HTML report will take a long time.\n" +
                                        "Do you want to proceed?",
                                "High Track Count Warning",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE
                        );
                        proceed[0] = (choice == JOptionPane.YES_OPTION);
                    });
                } catch (Exception e) {
                    return false;
                }
                return proceed[0];
            };

            // --- CALL THE NEW ITERATIVE METHOD ---
            File masterDir = preProcessing.detectSlowObjectsIterative(config, safetyPrompt);

            if (masterDir == null) {
                eventBus.post(new DetectionFinishedEvent(null, false, false, "Iterative run aborted by user.", null, 0, 0, null));
            } else {
                // We pass the masterDir.
                // The MainApplicationPanel will call Desktop.getDesktop().browse(masterDir.toURI())
                // which automatically opens the folder in Windows Explorer / macOS Finder!
                eventBus.post(new DetectionFinishedEvent(masterDir, true, false, null, null, 0, 0, null));
            }

        } catch (Exception ex) {
            ApplicationWindow.logger.log(Level.SEVERE, "Iterative Detection failed", ex);
            eventBus.post(new DetectionFinishedEvent(null, false, false, ex.getMessage(), null, 0, 0, null));
        }
    }
}