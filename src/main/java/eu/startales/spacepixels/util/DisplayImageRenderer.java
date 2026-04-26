/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */

package eu.startales.spacepixels.util;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * Generic grayscale display renderer shared by UI previews and report export paths.
 */
public final class DisplayImageRenderer {

    public static double autoStretchBlackSigma = 0.5;
    public static double autoStretchWhiteSigma = 5.0;

    private DisplayImageRenderer() {
    }

    public static BufferedImage createDisplayImage(short[][] imageData) {
        return createDisplayImage(imageData, autoStretchBlackSigma, autoStretchWhiteSigma);
    }

    public static BufferedImage createDisplayImage(short[][] imageData,
                                                   double blackSigma,
                                                   double whiteSigma) {
        int height = imageData.length;
        int width = imageData[0].length;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = image.getRaster();

        long sum = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                sum += (imageData[y][x] + 32768);
            }
        }
        double mean = (double) sum / (width * height);

        double sumSqDiff = 0;
        int actualMax = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int val = imageData[y][x] + 32768;
                if (val > actualMax) {
                    actualMax = val;
                }

                double diff = val - mean;
                sumSqDiff += (diff * diff);
            }
        }
        double variance = sumSqDiff / (width * height);
        double sigma = Math.sqrt(variance);

        double blackPoint = mean - (blackSigma * sigma);
        double whitePoint = mean + (whiteSigma * sigma);

        if (blackPoint < 0) {
            blackPoint = 0;
        }
        if (whitePoint > actualMax) {
            whitePoint = actualMax;
        }

        double range = whitePoint - blackPoint;
        if (range <= 0) {
            range = 1.0;
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int val = imageData[y][x] + 32768;

                double adjustedVal = val - blackPoint;
                if (adjustedVal < 0) {
                    adjustedVal = 0;
                }
                if (adjustedVal > range) {
                    adjustedVal = range;
                }

                double normalized = adjustedVal / range;
                double stretched = Math.sqrt(normalized);
                int displayValue = (int) (stretched * 255.0);

                if (displayValue > 255) {
                    displayValue = 255;
                }
                if (displayValue < 0) {
                    displayValue = 0;
                }

                raster.setSample(x, y, 0, displayValue);
            }
        }

        return image;
    }
}
