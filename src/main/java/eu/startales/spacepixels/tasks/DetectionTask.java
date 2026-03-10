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
import nom.tam.fits.Fits;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;

import eu.startales.spacepixels.events.DetectionFinishedEvent;
import eu.startales.spacepixels.events.DetectionStartedEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageDisplayUtils;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.RawImageAnnotator;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.logging.Level;
import java.util.function.IntPredicate;

public class DetectionTask implements Runnable {
    private final EventBus eventBus;
    private final ImageProcessing preProcessing;
    private final FitsFileInformation[] selectedFiles;

    // Hold the configuration
    private final DetectionConfig config;

    public DetectionTask(EventBus eventBus, ImageProcessing preProcessing, FitsFileInformation[] selectedFiles, DetectionConfig config) {
        this.eventBus = eventBus;
        this.preProcessing = preProcessing;
        this.selectedFiles = selectedFiles;
        this.config = config;
    }

    @Override
    public void run() {
        eventBus.post(new DetectionStartedEvent());

        try {
            if (selectedFiles != null && selectedFiles.length > 0) {
                // --- QUICK DETECTION ON SINGLE IMAGE ---
                FitsFileInformation aSelectedFile = selectedFiles[0];
                Fits selectedFitsImage = new Fits(aSelectedFile.getFilePath());

                Object kernelData = selectedFitsImage.getHDU(0).getKernel();
                if (kernelData instanceof short[][]) {
                    short[][] imageData = (short[][]) kernelData;

                    short[][] debugImage = new short[imageData.length][imageData[0].length];
                    for (int x = 0; x < imageData.length; x++) {
                        System.arraycopy(imageData[x], 0, debugImage[x], 0, imageData[x].length);
                    }

                    List<SourceExtractor.DetectedObject> objects = SourceExtractor.extractSources(
                            debugImage,
                            config.detectionSigmaMultiplier,
                            config.minDetectionPixels,
                            config
                    );

                    int streakCount = 0;
                    int starCount = 0;

                    for (SourceExtractor.DetectedObject obj : objects) {
                        if (obj.isStreak) streakCount++;
                        else starCount++;
                    }

                    RawImageAnnotator.drawDetections(debugImage, objects);
                    BufferedImage finalImageToDisplay = ImageDisplayUtils.createDisplayImage(debugImage);
                    ImageDisplayUtils.analyzeFitsData(imageData);
                    selectedFitsImage.close();

                    // Post success for Quick Detection
                    eventBus.post(new DetectionFinishedEvent(null, true, true, null, finalImageToDisplay, starCount, streakCount, aSelectedFile.getFileName()));
                } else {
                    throw new Exception("Cannot understand FITS format: expected short[][], got " + kernelData.getClass().getName());
                }

            } else {
                // --- BATCH DETECTION ---

                // --- NEW: Define the Safety Valve Callback ---
                IntPredicate safetyPrompt = (trackCount) -> {
                    int SAFE_TRACK_LIMIT = 50; // Adjust this limit as needed
                    if (trackCount <= SAFE_TRACK_LIMIT) {
                        return true;
                    }

                    final boolean[] proceed = {false};
                    try {
                        // Safely pause the background thread to show a UI prompt
                        SwingUtilities.invokeAndWait(() -> {
                            int choice = JOptionPane.showConfirmDialog(
                                    null,
                                    "The engine found an unusually high number of moving tracks (" + trackCount + ").\n\n" +
                                            "Generating image crops, GIFs, and an HTML report for this many objects will take a long time and consume significant disk space.\n" +
                                            "This usually indicates the Detection Sigma was set too low and the engine linked background noise.\n\n" +
                                            "Do you want to proceed with generating the report anyway?",
                                    "High Track Count Warning",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.WARNING_MESSAGE
                            );
                            proceed[0] = (choice == JOptionPane.YES_OPTION);
                        });
                    } catch (Exception e) {
                        return false; // Safely abort if interrupted
                    }
                    return proceed[0];
                };

                // --- NEW: Pass the callback alongside the config ---
                File exportDir = preProcessing.detectObjects(config, safetyPrompt);

                if (exportDir == null) {
                    // This implies the user clicked "No" and ImageProcessing returned null
                    eventBus.post(new DetectionFinishedEvent(null, false, false, "Report generation aborted by user due to high track count. Try raising your thresholds.", null, 0, 0, null));
                } else {
                    // Post success for Batch Detection
                    eventBus.post(new DetectionFinishedEvent(exportDir, true, false, null, null, 0, 0, null));
                }
            }
        } catch (Exception ex) {
            ApplicationWindow.logger.log(Level.SEVERE, "Detection failed", ex);
            // Post failure
            eventBus.post(new DetectionFinishedEvent(null, false, false, ex.getMessage(), null, 0, 0, null));
        }
    }
}