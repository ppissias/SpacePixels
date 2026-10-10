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

/**
 * The display stretch as a lookup table: the grey level (0 to 255) of every 16-bit value of one channel.
 *
 * <p>Every stretch algorithm maps a pixel through a function of its value and of whole-frame statistics (the
 * histogram percentiles, the mean, or the smallest and largest value after each iteration). The histogram holds all
 * of them, so the table gives exactly the grey levels that {@link FitsVisualizationRenderer} draws, while a stretch
 * change costs a pass over 65,536 values instead of over every pixel of every frame.</p>
 */
public final class DisplayStretch {

    /** Number of 16-bit values; tables and histograms are indexed by {@code value - Short.MIN_VALUE}. */
    public static final int VALUES = 65536;

    private DisplayStretch() {
    }

    /** Histogram of one channel, indexed by {@code value - Short.MIN_VALUE}. */
    public static int[] histogram(short[] data) {
        int[] histogram = new int[VALUES];
        for (short value : data) {
            histogram[value - Short.MIN_VALUE]++;
        }
        return histogram;
    }

    /** Grey level of every value of a channel with this histogram, stretched as the renderer stretches it. */
    public static byte[] lookupTable(int[] histogram, StretchAlgorithm algorithm, int primary, int secondary) {
        long pixels = 0;
        for (int count : histogram) {
            pixels += count;
        }
        short[] stretched;
        switch (algorithm) {
            case ENHANCE_HIGH:
                stretched = enhanceHigh(histogram, primary, secondary);
                break;
            case EXTREME:
                stretched = extreme(histogram, pixels, primary, secondary);
                break;
            case ASINH:
                stretched = asinh(histogram, pixels, primary, secondary);
                break;
            case ENHANCE_LOW:
            default:
                stretched = enhanceLow(histogram, primary, secondary);
                break;
        }
        byte[] table = new byte[VALUES];
        for (int index = 0; index < VALUES; index++) {
            int absValue = stretched[index] + Short.MAX_VALUE + 1;
            if (absValue > 2 * Short.MAX_VALUE) {
                absValue = 2 * Short.MAX_VALUE;
            }
            float intensity = ((float) absValue) / (2 * (float) Short.MAX_VALUE);
            // As java.awt.Color(float, float, float) rounds a component.
            table[index] = (byte) (int) (intensity * 255 + 0.5);
        }
        return table;
    }

    private static short[] identity() {
        short[] values = new short[VALUES];
        for (int index = 0; index < VALUES; index++) {
            values[index] = (short) (index + Short.MIN_VALUE);
        }
        return values;
    }

    private static short[] enhanceHigh(int[] histogram, int intensity, int iterations) {
        short[] values = identity();
        for (int iteration = 0; iteration < iterations; iteration++) {
            short minimumValue = Short.MAX_VALUE;
            for (int index = 0; index < VALUES; index++) {
                int absValue = values[index] - Short.MIN_VALUE;
                float newValue = (float) absValue * (1 + ((float) intensity / 100));
                newValue = newValue - Short.MAX_VALUE;
                values[index] = newValue > Short.MAX_VALUE ? Short.MAX_VALUE : (short) newValue;
                if (histogram[index] > 0 && minimumValue > values[index]) {
                    minimumValue = values[index];
                }
            }
            int minimumValueDistanceFromZero = Math.min(minimumValue - Short.MIN_VALUE, 2 * Short.MAX_VALUE);
            for (int index = 0; index < VALUES; index++) {
                values[index] = (short) (values[index] - minimumValueDistanceFromZero);
            }
        }
        return values;
    }

