package eu.startales.spacepixels.util.skycatalog;

import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Reads answers as the services give them (samples from real queries of the klangwolde-2 and M81 fields). */
public class SkyCatalogueClientTest {

    @Test
    public void csvTableReadsQuotedPaddedAndEmptyFields() {
        CsvTable table = CsvTable.parse("Name,max,Period\r\n"
                + "\"V0959 Mon                     \",9.86,0.29575\r\n"
                + "\"Gaia DR3 3131123006665005568, a \"\"name\"\"\",14.85,\n");
        assertEquals(2, table.size());
        assertEquals("V0959 Mon", table.text(0, "Name"));
        assertEquals(0.29575, table.number(0, "Period"), 1e-12);
        assertEquals("Gaia DR3 3131123006665005568, a \"name\"", table.text(1, "Name"));
        assertNull(table.number(1, "Period"));
        assertNull(table.text(1, "missing"));
    }

    @Test
    public void readsStarsFromVizierAndFromTheEsaArchive() throws IOException {
        List<SkyCatalogue.Star> vizier = SkyCatalogueClient.parseStars(CsvTable.parse("RA_ICRS,DE_ICRS,pmRA,pmDE,Gmag,BP-RP\n"
                        + "99.91990635139,5.87415290699,-1.098,-2.233,14.915211,1.00119\n"
                        + "99.9260233005,5.87738069072,,,14.373812,\n"),
                "RA_ICRS", "DE_ICRS", "pmRA", "pmDE", "Gmag", "BP-RP");
        assertEquals(2, vizier.size());
        assertEquals(99.91990635139, vizier.get(0).ra, 1e-12);
        assertEquals(-2.233, vizier.get(0).pmdec, 1e-12);
        assertEquals(1.00119, vizier.get(0).bpRp, 1e-12);
        assertNull(vizier.get(1).pmra);
        assertNull(vizier.get(1).bpRp);

        List<SkyCatalogue.Star> esa = SkyCatalogueClient.parseStars(CsvTable.parse("ra,dec,pmra,pmdec,phot_g_mean_mag,bp_rp\n"
                        + "99.00394206612629,6.084825828813385,0.7527768707576667,0.43755791337341027,11.210193,3.1964054\n"),
                "ra", "dec", "pmra", "pmdec", "phot_g_mean_mag", "bp_rp");
        assertEquals(11.210193, esa.get(0).g, 1e-9);
    }

    @Test(expected = IOException.class)
    public void anAnswerWithOtherColumnsIsAnError() throws IOException {
        SkyCatalogueClient.parseStars(CsvTable.parse("x,y\n1,2\n"), "ra", "dec", "pmra", "pmdec", "g", "bp_rp");
    }

    @Test
    public void namesDeepSkyObjectsByTheirBestKnownCatalogue() {
        assertArrayEquals(new String[]{"M 81", "0"}, SkyCatalogueClient.catalogueName("M  81"));
        assertArrayEquals(new String[]{"NGC 3031", "1"}, SkyCatalogueClient.catalogueName("NGC  3031"));
        assertArrayEquals(new String[]{"Sh 2-275", "3"}, SkyCatalogueClient.catalogueName("SH  2-275"));
        assertArrayEquals(new String[]{"Collinder 106", "4"}, SkyCatalogueClient.catalogueName("Cl Collinder 106"));
        assertArrayEquals(new String[]{"LBN 204.31+00.62", "9"}, SkyCatalogueClient.catalogueName("LBN 204.31+00.62"));
        // A star of a cluster, not the cluster.
        assertNull(SkyCatalogueClient.catalogueName("NGC 2251 103"));
        assertNull(SkyCatalogueClient.catalogueName("2MASX J09553318+6903549"));
    }

    @Test
    public void mergesTheNamesOfAnObjectAndAddsLargeGalaxies() {
        CsvTable named = CsvTable.parse("main_id,ra,dec,otype,galdim_majaxis,galdim_minaxis,galdim_angle,id\n"
                + "\"M  81\",148.88821939854,69.06529514038,\"Sy2\",21.38,10.23,157,\"NGC  3031\"\n"
                + "\"M  81\",148.88821939854,69.06529514038,\"Sy2\",21.38,10.23,157,\"M  81\"\n"
                + "\"M  81\",148.88821939854,69.06529514038,\"Sy2\",21.38,10.23,157,\"UGC  5318\"\n"
                + "\"NGC  2251\",98.6750,8.3667,\"OpC\",,,,\"NGC  2251\"\n"
                + "\"NGC  2251  103\",98.70,8.37,\"*\",,,,\"NGC  2251  103\"\n");
        CsvTable galaxies = CsvTable.parse("main_id,ra,dec,otype,galdim_majaxis,galdim_minaxis,galdim_angle\n"
                + "\"M  81\",148.88821939854,69.06529514038,\"Sy2\",21.38,10.23,157\n"
                + "\"LEDA  28731\",149.1,69.2,\"G\",1.907,0.9,40\n");
        List<SkyCatalogue.DeepSkyObject> objects = SkyCatalogueClient.parseDeepSky(named, galaxies);
        assertEquals(3, objects.size());
        assertEquals("M 81", objects.get(0).name);
        assertEquals(21.38, objects.get(0).majorArcmin, 1e-9);
        assertEquals(157, objects.get(0).angleDeg, 1e-9);
        assertEquals("NGC 2251", objects.get(1).name);
        assertNull(objects.get(1).majorArcmin);
        assertEquals("LEDA 28731", objects.get(2).name);
    }

