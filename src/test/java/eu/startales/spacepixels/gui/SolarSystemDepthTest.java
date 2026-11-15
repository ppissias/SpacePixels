package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.mpc.LightCurveTestAccess;
import eu.startales.spacepixels.skycatalog.PhotometryStars;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.solarsystem.SolarSystemQuery;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The asteroid fetch goes as deep as the frames reach, never shallower than the setting. */
public class SolarSystemDepthTest {
    private static SolarSystemQuery query(double depth) {
        return new SolarSystemQuery(LightCurveTestAccess.wcs(), 400, 400, 1_000_000L, 2_000_000L, depth,
                new SolarSystemQuery.Observer("W84", null, "observatory W84"));
    }

    private static SkyCatalogue catalogue(double limit) {
        var catalogue = new SkyCatalogue();
        var star = new SkyCatalogue.Star();
        star.g = 12;
        catalogue.stars.add(star);
        catalogue.photometryCheck = new PhotometryStars.Check();
        catalogue.photometryCheck.limitingMagnitude = limit;
        return catalogue;
    }

    @Test
    public void fetchesAsDeepAsTheFrames() {
        var deep = SolarSystemController.depth(query(19), null, catalogue(23.8));
        assertEquals("one magnitude below G 23.8, at most 25", 25.0, deep.query().magnitudeLimit(), 1e-9);
        assertTrue(deep.step().detail, deep.step().detail.contains("G 23.8") && deep.step().detail.contains("V 19.0"));
        var amateur = SolarSystemController.depth(query(19), null, catalogue(16.6));
        assertEquals("the setting is deeper than G 16.6 + 1", 19.0, amateur.query().magnitudeLimit(), 1e-9);
        assertNull(amateur.step());
        var middle = SolarSystemController.depth(query(19), null, catalogue(18.7));
        assertEquals(20.0, middle.query().magnitudeLimit(), 1e-9);
        var none = query(19);
        assertSame(none, SolarSystemController.depth(none, null, null).query());
    }
}
