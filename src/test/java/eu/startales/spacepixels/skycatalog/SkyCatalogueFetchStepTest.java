package eu.startales.spacepixels.skycatalog;

import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * What a catalogue fetch records when a service fails, answers with nothing, or is cancelled. The TAP answers are
 * recorded; nothing is asked of the network.
 */
public class SkyCatalogueFetchStepTest {

    private static final String NGC = "main_id,ra,dec,otype,galdim_majaxis,galdim_minaxis,galdim_angle,id\n"
            + "\"NGC  2236\",98.856,7.321,\"OpC\",8.4,8.4,0,\"NGC  2236\"\n";
    private static final String STAR = "Source,RA_ICRS,DE_ICRS,pmRA,pmDE,Gmag,BP-RP\n"
            + "3131123006665005568,98.856,7.321,0,0,14.9,1\n";
    private static final String ESA_STAR = "source_id,ra,dec,pmra,pmdec,phot_g_mean_mag,bp_rp\n"
            + "3131123006665005568,98.856,7.321,0,0,14.9,1\n";

    /** One recorded TAP answer. Tests throw to stand in for HTTP 503, a timeout or cancellation. */
    private interface Script {
        CsvTable answer(String service, String adql) throws IOException, InterruptedException;
    }

    /** A VSX 503 leaves the SIMBAD and Gaia rows succeeded, including counts of zero. */
    @Test
    public void aVsx503KeepsTheOtherAnswers() throws Exception {
        List<List<FetchStep>> published = new ArrayList<>();
        SkyCatalogue catalogue = fetch(new SkyCatalogueClient.Progress() {
            /** Remembers each list the fetch publishes, on the fetching thread. */
            @Override public void stage(int number, int total, String text) { }
            /** @param steps the immutable steps finished so far */
            @Override public void steps(List<FetchStep> steps) { published.add(steps); }
        }, (service, adql) -> {
            if (adql.contains("B/vsx")) {
                throw new IOException("answered with HTTP 503.");
            }
            if (adql.contains("Gmag <= 15.00")) {
                return CsvTable.parse("");
            }
            return CsvTable.parse("");
        });
        assertEquals(0, catalogue.deepSky.size());
        assertTrue(catalogue.stars.isEmpty());
        assertEquals(1, catalogue.problems.size());
        assertTrue(catalogue.problems.get(0), catalogue.problems.get(0).startsWith("Variable stars (AAVSO VSX): answered with HTTP 503."));
        assertEquals("0 rows", step(catalogue, "SIMBAD", "Deep-sky catalogue names").outcome);
        assertEquals(FetchStep.Status.SUCCEEDED, step(catalogue, "SIMBAD", "Large galaxies").status);
        assertEquals(FetchStep.Status.SUCCEEDED, step(catalogue, "SIMBAD", "Common names").status);
        assertEquals(FetchStep.Status.SUCCEEDED, step(catalogue, "SIMBAD", "Named bright stars").status);
        FetchStep vsx = step(catalogue, "AAVSO VSX via VizieR", "Variable stars");
        assertEquals(FetchStep.Status.FAILED, vsx.status);
        assertEquals(SkyCatalogueClient.VIZIER_TAP, vsx.url);
        assertTrue(vsx.query, vsx.query.contains("B/vsx"));
        assertEquals(FetchStep.Status.FAILED, step(catalogue, "AAVSO VSX via Harvard", "Variable stars").status);
        assertEquals(FetchStep.Status.FAILED, step(catalogue, "AAVSO VSX via IDIA", "Variable stars").status);
        assertEquals("0 stars", step(catalogue, "Gaia via VizieR", "Stars").outcome);
        assertEquals(FetchStep.Status.NOT_ASKED, step(catalogue, "Gaia via VizieR", "Field depth").status);
        assertTrue(step(catalogue, "Gaia via VizieR", "Field depth").detail.contains("no stars"));
        assertTrue("the 503 is published before Gaia is asked", published.stream().anyMatch(list ->
                list.stream().anyMatch(row -> "AAVSO VSX via VizieR".equals(row.service) && row.status == FetchStep.Status.FAILED)
                        && list.stream().noneMatch(row -> "Stars".equals(row.question))));
    }