    @Test
    public void readsVariablesWithRangesAndAmplitudes() {
        List<SkyCatalogue.VariableStar> variables = SkyCatalogueClient.parseVariables(CsvTable.parse(
                "OID,Name,V,RAJ2000,DEJ2000,Type,max,n_max,f_min,min,Period\n"
                        + "126720,\"V0959 Mon                     \",0,99.9108,5.89806,\"NB                            \",9.86,\"V         \",,18.4,0.29575\n"
                        + "3172136,\"Gaia DR3 3131878160697846144  \",0,99.7949,5.9651,\"DSCT|GDOR|SXPHE               \",13.29,\"G         \",(,0.03,\n"));
        assertEquals(2, variables.size());
        SkyCatalogue.VariableStar nova = variables.get(0);
        assertEquals("V0959 Mon", nova.name);
        assertEquals("NB", nova.type);
        assertEquals("V", nova.band);
        assertFalse(nova.minIsAmplitude);
        assertEquals(126720, nova.oid);
        assertTrue(variables.get(1).minIsAmplitude);
        assertNull(variables.get(1).periodDays);
    }

    @Test
    public void takesTheServiceErrorMessageFromItsAnswer() {
        String body = "<?xml version=\"1.0\"?><VOTABLE><RESOURCE type=\"results\">"
                + "<INFO name=\"QUERY_STATUS\" value=\"ERROR\">Incorrect ADQL query: 5 unresolved identifiers</INFO></RESOURCE></VOTABLE>";
        assertEquals("answered with an error: Incorrect ADQL query: 5 unresolved identifiers", SkyCatalogueClient.errorText(400, body));
        assertEquals("answered with HTTP 503.", SkyCatalogueClient.errorText(503, "Service Unavailable"));
    }

    @Test
    public void queriesUseTheFieldOutlineAndTheDepth() {
        SkyField field = SkyField.of(WcsCoordinateTransformer.fromHeader(header()), 9544, 6361);
        String stars = SkyCatalogueClient.vizierStarQuery(field, 15);
        assertTrue(stars, stars.contains("FROM \"I/355/gaiadr3\""));
        assertTrue(stars, stars.contains("POLYGON('ICRS', "));
        assertTrue(stars, stars.endsWith("AND Gmag <= 15.00"));
        assertTrue(SkyCatalogueClient.esaStarQuery(field, 16.5).endsWith("AND phot_g_mean_mag <= 16.50"));
        assertTrue(SkyCatalogueClient.variableQuery(field, 15).endsWith("AND V < 2 AND (max IS NULL OR max <= 16.00)"));
        String named = SkyCatalogueClient.namedDeepSkyQuery(field);
        assertTrue(named, named.contains("i.id LIKE 'NGC %'") && named.contains("i.id LIKE 'SH %'"));
        assertTrue(SkyCatalogueClient.galaxyQuery(field).contains("otype='G..'"));
    }

    @Test
    public void fieldCoversTheFrameWithAMargin() {
        SkyField field = SkyField.of(WcsCoordinateTransformer.fromHeader(header()), 9544, 6361);
        assertEquals(98.856, field.centerRa, 1e-3);
        assertEquals(7.321, field.centerDec, 1e-3);
        // 9544 px of 1.536″, plus 3 % on each side.
        assertEquals(9544 * 1.536 / 3600 * 1.06, field.widthDeg, 0.01);
        assertEquals(6361 * 1.536 / 3600 * 1.06, field.heightDeg, 0.01);
        SkyField moved = SkyField.of(WcsCoordinateTransformer.fromHeader(header()), 9544, 6361);
        moved.centerRa += 0.2;
        assertTrue(field.isSameFieldAs(moved));
        moved.centerRa += 3;
        assertFalse(field.isSameFieldAs(moved));
    }

    private static Map<String, String> header() {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("CTYPE1", "RA---TAN");
        header.put("CTYPE2", "DEC--TAN");
        header.put("CRPIX1", "4772.5");
        header.put("CRPIX2", "3181.0");
        header.put("CRVAL1", "98.856");
        header.put("CRVAL2", "7.321");
        header.put("CD1_1", Double.toString(-1.536 / 3600));
        header.put("CD1_2", "0");
        header.put("CD2_1", "0");
        header.put("CD2_2", Double.toString(1.536 / 3600));
        return header;
    }
}
