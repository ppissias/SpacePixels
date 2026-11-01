package eu.startales.spacepixels.report;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The enhanced stack's combination: coverage, contributors, outliers, noise against the median, unequal frame noise,
 * stars under changing seeing and flat data. Values are physical ADU; stored pixels are value − 32768.
 */
public class EnhancedStackTest {
    private static final int SIZE = 160;
    private static final int FILL = Short.MIN_VALUE;

    /** A pixel covered by a minority of frames takes their values, not the fill that dominates the engine's median. */
    @Test public void aMinorityOfContributorsIsNotClippedAgainstFill() throws Exception {
        List<short[][]> frames = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            short[][] frame = constant(1000);
            if (i >= 10) frame[5][5] = (short) FILL;
            frames.add(frame);
        }
        EnhancedStack stack = EnhancedStack.build(frames, median(frames), null, null);
        assertEquals(1000, stack.pixels[5][5], 0.01);
        assertEquals(35, stack.frames);
    }

    /** One contributor gives its value, two their mean, none NaN. */
    @Test public void fewContributorsAreAveragedWithoutClipping() throws Exception {
        List<short[][]> frames = new ArrayList<>();
        int[] values = {1000, 1200, 1400, 1600};
        for (int value : values) {
            short[][] frame = noise(new Random(value), 1000, 10);
            frame[3][3] = (short) FILL;
            frame[4][4] = (short) FILL;
            frames.add(frame);
        }
        frames.get(0)[3][3] = (short) (5000 - 32768);
        frames.get(1)[4][4] = (short) (2000 - 32768);
        frames.get(2)[4][4] = (short) (2200 - 32768);
        frames.get(0)[7][7] = (short) FILL;
        frames.get(1)[7][7] = (short) FILL;
        frames.get(2)[7][7] = (short) FILL;
        frames.get(3)[7][7] = (short) FILL;
        EnhancedStack stack = EnhancedStack.build(frames, median(frames), null, null);
        assertEquals(5000, stack.pixels[3][3], 20);
        assertEquals(2100, stack.pixels[4][4], 20);
        assertTrue(Float.isNaN(stack.pixels[7][7]));
    }

    /** A hot pixel or a trail in one frame is clipped; the mean of many frames is less noisy than their median. */
    @Test public void outliersAreRemovedAndNoiseIsBelowTheMedians() throws Exception {
        List<short[][]> frames = new ArrayList<>();
        for (int i = 0; i < 15; i++) frames.add(noise(new Random(100 + i), 1000, 20));
        frames.get(4)[50][50] = (short) (20000 - 32768);
        for (int x = 20; x < 140; x++) frames.get(9)[80][x] = (short) (1400 - 32768);
        short[][] median = median(frames);
        EnhancedStack stack = EnhancedStack.build(frames, median, null, null);
        assertEquals(1000, stack.pixels[50][50], 25);
        for (int x = 20; x < 140; x++) assertTrue("trail at " + x, Math.abs(stack.pixels[80][x] - 1000) < 30);
        assertTrue(std(stack.pixels) < 0.9 * std(toFloat(median)));
    }

    /** Frame noise comes out right from three frames on, and a noisy frame weighs less than in a plain mean. */
    @Test public void unequalFrameNoiseIsMeasuredAndWeighted() throws Exception {
        double[] truth = {10, 40, 10, 10, 40};
        List<short[][]> frames = new ArrayList<>();
        for (int i = 0; i < truth.length; i++) frames.add(noise(new Random(7 + i), 1000, truth[i]));
        EnhancedStack stack = EnhancedStack.build(frames, median(frames), null, null);
        for (int i = 0; i < truth.length; i++) assertEquals("frame " + i, truth[i], stack.frameNoise[i], 0.1 * truth[i]);
        float[][] plain = new float[SIZE][SIZE];
        for (short[][] frame : frames) for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) plain[y][x] += (frame[y][x] + 32768) / 5f;
        assertTrue(std(stack.pixels) < 0.8 * std(plain));
        List<short[][]> three = frames.subList(0, 3);
        EnhancedStack small = EnhancedStack.build(three, median(three), null, null);
        for (int i = 0; i < 3; i++) assertEquals("of three, frame " + i, truth[i], small.frameNoise[i], 0.15 * truth[i]);
    }

    /** A star whose width changes from frame to frame keeps its flux: its pixels are not clipped as outliers. */
    @Test public void starsUnderChangingSeeingKeepTheirFlux() throws Exception {
        List<short[][]> frames = new ArrayList<>();
        double flux = 400_000;
        for (int i = 0; i < 12; i++) {
            short[][] frame = noise(new Random(300 + i), 1000, 15);
            double sigma = 1.2 + 0.08 * i;
            for (int y = 70; y <= 90; y++) for (int x = 70; x <= 90; x++) {
                double r2 = (x - 80) * (x - 80) + (y - 80) * (y - 80);
                frame[y][x] = (short) Math.min(32767, frame[y][x] + Math.round(flux / (2 * Math.PI * sigma * sigma) * Math.exp(-r2 / (2 * sigma * sigma))));
            }
            frames.add(frame);
        }
        EnhancedStack stack = EnhancedStack.build(frames, median(frames), null, null);
        double sum = 0;
        for (int y = 70; y <= 90; y++) for (int x = 70; x <= 90; x++) sum += stack.pixels[y][x] - 1000;
        assertEquals(flux, sum, 0.03 * flux);
    }

    /** Flat data gives the flat value, with the noise floor instead of an infinite weight. */
    @Test public void flatDataStaysFlat() throws Exception {
        List<short[][]> frames = new ArrayList<>();
        for (int i = 0; i < 5; i++) frames.add(constant(800));
        EnhancedStack stack = EnhancedStack.build(frames, median(frames), null, null);
        assertEquals(800, stack.pixels[10][10], 1e-3);
        assertEquals(EnhancedStack.MIN_NOISE, stack.frameNoise[0], 1e-9);
    }

    static short[][] constant(int value) {
        short[][] frame = new short[SIZE][SIZE];
        for (short[] row : frame) java.util.Arrays.fill(row, (short) (value - 32768));
        return frame;
    }

    static short[][] noise(Random random, double level, double sigma) {
        short[][] frame = new short[SIZE][SIZE];
        for (short[] row : frame) for (int x = 0; x < SIZE; x++) row[x] = (short) Math.round(level - 32768 + random.nextGaussian() * sigma);
        return frame;
    }

    /** The engine's convention: lower median over all frames, fill included. */
    static short[][] median(List<short[][]> frames) {
        short[][] out = new short[SIZE][SIZE];
        short[] values = new short[frames.size()];
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            for (int i = 0; i < frames.size(); i++) values[i] = frames.get(i)[y][x];
            java.util.Arrays.sort(values);
            out[y][x] = values[(values.length - 1) / 2];
        }
        return out;
    }

    private static float[][] toFloat(short[][] image) {
        float[][] out = new float[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) out[y][x] = image[y][x] + 32768;
        return out;
    }

    /** Standard deviation over the middle of the image, away from the injected structures. */
    private static double std(float[][] image) {
        double sum = 0, sq = 0;
        int n = 0;
        for (int y = 10; y < 45; y++) for (int x = 10; x < 150; x++) { sum += image[y][x]; sq += image[y][x] * image[y][x]; n++; }
        double mean = sum / n;
        return Math.sqrt(sq / n - mean * mean);
    }
}
