package spv.tasks;

import com.google.common.eventbus.EventBus;
import nom.tam.fits.Fits;
import spv.events.DetectionFinishedEvent;
import spv.events.DetectionStartedEvent;
import spv.gui.ApplicationWindow;
import spv.util.FitsFileInformation;
import spv.util.ImageDisplayUtils;
import spv.util.ImagePreprocessing;
import spv.util.RawImageAnnotator;
import spv.util.SourceExtractor;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.logging.Level;

public class DetectionTask implements Runnable {
    private final EventBus eventBus;
    private final ImagePreprocessing preProcessing;
    private final FitsFileInformation[] selectedFiles;

    public DetectionTask(EventBus eventBus, ImagePreprocessing preProcessing, FitsFileInformation[] selectedFiles) {
        this.eventBus = eventBus;
        this.preProcessing = preProcessing;
        this.selectedFiles = selectedFiles;
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
                            SourceExtractor.detectionSigmaMultiplier,
                            SourceExtractor.minDetectionPixels
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
                preProcessing.detectObjects();

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