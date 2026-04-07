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

import eu.startales.spacepixels.events.EngineProgressUpdateEvent;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

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
import java.awt.Graphics2D;
import java.io.File;
import java.util.List;
import java.util.logging.Level;

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
        try {
            if (selectedFiles != null && selectedFiles.length > 0) {
                // --- QUICK DETECTION ON SINGLE IMAGE ---
                // (No Progress Dialog triggered here)

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
                    ).objects;

                    int streakCount = 0;
                    int starCount = 0;

                    for (SourceExtractor.DetectedObject obj : objects) {
                        if (obj.isStreak) streakCount++;
                        else starCount++;
                    }

                    BufferedImage grayImage = ImageDisplayUtils.createDisplayImage(debugImage);
                    BufferedImage finalImageToDisplay = new BufferedImage(grayImage.getWidth(), grayImage.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2d = finalImageToDisplay.createGraphics();
                    g2d.drawImage(grayImage, 0, 0, null);
                    g2d.dispose();

                    RawImageAnnotator.drawExactBlobs(finalImageToDisplay, objects);
                    RawImageAnnotator.drawDetections(finalImageToDisplay, objects);
                    selectedFitsImage.close();

                    // Post success for Quick Detection
                    eventBus.post(new DetectionFinishedEvent(null, true, true, null, finalImageToDisplay, starCount, streakCount, aSelectedFile.getFileName()));
                } else {
                    throw new Exception("Cannot understand FITS format: expected short[][], got " + kernelData.getClass().getName());
                }

            } else {
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
                                    "The engine found an unusually high number of detections (" + summary.totalDetections + ").\n\n" +
                                            "Breakdown:\n" +
                                            " - Moving target tracks (non-streak): " + summary.movingTargets + "\n" +
                                            " - Confirmed multi-frame streak tracks: " + summary.streakTracks + "\n" +
                                            " - Single streaks: " + summary.singleStreaks + "\n" +
                                            " - Single-frame anomalies: " + summary.anomalies + "\n" +
                                            " - Suspected streak tracks (anomaly groupings): " + summary.suspectedStreakTracks + "\n" +
                                            " - Potential slow movers: " + summary.potentialSlowMovers + " (" + summary.slowMoverCandidates + " deep-stack candidates, " + summary.localRescueCandidates + " local rescue candidates)\n" +
                                            " - Broad local activity clusters: " + summary.localActivityClusters + "\n\n" +
                                            "Generating image crops, GIFs, and an HTML report for this many objects will take a long time and consume significant disk space.\n" +
                                            "This usually indicates the Detection Sigma was set too low and the engine linked background noise.\n\n" +
                                            "Do you want to proceed with generating the report anyway?",
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
