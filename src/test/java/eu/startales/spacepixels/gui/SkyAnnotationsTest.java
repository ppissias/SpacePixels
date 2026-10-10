package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import org.junit.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SkyAnnotationsTest {

    /** 1.5″ per pixel, north up and east left, centre at pixel (999.5, 799.5) of a 2000 × 1600 frame. */
    private static final double SCALE_DEG = 1.5 / 3600;
    private static final WcsCoordinateTransformer TRANSFORMER = WcsCoordinateTransformer.fromHeader(header());

    @Test
    public void placesObjectsOnTheFrameAndLeavesOutTheRest() {
        SkyCatalogue catalogue = new SkyCatalogue();
        catalogue.stars.add(star(150.0, 20.0, 12.3, null, null));
        catalogue.stars.add(star(152.0, 20.0, 9.0, null, null));
        SkyCatalogue.VariableStar variable = new SkyCatalogue.VariableStar();
        variable.name = "RR Test";
        variable.ra = 150.0;
        variable.dec = 20.0 + 100 * SCALE_DEG;
        catalogue.variables.add(variable);

        List<SkyMark> marks = SkyAnnotations.project(catalogue, TRANSFORMER, 2000, 1600, 0);
        assertEquals(2, marks.size());
        SkyMark variableMark = marks.get(0);
        assertEquals(SkyMark.Kind.VARIABLE, variableMark.kind);
        assertEquals(999.5, variableMark.x, 1e-6);
        assertEquals(799.5 + 100, variableMark.y, 1e-3);
        SkyMark starMark = marks.get(1);
        assertEquals(SkyMark.Kind.STAR, starMark.kind);
        assertEquals(999.5, starMark.x, 1e-6);
        assertEquals(799.5, starMark.y, 1e-6);
        assertEquals("12.3", starMark.label);
    }

    @Test
    public void movesGaiaStarsToTheDateOfTheFrame() {
        SkyCatalogue catalogue = new SkyCatalogue();
        // 1″ per year north and 1″ per year east (on the sky).
        catalogue.stars.add(star(150.0, 20.0, 10, 1000.0, 1000.0));
        long tenYearsLater = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli();
        SkyMark mark = SkyAnnotations.project(catalogue, TRANSFORMER, 2000, 1600, tenYearsLater).get(0);
        double years = (tenYearsLater - SkyCatalogue.GAIA_EPOCH_MILLIS) / (365.25 * 86_400_000.0);
        // East is towards smaller x, north towards larger y, 1.5″ per pixel.
        assertEquals(999.5 - years / 1.5, mark.x, 0.01);
        assertEquals(799.5 + years / 1.5, mark.y, 0.01);
        // An unknown date leaves the stars at the Gaia epoch.
        mark = SkyAnnotations.project(catalogue, TRANSFORMER, 2000, 1600, 0).get(0);
        assertEquals(999.5, mark.x, 1e-6);
    }

    @Test
    public void drawsDeepSkyObjectsWithTheirSizeAndOrientation() {
        SkyCatalogue catalogue = new SkyCatalogue();
        SkyCatalogue.DeepSkyObject galaxy = new SkyCatalogue.DeepSkyObject();
        galaxy.name = "M 81";
        galaxy.type = "Sy2";
        galaxy.ra = 150.0;
        galaxy.dec = 20.0;
        galaxy.majorArcmin = 10.0;
        galaxy.minorArcmin = 5.0;
        galaxy.angleDeg = 0.0;
        catalogue.deepSky.add(galaxy);
        SkyMark mark = SkyAnnotations.project(catalogue, TRANSFORMER, 2000, 1600, 0).get(0);
        // 5′ half axis at 1.5″ per pixel is 200 px; position angle 0 points north, which is +y here.
        assertEquals(200, mark.semiMajor, 0.5);
        assertEquals(100, mark.semiMinor, 0.5);
        assertEquals(Math.PI / 2, mark.angle, 1e-3);
        assertEquals("M 81 · Seyfert galaxy · 10 × 5′", mark.description);
    }

    @Test
    public void describesVariablesAndStars() {
        SkyCatalogue.VariableStar variable = new SkyCatalogue.VariableStar();
        variable.name = "V0959 Mon";
        variable.type = "NB";
        variable.max = 9.86;
        variable.min = 18.4;
        variable.band = "V";
        variable.periodDays = 0.29575;
        assertEquals("V0959 Mon · NB · 9.86–18.40 V · period 0.296 d", SkyAnnotations.variableDescription(variable));
        variable.min = 0.03;
        variable.minIsAmplitude = true;
        variable.periodDays = null;
        assertTrue(SkyAnnotations.variableDescription(variable).endsWith("9.86 V, amplitude 0.03"));
        assertEquals("Gaia G 14.2 · BP−RP 0.90", SkyAnnotations.starDescription(star(0, 0, 14.2, null, null, 0.9)));
    }

    private static SkyCatalogue.Star star(double ra, double dec, double g, Double pmra, Double pmdec) {
        return star(ra, dec, g, pmra, pmdec, null);
    }

    private static SkyCatalogue.Star star(double ra, double dec, double g, Double pmra, Double pmdec, Double bpRp) {
        SkyCatalogue.Star star = new SkyCatalogue.Star();
        star.ra = ra;
        star.dec = dec;
        star.g = g;
        star.pmra = pmra;
        star.pmdec = pmdec;
        star.bpRp = bpRp;
        return star;
    }

    private static Map<String, String> header() {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("CTYPE1", "RA---TAN");
        header.put("CTYPE2", "DEC--TAN");
        header.put("CRPIX1", "1000.5");
        header.put("CRPIX2", "800.5");
        header.put("CRVAL1", "150.0");
        header.put("CRVAL2", "20.0");
        header.put("CD1_1", Double.toString(-SCALE_DEG));
        header.put("CD1_2", "0");
        header.put("CD2_1", "0");
        header.put("CD2_2", Double.toString(SCALE_DEG));
        return header;
    }
}
