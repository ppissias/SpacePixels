package eu.startales.spacepixels.skycatalog;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SkyCatalogueStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void savesAndLoadsTheCatalogueOfASession() throws Exception {
        SkyCatalogue catalogue = new SkyCatalogue();
        catalogue.field = new SkyField();
        catalogue.field.centerRa = 98.856;
        catalogue.field.centerDec = 7.321;
        catalogue.field.cornerRa = new double[]{96.8, 100.9, 100.9, 96.8};
        catalogue.field.cornerDec = new double[]{6.0, 6.0, 8.7, 8.7};
        catalogue.starMagnitudeLimit = 15;
        catalogue.starSource = "Gaia DR3 via VizieR (CDS)";
        SkyCatalogue.Star star = new SkyCatalogue.Star();
        star.ra = 99.9199;
        star.dec = 5.8741;
        star.g = 14.9;
        star.pmra = -1.098;
        catalogue.stars.add(star);
        SkyCatalogue.DeepSkyObject cluster = new SkyCatalogue.DeepSkyObject();
        cluster.name = "NGC 2251";
        cluster.type = "OpC";
        catalogue.deepSky.add(cluster);
        catalogue.problems.add("Variable stars (AAVSO VSX): answered with HTTP 503.");
        catalogue.fetchSteps.add(FetchStep.of("AAVSO VSX", "Variable stars", FetchStep.Status.FAILED, "",
                "answered with HTTP 503.", SkyCatalogueClient.VIZIER_TAP, "SELECT Name FROM \"B/vsx/vsx\""));

        File session = folder.newFolder("session");
        SkyCatalogueStore.save(session, catalogue);
        assertTrue(SkyCatalogueStore.fileIn(session).isFile());
        assertArrayEquals(new String[]{SkyCatalogue.FILE_NAME}, session.list());

        SkyCatalogue loaded = SkyCatalogueStore.load(session);
        assertEquals(1, loaded.stars.size());
        assertEquals(-1.098, loaded.stars.get(0).pmra, 1e-12);
        assertNull(loaded.stars.get(0).pmdec);
        assertEquals("NGC 2251", loaded.deepSky.get(0).name);
        assertTrue(loaded.variables.isEmpty());
        assertEquals(1, loaded.problems.size());
        assertEquals(96.8, loaded.field.cornerRa[0], 1e-12);
        assertEquals("1 stars · 1 deep-sky · 0 variables", loaded.summary());
        assertEquals(1, loaded.fetchSteps.size());
        assertEquals(FetchStep.Status.FAILED, loaded.fetchSteps.get(0).status);
        assertEquals(SkyCatalogueClient.VIZIER_TAP, loaded.fetchSteps.get(0).url);
        assertEquals("SELECT Name FROM \"B/vsx/vsx\"", loaded.fetchSteps.get(0).query);
        assertNull(loaded.fetchSteps.get(0).liveUrl);
    }

    /** A file written before the step list existed loads its problem sentences and an empty list. */
    @Test
    public void anOlderFileHasSentencesAndNoSteps() throws Exception {
        File session = folder.newFolder("legacy");
        Files.writeString(SkyCatalogueStore.fileIn(session).toPath(),
                "{\"version\":1,\"field\":{\"centerRa\":98.856,\"centerDec\":7.321},"
                        + "\"problems\":[\"Variable stars (AAVSO VSX): answered with HTTP 503.\"]}",
                StandardCharsets.UTF_8);
        SkyCatalogue loaded = SkyCatalogueStore.load(session);
        assertTrue(loaded.fetchSteps.isEmpty());
        assertEquals(1, loaded.problems.size());
        List<FetchStep> rows = FetchStep.rowsFor(loaded.fetchSteps, loaded.problems);
        assertEquals(1, rows.size());
        assertEquals(FetchStep.Status.FAILED, rows.get(0).status);
        assertNull(rows.get(0).url);
        assertTrue(rows.get(0).service, rows.get(0).service.contains("AAVSO VSX"));
    }

    @Test
    public void ignoresAMissingDamagedOrNewerFile() throws Exception {
        File session = folder.newFolder("session");
        assertNull(SkyCatalogueStore.load(session));
        Files.write(SkyCatalogueStore.fileIn(session).toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        assertNull(SkyCatalogueStore.load(session));
        Files.write(SkyCatalogueStore.fileIn(session).toPath(),
                "{\"version\":99,\"field\":{\"centerRa\":1}}".getBytes(StandardCharsets.UTF_8));
        assertNull(SkyCatalogueStore.load(session));
    }
}
