package eu.startales.spacepixels.report;

import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionCatalogueTest {

    @Test
    public void findsTheVariablesNearAPositionClosestFirst() throws Exception {
        SkyCatalogue catalogue = new SkyCatalogue();
        catalogue.variables.add(variable("far", 150.0 + 8.0 / 3600, 20.0));
        catalogue.variables.add(variable("near", 150.0 + 1.0 / 3600, 20.0));
        catalogue.variables.add(variable("outside", 150.0, 20.0 + 30.0 / 3600));
        SessionCatalogue session = session(catalogue);
        List<SessionCatalogue.VariableMatch> matches = session.variablesNear(150.0, 20.0, 10.0);
        assertEquals(2, matches.size());
        assertEquals("near", matches.get(0).star.name);
        // 1 s of RA at Dec 20° is 0.94″.
        assertEquals(0.94, matches.get(0).separationArcsec, 0.01);
        assertEquals("far", matches.get(1).star.name);
    }

    @Test
    public void findsTheGaiaStarOfACandidate() throws Exception {
        SkyCatalogue catalogue = new SkyCatalogue();
        SkyCatalogue.Star star = new SkyCatalogue.Star();
        star.ra = 150.0;
        star.dec = 20.0 + 1.0 / 3600;
        star.g = 13.2;
        catalogue.stars.add(star);
        SessionCatalogue session = session(catalogue);
        assertEquals(13.2, session.gaiaStarNear(150.0, 20.0, 2.0).g, 1e-9);
        assertNull(session.gaiaStarNear(150.0, 20.0, 0.5));
    }

    @Test
    public void catalogueNameFallsBackFromProperNameToGaiaDesignationAndEscapesHtml() throws Exception {
        SkyCatalogue catalogue = new SkyCatalogue();
        SkyCatalogue.Star gaia = new SkyCatalogue.Star();
        gaia.ra = 150; gaia.dec = 20; gaia.sourceId = "3131123006665005568";
        catalogue.stars.add(gaia);
        var session = session(catalogue);
        var target = new PhotometryReportSectionWriter.SkyTarget(150, 20, 5);
        assertEquals("Gaia DR3 3131123006665005568", session.starIdentityNear(150, 20, 5).name());
        assertTrue(PhotometryReportSectionWriter.catalogueStarHtml(session, target).contains("Catalogue star:"));
        SkyCatalogue.NamedStar named = new SkyCatalogue.NamedStar();
        named.ra = 150; named.dec = 20; named.name = "Example <star> & name";
        catalogue.namedStars.add(named);
        String html = PhotometryReportSectionWriter.catalogueStarHtml(session, target);
        assertTrue(html.contains("Example &lt;star&gt; &amp; name"));
        assertTrue(html.contains("SIMBAD"));
        assertNull(session.starIdentityNear(151, 20, 5));
        catalogue.namedStars.clear(); gaia.sourceId = null;
        assertNull(session.starIdentityNear(150, 20, 5));
        assertEquals("", PhotometryReportSectionWriter.catalogueStarHtml(session, target));
    }

    @Test
    public void gaiaDesignationUsesTheSameProperMotionAsTheMagnitudeMatch() throws Exception {
        SkyCatalogue catalogue = new SkyCatalogue();
        SkyCatalogue.Star gaia = new SkyCatalogue.Star();
        gaia.ra = 150; gaia.dec = 0; gaia.pmra = 1000.0; gaia.pmdec = 0.0; gaia.sourceId = "1234567890123456789";
        catalogue.stars.add(gaia);
        Constructor<SessionCatalogue> constructor = SessionCatalogue.class.getDeclaredConstructor(SkyCatalogue.class, long.class);
        constructor.setAccessible(true);
        SessionCatalogue session = constructor.newInstance(catalogue, SkyCatalogue.GAIA_EPOCH_MILLIS + (long) (10 * 365.25 * 86400000));
        assertNull(session.starIdentityNear(150, 0, 1));
        var match = session.starIdentityNear(150 + 10.0 / 3600, 0, 1);
        assertEquals("Gaia DR3 1234567890123456789", match.name());
        assertEquals(0, match.separationArcsec(), 1e-6);
    }

    @Test
    public void aDifferentNearbyNamedStarDoesNotReplaceTheNearestGaiaIdentification() throws Exception {
        SkyCatalogue catalogue = new SkyCatalogue();
        SkyCatalogue.Star target = new SkyCatalogue.Star();
        target.ra = 150; target.dec = 20; target.sourceId = "1234567890123456789";
        SkyCatalogue.Star neighbour = new SkyCatalogue.Star();
        neighbour.ra = 150; neighbour.dec = 20 + 3.0 / 3600; neighbour.sourceId = "1234567890123456790";
        catalogue.stars.addAll(List.of(target, neighbour));
        SkyCatalogue.NamedStar name = new SkyCatalogue.NamedStar();
        name.ra = neighbour.ra; name.dec = neighbour.dec; name.name = "Neighbour";
        catalogue.namedStars.add(name);
        assertEquals("Gaia DR3 " + target.sourceId, session(catalogue).starIdentityNear(150, 20, 5).name());
    }

    @Test
    public void explainsTheCatalogueEntry() {
        assertEquals("EW (W UMa-type eclipsing binary)", SessionCatalogue.describeVsxType("EW"));
        assertEquals("DSCT|GDOR|SXPHE (delta Scuti variable)", SessionCatalogue.describeVsxType("DSCT|GDOR|SXPHE"));
        assertEquals("RRAB: (RR Lyrae variable)", SessionCatalogue.describeVsxType("RRAB:"));
        assertEquals("XYZ", SessionCatalogue.describeVsxType("XYZ"));
        assertEquals("6.5 h", SessionCatalogue.formatPeriod(0.27));
        assertEquals("2.50 d", SessionCatalogue.formatPeriod(2.5));

        SkyCatalogue.VariableStar range = variable("a", 0, 0);
        range.max = 14.6;
        range.min = 15.3;
        range.band = "R1";
        assertEquals("Catalogue range: 14.60–15.30 R1", PhotometryReportSectionWriter.vsxRange(range));
        range.minIsAmplitude = true;
        range.min = 0.71;
        assertEquals("Catalogue amplitude: 0.71 mag (listed magnitude 14.60 R1)", PhotometryReportSectionWriter.vsxRange(range));

        assertEquals("This session spans 5.0 h, 77.2% of the catalogued period.", PhotometryReportSectionWriter.periodCoverage(0.27, 300));
        assertEquals("This session spans 5.0 h, 3.0 times the catalogued period.", PhotometryReportSectionWriter.periodCoverage(0.07, 300));
        assertEquals("This session spans 5.0 h, 0.7% of the catalogued period.", PhotometryReportSectionWriter.periodCoverage(30, 300));
        // A short window can still contain a large eclipse: coverage must not imply a small amplitude.
        assertEquals("This session spans 75 min, 2.4% of the catalogued period.", PhotometryReportSectionWriter.periodCoverage(2.178775, 74.7216));
        assertNull(PhotometryReportSectionWriter.periodCoverage(0.27, Double.NaN));
    }

    private static SkyCatalogue.VariableStar variable(String name, double ra, double dec) {
        SkyCatalogue.VariableStar variable = new SkyCatalogue.VariableStar();
        variable.name = name;
        variable.ra = ra;
        variable.dec = dec;
        return variable;
    }

    private static SessionCatalogue session(SkyCatalogue catalogue) throws Exception {
        Constructor<SessionCatalogue> constructor = SessionCatalogue.class.getDeclaredConstructor(SkyCatalogue.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(catalogue, 0L);
    }
}
