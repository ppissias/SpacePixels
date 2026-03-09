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
import eu.startales.spacepixels.events.FullSizeGenerationFinishedEvent;
import eu.startales.spacepixels.events.FullSizeGenerationStartedEvent;
import eu.startales.spacepixels.util.ImagePreprocessing;
import eu.startales.spacepixels.util.StretchAlgorithm;

import java.awt.image.BufferedImage;

public class GenerateFullSizeTask implements Runnable {
    private final EventBus eventBus;
    private final ImagePreprocessing preProcessing;
    private final String filePath;
    private final int width;
    private final int height;
    private final int stretchFactor;
    private final int iterations;
    private final StretchAlgorithm algo;

    public GenerateFullSizeTask(EventBus eventBus, ImagePreprocessing preProcessing, String filePath,
                                int width, int height, int stretchFactor, int iterations, StretchAlgorithm algo) {
        this.eventBus = eventBus;
        this.preProcessing = preProcessing;
        this.filePath = filePath;
        this.width = width;
        this.height = height;
        this.stretchFactor = stretchFactor;
        this.iterations = iterations;
        this.algo = algo;
    }

    @Override
    public void run() {
        eventBus.post(new FullSizeGenerationStartedEvent());

        try {
            Fits fitsImage = new Fits(filePath);
            Object kernelData = fitsImage.getHDU(0).getKernel();

            BufferedImage fullSize = preProcessing.getStretchedImageFullSize(kernelData, width, height, stretchFactor, iterations, algo);
            fitsImage.close();

            eventBus.post(new FullSizeGenerationFinishedEvent(fullSize, true, null));
        } catch (Exception e) {
            e.printStackTrace();
            eventBus.post(new FullSizeGenerationFinishedEvent(null, false, e.getMessage()));
        }
    }
}