    /** A named-star failure keeps the deep-sky objects the earlier SIMBAD queries already parsed. */
    @Test
    public void aNamedStarFailureKeepsDeepSkyAlreadyParsed() throws Exception {
        SkyCatalogue catalogue = fetch((service, adql) -> {
            if (adql.contains("i.id LIKE 'M %'")) {
                return CsvTable.parse(NGC);
            }
            if (adql.contains("f.filter")) {
                throw new IOException("answered with HTTP 503.");
            }
            if (adql.contains("otype='G..'")) {
                throw new IOException("answered with HTTP 404.");
            }
            return CsvTable.parse("");
        });
        assertEquals(1, catalogue.deepSky.size());
        assertEquals("NGC 2236", catalogue.deepSky.get(0).name);
        assertTrue(catalogue.namedStars.isEmpty());
        String problems = String.join("\n", catalogue.problems);
        assertTrue(problems, problems.contains("Large galaxies (SIMBAD): answered with HTTP 404."));
        assertTrue(problems, problems.contains("Named bright stars (SIMBAD): did not answer earlier."));
        assertFalse(problems, problems.contains("Deep-sky objects (SIMBAD)"));
        assertEquals("1 row", step(catalogue, "SIMBAD", "Deep-sky catalogue names").outcome);
        assertEquals(FetchStep.Status.FAILED, step(catalogue, "SIMBAD", "Large galaxies").status);
        assertEquals(FetchStep.Status.NOT_ASKED, step(catalogue, "SIMBAD", "Named bright stars").status);
    }

    /** VizieR stays a failed row when the ESA archive then returns the stars. */
    @Test
    public void aVizierFailureIsKeptWhenTheEsaArchiveSucceeds() throws Exception {
        SkyCatalogue catalogue = fetch((service, adql) -> {
            if (SkyCatalogueClient.ESA_GAIA_TAP.equals(service)) {
                return CsvTable.parse(ESA_STAR);
            }
            if (adql.contains("Gmag <= 20.50")) {
                return CsvTable.parse("");
            }
            if (adql.contains("I/355/gaiadr3")) {
                throw new IOException("answered with HTTP 503.");
            }
            return CsvTable.parse("");
        });
        assertEquals(1, catalogue.stars.size());
        assertEquals("Gaia DR3 via the ESA Gaia archive", catalogue.starSource);
        assertTrue(catalogue.problems.isEmpty());
        FetchStep vizier = step(catalogue, "Gaia via VizieR", "Stars");
        FetchStep esa = step(catalogue, "Gaia via the ESA archive", "Stars");
        assertEquals(FetchStep.Status.FAILED, vizier.status);
        assertEquals(FetchStep.Status.SUCCEEDED, esa.status);
        assertEquals("1 star", esa.outcome);
        assertEquals(SkyCatalogueClient.ESA_GAIA_TAP, esa.url);
        assertTrue(esa.query, esa.query.contains("phot_g_mean_mag"));
        assertTrue(catalogue.fetchSteps.indexOf(vizier) < catalogue.fetchSteps.indexOf(esa));
        assertTrue(catalogue.fetchSteps.stream().noneMatch(row -> "Gaia via ARI Heidelberg".equals(row.service)));
        assertEquals(FetchStep.Status.SUCCEEDED, step(catalogue, "Gaia via VizieR", "Field depth").status);
        assertEquals("4 of 4 patches", step(catalogue, "Gaia via VizieR", "Field depth").outcome);
    }

