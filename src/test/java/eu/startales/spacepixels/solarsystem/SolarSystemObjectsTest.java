package eu.startales.spacepixels.solarsystem;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.skycatalog.FetchStep;
import eu.startales.spacepixels.skycatalog.SkyField;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The predictions between their samples, their names and summary, their file, and the query built from the frames and
 * the settings.
 */
public class SolarSystemObjectsTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final long T0 = Instant.parse("2026-03-10T02:00:00Z").toEpochMilli();
    private static final long HOUR = 3_600_000L;

    @Test
    public void interpolatesBetweenHourlySamplesAndNotBeyondThem() {
        // A path that bends: Dec follows a cubic in time, which four samples reproduce exactly.
        SolarSystemObjects.Body body = new SolarSystemObjects.Body();
        for (int hour = 0; hour <= 5; hour++) {
            body.samples.add(sample(T0 + hour * HOUR, (359.0 + hour * 0.5) % 360, cubic(hour), 14.0 + hour * 0.1, 1.0 + hour));
        }
        SolarSystemObjects.Position position = SolarSystemObjects.positionAt(body, T0 + (long) (2.25 * HOUR));
        assertEquals("RA continues across 0°", (359.0 + 1.125) % 360, position.ra(), 1e-9);
        assertEquals(cubic(2.25), position.dec(), 1e-9);
        assertEquals(14.225, position.magnitude(), 1e-9);
        assertEquals(3.25, position.raSigmaArcsec(), 1e-9);
        assertEquals(359.0, SolarSystemObjects.positionAt(body, T0).ra(), 1e-9);
        assertEquals(cubic(5), SolarSystemObjects.positionAt(body, T0 + 5 * HOUR).dec(), 1e-9);
        assertNull(SolarSystemObjects.positionAt(body, T0 - 1));
        assertNull(SolarSystemObjects.positionAt(body, T0 + 5 * HOUR + 1));
        // Two samples: straight-line motion; unknown uncertainties stay unknown.
        SolarSystemObjects.Body line = new SolarSystemObjects.Body();
        line.samples.add(sample(T0, 10, 0, null, null));
        line.samples.add(sample(T0 + HOUR, 11, 1, null, null));
        SolarSystemObjects.Position middle = SolarSystemObjects.positionAt(line, T0 + HOUR / 2);
        assertEquals(10.5, middle.ra(), 1e-12);
        assertEquals(0.5, middle.dec(), 1e-12);
        assertNull(middle.raSigmaArcsec());
    }

    private static double cubic(double hours) {
        return 10 + 0.02 * hours - 0.003 * hours * hours + 0.0004 * hours * hours * hours;
    }

    @Test
    public void namesAndSummary() {
        assertEquals("194 Prokne", SolarSystemObjects.label(body("194 Prokne (A879 FA)", false)));
        assertEquals("(2014 QV125)", SolarSystemObjects.label(body("(2014 QV125)", false)));
        assertEquals("C/2018 Y1 (Iwamoto)", SolarSystemObjects.label(body("C/2018 Y1 (Iwamoto)", true)));
        SolarSystemObjects objects = new SolarSystemObjects();
        assertEquals("no asteroids or comets", objects.summary());
        objects.bodies.add(body("194 Prokne (A879 FA)", false));
        assertEquals("1 asteroid", objects.summary());
        objects.bodies.add(body("(2014 QV125)", false));
        objects.bodies.add(body("C/2018 Y1 (Iwamoto)", true));
        assertEquals("2 asteroids and 1 comet", objects.summary());
    }

    @Test
    public void keepsThePredictionsOfTheSameFieldAndSpan() throws Exception {
        File session = folder.newFolder();
        SolarSystemObjects objects = new SolarSystemObjects();
        objects.field = SkyField.of(SolarSystemClientTest.TRANSFORMER, 2000, 1600);
        objects.startMillis = T0;
        objects.endMillis = T0 + HOUR;
        objects.magnitudeLimit = 19;
        objects.observer = "site from the FITS header";
        SolarSystemObjects.Body comet = body("C/2018 Y1 (Iwamoto)", true);
        comet.orbitSolution = "JPL#20";
        comet.samples.add(sample(T0, 150, 20, 11.6, 0.2));
        objects.bodies.add(comet);
        objects.fetchSteps.add(FetchStep.of("JPL SB-Ident", "Field search", FetchStep.Status.SUCCEEDED, "1 object", "",
                "https://ssd-api.jpl.nasa.gov/sb_ident.api?lat=37.900000&lon=23.700000&alt=0.1200&vmag-lim=19.0", null));
        objects.fetchSteps.add(FetchStep.of("JPL Horizons", "194 Prokne (A879 FA)", FetchStep.Status.FAILED,
                "Kept with the field search's straight-line motion, without an uncertainty.", "JPL answered HTTP 503.",
                "https://ssd.jpl.nasa.gov/api/horizons.api?format=json&SITE_COORD='23.700000%2C37.900000%2C0.1200'&STEP_SIZE='10+m'",
                null));
        SolarSystemStore.save(session, objects);

        String text = Files.readString(SolarSystemStore.fileIn(session).toPath(), StandardCharsets.UTF_8);
        assertFalse("the file names where the site came from, never the site", text.contains("37.9"));
        SolarSystemObjects loaded = SolarSystemStore.load(session);
        assertEquals(1, loaded.bodies.size());
        assertTrue(loaded.bodies.get(0).comet);
        assertEquals(11.6, loaded.bodies.get(0).samples.get(0).magnitude, 0);
        assertEquals("JPL#20", loaded.bodies.get(0).orbitSolution);
        assertEquals(2, loaded.fetchSteps.size());
        assertTrue(loaded.fetchSteps.get(0).url, loaded.fetchSteps.get(0).url.contains("vmag-lim=19.0"));
        assertFalse(loaded.fetchSteps.get(0).url, loaded.fetchSteps.get(0).url.contains("lat=") || loaded.fetchSteps.get(0).url.contains("37.9"));
        assertNull(loaded.fetchSteps.get(0).liveUrl);
        assertFalse(loaded.fetchSteps.get(1).url, loaded.fetchSteps.get(1).url.contains("SITE_COORD") || loaded.fetchSteps.get(1).url.contains("37.9"));
        assertTrue(loaded.fetchSteps.get(1).url, loaded.fetchSteps.get(1).url.contains("STEP_SIZE"));

        SkyField field = SkyField.of(SolarSystemClientTest.TRANSFORMER, 2000, 1600);
        assertTrue(SolarSystemStore.matches(loaded, field, new long[]{T0, T0 + HOUR}));
        assertFalse("a later frame is not covered", SolarSystemStore.matches(loaded, field, new long[]{T0, T0 + 2 * HOUR}));
        SkyField elsewhere = SkyField.of(eu.startales.spacepixels.fits.WcsCoordinateTransformer.fromHeader(
                SolarSystemClientTest.wcsHeader(160, 20)), 2000, 1600);
        assertFalse("another field", SolarSystemStore.matches(loaded, elsewhere, new long[]{T0, T0 + HOUR}));
        assertFalse("frames without times", SolarSystemStore.matches(loaded, field, null));

        Files.writeString(SolarSystemStore.fileIn(session).toPath(), "{not json", StandardCharsets.UTF_8);
        assertNull(SolarSystemStore.load(session));

        Files.writeString(SolarSystemStore.fileIn(session).toPath(),
                "{\"version\":1,\"field\":{\"centerRa\":150.0,\"centerDec\":20.0},"
                        + "\"problems\":[\"Field search (JPL SB-Ident): JPL did not answer.\"]}",
                StandardCharsets.UTF_8);
        SolarSystemObjects legacy = SolarSystemStore.load(session);
        assertTrue(legacy.fetchSteps.isEmpty());
        assertEquals(1, legacy.problems.size());
        assertEquals(1, FetchStep.rowsFor(legacy.fetchSteps, legacy.problems).size());
        assertNull(FetchStep.rowsFor(legacy.fetchSteps, legacy.problems).get(0).url);
    }

    @Test
    public void theQueryComesFromTheFramesAndTheSettings() {
        AppConfig config = new AppConfig();
        config.solarSystemMagnitude = 18.5;
        FitsFileInformation first = frame("a.fit", "2026-03-10T02:00:00", "60", true);
        FitsFileInformation last = frame("b.fit", "2026-03-10T03:00:00", "60", true);
        SolarSystemQuery query = SolarSystemQuery.of(new FitsFileInformation[]{first, last}, config);
        assertEquals(T0, query.startMillis());
        assertEquals(T0 + HOUR + 60_000, query.endMillis());
        assertEquals(18.5, query.magnitudeLimit(), 0);
        assertEquals(2000, query.width());
        assertEquals("site from the FITS header", query.observer().description());
        assertEquals(37.9, query.observer().site().latitudeDeg(), 1e-12);

        config.observatoryCode = " j95 ";
        assertEquals("IAU code J95", SolarSystemQuery.of(new FitsFileInformation[]{first}, config).observer().description());
        config.observatoryCode = "500";
        assertEquals("the geocentre is no observatory for JPL's field search", "site from the FITS header",
                SolarSystemQuery.of(new FitsFileInformation[]{first}, config).observer().description());

        config.observatoryCode = "";
        FitsFileInformation noSite = frame("c.fit", "2026-03-10T02:00:00", "60", false);
        expectProblem(() -> SolarSystemQuery.of(new FitsFileInformation[]{noSite}, config), "No observing site");
        config.siteLat = "37.9";
        config.siteLong = "23.7E";
        assertEquals("site from the Astrometry Config tab",
                SolarSystemQuery.of(new FitsFileInformation[]{noSite}, config).observer().description());
        FitsFileInformation noTime = frame("d.fit", null, "60", true);
        expectProblem(() -> SolarSystemQuery.of(new FitsFileInformation[]{noTime}, config), "no observation time");
        FitsFileInformation unsolved = new FitsFileInformation("e.fit", "e.fit", true, 2000, 1600);
        expectProblem(() -> SolarSystemQuery.of(new FitsFileInformation[]{unsolved}, config), "plate solution");
    }

    private static void expectProblem(Runnable action, String text) {
        try {
            action.run();
            fail("expected: " + text);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(text));
        }
    }

    static FitsFileInformation frame(String name, String dateObs, String exposure, boolean site) {
        Map<String, String> header = new LinkedHashMap<>(SolarSystemClientTest.wcsHeader());
        if (dateObs != null) header.put("DATE-OBS", dateObs);
        header.put("EXPTIME", exposure);
        if (site) {
            header.put("SITELAT", "37.9");
            header.put("SITELONG", "23.7");
        }
        return new FitsFileInformation(name, name, true, 2000, 1600).withFitsHeader(header);
    }

    private static SolarSystemObjects.Body body(String name, boolean comet) {
        SolarSystemObjects.Body body = new SolarSystemObjects.Body();
        body.name = name;
        body.comet = comet;
        return body;
    }

    private static SolarSystemObjects.Sample sample(long time, double ra, double dec, Double magnitude, Double sigma) {
        SolarSystemObjects.Sample sample = new SolarSystemObjects.Sample();
        sample.timeMillis = time;
        sample.ra = ra;
        sample.dec = dec;
        sample.magnitude = magnitude;
        sample.raSigmaArcsec = sigma;
        sample.decSigmaArcsec = sigma;
        return sample;
    }
}
