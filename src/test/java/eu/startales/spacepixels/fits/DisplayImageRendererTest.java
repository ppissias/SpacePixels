package eu.startales.spacepixels.fits;

import org.junit.Test;

import java.awt.image.BufferedImage;
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
}