    /** Every Gaia service times out: each is a failed row, and the field depth is not asked. */
    @Test
    public void everyGaiaServiceFailingLeavesTheFieldDepthUnasked() throws Exception {
        SkyCatalogue catalogue = fetch((service, adql) -> {
            if (SkyCatalogueClient.ESA_GAIA_TAP.equals(service) || SkyCatalogueClient.ARI_GAIA_TAP.equals(service)
                    || adql.contains("I/355/gaiadr3")) {
                throw new IOException("did not answer within 150 s.");
            }
            return CsvTable.parse("");
        });
        assertTrue(catalogue.stars.isEmpty());
        String problems = String.join("\n", catalogue.problems);
        assertTrue(problems, problems.contains("Stars (Gaia):"));
        assertEquals(FetchStep.Status.FAILED, step(catalogue, "Gaia via VizieR", "Stars").status);
        assertEquals(FetchStep.Status.FAILED, step(catalogue, "Gaia via the ESA archive", "Stars").status);
        assertEquals(FetchStep.Status.FAILED, step(catalogue, "Gaia via ARI Heidelberg", "Stars").status);
        assertEquals(FetchStep.Status.NOT_ASKED, step(catalogue, "Gaia via VizieR", "Field depth").status);
        assertTrue(step(catalogue, "Gaia via VizieR", "Field depth").detail.contains("no stars"));
    }

    /** The third faint patch fails; the three that answered stay, under one row. */
    @Test
    public void aFailedDepthPatchKeepsThePatchesAlreadyFetched() throws Exception {
        int[] depth = {0};
        SkyCatalogue catalogue = fetch((service, adql) -> {
            if (adql.contains("phot_g_mean_mag <= 20.50")) {
                throw new IOException("answered with HTTP 503.");
            }
            if (adql.contains("Gmag <= 20.50")) {
                if (++depth[0] == 3) {
                    throw new IOException("answered with HTTP 503.");
                }
                return CsvTable.parse("");
            }
            if (adql.contains("I/355/gaiadr3")) {
                return CsvTable.parse(STAR);
            }
            return CsvTable.parse("");
        });
        assertEquals(1, catalogue.stars.size());
        assertEquals(3, catalogue.depthSamples.size());
        FetchStep patches = step(catalogue, "Gaia via VizieR", "Field depth");
        assertEquals(FetchStep.Status.FAILED, patches.status);
        assertEquals("3 of 4 patches", patches.outcome);
        assertTrue(patches.detail, patches.detail.contains("HTTP 503"));
        assertEquals(FetchStep.Status.FAILED, step(catalogue, "Gaia via the ESA archive", "Field depth").status);
        assertEquals("0 of 1 patches", step(catalogue, "Gaia via ARI Heidelberg", "Field depth").outcome);
        assertEquals(1, catalogue.problems.stream().filter(line -> line.startsWith("Faint stars for the field depth (Gaia):")).count());
        assertTrue(catalogue.problems.toString(), catalogue.problems.stream().anyMatch(line -> line.contains("3 of 4 patches answered")));
    }

    /** Reaching the star limit keeps the stars and records the existing advice as a failed row. */
    @Test
    public void theStarCapStaysAFailedRowAndKeepsTheStars() throws Exception {
        SkyCatalogueClient client = client((service, adql) -> {
            if (adql.contains("I/355/gaiadr3")) {
                return CsvTable.parse(STAR);
            }
            return CsvTable.parse("");
        }, 1);
        SkyCatalogue catalogue = client.fetch(field(), 15, (number, total, text) -> { });
        assertEquals(1, catalogue.stars.size());
        FetchStep limit = step(catalogue, "Gaia", "Star depth limit");
        assertEquals(FetchStep.Status.FAILED, limit.status);
        assertEquals("Only part of the stars were fetched", limit.outcome);
        assertTrue(limit.detail, limit.detail.contains("more than 1"));
        assertTrue(catalogue.problems.toString(), catalogue.problems.stream().anyMatch(line -> line.contains("more than 1")));
        assertEquals(FetchStep.Status.SUCCEEDED, step(catalogue, "Gaia via VizieR", "Field depth").status);
    }

