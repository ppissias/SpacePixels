/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.report;

import io.github.ppissias.jtransient.core.DataArea;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The enhanced stack: a noise-clipped, noise-weighted mean of the frames the run kept, in physical ADU, as a basis for
 * a less noisy picture of the field (SpacePixels F18). It is a presentation product; detection uses the median stack.
 *
 * <ol>
 *   <li>Each frame's sky offset against the median stack is removed (the median of the frame minus the median stack
 *       over a sample of the data area).</li>
 *   <li>Each frame's noise comes from differences between frames, Var(Fi − Fj) = σi² + σj², by least squares over
 *       neighbouring pairs: static structure cancels and no reference covariance enters, so the estimate holds from
 *       three frames on. A level-dependent term {@code b} (variance per ADU above the sky) is fitted from the same
 *       differences.</li>
 *   <li>At each pixel the first centre is the median of the frames with image data there (registration fill is missing
 *       data, never a value). A value is clipped when it lies more than 3 standard deviations from the centre, from its
 *       frame's noise and the level term at the shared centre; the kept values are averaged with weights of one over
 *       their variance; two passes, in which a clipped value can return. Without a level term, only pixels near the sky
 *       are clipped, so stars are not cut under changing seeing.</li>
 *   <li>One or two contributors are averaged without clipping; a pixel without contributors is NaN.</li>
 * </ol>
 *
 * <p>Rows are processed in bands on owned workers that are joined before {@link #build} returns; the frames are only
 * read. The only large allocation is the output plane.</p>
 */
final class EnhancedStack {
    /** Clipping threshold in standard deviations. */
    static final double CLIP_SIGMA = 3.0;
    /** Smallest frame noise used, in ADU, so flat or quantised data cannot give infinite weights. */
    static final double MIN_NOISE = 0.5;
    /** Physical ADU value of a stored 16-bit pixel: stored + 32768. */
    private static final int SHIFT = 32768;
    private static final int BAND_ROWS = 64;
    private static final int SAMPLE_PIXELS = 300_000;

    /** The stack, indexed {@code [y][x]}, in physical ADU; NaN where no kept frame has data. */
    final float[][] pixels;
    /** Number of frames combined. */
    final int frames;
    /** Each frame's noise in ADU, in the order of the frames given. */
    final double[] frameNoise;
    /** Variance per ADU above the sky; zero when it could not be fitted. */
    final double levelTerm;

    private EnhancedStack(float[][] pixels, int frames, double[] frameNoise, double levelTerm) {
        this.pixels = pixels;
        this.frames = frames;
        this.frameNoise = frameNoise;
        this.levelTerm = levelTerm;
    }

    /**
     * Builds the stack.
     *
     * @param frames the kept frames' stored (signed-short) pixels, all of one size; only read
     * @param median the engine's median stack of the same frames, or null
     * @param area where the sky statistics are sampled (the data area), or null for the whole frame
     * @param checkpoint called on the calling thread between bands; it may throw to cancel
     * @return the stack, or null when no frame is given
     */
    static EnhancedStack build(List<short[][]> frames, short[][] median, DataArea area, Runnable checkpoint)
            throws InterruptedException {
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        int n = frames.size();
        int height = frames.get(0).length;
        int width = frames.get(0)[0].length;
        int[][] sample = sample(width, height, area);
        double[] offsets = offsets(frames, median, sample);
        double sky = median != null ? skyLevel(median, sample) : skyLevel(frames.get(0), sample) - offsets[0];
        double[] variance = frameVariances(frames, offsets, sample);
        double levelTerm = median != null ? levelTerm(frames, offsets, variance, median, sky, sample) : 0.0;
        double meanNoise = 0;
        for (double v : variance) {
            meanNoise += Math.sqrt(v) / n;
        }

        float[][] out = new float[height][];
        int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 8));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> bands = new ArrayList<>();
            for (int start = 0; start < height; start += BAND_ROWS) {
                int from = start;
                int to = Math.min(height, start + BAND_ROWS);
                double clipLimit = 5 * meanNoise;
                bands.add(pool.submit(() -> {
                    Pixel work = new Pixel(n);
                    for (int y = from; y < to; y++) {
                        float[] row = new float[width];
                        for (int x = 0; x < width; x++) {
                            row[x] = work.combine(frames, offsets, variance, levelTerm, sky, clipLimit, y, x);
                        }
                        out[y] = row;
                    }
                }));
            }
            for (Future<?> band : bands) {
                if (checkpoint != null) {
                    checkpoint.run();
                }
                try {
                    band.get();
                } catch (ExecutionException failure) {
                    throw new IllegalStateException("The enhanced stack failed", failure.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(1, TimeUnit.MINUTES);
        }
        double[] noise = new double[n];
        for (int i = 0; i < n; i++) {
            noise[i] = Math.sqrt(variance[i]);
        }
        return new EnhancedStack(out, n, noise, levelTerm);
    }

    /** One worker's per-pixel scratch and the pixel combination. */
    private static final class Pixel {
        private final double[] values;
        private final double[] variances;
        private final double[] sorted;

        Pixel(int n) {
            values = new double[n];
            variances = new double[n];
            sorted = new double[n];
        }

        /** The combined value of one pixel. */
        float combine(List<short[][]> frames, double[] offsets, double[] variance, double levelTerm, double sky,
                      double clipLimit, int y, int x) {
            int count = 0;
            for (int i = 0; i < frames.size(); i++) {
                int stored = frames.get(i)[y][x];
                if (stored > DataArea.FILL_THRESHOLD) {
                    values[count] = stored + SHIFT - offsets[i];
                    variances[count] = variance[i];
                    count++;
                }
            }
            if (count == 0) {
                return Float.NaN;
            }
            if (count == 1) {
                return (float) values[0];
            }
            double centre;
            if (count == 2) {
                centre = 0.5 * (values[0] + values[1]);
                double level = Math.max(0, centre - sky) * levelTerm;
                double w0 = 1 / (variances[0] + level);
                double w1 = 1 / (variances[1] + level);
                return (float) ((w0 * values[0] + w1 * values[1]) / (w0 + w1));
            }
            System.arraycopy(values, 0, sorted, 0, count);
            Arrays.sort(sorted, 0, count);
            centre = count % 2 == 1 ? sorted[count / 2] : 0.5 * (sorted[count / 2 - 1] + sorted[count / 2]);
            double median = centre;
            for (int pass = 0; pass < 2; pass++) {
                double above = Math.max(0, centre - sky);
                boolean clip = levelTerm > 0 || above < clipLimit;
                double sum = 0;
                double weights = 0;
                for (int i = 0; i < count; i++) {
                    double v = variances[i] + levelTerm * above;
                    if (clip && Math.abs(values[i] - centre) > CLIP_SIGMA * Math.sqrt(v)) {
                        continue;
                    }
                    sum += values[i] / v;
                    weights += 1 / v;
                }
                centre = weights > 0 ? sum / weights : median;
            }
            return (float) centre;
        }
    }

    /** Up to {@link #SAMPLE_PIXELS} positions on a regular grid inside the area. */
    static int[][] sample(int width, int height, DataArea area) {
        long pixels = area != null ? Math.max(1, area.getPixelCount()) : (long) width * height;
        int step = (int) Math.max(1, Math.ceil(Math.sqrt(pixels / (double) SAMPLE_PIXELS)));
        List<int[]> positions = new ArrayList<>();
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                if (area == null || area.contains(x, y)) {
                    positions.add(new int[]{y, x});
                }
            }
        }
        return positions.toArray(new int[0][]);
    }

    /** Each frame's sky offset: against the median stack, or against the first frame without one. */
    static double[] offsets(List<short[][]> frames, short[][] median, int[][] sample) {
        double[] offsets = new double[frames.size()];
        short[][] reference = median != null ? median : frames.get(0);
        for (int i = 0; i < frames.size(); i++) {
            short[][] frame = frames.get(i);
            double[] d = new double[sample.length];
            int n = 0;
            for (int[] p : sample) {
                int a = frame[p[0]][p[1]];
                int b = reference[p[0]][p[1]];
                if (a > DataArea.FILL_THRESHOLD && b > DataArea.FILL_THRESHOLD) {
                    d[n++] = a - b;
                }
            }
            offsets[i] = n > 0 ? median(d, n) : 0.0;
        }
        return offsets;
    }

    /**
     * Each frame's noise variance from the robust spread of frame differences: Var(Fi − Fj) = σi² + σj² over the pairs
     * (i, i+1) and (i, i+2), solved by least squares. With two frames both get half the pair's variance; a frame without
     * a usable pair, or a non-physical solution, takes the mean of its pairs' halves.
     */
    static double[] frameVariances(List<short[][]> frames, double[] offsets, int[][] sample) {
        int n = frames.size();
        double[] variance = new double[n];
        if (n == 1) {
            variance[0] = 1.0;
            return variance;
        }
        List<int[]> pairs = new ArrayList<>();
        List<Double> pairVariance = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int d = 1; d <= 2; d++) {
                int j = i + d;
                if (j >= n) {
                    continue;
                }
                double v = robustVariance(frames.get(i), offsets[i], frames.get(j), offsets[j], sample);
                if (Double.isFinite(v)) {
                    pairs.add(new int[]{i, j});
                    pairVariance.add(v);
                }
            }
        }
        double[] halves = new double[n];
        int[] counts = new int[n];
        for (int k = 0; k < pairs.size(); k++) {
            for (int idx : pairs.get(k)) {
                halves[idx] += pairVariance.get(k) / 2;
                counts[idx]++;
            }
        }
        double[] solution = n >= 3 ? leastSquares(n, pairs, pairVariance) : null;
        double floor = MIN_NOISE * MIN_NOISE;
        for (int i = 0; i < n; i++) {
            double fallback = counts[i] > 0 ? halves[i] / counts[i] : 1.0;
            double v = solution != null && Double.isFinite(solution[i]) && solution[i] > 0 ? solution[i] : fallback;
            variance[i] = Math.max(floor, v);
        }
        return variance;
    }

    /** (1.4826 MAD)² of the sky-matched difference of two frames over the sample. */
    private static double robustVariance(short[][] a, double offsetA, short[][] b, double offsetB, int[][] sample) {
        double[] d = new double[sample.length];
        int n = 0;
        for (int[] p : sample) {
            int va = a[p[0]][p[1]];
            int vb = b[p[0]][p[1]];
            if (va > DataArea.FILL_THRESHOLD && vb > DataArea.FILL_THRESHOLD) {
                d[n++] = (va - offsetA) - (vb - offsetB);
            }
        }
        if (n < 100) {
            return Double.NaN;
        }
        double m = median(d, n);
        for (int i = 0; i < n; i++) {
            d[i] = Math.abs(d[i] - m);
        }
        double mad = 1.4826 * median(d, n);
        return mad * mad;
    }

    /** Least squares for x in x_i + x_j = v over the pairs (normal equations, Gaussian elimination). */
    private static double[] leastSquares(int n, List<int[]> pairs, List<Double> values) {
        double[][] m = new double[n][n + 1];
        for (int k = 0; k < pairs.size(); k++) {
            int i = pairs.get(k)[0];
            int j = pairs.get(k)[1];
            double v = values.get(k);
            m[i][i] += 1; m[j][j] += 1; m[i][j] += 1; m[j][i] += 1;
            m[i][n] += v; m[j][n] += v;
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(m[pivot][col]) < 1e-12) {
                return null;
            }
            double[] tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp;
            for (int r = 0; r < n; r++) {
                if (r == col) {
                    continue;
                }
                double f = m[r][col] / m[col][col];
                for (int c = col; c <= n; c++) {
                    m[r][c] -= f * m[col][c];
                }
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = m[i][n] / m[i][i];
        }
        return x;
    }

    /**
     * The level-dependent variance per ADU above the sky, from neighbouring frame pairs: in bins of the median stack's
     * level, (Var(Fi − Fj) − σi² − σj²) / (2 level), and the median of those estimates. Zero when no bin gives one or
     * the result is not positive.
     */
    static double levelTerm(List<short[][]> frames, double[] offsets, double[] variance, short[][] median, double sky,
                            int[][] sample) {
        int n = frames.size();
        if (n < 2) {
            return 0.0;
        }
        double noise = 0;
        for (double v : variance) {
            noise += Math.sqrt(v) / n;
        }
        double[] levels = new double[sample.length];
        int count = 0;
        for (int[] p : sample) {
            int m = median[p[0]][p[1]];
            if (m > DataArea.FILL_THRESHOLD) {
                levels[count++] = m + SHIFT - sky;
            }
        }
        if (count == 0) {
            return 0.0;
        }
        double[] sortedLevels = Arrays.copyOf(levels, count);
        Arrays.sort(sortedLevels);
        double top = sortedLevels[(int) (0.99 * (count - 1))];
        double bottom = 3 * noise;
        if (!(top > bottom)) {
            return 0.0;
        }
        int bins = 12;
        List<Double> estimates = new ArrayList<>();
        for (int i = 0; i + 1 < n; i++) {
            List<List<Double>> diffs = new ArrayList<>();
            List<List<Double>> binLevels = new ArrayList<>();
            for (int b = 0; b < bins; b++) {
                diffs.add(new ArrayList<>());
                binLevels.add(new ArrayList<>());
            }
            for (int[] p : sample) {
                int m = median[p[0]][p[1]];
                int a = frames.get(i)[p[0]][p[1]];
                int c = frames.get(i + 1)[p[0]][p[1]];
                if (m <= DataArea.FILL_THRESHOLD || a <= DataArea.FILL_THRESHOLD || c <= DataArea.FILL_THRESHOLD) {
                    continue;
                }
                double level = m + SHIFT - sky;
                if (level < bottom || level > top) {
                    continue;
                }
                int b = (int) Math.min(bins - 1, Math.floor(bins * Math.log(level / bottom) / Math.log(top / bottom)));
                diffs.get(b).add((a - offsets[i]) - (c - offsets[i + 1]));
                binLevels.get(b).add(level);
            }
            for (int b = 0; b < bins; b++) {
                List<Double> d = diffs.get(b);
                if (d.size() < 200) {
                    continue;
                }
                double[] values = d.stream().mapToDouble(Double::doubleValue).toArray();
                double m = median(values, values.length);
                for (int k = 0; k < values.length; k++) {
                    values[k] = Math.abs(values[k] - m);
                }
                double mad = 1.4826 * median(values, values.length);
                double[] lv = binLevels.get(b).stream().mapToDouble(Double::doubleValue).toArray();
                double level = median(lv, lv.length);
                estimates.add((mad * mad - variance[i] - variance[i + 1]) / (2 * level));
            }
        }
        if (estimates.isEmpty()) {
            return 0.0;
        }
        double[] e = estimates.stream().mapToDouble(Double::doubleValue).toArray();
        double result = median(e, e.length);
        return Double.isFinite(result) && result > 0 ? result : 0.0;
    }

    /** The median of the median stack (or a frame) over the sample, in physical ADU. */
    private static double skyLevel(short[][] image, int[][] sample) {
        double[] v = new double[sample.length];
        int n = 0;
        for (int[] p : sample) {
            int s = image[p[0]][p[1]];
            if (s > DataArea.FILL_THRESHOLD) {
                v[n++] = s + SHIFT;
            }
        }
        return n > 0 ? median(v, n) : 0.0;
    }

    /** The median of the first {@code n} values (the array is reordered). */
    private static double median(double[] values, int n) {
        Arrays.sort(values, 0, n);
        return n % 2 == 1 ? values[n / 2] : 0.5 * (values[n / 2 - 1] + values[n / 2]);
    }
}
