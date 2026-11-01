/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.fits;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * Generic grayscale display renderer shared by UI previews and report export paths; the stretch sigmas come from the
 * caller's {@link eu.startales.spacepixels.config.VisualizationSettings}.
 */
public final class DisplayImageRenderer {

    private DisplayImageRenderer() {
    }

    public static BufferedImage createDisplayImage(short[][] imageData,
                                                   double blackSigma,
                                                   double whiteSigma) {
        double[] levels = displayLevels(imageData, blackSigma, whiteSigma);
        return createDisplayImageAtLevels(imageData, levels[0], levels[1]);
    }

    public static double[] displayLevels(short[][] imageData, double blackSigma, double whiteSigma) {
        int height = imageData.length;
        int width = imageData[0].length;
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

        return new double[]{blackPoint, whitePoint};
    }

    /**
     * The same automatic stretch for a floating-point image in physical ADU (the 16-bit images' shifted values), such as
     * the enhanced stack. Only finite pixels count towards the levels; NaN (no data) is drawn black. No conversion to
     * 16 bits takes place.
     */
    public static BufferedImage createDisplayImage(float[][] imageData, double blackSigma, double whiteSigma) {
        int height = imageData.length, width = imageData[0].length;
        double sum = 0;
        long count = 0;
        double max = 0;
        for (float[] row : imageData) {
            for (float value : row) {
                if (Float.isFinite(value)) {
                    sum += value;
                    count++;
                    max = Math.max(max, value);
                }
            }
        }
        double mean = count > 0 ? sum / count : 0;
        double sumSqDiff = 0;
        for (float[] row : imageData) {
            for (float value : row) {
                if (Float.isFinite(value)) {
                    sumSqDiff += (value - mean) * (value - mean);
                }
            }
        }
        double sigma = count > 0 ? Math.sqrt(sumSqDiff / count) : 0;
        double blackPoint = Math.max(0, mean - blackSigma * sigma);
        double whitePoint = Math.min(max, mean + whiteSigma * sigma);
        double range = whitePoint - blackPoint;
        if (range <= 0) {
            range = 1.0;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = image.getRaster();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float value = imageData[y][x];
                if (!Float.isFinite(value)) {
                    raster.setSample(x, y, 0, 0);
                    continue;
                }
                double normalized = Math.max(0, Math.min(range, value - blackPoint)) / range;
                raster.setSample(x, y, 0, Math.max(0, Math.min(255, (int) (Math.sqrt(normalized) * 255.0))));
            }
        }
        return image;
    }

    /** Fixed display levels for comparisons in which brightness changes must stay visible. */
    public static BufferedImage createDisplayImageAtLevels(short[][] imageData, double blackPoint, double whitePoint) {
        int height = imageData.length, width = imageData[0].length;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = image.getRaster();
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