    /** Cancelling during VSX keeps the SIMBAD rows and marks the rest not asked. */
    @Test
    public void cancellingMarksTheQuestionsNotYetSent() throws Exception {
        List<FetchStep> shown = new ArrayList<>();
        SkyCatalogueClient client = client((service, adql) -> {
            if (adql.contains("B/vsx")) {
                throw new InterruptedException();
            }
            if (adql.contains("i.id LIKE 'M %'")) {
                return CsvTable.parse(NGC);
            }
            return CsvTable.parse("");
        }, SkyCatalogueClient.MAX_STARS);
        try {
            client.fetch(field(), 15, new SkyCatalogueClient.Progress() {
                /** @param number ignored; cancellation is observed through {@link #steps} */
                @Override public void stage(int number, int total, String text) { }
                /** @param steps the list published before the fetch throws */
                @Override public void steps(List<FetchStep> steps) {
                    shown.clear();
                    shown.addAll(steps);
                }
            });
            fail("the fetch was cancelled");
        } catch (InterruptedException expected) {
            assertEquals(FetchStep.Status.SUCCEEDED, step(shown, "SIMBAD", "Deep-sky catalogue names").status);
            assertEquals("1 row", step(shown, "SIMBAD", "Deep-sky catalogue names").outcome);
            assertEquals(FetchStep.Status.NOT_ASKED, step(shown, "AAVSO VSX via VizieR", "Variable stars").status);
            assertTrue(step(shown, "AAVSO VSX via VizieR", "Variable stars").detail.contains("cancelled"));
            assertEquals("Not sent.", step(shown, "Gaia via VizieR", "Stars").detail);
            assertEquals(FetchStep.Status.NOT_ASKED, step(shown, "Gaia via VizieR", "Field depth").status);
            assertTrue(shown.stream().noneMatch(row -> "Gaia via the ESA archive".equals(row.service)));
            assertTrue(shown.stream().noneMatch(row -> "Gaia via ARI Heidelberg".equals(row.service)));
        }
    }

    /** Strasbourg fails the galaxy query and Harvard returns the galaxy. IDIA is not asked. */
    @Test
    public void harvardKeepsTheGalaxyWhenStrasbourgFails() throws Exception {
        SkyCatalogue catalogue = fetch((service, adql) -> {
            if (adql.contains("otype='G..'") && SkyCatalogueClient.SIMBAD_TAP.equals(service)) {
                throw new IOException("answered with HTTP 503.");
            }
            if (adql.contains("otype='G..'") && SkyCatalogueClient.SIMBAD_HARVARD.equals(service)) {
                return CsvTable.parse("main_id,ra,dec,otype,galdim_majaxis,galdim_minaxis,galdim_angle\n"
                        + "\"NGC  2236\",98.856,7.321,\"OpC\",8.4,8.4,0\n");
            }
            if (SkyCatalogueClient.SIMBAD_IDIA.equals(service)) {
                throw new IOException("IDIA was asked");
            }
            return CsvTable.parse("");
        });
        assertEquals(1, catalogue.deepSky.size());
        assertEquals(FetchStep.Status.FAILED, step(catalogue, "SIMBAD", "Large galaxies").status);
        FetchStep harvard = step(catalogue, "SIMBAD via Harvard", "Large galaxies");
        assertEquals(FetchStep.Status.SUCCEEDED, harvard.status);
        assertEquals(SkyCatalogueClient.SIMBAD_HARVARD, harvard.url);
        assertTrue(catalogue.fetchSteps.stream().noneMatch(row -> "SIMBAD via IDIA".equals(row.service)
                && "Large galaxies".equals(row.question)));
    }

