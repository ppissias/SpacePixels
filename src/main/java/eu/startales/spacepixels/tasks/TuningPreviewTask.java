package eu.startales.spacepixels.tasks;

import com.google.common.eventbus.EventBus;
import eu.startales.spacepixels.events.TuningPreviewFinishedEvent;
import eu.startales.spacepixels.events.TuningPreviewStartedEvent;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageDisplayUtils;
import eu.startales.spacepixels.util.RawImageAnnotator;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;
import nom.tam.fits.Fits;

import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.util.List;

public class TuningPreviewTask implements Runnable {

    private final EventBus eventBus;
    private final FitsFileInformation targetFile;
    private final DetectionConfig config;
    private final int displayIndex;
    private final int totalFiles;

    public TuningPreviewTask(EventBus eventBus, FitsFileInformation targetFile, DetectionConfig config, int displayIndex, int totalFiles) {
        this.eventBus = eventBus;
        this.targetFile = targetFile;
        this.config = config;
        this.displayIndex = displayIndex;
        this.totalFiles = totalFiles;
    }

    @Override
    public void run() {
        eventBus.post(new TuningPreviewStartedEvent());

        try {
            Fits fitsImage = new Fits(targetFile.getFilePath());
            Object kernelData = fitsImage.getHDU(0).getKernel();
            fitsImage.close();

            if (!(kernelData instanceof short[][])) {
                throw new Exception("Expected short[][] FITS format.");
            }

            short[][] imageData = (short[][]) kernelData;
            short[][] debugImage = new short[imageData.length][imageData[0].length];
            for (int x = 0; x < imageData.length; x++) {
                System.arraycopy(imageData[x], 0, debugImage[x], 0, imageData[x].length);
            }

            // EXTRACT
            List<SourceExtractor.DetectedObject> objects = SourceExtractor.extractSources(
                    debugImage,
                    config.detectionSigmaMultiplier,
                    config.minDetectionPixels,
                    config
            ).objects;

            // Tally Results
            int streakCount = 0;
            int pointCount = 0;
            for (SourceExtractor.DetectedObject obj : objects) {
                if (obj.isNoise) continue;
                if (obj.isStreak) streakCount++;
                else pointCount++;
            }

            String windowTitle = String.format("Tuning Preview: %s [%d/%d] [Point Sources: %d | Streaks: %d]",
                    targetFile.getFileName(), displayIndex, totalFiles, pointCount, streakCount);

            // Render
            BufferedImage grayImage = ImageDisplayUtils.createDisplayImage(debugImage);
            
            // Convert the Grayscale canvas to an RGB canvas so colored overlays actually show up!
            BufferedImage previewImage = new BufferedImage(grayImage.getWidth(), grayImage.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = previewImage.createGraphics();
            g2d.drawImage(grayImage, 0, 0, null);
            g2d.dispose();

            RawImageAnnotator.drawExactBlobs(previewImage, objects);
            RawImageAnnotator.drawDetections(previewImage, objects);

            // Post success
            eventBus.post(new TuningPreviewFinishedEvent(true, previewImage, windowTitle, null));

        } catch (Exception ex) {
            // Post failure
            eventBus.post(new TuningPreviewFinishedEvent(false, null, null, ex.getMessage()));
        }
    }
}