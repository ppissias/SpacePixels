package eu.startales.spacepixels.solarsystem;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Tests shared JPL Small-Body Database lookup URL construction for asteroids, comets and inferred designations. */
public class JplSbdbLookupTest {

    private static final String PREFIX = "https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr=";

    @Test
    public void buildsUrlsForNumberedProvisionalAndCometDesignations() {
        assertEquals(PREFIX + "194", JplSbdbLookup.informationUrl("194 Prokne (A879 FA)", false));
        assertEquals(PREFIX + "649341", JplSbdbLookup.informationUrl("649341 (2011 DK30)"));
        assertEquals(PREFIX + "2010%20DR82", JplSbdbLookup.informationUrl("(2010 DR82)"));
        assertEquals(PREFIX + "2010%20DR82", JplSbdbLookup.informationUrl("2010 DR82"));
        assertEquals(PREFIX + "C%2F2018%20Y1", JplSbdbLookup.informationUrl("C/2018 Y1 (Iwamoto)"));
        assertEquals(PREFIX + "73P-B", JplSbdbLookup.informationUrl("73P-B/Schwassmann-Wachmann 3"));
        assertNull(JplSbdbLookup.informationUrl(null));
        assertNull(JplSbdbLookup.informationUrl("   "));
    }

    @Test
    public void detectsCometDesignationPrefixes() {
        assertTrue(JplSbdbLookup.looksLikeComet("C/2023 A3 (Tsuchinshan-ATLAS)"));
        assertTrue(JplSbdbLookup.looksLikeComet("12P/Pons-Brooks"));
        assertTrue(JplSbdbLookup.looksLikeComet("73P-B/Schwassmann-Wachmann 3"));
        assertTrue(JplSbdbLookup.looksLikeComet("3I/ATLAS"));
        assertFalse(JplSbdbLookup.looksLikeComet("194 Prokne (A879 FA)"));
        assertFalse(JplSbdbLookup.looksLikeComet("2026 AB1"));
        assertFalse(JplSbdbLookup.looksLikeComet(null));
    }
}