    /** VizieR fails every faint patch and the ESA archive then returns them. Heidelberg is not asked. */
    @Test
    public void theEsaArchiveKeepsTheDepthPatchesWhenVizieRFails() throws Exception {
        SkyCatalogue catalogue = fetch((service, adql) -> {
            if (adql.contains("Gmag <= 20.50")) {
                throw new IOException("did not answer within 150 s.");
            }
            if (SkyCatalogueClient.ESA_GAIA_TAP.equals(service) && adql.contains("phot_g_mean_mag <= 20.50")) {
                return CsvTable.parse("ra,dec,pmra,pmdec,phot_g_mean_mag,bp_rp\n98.8,7.3,1,1,19.5,0.4\n");
            }
            if (SkyCatalogueClient.ARI_GAIA_TAP.equals(service) && adql.contains("phot_g_mean_mag <= 20.50")) {
                throw new IOException("Heidelberg was asked for the field depth");
            }
            if (adql.contains("I/355/gaiadr3") && adql.contains("Gmag <= 15.00")) {
                return CsvTable.parse(STAR);
            }
            return CsvTable.parse("");
        });
        assertEquals(4, catalogue.depthSamples.size());
        assertEquals(1, catalogue.depthSamples.get(0).stars.size());
        assertEquals(FetchStep.Status.FAILED, step(catalogue, "Gaia via VizieR", "Field depth").status);
        assertEquals("0 of 4 patches", step(catalogue, "Gaia via VizieR", "Field depth").outcome);
        assertEquals(FetchStep.Status.SUCCEEDED, step(catalogue, "Gaia via the ESA archive", "Field depth").status);
        assertEquals("4 of 4 patches", step(catalogue, "Gaia via the ESA archive", "Field depth").outcome);
        assertTrue(catalogue.fetchSteps.stream().noneMatch(row -> "Gaia via ARI Heidelberg".equals(row.service)
                && "Field depth".equals(row.question)));
    }

    /** VizieR's VSX query fails and the Harvard copy returns the one star that lies in the frame. IDIA is not asked. */
    @Test
    public void harvardKeepsTheVariableInsideTheFrame() throws Exception {
        String page = "<?xml version=\"1.0\"?><VOTABLE><RESOURCE><TABLE>"
                + "<FIELD name=\"OID\"/><FIELD name=\"Name\"/><FIELD name=\"V\"/><FIELD name=\"RAJ2000\"/>"
                + "<FIELD name=\"DEJ2000\"/><FIELD name=\"Type\"/><FIELD name=\"max\"/><FIELD name=\"Period\"/>"
                + "<DATA><TABLEDATA>"
                + "<TR><TD>1</TD><TD>Inside Star</TD><TD>0</TD><TD>98.856</TD><TD>7.321</TD><TD>EA</TD><TD>12.5</TD><TD>1.2</TD></TR>"
                + "<TR><TD>2</TD><TD>Far Away</TD><TD>0</TD><TD>10</TD><TD>80</TD><TD>EB</TD><TD>11</TD><TD>2</TD></TR>"
                + "<TR><TD>3</TD><TD>Constant</TD><TD>2</TD><TD>98.856</TD><TD>7.321</TD><TD>CST</TD><TD>10</TD><TD></TD></TR>"
                + "<TR><TD>4</TD><TD>Too Faint</TD><TD>0</TD><TD>98.856</TD><TD>7.330</TD><TD>EA</TD><TD>20</TD><TD>3</TD></TR>"
                + "</TABLEDATA></DATA></TABLE></RESOURCE></VOTABLE>";
        List<String> asked = new ArrayList<>();
        SkyCatalogueClient client = new SkyCatalogueClient((service, adql, maxRecords, timeout) -> {
            if (adql.contains("B/vsx")) {
                throw new IOException("did not answer within 60 s.");
            }
            return CsvTable.parse("");
        }, (url, timeout) -> {
            asked.add(url);
            if (url.startsWith(SkyCatalogueClient.VSX_HARVARD)) {
                return page;
            }
            throw new IOException("IDIA was asked");
        }, SkyCatalogueClient.MAX_STARS);
        SkyCatalogue catalogue = client.fetch(field(), 15, (number, total, text) -> { });
        assertEquals(1, catalogue.variables.size());
        assertEquals("Inside Star", catalogue.variables.get(0).name);
        assertEquals("EA", catalogue.variables.get(0).type);
        assertEquals(1.2, catalogue.variables.get(0).periodDays, 0);
        assertEquals(1, asked.size());
        assertTrue(asked.get(0), asked.get(0).startsWith(SkyCatalogueClient.VSX_HARVARD));
        assertEquals(FetchStep.Status.FAILED, step(catalogue, "AAVSO VSX via VizieR", "Variable stars").status);
        FetchStep harvard = step(catalogue, "AAVSO VSX via Harvard", "Variable stars");
        assertEquals(FetchStep.Status.SUCCEEDED, harvard.status);
        assertEquals("1 row", harvard.outcome);
        assertEquals("AAVSO VSX via Harvard", catalogue.variableSource);
        assertEquals(asked.get(0), harvard.url);
        assertNull(harvard.query);
        assertTrue(catalogue.fetchSteps.stream().noneMatch(row -> "AAVSO VSX via IDIA".equals(row.service)));
    }

