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

// --- NEW IMPORTS ---
import eu.startales.spacepixels.events.EngineProgressUpdateEvent;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener; // Update to match your actual package

import eu.startales.spacepixels.events.DetectionFinishedEvent;
import eu.startales.spacepixels.events.DetectionStartedEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.util.ImageProcessing;

import javax.swing.*;
import java.io.File;
import java.util.logging.Level;

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
        // --- NEW: Prompt user for maximum frames before starting ---
        final int[] maxFrames = {0};
        final boolean[] cancelled = {false};
        
        try {
            SwingUtilities.invokeAndWait(() -> {
                JPanel panel = new JPanel(new java.awt.BorderLayout(5, 5));
                panel.add(new JLabel("<html>Enter the maximum number of frames to use for the iterative passes<br>(Leave empty or enter 0 to go up to the total number of frames):</html>"), java.awt.BorderLayout.NORTH);
                
                JTextField inputField = new JTextField(10);
                JPanel fieldPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
                fieldPanel.add(inputField);
                panel.add(fieldPanel, java.awt.BorderLayout.CENTER);

                int result = JOptionPane.showConfirmDialog(
                        null,
                        panel,
                        "Limit Iterative Passes",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                );
                if (result != JOptionPane.OK_OPTION) {
                    cancelled[0] = true;
                } else {
                    String input = inputField.getText();
                    if (input != null && !input.trim().isEmpty()) {
                        try {
                            maxFrames[0] = Integer.parseInt(input.trim());
                        } catch (NumberFormatException ignored) {}
                    }
                }
            });
        } catch (Exception e) {
            ApplicationWindow.logger.log(Level.WARNING, "Failed to show max frames dialog", e);
        }
        
        if (cancelled[0]) {
            return; // Exit silently if user clicked cancel
        }

        // 1. Trigger the Progress Dialog NOW
        eventBus.post(new DetectionStartedEvent());

        try {
            // Define the Safety Valve Callback
            ImageProcessing.DetectionSafetyPrompt safetyPrompt = (summary) -> {
                int safeDetectionLimit = 100; // Increased limit slightly since iterative runs aggregate detections
                if (summary.totalDetections <= safeDetectionLimit) {
                    return true;
                }

                final boolean[] proceed = {false};
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        int choice = JOptionPane.showConfirmDialog(
                                null,
                                "The iterative engine found a very high number of detections in this pass (" + summary.totalDetections + ").\n\n" +
                                        "Breakdown:\n" +
                                        " - Moving target tracks: " + summary.movingTargets + "\n" +
                                        " - Multi-frame streak tracks: " + summary.streakTracks + "\n" +
                                        " - Single streaks: " + summary.singleStreaks + "\n" +
                                        " - High-energy anomalies: " + summary.anomalies + "\n" +
                                        " - Potential slow movers: " + summary.potentialSlowMovers + " (" + summary.slowMoverCandidates + " deep-stack candidates, " + summary.maximumStackTransientStreaks + " unmatched maximum-stack streaks)\n\n" +
                                        "Generating the HTML report will take a long time.\n" +
                                        "Do you want to proceed?",
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

            // --- NEW: Define the Progress Callback Bridge ---
            TransientEngineProgressListener progressListener = (percentage, message) -> {
                // Instantly bridge the pure Java call to the Guava EventBus
                eventBus.post(new EngineProgressUpdateEvent(percentage, message));
            };

            // --- CALL THE NEW ITERATIVE METHOD WITH THE MAX LIMIT ---
            File masterDir = preProcessing.detectSlowObjectsIterative(config, safetyPrompt, progressListener, maxFrames[0]);

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
