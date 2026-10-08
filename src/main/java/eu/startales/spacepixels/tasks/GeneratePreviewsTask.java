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
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.StretchAlgorithm;

import java.awt.image.BufferedImage;

public class GeneratePreviewsTask implements Runnable {
    private final EventBus eventBus;
    private final ImageProcessing preProcessing;
    private final String filePath;
    private final int stretchFactor;
    private final int iterations;
    private final StretchAlgorithm algo;

    public GeneratePreviewsTask(EventBus eventBus, ImageProcessing preProcessing, String filePath,
                                int stretchFactor, int iterations, StretchAlgorithm algo) {
        this.eventBus = eventBus;
        this.preProcessing = preProcessing;
        this.filePath = filePath;
        this.stretchFactor = stretchFactor;
        this.iterations = iterations;
        this.algo = algo;
    }

    /** Longest side of the preview images; larger frames are reduced by taking every n-th pixel. */
    public static final int MAX_PREVIEW_SIDE = 2400;

    @Override
    public void run() {
        try {
            Fits fitsImage = new Fits(filePath);
            Object kernelData = ImageProcessing.getImageHDU(fitsImage).getKernel();

            // The whole frame, reduced to display size; the stretch statistics then cover the whole frame too.
            Object previewData = reduce(kernelData, MAX_PREVIEW_SIDE);
            BufferedImage orig;
            BufferedImage stretched;
            if (previewData != null) {
                int[] size = size(previewData);
                orig = linearImage(previewData);
                stretched = preProcessing.getStretchedImageFullSize(previewData, size[0], size[1], stretchFactor, iterations, algo);
            } else {
                orig = preProcessing.getImagePreview(kernelData);
                stretched = preProcessing.getStretchedImagePreview(kernelData, stretchFactor, iterations, algo);
            }

            fitsImage.close();

            eventBus.post(new PreviewGenerationFinishedEvent(orig, stretched, true));
        } catch (Exception e) {
            e.printStackTrace();
            eventBus.post(new PreviewGenerationFinishedEvent(null, null, false));
        }
    }

    /**
     * Keeps every n-th pixel of 16-bit mono or colour data so the longest side is at most {@code maxSide}; returns
     * the data unchanged when it is small enough, and null for other pixel types.
     */
    static Object reduce(Object kernel, int maxSide) {
        if (kernel instanceof short[][]) {
            short[][] data = (short[][]) kernel;
            int step = step(data[0].length, data.length, maxSide);
            return step == 1 ? data : reducePlane(data, step);
        }
        if (kernel instanceof short[][][]) {
            short[][][] data = (short[][][]) kernel;
            int step = step(data[0][0].length, data[0].length, maxSide);
            if (step == 1) {
                return data;
            }
            short[][][] reduced = new short[data.length][][];
            for (int channel = 0; channel < data.length; channel++) {
                reduced[channel] = reducePlane(data[channel], step);
            }
            return reduced;
        }
        return null;
    }

    private static int step(int width, int height, int maxSide) {
        return Math.max(1, (int) Math.ceil(Math.max(width, height) / (double) maxSide));
    }

    private static short[][] reducePlane(short[][] plane, int step) {
        int height = (plane.length + step - 1) / step;
        int width = (plane[0].length + step - 1) / step;
        short[][] reduced = new short[height][width];
        for (int row = 0; row < height; row++) {
            short[] source = plane[row * step];
            for (int column = 0; column < width; column++) {
                reduced[row][column] = source[column * step];
            }
        }
        return reduced;
    }

    /** {width, height} of mono or colour data. */
    static int[] size(Object data) {
        if (data instanceof short[][]) {
            short[][] plane = (short[][]) data;
            return new int[]{plane[0].length, plane.length};
        }
        short[][] plane = ((short[][][]) data)[0];
        return new int[]{plane[0].length, plane.length};
    }

    /** The unstretched frame, mapped linearly from the 16-bit range to grey (or colour). */
    static BufferedImage linearImage(Object data) {
        int[] size = size(data);
        BufferedImage image = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
        boolean colour = data instanceof short[][][];
        short[][][] channels = colour ? (short[][][]) data : new short[][][]{(short[][]) data};
        for (int row = 0; row < size[1]; row++) {
            for (int column = 0; column < size[0]; column++) {
                int red = (channels[0][row][column] + 32768) >> 8;
                int green = colour ? (channels[1][row][column] + 32768) >> 8 : red;
                int blue = colour ? (channels[2][row][column] + 32768) >> 8 : red;
                image.setRGB(column, row, (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }
}
