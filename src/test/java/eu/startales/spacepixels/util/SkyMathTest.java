package eu.startales.spacepixels.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pins the contract of the angle and angular-separation helpers in {@link SkyMath}. */
public class SkyMathTest {

    @Test
    public void anglesWrapIntoTheirRanges() {
        assertEquals(350, SkyMath.normalizeDegrees(-10), 1e-12);
        assertEquals(0, SkyMath.normalizeDegrees(360), 0);
        assertEquals(5, SkyMath.normalizeDegrees(725), 1e-12);
        assertTrue(Double.isNaN(SkyMath.normalizeDegrees(Double.NaN)));

        assertEquals(-170, SkyMath.normalizeSignedDegrees(190), 1e-12);
        assertEquals(170, SkyMath.normalizeSignedDegrees(-190), 1e-12);
        assertEquals("both ends are kept", 180, SkyMath.normalizeSignedDegrees(180), 0);
        assertEquals(-180, SkyMath.normalizeSignedDegrees(-180), 0);
        assertEquals(180, SkyMath.normalizeSignedDegrees(540), 0);
        assertTrue(Double.isNaN(SkyMath.normalizeSignedDegrees(Double.POSITIVE_INFINITY)));
        assertTrue(Double.isNaN(SkyMath.normalizeSignedDegrees(Double.NEGATIVE_INFINITY)));
        assertTrue(Double.isNaN(SkyMath.normalizeSignedDegrees(Double.NaN)));
    }

    @Test
    public void fieldOfViewIsClamped() {
        assertEquals(2, SkyMath.clampFieldOfViewDegrees(5, 0.1, 2), 0);
        assertEquals(0.1, SkyMath.clampFieldOfViewDegrees(0, 0.1, 2), 0);
        assertTrue(Double.isNaN(SkyMath.clampFieldOfViewDegrees(Double.NaN, 0.1, 2)));
    }

    @Test
    public void separationHandlesPoleRaWrapAntipodesAndTinyAngles() {
        assertEquals(0.2, SkyMath.separationDegrees(359.9, 0, 0.1, 0), 1e-9);
        assertEquals(0.2, SkyMath.separationDegrees(0, 89.9, 180, 89.9), 1e-9);
        assertEquals(180.0, SkyMath.separationDegrees(0, 45, 180, -45), 1e-9);
        assertEquals(SkyMath.separationDegrees(10, 20, 10.5, 20.5) * 3600.0,
                SkyMath.separationArcsec(10, 20, 10.5, 20.5), 0);

        double oneMilliarcsec = 1 / 3.6e6;
        assertEquals(0.001, SkyMath.separationArcsec(10, 20, 10, 20 + oneMilliarcsec), 1e-9);

        assertTrue(Double.isNaN(SkyMath.separationDegrees(Double.POSITIVE_INFINITY, 0, 0, 0)));
        assertTrue(Double.isNaN(SkyMath.separationArcsec(0, Double.NaN, 0, 0)));
    }
}
