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

    /** Where the automatic asinh stretch draws the sky median, as a fraction of white. */
    public static final double ASINH_SKY_LEVEL = 0.15;
    /** The black point of the automatic asinh stretch, in sky noise below the sky median. */
    public static final double ASINH_BLACK_SKY_SIGMA = 2.0;
    /** Most pixels sampled for the sky median and noise. */
    private static final int SKY_SAMPLES = 2_000_000;

    /**
     * Draws a floating-point image in physical ADU (the 16-bit images' shifted values), such as the enhanced stack, with
     * the automatic asinh stretch of {@link #asinhLevels}: faint structure just above the sky is lifted strongly and
     * bright stars are compressed rather than clipped. NaN (no data) is drawn black.
     */
    public static BufferedImage createAsinhDisplayImage(float[][] imageData) {
        AsinhLevels levels = asinhLevels(imageData);
        int height = imageData.length, width = imageData[0].length;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = image.getRaster();
        // AsinhLevels.display with its constants taken out of the pixel loop.
        double black = levels.black(), scale = 1.0 / ((levels.white() - black) * levels.softening());
        double limit = 1.0 / levels.softening(), gray = 255.0 / asinh(limit);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            float[] values = imageData[y];
            for (int x = 0; x < width; x++) {
                float value = values[x];
                row[x] = Float.isFinite(value)
                        ? (int) Math.round(asinh(Math.max(0, Math.min(limit, (value - black) * scale))) * gray) : 0;
            }
            raster.setSamples(0, y, width, 1, 0, row);
        }
        return image;
    }

    /**
     * The levels of the automatic asinh stretch of an image in physical ADU. The black point lies
     * {@value #ASINH_BLACK_SKY_SIGMA} sky sigma below the sky median and the white point at the brightest pixel. The
     * strength is chosen so that the sky median is drawn at {@value #ASINH_SKY_LEVEL} of white: a darker or brighter
     * sky, a wider or narrower range, all give the same sky brightness on screen. The sky median and its noise (from
     * the median absolute deviation) come from up to two million evenly spread finite pixels.
     */
    public static AsinhLevels asinhLevels(float[][] imageData) {
        long finite = 0;
        double max = Double.NEGATIVE_INFINITY;
        for (float[] row : imageData) {
            for (float value : row) {
                if (Float.isFinite(value)) {
                    finite++;
                    max = Math.max(max, value);
                }
            }
        }
        if (finite == 0) {
            return new AsinhLevels(0, 1, 1);
        }
        long stride = Math.max(1, (finite + SKY_SAMPLES - 1) / SKY_SAMPLES);
        float[] sample = new float[(int) ((finite + stride - 1) / stride)];
        int count = 0;
        long index = 0;
        for (float[] row : imageData) {
            for (float value : row) {
                if (Float.isFinite(value) && index++ % stride == 0) {
                    sample[count++] = value;
                }
            }
        }
        double sky = median(sample, count);
        for (int i = 0; i < count; i++) {
            sample[i] = (float) Math.abs(sample[i] - sky);
        }
        double noise = 1.4826 * median(sample, count);
        double black = sky - ASINH_BLACK_SKY_SIGMA * noise;
        if (!(max > black)) {
            return new AsinhLevels(black, black + 1, 1);
        }
        double skyPosition = (sky - black) / (max - black);
        return new AsinhLevels(black, max, softening(skyPosition, ASINH_SKY_LEVEL));
    }

    /**
     * The softening {@code a} of {@code asinh(x / a) / asinh(1 / a)} that draws {@code skyPosition} (0 to 1) at
     * {@code level}. Smaller is stronger; when the sky is already at or above the level, the stretch is nearly linear.
     */
    static double softening(double skyPosition, double level) {
        double low = 1e-12, high = 1e3;
        if (!(skyPosition > 0) || skyPosition >= level) {
            return high;
        }
        for (int i = 0; i < 100; i++) {
            double middle = Math.sqrt(low * high);
            if (asinh(skyPosition / middle) / asinh(1 / middle) > level) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return Math.sqrt(low * high);
    }

    /** The levels of an asinh stretch: black and white points in ADU, and the softening (smaller is stronger). */
    public record AsinhLevels(double black, double white, double softening) {
        /** The display value, 0 to 1, of a value in ADU. */
        public double display(double value) {
            double x = Math.max(0, Math.min(1, (value - black) / (white - black)));
            return asinh(x / softening) / asinh(1 / softening);
        }
    }

    private static double asinh(double x) {
        return Math.log(x + Math.sqrt(x * x + 1));
    }

    /** The median of the first {@code count} values, which it sorts. */
    private static double median(float[] values, int count) {
        java.util.Arrays.sort(values, 0, count);
        return count % 2 == 1 ? values[count / 2] : 0.5 * (values[count / 2 - 1] + values[count / 2]);
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
