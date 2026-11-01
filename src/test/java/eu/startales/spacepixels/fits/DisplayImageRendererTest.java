package eu.startales.spacepixels.fits;

import io.github.ppissias.jtransient.core.DataArea;
import org.junit.Test;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The automatic asinh stretch of floating-point images such as the enhanced stack. */
public class DisplayImageRendererTest {

    /** The sky median is drawn at the chosen level, two sky sigma below it is black and the brightest pixel white. */
    @Test public void theSkyLandsAtItsLevelAndTheRangeIsKept() {
        float[][] image = sky(new Random(3), 200, 1000, 20);
        image[100][100] = 60000;
        image[0][0] = Float.NaN;
        DisplayImageRenderer.AsinhLevels levels = DisplayImageRenderer.asinhLevels(image);
        assertEquals(1000, levels.black() + DisplayImageRenderer.ASINH_BLACK_SKY_SIGMA * 20, 2.0);
        assertEquals(60000, levels.white(), 0.0);
        assertEquals(DisplayImageRenderer.ASINH_SKY_LEVEL, levels.display(1000), 0.01);
        assertEquals(0, levels.display(levels.black()), 1e-12);
        assertEquals(1, levels.display(60000), 1e-12);
        double previous = -1;
        for (double value = 900; value <= 60000; value += 50) {
            assertTrue(levels.display(value) >= previous);
            previous = levels.display(value);
        }

        BufferedImage picture = DisplayImageRenderer.createAsinhDisplayImage(image);
        assertEquals(0, picture.getRaster().getSample(0, 0, 0));
        assertEquals(255, picture.getRaster().getSample(100, 100, 0));
        int[] values = picture.getRaster().getSamples(10, 10, 50, 50, 0, (int[]) null);
        java.util.Arrays.sort(values);
        assertEquals(DisplayImageRenderer.ASINH_SKY_LEVEL * 255, values[values.length / 2], 3);
    }

    /** Faint structure a few sky sigma above the sky is lifted far above the sky, unlike in a linear picture. */
    @Test public void faintStructureIsLifted() {
        float[][] image = sky(new Random(5), 100, 1000, 20);
        image[50][50] = 60000;
        DisplayImageRenderer.AsinhLevels levels = DisplayImageRenderer.asinhLevels(image);
        double linear = (1100 - levels.black()) / (levels.white() - levels.black());
        assertTrue(levels.display(1100) - levels.display(1000) > 0.1);
        assertTrue(levels.display(1100) > 50 * linear);
    }

    /** Flat data, an image without data and a bright sky give pictures, not failures. */
    @Test public void degenerateImagesStillDraw() {
        float[][] flat = new float[20][20];
        for (float[] row : flat) java.util.Arrays.fill(row, 800);
        assertEquals(20, DisplayImageRenderer.createAsinhDisplayImage(flat).getWidth());
        float[][] empty = new float[5][5];
        for (float[] row : empty) java.util.Arrays.fill(row, Float.NaN);
        assertEquals(0, DisplayImageRenderer.createAsinhDisplayImage(empty).getRaster().getSample(2, 2, 0));
        assertEquals(1e3, DisplayImageRenderer.softening(0.5, DisplayImageRenderer.ASINH_SKY_LEVEL), 0.0);
    }

    private static float[][] sky(Random random, int size, double level, double sigma) {
        float[][] image = new float[size][size];
        for (float[] row : image) for (int x = 0; x < size; x++) row[x] = (float) (level + random.nextGaussian() * sigma);
        return image;
    }

    /**
     * Ordinary 16-bit automatic stretch uses sky median and robust sky noise (1.4826 * MAD), ignoring saturated stars,
     * out-of-frame padding (-32768) and registration fill (<= DataArea.FILL_THRESHOLD), which stay black.
     */
    @Test public void robustLevelsIgnoreBrightOutliersAndRegistrationFill() {
        Random random = new Random(7);
        int size = 80;
        short[][] clean = new short[size][size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int adu = (int) Math.round(1000 + random.nextGaussian() * 20);
                clean[y][x] = (short) (adu - 32768);
            }
        }
        double[] cleanLevels = DisplayImageRenderer.displayLevels(clean, 0.5, 5.0);
        assertEquals(1000 - 0.5 * 20, cleanLevels[0], 3.0);

        short[][] withFillAndStar = new short[size][size];
        for (int y = 0; y < size; y++) {
            System.arraycopy(clean[y], 0, withFillAndStar[y], 0, size);
        }
        // Add a registration fill border (stored <= DataArea.FILL_THRESHOLD) and a saturated star core.
        for (int y = 0; y < 15; y++) {
            for (int x = 0; x < size; x++) {
                withFillAndStar[y][x] = (y % 2 == 0) ? Short.MIN_VALUE : DataArea.FILL_THRESHOLD;
            }
        }
        for (int y = 40; y < 44; y++) {
            for (int x = 40; x < 44; x++) {
                withFillAndStar[y][x] = Short.MAX_VALUE;
            }
        }

        double[] outlierLevels = DisplayImageRenderer.displayLevels(withFillAndStar, 0.5, 5.0);
        assertEquals("black point stays anchored to the sky median", cleanLevels[0], outlierLevels[0], 3.0);
        assertEquals("white point stays anchored to sky + 5 * sky noise rather than the saturated star",
                1000 + 5.0 * 20, outlierLevels[1], 12.0);

        BufferedImage rendered = DisplayImageRenderer.createDisplayImage(withFillAndStar, 0.5, 5.0);
        assertEquals("out-of-frame padding stays black", 0, rendered.getRaster().getSample(0, 0, 0));
        assertEquals("registration fill stays black", 0, rendered.getRaster().getSample(0, 1, 0));
        assertEquals("saturated star core reaches white", 255, rendered.getRaster().getSample(41, 41, 0));
    }

    /** Shared animation levels take the median sky and median noise across frames while preserving target peaks. */
    @Test public void sharedDisplayLevelsAcrossFramesKeepTargetContrastStable() {
        short[][] emptyFrame = new short[30][30];
        short[][] targetFrame = new short[30][30];
        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 30; x++) {
                short skyVal = (short) (1000 + ((x + y) % 5) * 4 - 32768);
                emptyFrame[y][x] = skyVal;
                targetFrame[y][x] = skyVal;
            }
        }
        targetFrame[15][15] = (short) (5000 - 32768);

        double[] shared = DisplayImageRenderer.sharedDisplayLevels(
                List.of(emptyFrame, targetFrame, emptyFrame), 0.5, 5.0);
        BufferedImage emptyRendered = DisplayImageRenderer.createDisplayImageAtLevels(emptyFrame, shared[0], shared[1]);
        BufferedImage targetRendered = DisplayImageRenderer.createDisplayImageAtLevels(targetFrame, shared[0], shared[1]);

        assertEquals("same sky pixel renders identically across frames",
                emptyRendered.getRaster().getSample(5, 5, 0),
                targetRendered.getRaster().getSample(5, 5, 0));
        assertEquals("target peak reaches white", 255, targetRendered.getRaster().getSample(15, 15, 0));
    }
}
