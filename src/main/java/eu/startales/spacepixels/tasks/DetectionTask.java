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

// --- NEW IMPORTS ---
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;

import eu.startales.spacepixels.events.DetectionFinishedEvent;
import eu.startales.spacepixels.events.DetectionStartedEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageDisplayUtils;
import eu.startales.spacepixels.util.ImagePreprocessing;
import eu.startales.spacepixels.util.RawImageAnnotator;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.logging.Level;

public class DetectionTask implements Runnable {
    private final EventBus eventBus;
    private final ImagePreprocessing preProcessing;
    private final FitsFileInformation[] selectedFiles;

    // --- NEW: Hold the configuration ---
    private final DetectionConfig config;

    // --- NEW: Add config to the constructor ---
    public DetectionTask(EventBus eventBus, ImagePreprocessing preProcessing, FitsFileInformation[] selectedFiles, DetectionConfig config) {
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

                    // --- NEW: Pass the config values into the extractor ---
                    List<SourceExtractor.DetectedObject> objects = SourceExtractor.extractSources(
                            debugImage,
                            config.detectionSigmaMultiplier,
                            config.minDetectionPixels,
                            config // The 4th argument we added earlier!
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
                    eventBus.post(new DetectionFinishedEvent(true, true, null, finalImageToDisplay, starCount, streakCount, aSelectedFile.getFileName()));
                } else {
                    throw new Exception("Cannot understand FITS format: expected short[][], got " + kernelData.getClass().getName());
                }

            } else {
                // --- BATCH DETECTION ---
                // --- NEW: Pass the config to the preprocessor ---
                preProcessing.detectObjects(config);

                // Post success for Batch Detection
                eventBus.post(new DetectionFinishedEvent(true, false, null, null, 0, 0, null));
            }
        } catch (Exception ex) {
            ApplicationWindow.logger.log(Level.SEVERE, "Detection failed", ex);
            // Post failure
            eventBus.post(new DetectionFinishedEvent(false, false, ex.getMessage(), null, 0, 0, null));
        }
    }
}