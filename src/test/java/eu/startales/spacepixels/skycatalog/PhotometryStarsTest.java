package eu.startales.spacepixels.skycatalog;

import eu.startales.spacepixels.mpc.LightCurveTestAccess;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The check after a catalogue fetch: enough usable comparison stars, or a warning saying why not. */
public class PhotometryStarsTest {
    /** Twelve clear stars are enough; the same field with its stars saturated gets a warning that names the depth. */
    @Test
    public void countsUsableStarsAndWarns() {
        var wcs = LightCurveTestAccess.wcs();
        double[][] clear = new double[12][];
        double[][] bright = new double[12][];
        for (int k = 0; k < 12; k++) {
            clear[k] = new double[]{40 + (k % 4) * 100, 60 + (k / 4) * 130, 2000 + 300 * k};
            bright[k] = new double[]{clear[k][0], clear[k][1], 400_000};
        }
        var catalogue = new SkyCatalogue();
        catalogue.starMagnitudeLimit = 15;
        catalogue.stars = LightCurveTestAccess.catalogue(wcs, clear);
        var check = PhotometryStars.check(LightCurveTestAccess.frame(clear, 1, 3), "frame_000010.fit", wcs, catalogue);
        assertEquals(12, check.onFrame);
        assertEquals(12, check.usable);
        assertTrue(check.enough());
        assertNull(check.warning());
        assertEquals(3.0, check.fwhm, 0.3);
        assertTrue("a limiting magnitude from the clear stars", check.limitingMagnitude > 15 && check.limitingMagnitude < 25);
        assertEquals(Math.min(25, Math.ceil((check.limitingMagnitude + 1) * 2) / 2), check.solarSystemDepth(25), 1e-9);
        assertEquals(Math.min(18, check.solarSystemDepth(25)), check.solarSystemDepth(18), 1e-9);

        var saturated = PhotometryStars.check(LightCurveTestAccess.frame(bright, 1, 3), "frame_000010.fit", wcs, catalogue);
        assertEquals(12, saturated.saturated);
        assertEquals(0, saturated.usable);
        assertFalse(saturated.enough());
        assertTrue("no profile to measure", Double.isNaN(saturated.fwhm));
        assertTrue("no unsaturated star, no limit", Double.isNaN(saturated.limitingMagnitude) && Double.isNaN(saturated.solarSystemDepth(25)));
        String warning = saturated.warning();
        assertTrue(warning, warning.startsWith("Only 0 of the 12 catalogue stars") && warning.contains("12 saturated")
                && warning.contains("Raise Star Depth (now G 15.0, up to 18)"));
    }

    /** The depth samples' stars join the candidates, each star once. */
    @Test
    public void addsDepthSampleStarsOnce() {
        var catalogue = new SkyCatalogue();
        var a = star("1", 10, 20);
        var b = star("2", 10.01, 20);
        catalogue.stars.add(a);
        var sample = new SkyCatalogue.DepthSample();
        sample.stars.add(star("1", 10, 20));
        sample.stars.add(b);
        catalogue.depthSamples.add(sample);
        assertEquals(2, PhotometryStars.candidates(catalogue).size());
        assertEquals(0, PhotometryStars.candidates(null).size());
    }

    private static SkyCatalogue.Star star(String id, double ra, double dec) {
        var star = new SkyCatalogue.Star();
        star.sourceId = id;
        star.ra = ra;
        star.dec = dec;
        star.g = 16;
        return star;
    }
}
