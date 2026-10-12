package eu.startales.spacepixels.util.reporting;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StarShapeTest {

    private static final double FWHM_PER_SIGMA = 2.0 * Math.sqrt(2.0 * Math.log(2.0));

    /** A frame with sky 1000 and one pixel-integrated elliptical Gaussian star, plus an optional tail along +x. */
    private static short[][] frame(int size, double x0, double y0, double sigmaMajor, double sigmaMinor, double angle,
                                   double flux, double tailFlux, double noise, long seed) {
        Random random = new Random(seed);
        short[][] data = new short[size][size];
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        int sub = 8;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double sum = 0;
                for (int sy = 0; sy < sub; sy++) {
                    for (int sx = 0; sx < sub; sx++) {
                        double dx = x - 0.5 + (sx + 0.5) / sub - x0;
                        double dy = y - 0.5 + (sy + 0.5) / sub - y0;
                        double u = dx * cos + dy * sin;
                        double v = -dx * sin + dy * cos;
                        sum += Math.exp(-0.5 * (u * u / (sigmaMajor * sigmaMajor) + v * v / (sigmaMinor * sigmaMinor)));
                        if (tailFlux > 0) {
                            double tx = dx - 2.5;
                            sum += tailFlux / flux * Math.exp(-0.5 * (tx * tx / 9 + dy * dy / 1.5));
                        }
                    }
                }
                double norm = flux / (2 * Math.PI * sigmaMajor * sigmaMinor);
                double value = 1000 + norm * sum / (sub * sub) + noise * random.nextGaussian();
                data[y][x] = (short) (Math.round(value) - 32768);
            }
        }
        return data;
    }

    @Test
    public void measuresRoundStarSize() {
        double sigma = 3.0 / FWHM_PER_SIGMA;
        short[][] data = frame(41, 20.3, 19.6, sigma, sigma, 0, 50_000, 0, 0, 1);
        StarShape star = StarShape.measure(data, 20, 20, 15, 3, 2.2);
        assertNotNull(star);
        assertEquals(3.0, star.fwhm, 0.05);
        assertEquals(20.3, star.x, 0.02);
        assertEquals(19.6, star.y, 0.02);
        assertEquals(1.0, star.axisRatio(), 0.01);
        assertEquals(1000, star.background, 1);
        assertEquals(50_000, star.flux, 500);
        assertEquals(0, star.offsetX, 0.02);
    }

    @Test
    public void measuresElongationAndDirection() {
        double angle = Math.toRadians(30);
        short[][] data = frame(41, 20, 20, 2.0, 1.25, angle, 80_000, 0, 0, 2);
        StarShape star = StarShape.measure(data, 20, 20, 15, 3, 2.2);
        assertNotNull(star);
        assertEquals(1.6, star.axisRatio(), 0.03);
        assertEquals(30, Math.toDegrees(0.5 * Math.atan2(star.e2, star.e1)), 1.0);
        assertEquals(FWHM_PER_SIGMA * Math.sqrt(2.0 * 1.25), star.fwhm, 0.08);
    }

    @Test
    public void tailMovesTheLightCentreOutwards() {
        double sigma = 3.0 / FWHM_PER_SIGMA;
        short[][] data = frame(41, 20, 20, sigma, sigma, 0, 60_000, 12_000, 3, 3);
        StarShape star = StarShape.measure(data, 20, 20, 15, 3, 2.2);
        assertNotNull(star);
        assertTrue("offset " + star.offsetX, star.offsetX > 0.3);
        assertEquals(0, star.offsetY, 0.1);
    }

    @Test
    public void staysCloseWithNoise() {
        double sigma = 4.0 / FWHM_PER_SIGMA;
        short[][] data = frame(41, 20, 20, sigma, sigma, 0, 40_000, 0, 10, 4);
        StarShape star = StarShape.measure(data, 20, 20, 15, 4, 2.2);
        assertNotNull(star);
        assertEquals(4.0, star.fwhm, 0.15);
        assertTrue(star.snr > 100);
    }

    @Test
    public void refusesCutOutsOverTheEdgeOrPadding() {
        short[][] data = frame(41, 20, 20, 1.5, 1.5, 0, 50_000, 0, 0, 5);
        assertNull(StarShape.measure(data, 5, 20, 15, 3, 2.2));
        data[20][34] = (short) -32768; // an empty pixel inside the cut-out
        assertNull(StarShape.measure(data, 20, 20, 15, 3, 2.2));
    }

    @Test
    public void flagsAFlatTop() {
        double sigma = 3.0 / FWHM_PER_SIGMA;
        short[][] data = frame(41, 20, 20, sigma, sigma, 0, 2_000_000, 0, 0, 6);
        for (short[] row : data) {
            for (int x = 0; x < row.length; x++) {
                row[x] = (short) Math.min(row[x], 65535 - 32768);
            }
        }
        StarShape star = StarShape.measure(data, 20, 20, 15, 3, 2.2);
        assertNotNull(star);
        assertTrue(star.flatTopPixels >= 3);
    }
}