    /** Harvard fails and IDIA then returns the star. The Harvard failure stays. */
    @Test
    public void idiaKeepsTheVariableWhenHarvardFails() throws Exception {
        String page = "<?xml version=\"1.0\"?><VOTABLE><RESOURCE><TABLE>"
                + "<FIELD name=\"Name\"/><FIELD name=\"V\"/><FIELD name=\"RAJ2000\"/><FIELD name=\"DEJ2000\"/><FIELD name=\"Type\"/>"
                + "<DATA><TABLEDATA><TR><TD>From IDIA</TD><TD>1</TD><TD>98.856</TD><TD>7.321</TD><TD>EW</TD></TR>"
                + "</TABLEDATA></DATA></TABLE></RESOURCE></VOTABLE>";
        SkyCatalogueClient client = new SkyCatalogueClient((service, adql, maxRecords, timeout) -> {
            if (adql.contains("B/vsx")) {
                throw new IOException("answered with HTTP 503.");
            }
            return CsvTable.parse("");
        }, (url, timeout) -> {
            if (url.startsWith(SkyCatalogueClient.VSX_HARVARD)) {
                throw new IOException("answered with HTTP 503.");
            }
            return page;
        }, SkyCatalogueClient.MAX_STARS);
        SkyCatalogue catalogue = client.fetch(field(), 15, (number, total, text) -> { });
        assertEquals(1, catalogue.variables.size());
        assertEquals("From IDIA", catalogue.variables.get(0).name);
        assertEquals(FetchStep.Status.FAILED, step(catalogue, "AAVSO VSX via Harvard", "Variable stars").status);
        assertEquals(FetchStep.Status.SUCCEEDED, step(catalogue, "AAVSO VSX via IDIA", "Variable stars").status);
        assertTrue(step(catalogue, "AAVSO VSX via IDIA", "Variable stars").url.startsWith(SkyCatalogueClient.VSX_IDIA));
    }

