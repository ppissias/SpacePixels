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

import io.github.ppissias.jtransient.core.DataArea;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.List;

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

    /**
     * Robust automatic display levels (black and white points in unsigned ADU) from the valid pixels' sky median and
     * robust sky noise ({@code 1.4826 * MAD}), excluding out-of-frame padding and registration fill
     * ({@code stored <= DataArea.FILL_THRESHOLD}).
     */
    public static double[] displayLevels(short[][] imageData, double blackSigma, double whiteSigma) {
        if (imageData == null || imageData.length == 0 || imageData[0].length == 0) {
            return new double[]{0.0, 0.0};
        }
        FrameStats stats = frameStats(imageData);
        if (!stats.valid) {
            return new double[]{0.0, 0.0};
        }
        double blackPoint = Math.max(0.0, stats.sky - (blackSigma * stats.noise));
        double whitePoint = stats.noise > 0.0 ? Math.min(stats.actualMax, stats.sky + (whiteSigma * stats.noise)) : stats.actualMax;
        return new double[]{blackPoint, whitePoint};
    }

    /**
     * Shared display levels across a sequence of frame crops: uses the median of the frames' sky medians and the
     * median of their robust sky noises, bounded by the brightest valid pixel across the sequence.
     */
    public static double[] sharedDisplayLevels(List<short[][]> frames, double blackSigma, double whiteSigma) {
        if (frames == null || frames.isEmpty()) {
            return new double[]{0.0, 0.0};
        }
        float[] skies = new float[frames.size()];
        float[] noises = new float[frames.size()];
        int validFrames = 0;
        int maxActual = 0;
        for (short[][] frame : frames) {
            if (frame == null || frame.length == 0 || frame[0].length == 0) {
                continue;
            }
            FrameStats stats = frameStats(frame);
            if (!stats.valid) {
                continue;
            }
            skies[validFrames] = (float) stats.sky;
            noises[validFrames] = (float) stats.noise;
            validFrames++;
            if (stats.actualMax > maxActual) {
                maxActual = stats.actualMax;
            }
        }
        if (validFrames == 0) {
            return new double[]{0.0, 0.0};
        }
        double sky = median(skies, validFrames);
        double noise = median(noises, validFrames);
        double blackPoint = Math.max(0.0, sky - (blackSigma * noise));
        double whitePoint = noise > 0.0 ? Math.min(maxActual, sky + (whiteSigma * noise)) : maxActual;
        return new double[]{blackPoint, whitePoint};
    }

    private record FrameStats(boolean valid, double sky, double noise, int actualMax) {
        static final FrameStats INVALID = new FrameStats(false, 0.0, 0.0, 0);
    }

    private static FrameStats frameStats(short[][] imageData) {
        int height = imageData.length;
        int width = imageData[0].length;
        int[] counts = new int[65536];
        long validCount = 0;
        int actualMin = Integer.MAX_VALUE;
        int actualMax = Integer.MIN_VALUE;

        for (int y = 0; y < height; y++) {
            short[] row = imageData[y];
            for (int x = 0; x < width; x++) {
                short raw = row[x];
                if (raw <= DataArea.FILL_THRESHOLD) {
                    continue;
                }
                int val = raw + 32768;
                counts[val]++;
                validCount++;
                if (val < actualMin) {
                    actualMin = val;
                }
                if (val > actualMax) {
                    actualMax = val;
                }
            }
        }

        if (validCount == 0) {
            return FrameStats.INVALID;
        }

        long lowRank = (validCount - 1) / 2;
        long highRank = validCount / 2;
        long running = 0;
        int m1 = actualMin;
        int m2 = actualMin;
        boolean foundM1 = false;
        for (int v = actualMin; v <= actualMax; v++) {
            int c = counts[v];
            if (c == 0) {
                continue;
            }
            running += c;
            if (!foundM1 && running > lowRank) {
                m1 = v;
                foundM1 = true;
            }
            if (running > highRank) {
                m2 = v;
                break;
            }
        }
        double sky = 0.5 * (m1 + m2);

        double baseDistance = 0.5 * (m2 - m1);
        long madRunning = 0;
        double madLow = 0.0;
        double madHigh = 0.0;
        boolean foundMadLow = false;
        int maxK = Math.max(m1 - actualMin, actualMax - m2);
        for (int k = 0; k <= maxK; k++) {
            long c;
            if (k == 0 && m1 == m2) {
                c = counts[m1];
            } else {
                int left = m1 - k;
                int right = m2 + k;
                c = (left >= actualMin ? counts[left] : 0L) + (right <= actualMax ? counts[right] : 0L);
            }
            if (c == 0) {
                continue;
            }
            madRunning += c;
            double dist = baseDistance + k;
            if (!foundMadLow && madRunning > lowRank) {
                madLow = dist;
                foundMadLow = true;
            }
            if (madRunning > highRank) {
                madHigh = dist;
                break;
            }
        }
        double mad = 0.5 * (madLow + madHigh);
        double noise = 1.4826 * mad;
        if (noise == 0.0 && actualMax > actualMin) {
            double sumSq = 0.0;
            for (int v = actualMin; v <= actualMax; v++) {
                int c = counts[v];
                if (c != 0) {
                    double diff = v - sky;
                    sumSq += c * diff * diff;
                }
            }
            noise = Math.sqrt(sumSq / validCount);
        }
        return new FrameStats(true, sky, noise, actualMax);
    }

    /** Maps a normalized value (clamped to 0..1) through the square-root tone curve to an 8-bit grey level (0..255). */
    public static int sqrtToneCurve(double normalized) {
        double clamped = Math.max(0.0, Math.min(1.0, normalized));
        return (int) Math.round(Math.sqrt(clamped) * 255.0);
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
            short[] row = imageData[y];
            for (int x = 0; x < width; x++) {
                short raw = row[x];
                if (raw <= DataArea.FILL_THRESHOLD) {
                    raster.setSample(x, y, 0, 0);
                    continue;
                }
                int val = raw + 32768;
                int displayValue = sqrtToneCurve((val - blackPoint) / range);
                raster.setSample(x, y, 0, displayValue);
            }
        }

        return image;
    }
}
