package eu.startales.spacepixels.tasks;

import com.google.common.eventbus.EventBus;
import eu.startales.spacepixels.events.BlinkFinishedEvent;
import eu.startales.spacepixels.events.BlinkFrameUpdateEvent;
import eu.startales.spacepixels.events.BlinkStartedEvent;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.StretchAlgorithm;
import nom.tam.fits.Fits;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;

public class BlinkImagesTask implements Runnable {

    private final EventBus eventBus;
    private final ImageProcessing imageProcessing;
    private final FitsFileInformation[] files;
    private final int stretchFactor;
    private final int iterations;
    private final StretchAlgorithm algorithm;
    private final AtomicBoolean isBlinking;

    public BlinkImagesTask(EventBus eventBus, ImageProcessing imageProcessing, FitsFileInformation[] files,
                           int stretchFactor, int iterations, StretchAlgorithm algorithm, AtomicBoolean isBlinking) {
        this.eventBus = eventBus;
        this.imageProcessing = imageProcessing;
        this.files = files;
        this.stretchFactor = stretchFactor;
        this.iterations = iterations;
        this.algorithm = algorithm;
        this.isBlinking = isBlinking;
    }

    @Override
    public void run() {
        eventBus.post(new BlinkStartedEvent());

        try {
            if (files == null || files.length == 0) {
                eventBus.post(new BlinkFinishedEvent(false, "No files selected for blinking."));
                return;
            }

            // 1. Pre-load and stretch all images into memory
            BufferedImage[] loadedImages = new BufferedImage[files.length];
            for (int i = 0; i < files.length; i++) {
                // If the user cancelled while we were loading, abort early
                if (!isBlinking.get()) {
                    eventBus.post(new BlinkFinishedEvent(true, null));
                    return;
                }

                Fits fitsImage = new Fits(files[i].getFilePath());
                Object kernelData = fitsImage.getHDU(0).getKernel();
                fitsImage.close();

                loadedImages[i] = imageProcessing.getStretchedImageFullSize(
                        kernelData,
                        files[i].getSizeWidth(),
                        files[i].getSizeHeight(),
                        stretchFactor,
                        iterations,
                        algorithm
                );
            }

            // 2. The Animation Loop
            int currentIndex = 0;
            while (isBlinking.get()) {
                // Emit the current frame to the UI
                eventBus.post(new BlinkFrameUpdateEvent(loadedImages[currentIndex]));

                // Wait 500ms
                Thread.sleep(500);

                // Advance index
                currentIndex++;
                if (currentIndex >= loadedImages.length) {
                    currentIndex = 0;
                }
            }

            // Clean exit
            eventBus.post(new BlinkFinishedEvent(true, null));

        } catch (Exception ex) {
            eventBus.post(new BlinkFinishedEvent(false, ex.getMessage()));
        }
    }
}