    /** VizieR and the ESA archive fail, and ARI Heidelberg then returns the stars. Both failures stay. */
    @Test
    public void heidelbergKeepsTheStarsWhenTheEarlierServicesFail() throws Exception {
        SkyCatalogue catalogue = fetch((service, adql) -> {
            if (SkyCatalogueClient.ARI_GAIA_TAP.equals(service)) {
                return CsvTable.parse(ESA_STAR);
            }
            if (adql.contains("Gmag <= 20.50")) {
                return CsvTable.parse("");
            }
            if (adql.contains("I/355/gaiadr3") || SkyCatalogueClient.ESA_GAIA_TAP.equals(service)) {
                throw new IOException("answered with HTTP 503.");
            }
            return CsvTable.parse("");
        });
        assertEquals(1, catalogue.stars.size());
        assertEquals("Gaia DR3 via ARI Heidelberg", catalogue.starSource);
        String problems = String.join("\n", catalogue.problems);
        assertFalse(problems, problems.contains("Stars (Gaia"));
        FetchStep heidelberg = step(catalogue, "Gaia via ARI Heidelberg", "Stars");
        assertEquals(FetchStep.Status.SUCCEEDED, heidelberg.status);
        assertEquals("1 star", heidelberg.outcome);
        assertEquals(SkyCatalogueClient.ARI_GAIA_TAP, heidelberg.url);
        assertTrue(heidelberg.query, heidelberg.query.contains("gaiadr3.gaia_source"));
        assertTrue(catalogue.fetchSteps.indexOf(step(catalogue, "Gaia via the ESA archive", "Stars"))
                < catalogue.fetchSteps.indexOf(heidelberg));
    }