    private static short[] enhanceLow(int[] histogram, int intensity, int iterations) {
        short[] values = identity();
        for (int iteration = 0; iteration < iterations; iteration++) {
            short minimumValue = Short.MAX_VALUE;
            short maximumValue = Short.MIN_VALUE;
            for (int index = 0; index < VALUES; index++) {
                int absValue = values[index] - Short.MIN_VALUE;
                float scale = 1 - (((float) absValue) / (2 * (float) Short.MAX_VALUE));
                float newValue = (float) absValue * (1 + (((float) intensity / 100) * scale));
                newValue = newValue - Short.MAX_VALUE;
                values[index] = newValue > Short.MAX_VALUE ? Short.MAX_VALUE : (short) newValue;
                if (histogram[index] > 0) {
                    minimumValue = (short) Math.min(minimumValue, values[index]);
                    maximumValue = (short) Math.max(maximumValue, values[index]);
                }
            }
            int minimumValueDistanceFromZero = Math.min(minimumValue - Short.MIN_VALUE, 2 * Short.MAX_VALUE);
            int maximumValueDistanceFromMax = Math.min(Short.MAX_VALUE - maximumValue, 2 * Short.MAX_VALUE);
            float stretchCoefficient = 1 + (((float) maximumValueDistanceFromMax) / (2 * (float) Short.MAX_VALUE));
            for (int index = 0; index < VALUES; index++) {
                int absValue = values[index] - Short.MIN_VALUE - minimumValueDistanceFromZero;
                float newValue = ((float) absValue) * stretchCoefficient;
                newValue = newValue - Short.MAX_VALUE;
                values[index] = newValue > Short.MAX_VALUE ? Short.MAX_VALUE : (short) newValue;
            }
        }
        return values;
    }

    private static short[] extreme(int[] histogram, long pixels, int threshold, int intensity) {
        long allPixelSumValue = 0;
        for (int index = 0; index < VALUES; index++) {
            allPixelSumValue += (long) index * histogram[index];
        }
        float averageNoiseLevel = ((float) allPixelSumValue) / (float) pixels;
        short[] values = identity();
        float brightValue = (((float) intensity) / 20) * (2 * (float) Short.MAX_VALUE) - Short.MAX_VALUE;
        short bright = brightValue > Short.MAX_VALUE ? Short.MAX_VALUE : (short) brightValue;
        for (int index = 0; index < VALUES; index++) {
            if (index >= averageNoiseLevel + 10 * threshold) {
                values[index] = bright;
            }
        }
        return values;
    }

    private static short[] asinh(int[] histogram, long pixels, int blackPointPercent, int stretchStrength) {
        int blackPointValue = percentile(histogram, pixels, blackPointPercent / 100.0);
        int whitePointValue = percentile(histogram, pixels, 0.999);
        if (whitePointValue <= blackPointValue) {
            whitePointValue = blackPointValue + 1;
        }
        double usableRange = whitePointValue - blackPointValue;
        double stretchScale = Math.max(1.0, stretchStrength);
        double normalization = asinh(stretchScale);
        short[] values = new short[VALUES];
        for (int index = 0; index < VALUES; index++) {
            double normalizedValue = Math.max(0.0, Math.min(1.0, (index - blackPointValue) / usableRange));
            double stretchedValue = asinh(normalizedValue * stretchScale) / normalization;
            long unsignedValue = Math.round(stretchedValue * ((2.0 * Short.MAX_VALUE) + 1.0));
            unsignedValue = Math.max(0, Math.min((2 * Short.MAX_VALUE) + 1, unsignedValue));
            values[index] = (short) (unsignedValue + Short.MIN_VALUE);
        }
        return values;
    }

    private static double asinh(double value) {
        return Math.log(value + Math.sqrt((value * value) + 1.0));
    }

    private static int percentile(int[] histogram, long totalPixels, double percentile) {
        if (totalPixels <= 0) {
            return 0;
        }
        long targetCount = Math.max(0L, Math.min(totalPixels - 1, (long) Math.floor((totalPixels - 1) * percentile)));
        long runningCount = 0;
        for (int value = 0; value < histogram.length; value++) {
            runningCount += histogram[value];
            if (runningCount > targetCount) {
                return value;
            }
        }
        return histogram.length - 1;
    }
}
