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

import eu.startales.spacepixels.events.EngineProgressUpdateEvent;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

import eu.startales.spacepixels.events.DetectionFinishedEvent;
import eu.startales.spacepixels.events.DetectionStartedEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.util.ImageProcessing;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
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
            ImageProcessing.DetectionSafetyPrompt safetyPrompt = (summary) -> {
                int safeDetectionLimit = 50;
                if (summary.totalDetections <= safeDetectionLimit) {
                    return true;
                }

                final boolean[] proceed = {false};
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        int choice = JOptionPane.showConfirmDialog(
                                null,
                                summary.warningMessage(preProcessing.hasPlateSolvedFrame(), safeDetectionLimit),
                                "High Detection Count Warning",
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
            Throwable rootCause = t;
            while (rootCause != null && !(rootCause instanceof OutOfMemoryError)) {
                if (rootCause == rootCause.getCause()) break;
                rootCause = rootCause.getCause();
            }

            if (rootCause instanceof OutOfMemoryError) {
                if (!ApplicationWindow.OOM_FLAG) {
                    ApplicationWindow.OOM_FLAG = true;
                    ApplicationWindow.logger.log(Level.SEVERE, "Out of memory during detection task", rootCause);
                    
                    Thread doomThread = new Thread(() -> {
                        try { Thread.sleep(10000); } catch (Exception ignore) {}
                        System.exit(1);
                    });
                    doomThread.setDaemon(true);
                    doomThread.start();

                    SwingUtilities.invokeLater(() -> {
                        try {
                            JOptionPane.showMessageDialog(null,
                                    "SpacePixels has run out of memory and must close.\n" +
                                    "Please process fewer images or increase the Java heap space (e.g., allocate more memory via -Xmx).",
                                    "Fatal Error: Out of Memory",
                                    JOptionPane.ERROR_MESSAGE);
                        } catch (Throwable ignored) {
                        } finally {
                            System.exit(1);
                        }
                    });
                }
            } else if (t instanceof Exception) {
                Exception ex = (Exception) t;
                ApplicationWindow.logger.log(Level.SEVERE, "Detection failed", ex);
                eventBus.post(new DetectionFinishedEvent(null, false, false, ex.getMessage(), null, 0, 0, null));
            } else {
                ApplicationWindow.logger.log(Level.SEVERE, "Fatal error during detection task", t);
            }
        }
    }
}