    /** The plate-solution fit asks only for the stars and still names every service when all three fail. */
    @Test
    public void fetchStarsStillReportsEveryFailure() throws Exception {
        SkyCatalogueClient client = client((service, adql) -> {
            throw new IOException(SkyCatalogueClient.ESA_GAIA_TAP.equals(service) ? "did not answer within 150 s." : "answered with HTTP 503.");
        }, SkyCatalogueClient.MAX_STARS);
        try {
            client.fetchStars(field(), 15);
            fail("every service failed");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("VizieR")
                    && expected.getMessage().contains("ESA archive")
                    && expected.getMessage().contains("ARI Heidelberg"));
        }
    }

    /** A saved JPL address is the request that was sent, including the observing site. */
    @Test
    public void aSavedAddressKeepsTheRequest() {
        String sent = "https://ssd-api.jpl.nasa.gov/sb_ident.api?lat=37.900000&lon=23.700000&alt=0.1200&vmag-lim=19.0"
                + "&SITE_COORD=23.700000%2C37.900000%2C0.1200";
        FetchStep step = FetchStep.of("JPL SB-Ident", "Field search", FetchStep.Status.SUCCEEDED, "0 objects", "", sent, null);
        assertEquals(sent, step.url);
        assertEquals(sent, step.openUrl());
        assertEquals("", step.detail);
        List<FetchStep> legacy = FetchStep.rowsFor(List.of(), List.of("Variable stars (AAVSO VSX): answered with HTTP 503."));
        assertEquals(FetchStep.Status.FAILED, legacy.get(0).status);
        assertNull(legacy.get(0).url);
        assertEquals("Failed", legacy.get(0).resultWord());
        assertEquals("Not asked", FetchStep.of("Gaia via VizieR", "Field depth", FetchStep.Status.NOT_ASKED, "", "", null, null).resultWord());
    }

    /** A cone-search page that says OVERFLOW keeps the rows and says the answer was cut off. */
    @Test
    public void anOverflowConeSearchStaysPartial() throws Exception {
        String page = "<?xml version=\"1.0\"?><VOTABLE><RESOURCE>"
                + "<INFO name=\"QUERY_STATUS\" value=\"OVERFLOW\"/>"
                + "<TABLE><FIELD name=\"Name\"/><FIELD name=\"V\"/><FIELD name=\"RAJ2000\"/><FIELD name=\"DEJ2000\"/>"
                + "<FIELD name=\"max\"/><DATA><TABLEDATA>"
                + "<TR><TD>Kept</TD><TD>0</TD><TD>98.856</TD><TD>7.321</TD><TD>12</TD></TR>"
                + "</TABLEDATA></DATA></TABLE></RESOURCE></VOTABLE>";
        SkyCatalogueClient client = new SkyCatalogueClient((service, adql, maxRecords, timeout) -> {
            if (adql.contains("B/vsx")) {
                throw new IOException("answered with HTTP 503.");
            }
            return CsvTable.parse("");
        }, (url, timeout) -> page, SkyCatalogueClient.MAX_STARS);
        SkyCatalogue catalogue = client.fetch(field(), 15, (number, total, text) -> { });
        assertEquals(1, catalogue.variables.size());
        assertEquals("Kept", catalogue.variables.get(0).name);
        FetchStep harvard = step(catalogue, "AAVSO VSX via Harvard", "Variable stars");
        assertEquals(FetchStep.Status.FAILED, harvard.status);
        assertTrue(harvard.outcome, harvard.outcome.contains("(truncated)"));
        assertTrue(catalogue.problems.toString(), catalogue.problems.stream().anyMatch(line -> line.contains("cut off")));
        assertTrue(catalogue.fetchSteps.stream().noneMatch(row -> "AAVSO VSX via IDIA".equals(row.service)));
    }

    /** Strasbourg timing out on the first SIMBAD question is not asked again for the later ones. */
    @Test
    public void aCopyThatTimedOutIsNotAskedAgain() throws Exception {
        int[] strasbourg = {0};
        SkyCatalogue catalogue = fetch((service, adql) -> {
            if (SkyCatalogueClient.SIMBAD_TAP.equals(service)) {
                strasbourg[0]++;
                throw new IOException("did not answer within 60 s.");
            }
            return CsvTable.parse("");
        });
        assertEquals(1, strasbourg[0]);
        assertEquals(FetchStep.Status.FAILED, step(catalogue, "SIMBAD", "Deep-sky catalogue names").status);
        assertEquals(FetchStep.Status.NOT_ASKED, step(catalogue, "SIMBAD", "Large galaxies").status);
        assertTrue(step(catalogue, "SIMBAD", "Large galaxies").detail.contains("did not answer earlier"));
        assertEquals(FetchStep.Status.NOT_ASKED, step(catalogue, "SIMBAD", "Named bright stars").status);
        assertEquals(FetchStep.Status.SUCCEEDED, step(catalogue, "SIMBAD via Harvard", "Large galaxies").status);
    }

    /** A full queue is not a reason to skip the copy. HTTP 503 is asked again. */
    @Test
    public void aFullQueueIsAskedAgain() throws Exception {
        int[] strasbourg = {0};
        fetch((service, adql) -> {
            if (SkyCatalogueClient.SIMBAD_TAP.equals(service)) {
                strasbourg[0]++;
                throw new IOException("answered with HTTP 503.");
            }
            return CsvTable.parse("");
        });
        assertEquals(4, strasbourg[0]);
    }

    /** Fetches the standard test field to magnitude 15. */
    private static SkyCatalogue fetch(Script script) throws InterruptedException {
        return fetch((number, total, text) -> { }, script);
    }

    /** Fetches the standard test field, publishing steps through {@code progress}. */
    private static SkyCatalogue fetch(SkyCatalogueClient.Progress progress, Script script) throws InterruptedException {
        return client(script, SkyCatalogueClient.MAX_STARS).fetch(field(), 15, progress);
    }

    /** A client whose TAP queries are answered by {@code script}, with this star limit. */
    private static SkyCatalogueClient client(Script script, int starCap) {
        return new SkyCatalogueClient((service, adql, maxRecords, timeout) -> script.answer(service, adql), starCap);
    }

    /** The step for this service and question, or a failure naming the rows that were recorded. */
    private static FetchStep step(SkyCatalogue catalogue, String service, String question) {
        return step(catalogue.fetchSteps, service, question);
    }

    /** The step for this service and question. */
    private static FetchStep step(List<FetchStep> steps, String service, String question) {
        return steps.stream()
                .filter(row -> service.equals(row.service) && question.equals(row.question))
                .findFirst()
                .orElseThrow(() -> new AssertionError(steps.stream().map(row -> row.service + " / " + row.question).toList()));
    }

    /** A solved field large enough for four depth patches. */
    private static SkyField field() {
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
        return SkyField.of(WcsCoordinateTransformer.fromHeader(header), 9544, 6361);
    }
}
