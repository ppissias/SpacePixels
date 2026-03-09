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
import eu.startales.spacepixels.events.PreviewGenerationFinishedEvent;
import eu.startales.spacepixels.util.ImagePreprocessing;
import eu.startales.spacepixels.util.StretchAlgorithm;

import java.awt.image.BufferedImage;

public class GeneratePreviewsTask implements Runnable {
    private final EventBus eventBus;
    private final ImagePreprocessing preProcessing;
    private final String filePath;
    private final int stretchFactor;
    private final int iterations;
    private final StretchAlgorithm algo;

    public GeneratePreviewsTask(EventBus eventBus, ImagePreprocessing preProcessing, String filePath,
                                int stretchFactor, int iterations, StretchAlgorithm algo) {
        this.eventBus = eventBus;
        this.preProcessing = preProcessing;
        this.filePath = filePath;
        this.stretchFactor = stretchFactor;
        this.iterations = iterations;
        this.algo = algo;
    }

    @Override
    public void run() {
        try {
            Fits fitsImage = new Fits(filePath);
            Object kernelData = fitsImage.getHDU(0).getKernel();

            BufferedImage orig = preProcessing.getImagePreview(kernelData);
            BufferedImage stretched = preProcessing.getStretchedImagePreview(kernelData, stretchFactor, iterations, algo);

            fitsImage.close();

            eventBus.post(new PreviewGenerationFinishedEvent(orig, stretched, true));
        } catch (Exception e) {
            e.printStackTrace();
            eventBus.post(new PreviewGenerationFinishedEvent(null, null, false));
        }
    }
}