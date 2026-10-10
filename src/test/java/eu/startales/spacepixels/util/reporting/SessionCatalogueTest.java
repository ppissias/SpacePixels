package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
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
        assertEquals("range 14.60–15.30 R1", PhotometryReportSectionWriter.vsxRange(range));
        range.minIsAmplitude = true;
        range.min = 0.71;
        assertEquals("14.60 R1, amplitude 0.71", PhotometryReportSectionWriter.vsxRange(range));

        assertEquals("This session covers 5.0 h, about 77 % of a cycle.", PhotometryReportSectionWriter.periodCoverage(0.27, 300));
        assertEquals("This session covers 5.0 h, about 3 cycles.", PhotometryReportSectionWriter.periodCoverage(0.07, 300));
        assertTrue(PhotometryReportSectionWriter.periodCoverage(30, 300).startsWith("This session covers 5.0 h, less than a tenth"));
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
