package eu.startales.spacepixels.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pins the contract of each angle helper; the separation variants agree to well below a milliarcsecond (SP-10). */
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

        assertEquals("-180 becomes 180", 180, SkyMath.wrapSignedDegrees(-180), 0);
        assertEquals(180, SkyMath.wrapSignedDegrees(540), 0);
        assertEquals(170, SkyMath.wrapSignedDegrees(-190), 1e-12);
    }

    @Test
    public void fieldOfViewIsClamped() {
        assertEquals(2, SkyMath.clampFieldOfViewDegrees(5, 0.1, 2), 0);
        assertEquals(0.1, SkyMath.clampFieldOfViewDegrees(0, 0.1, 2), 0);
        assertTrue(Double.isNaN(SkyMath.clampFieldOfViewDegrees(Double.NaN, 0.1, 2)));
    }

    @Test
    public void separationVariantsAgreeAcrossThePoleAndTheRaWrap() {
        double[][] pairs = {{10, 20, 10.5, 20.5}, {0, 89.9, 180, 89.9}, {359.9, 0, 0.1, 0}, {150, 70, 150.01, 70}, {0, -45, 90, 45}};
        for (double[] p : pairs) {
            double reference = SkyMath.separationDegreesAtan2(p[0], p[1], p[2], p[3]);
            assertEquals(reference, SkyMath.separationDegreesHaversine(p[0], p[1], p[2], p[3]), 1e-12);
            assertEquals(reference, SkyMath.separationDegreesHaversineUnclamped(p[0], p[1], p[2], p[3]), 1e-12);
            assertEquals(reference, SkyMath.separationDegreesCosineRule(p[0], p[1], p[2], p[3]), 1e-9);
            assertEquals(reference * 3600, SkyMath.separationArcsecHaversine(p[0], p[1], p[2], p[3]), 1e-8);
            assertEquals(reference * 3600, SkyMath.separationArcsecHaversineRaDeltaInDegrees(p[0], p[1], p[2], p[3]), 1e-8);
            assertEquals(reference * 3600, SkyMath.separationArcsecHaversineDeltasInDegrees(p[0], p[1], p[2], p[3]), 1e-8);
        }
        assertEquals(0.2, SkyMath.separationDegreesAtan2(359.9, 0, 0.1, 0), 1e-9);
        assertEquals(0.2, SkyMath.separationDegreesAtan2(0, 89.9, 180, 89.9), 1e-9);
    }

    @Test
    public void onlyTheCosineRuleRejectsNonFiniteInputAndLosesTinySeparations() {
        assertTrue(Double.isNaN(SkyMath.separationDegreesCosineRule(Double.POSITIVE_INFINITY, 0, 0, 0)));
        double oneMilliarcsec = 1 / 3.6e6;
        assertEquals(0.001, SkyMath.separationArcsecHaversine(10, 20, 10, 20 + oneMilliarcsec), 1e-9);
        assertEquals(0.001, SkyMath.separationDegreesAtan2(10, 20, 10, 20 + oneMilliarcsec) * 3600, 1e-9);
        double cosineRule = SkyMath.separationDegreesCosineRule(10, 20, 10, 20 + oneMilliarcsec) * 3600;
        assertTrue("the cosine rule resolves only about 0.003 arcsec: " + cosineRule, cosineRule >= 0 && cosineRule < 0.01);
    }
}
