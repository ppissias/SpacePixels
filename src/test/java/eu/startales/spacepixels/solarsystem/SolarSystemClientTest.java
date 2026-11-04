package eu.startales.spacepixels.solarsystem;

import eu.startales.spacepixels.fits.ObservingSite;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.skycatalog.SkyField;
import org.junit.Test;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The JPL client against recorded answers: names to Horizons commands, the field search and Horizons parsing, the
 * search box, the frame crossing and a whole fetch, including an object Horizons cannot answer for.
 */
public class SolarSystemClientTest {

    /** 1.5″ per pixel, north up and east left, centre RA 150°, Dec 20° at pixel (999.5, 799.5) of 2000 × 1600. */
    private static final double SCALE_DEG = 1.5 / 3600;
    static final WcsCoordinateTransformer TRANSFORMER = WcsCoordinateTransformer.fromHeader(wcsHeader());
    private static final long START = Instant.parse("2026-03-10T02:00:00Z").toEpochMilli();
    private static final long END = Instant.parse("2026-03-10T03:00:00Z").toEpochMilli();

    @Test
    public void namesBecomeHorizonsCommands() {
        assertEquals("194;", SolarSystemClient.horizonsCommand("194 Prokne (A879 FA)"));
        assertEquals("36318;", SolarSystemClient.horizonsCommand("36318 (2000 LJ18)"));
        assertEquals("301;", SolarSystemClient.horizonsCommand("301 Bavaria (A890 WA)"));
        assertEquals("DES=2014 QV125;", SolarSystemClient.horizonsCommand("(2014 QV125)"));
        assertEquals("DES=2014 QV125;", SolarSystemClient.horizonsCommand("2014 QV125"));
        assertEquals("DES=6344 P-L;", SolarSystemClient.horizonsCommand("(6344 P-L)"));
        assertEquals("DES=41P;CAP;NOFRAG", SolarSystemClient.horizonsCommand("41P/Tuttle-Giacobini-Kresak"));
        assertEquals("DES=73P-B;CAP", SolarSystemClient.horizonsCommand("73P-B/Schwassmann-Wachmann"));
        assertEquals("DES=C/2018 Y1;CAP;NOFRAG", SolarSystemClient.horizonsCommand("C/2018 Y1 (Iwamoto)"));
        assertEquals("DES=P/2019 LD2;CAP;NOFRAG", SolarSystemClient.horizonsCommand("P/2019 LD2 (ATLAS)"));
        assertEquals("DES=2I;CAP;NOFRAG", SolarSystemClient.horizonsCommand("2I/Borisov"));
        assertEquals("DES=A/2017 U1;", SolarSystemClient.horizonsCommand("A/2017 U1"));
        assertTrue(SolarSystemClient.isComet("C/2018 Y1 (Iwamoto)"));
        assertTrue(SolarSystemClient.isComet("41P/Tuttle-Giacobini-Kresak"));
        assertFalse(SolarSystemClient.isComet("194 Prokne (A879 FA)"));
        assertFalse(SolarSystemClient.isComet("A/2017 U1"));
    }

    @Test
    public void theFieldSearchAsksForTheDepthTheGroupAndTheSite() {
        String url = JplSbIdentUrlBuilder.buildField(null, 37.9, 23.7, 120.0, START, 150, 20, 0.5, 0.4, 19.0, "neo");
        assertTrue(url, url.contains("lat=37.900000&lon=23.700000&alt=0.1200"));
        assertTrue(url, url.contains("obs-time=2026-03-10_02%3A00%3A00"));
        assertTrue(url, url.contains("fov-ra-hwidth=0.5000&fov-dec-hwidth=0.4000"));
        assertTrue(url, url.contains("two-pass=true&suppress-first-pass=true"));
        assertTrue(url, url.contains("vmag-lim=19.0") && url.contains("sb-group=neo"));
        assertTrue("comets have no asteroid magnitude parameters", url.contains("mag-required=false"));
        assertNull("no observer", JplSbIdentUrlBuilder.buildField(null, null, null, null, START, 150, 20, 0.5, 0.4, 19.0, null));
        String around = JplSbIdentUrlBuilder.build("568", null, null, null, START, 150, 20, 0.1, null);
        assertTrue(around, around.startsWith("https://ssd-api.jpl.nasa.gov/sb_ident.api?mpc-code=568&") && !around.contains("vmag-lim"));
        assertTrue("the report's lookup around a finding also returns comets", around.contains("mag-required=false"));
    }

    @Test
    public void readsTheFieldSearch() throws IOException {
        SolarSystemClient.Search search = SolarSystemClient.parseFieldSearch(fieldAnswer(List.of(
                row("194 Prokne (A879 FA)", "10:00:00.00", "+20 00'00.0\"", "14.1", "4.444E+01", "-1.275E+01"),
                row("C/2018 Y1 (Iwamoto)", "09:59:30.00", "-00 30'00.0\"", "11.6T", "-9.369E+02", "5.414E+02"))), 42L);
        assertEquals("1.1", search.version());
        assertEquals(2, search.candidates().size());
        SolarSystemClient.Candidate prokne = search.candidates().get(0);
        assertEquals("194 Prokne (A879 FA)", prokne.name());
        assertEquals(150.0, prokne.ra(), 1e-9);
        assertEquals(20.0, prokne.dec(), 1e-9);
        assertEquals(14.1, prokne.magnitude(), 1e-9);
        assertEquals(44.44, prokne.raRateArcsecPerHour(), 1e-9);
        assertEquals(-12.75, prokne.decRateArcsecPerHour(), 1e-9);
        assertEquals(42L, prokne.epochMillis());
        SolarSystemClient.Candidate comet = search.candidates().get(1);
        assertTrue(comet.comet());
        assertEquals(-0.5, comet.dec(), 1e-9);
        assertEquals("a total magnitude keeps its number", 11.6, comet.magnitude(), 1e-9);
        // An empty field is a valid answer, also in the form JPL gives when nothing matches.
        assertTrue(SolarSystemClient.parseFieldSearch("{\"signature\":{\"version\":\"1.1\"},\"n_second_pass\":0}", 0)
                .candidates().isEmpty());
        assertTrue(SolarSystemClient.parseFieldSearch("{\"signature\":{\"version\":\"1.1\"},\"sb_constraints\":{\"sb-group\":\"neo\"},"
                + "\"warning\":\"no matching records\",\"observer\":{\"location\":\"User-defined Location\"}}", 0).candidates().isEmpty());
        try {
            SolarSystemClient.parseFieldSearch("{\"signature\":{\"version\":\"1.1\"}}", 0);
            throw new AssertionError("an answer that is neither objects nor an empty field must fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("without a list"));
        }
    }

    @Test
    public void readsHorizonsPositionsMagnitudesAndUncertainties() throws IOException {
        SolarSystemObjects.Body body = SolarSystemClient.parseHorizons(horizonsAnswer("194 Prokne (A879 FA)", "JPL#172", "APmag",
                List.of(new double[]{-10, 150.0, 20.0, 14.06, 0.005, 0.002}, new double[]{70, 150.01, 19.99, 14.05, 0.006, 0.002})),
                candidate("194 Prokne (A879 FA)", 150, 20, 7200, 0));
        assertEquals("JPL#172", body.orbitSolution);
        assertEquals(2, body.samples.size());
        assertEquals(START - 10 * 60_000L, body.samples.get(0).timeMillis);
        assertEquals(150.01, body.samples.get(1).ra, 1e-12);
        assertEquals(14.05, body.samples.get(1).magnitude, 1e-12);
        assertEquals(0.006, body.samples.get(1).raSigmaArcsec, 1e-12);
        SolarSystemObjects.Body comet = SolarSystemClient.parseHorizons(horizonsAnswer("Iwamoto (C/2018 Y1)", "JPL#20", "T-mag",
                List.of(new double[]{0, 150.0, 20.0, 11.6, 0.166, 0.149})), candidate("C/2018 Y1 (Iwamoto)", 150, 20, 0, 0));
        assertTrue(comet.comet);
        assertEquals("the total magnitude of a comet", 11.6, comet.samples.get(0).magnitude, 1e-12);
    }

    @Test
    public void anUnknownObjectIsAProblemNotAnEmptyBody() {
        try {
            SolarSystemClient.parseHorizons("{\"signature\":{\"version\":\"1.2\"},\"result\":\"No matches found.\"}",
                    candidate("(2099 ZZ1)", 150, 20, 0, 0));
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("(2099 ZZ1)"));
            return;
        }
        throw new AssertionError("an answer without positions must fail");
    }

    @Test
    public void searchesTheFieldWidenedByTheMargin() {
        SkyField field = SkyField.of(TRANSFORMER, 2000, 1600);
        double[] box = SolarSystemClient.searchBox(field, 0.5);
        assertEquals(150.0, box[0], 1e-9);
        assertEquals("Dec: the frame with its 3 % margin, plus 0.5°", 20.0, box[1], 0.01);
        assertEquals(1600 * 1.06 * SCALE_DEG / 2 + 0.5, box[3], 0.01);
        assertTrue("RA is widened by more than 0.5° on the sky", box[2] > 2000 * 1.06 * SCALE_DEG / 2 + 0.5);
        // A field that reaches a pole is searched all round.
        SkyField polar = SkyField.of(WcsCoordinateTransformer.fromHeader(wcsHeader(150, 89.8)), 2000, 1600);
        assertEquals(180.0, SolarSystemClient.searchBox(polar, 0.5)[2], 0);
    }

    @Test
    public void aFastObjectCrossingBetweenSamplesCountsAsOnTheFrame() {
        SolarSystemQuery query = query();
        SolarSystemObjects.Sample east = sample(START, 150 + 2.0 / Math.cos(Math.toRadians(20)), 20);
        SolarSystemObjects.Sample west = sample(END, 150 - 2.0 / Math.cos(Math.toRadians(20)), 20);
        assertTrue(SolarSystemClient.crossesFrame(query, List.of(east, west)));
        SolarSystemObjects.Sample north = sample(END, 150 - 2.0 / Math.cos(Math.toRadians(20)), 21);
        assertFalse(SolarSystemClient.crossesFrame(query, List.of(sample(START, 152.5, 21), north)));
    }

    @Test
    public void horizonsIsAskedForTheObserverAndTheSpan() {
        String url = URLDecoder.decode(SolarSystemClient.horizonsUri(query(), candidate("194 Prokne (A879 FA)", 150, 20, 44, -12))
                .toString(), StandardCharsets.UTF_8);
        assertTrue(url, url.contains("COMMAND='194;'"));
        assertTrue(url, url.contains("CENTER='coord@399'"));
        assertTrue(url, url.contains("SITE_COORD='23.700000,37.900000,0.1200'"));
        assertTrue(url, url.contains("START_TIME='2026-03-09 12:00:00'"));
        assertTrue(url, url.contains("STOP_TIME='2026-03-10 17:00:00'"));
        assertTrue(url, url.contains("STEP_SIZE='10 m'"));
        assertTrue(url, url.contains("QUANTITIES='1,9,36'"));
        String fast = URLDecoder.decode(SolarSystemClient.horizonsUri(query(), candidate("99942 Apophis (2004 MN4)", 150, 20, 7200, 0))
                .toString(), StandardCharsets.UTF_8);
        assertTrue("a fast, close object is sampled every 2 minutes", fast.contains("STEP_SIZE='2 m'"));
        SolarSystemQuery withCode = new SolarSystemQuery(TRANSFORMER, 2000, 1600, START, END, 19,
                new SolarSystemQuery.Observer("J95", null, "IAU code J95"));
        String coded = URLDecoder.decode(SolarSystemClient.horizonsUri(withCode, candidate("194 Prokne (A879 FA)", 150, 20, 44, -12))
                .toString(), StandardCharsets.UTF_8);
        assertTrue(coded, coded.contains("CENTER='J95@399'") && !coded.contains("SITE_COORD"));
    }

    @Test
    public void fetchesTheObjectsThatCrossTheFrame() throws Exception {
        double cosDec = Math.cos(Math.toRadians(20));
        List<String> asked = new ArrayList<>();
        SolarSystemClient client = new SolarSystemClient((uri, timeout) -> {
            String url = URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8);
            asked.add(url);
            if (url.contains("sb_ident.api") && url.contains("sb-group=neo")) {
                // East of the frame at the middle of the span, moving west at 2° an hour: it crosses after 18 minutes.
                return fieldAnswer(List.of(row("99942 Apophis (2004 MN4)", hours(150 + 0.6 / cosDec), "+20 00'00.0\"",
                        "19.5", "-7.200E+03", "0")));
            }
            if (url.contains("sb_ident.api")) {
                return fieldAnswer(List.of(
                        row("194 Prokne (A879 FA)", "10:00:00.00", "+20 00'00.0\"", "14.1", "4.444E+01", "-1.275E+01"),
                        row("(2014 QV125)", "11:00:00.00", "+20 00'00.0\"", "20.5", "5.517E+01", "-2.180E+01"),
                        row("C/2018 Y1 (Iwamoto)", "10:00:10.00", "+20 05'00.0\"", "11.6T", "1.000E+01", "0")));
            }
            if (url.contains("COMMAND='194;'")) {
                return horizonsAnswer("194 Prokne (A879 FA)", "JPL#172", "APmag", List.of(
                        new double[]{-10, 150.0, 20.0, 14.1, 0.005, 0.002}, new double[]{30, 150.001, 19.999, 14.1, 0.005, 0.002},
                        new double[]{70, 150.002, 19.998, 14.1, 0.005, 0.002}));
            }
            if (url.contains("COMMAND='DES=C/2018 Y1;CAP;NOFRAG'")) {
                return horizonsAnswer("Iwamoto (C/2018 Y1)", "JPL#20", "T-mag", List.of(
                        new double[]{-10, 150.04, 20.083, 11.6, 0.2, 0.2}, new double[]{70, 150.05, 20.083, 11.6, 0.2, 0.2}));
            }
            throw new IOException("JPL answered HTTP 503.");
        });
        List<String> stages = new ArrayList<>();
        SolarSystemObjects result = client.fetch(query(), stages::add);

        Map<String, SolarSystemObjects.Body> bodies = new LinkedHashMap<>();
        result.bodies.forEach(body -> bodies.put(body.name, body));
        assertEquals(List.of("C/2018 Y1 (Iwamoto)", "194 Prokne (A879 FA)", "99942 Apophis (2004 MN4)"), new ArrayList<>(bodies.keySet()));
        assertEquals("JPL#172", bodies.get("194 Prokne (A879 FA)").orbitSolution);
        assertTrue(bodies.get("C/2018 Y1 (Iwamoto)").comet);
        assertNull("Horizons failed: straight-line motion from the field search",
                bodies.get("99942 Apophis (2004 MN4)").orbitSolution);
        assertEquals(1, result.problems.size());
        assertTrue(result.problems.get(0), result.problems.get(0).contains("1 of 3 objects"));
        assertEquals("JPL SB-Ident API 1.1, JPL Horizons API 1.2", result.source);
        assertEquals("site from the FITS header", result.observer);
        assertEquals(START, result.startMillis);
        assertEquals(19.0, result.magnitudeLimit, 0);
        assertTrue(asked.get(0), asked.get(0).contains("vmag-lim=19.0") && asked.get(0).contains("obs-time=2026-03-10_02:30:00"));
        assertTrue("the far object was not asked of Horizons", asked.stream().noneMatch(url -> url.contains("QV125;")));
        assertTrue(stages.toString(), stages.contains("asteroids and comets in the field (JPL)"));
        // The straight-line Apophis is on the frame during the span.
        SolarSystemObjects.Position apophis = SolarSystemObjects.positionAt(bodies.get("99942 Apophis (2004 MN4)"), START + 48 * 60_000L);
        double[] pixel = TRANSFORMER.skyToPixel(apophis.ra(), apophis.dec());
        assertEquals(999.5, pixel[0], 5);
    }

    /** Extended clock samples must not admit an object that crosses the field only far outside the observing span. */
    @Test public void extendedClockCoverageDoesNotWidenFinalFieldSelection() throws Exception {
        var client = new SolarSystemClient((uri, timeout) -> {
            String url = URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8);
            if (url.contains("sb-group=neo")) return fieldAnswer(List.of());
            if (url.contains("sb_ident.api")) return fieldAnswer(List.of(
                    row("194 Prokne (A879 FA)", "10:00:00.00", "+20 00'00.0\"", "14.1", "0", "0")));
            return horizonsAnswer("194 Prokne (A879 FA)", "JPL#172", "APmag", List.of(
                    new double[]{-840, 150, 20, 14.1, .01, .01},
                    new double[]{-10, 152, 22, 14.1, .01, .01}, new double[]{0, 152, 22, 14.1, .01, .01},
                    new double[]{60, 152, 22, 14.1, .01, .01}, new double[]{70, 152, 22, 14.1, .01, .01},
                    new double[]{900, 150, 20, 14.1, .01, .01}));
        });
        var result = client.fetch(query(), ignored -> { });
        assertTrue(result.problems.toString(), result.problems.isEmpty());
        assertTrue(result.bodies.isEmpty());
    }

    @Test
    public void aFailingFieldSearchIsAProblemAndLeavesNoSource() throws Exception {
        SolarSystemClient client = new SolarSystemClient((uri, timeout) -> {
            throw new IOException("JPL did not answer within 300 s.");
        });
        SolarSystemObjects result = client.fetch(query(), text -> { });
        assertTrue(result.bodies.isEmpty());
        assertEquals("nothing else is asked", 1, result.problems.size());
        assertTrue(result.problems.get(0), result.problems.get(0).startsWith("Field search (JPL SB-Ident): JPL did not answer"));
        assertEquals("", result.source);
    }

    // ==========================================
    // FIXTURES
    // ==========================================

    static SolarSystemQuery query() {
        return new SolarSystemQuery(TRANSFORMER, 2000, 1600, START, END, 19,
                new SolarSystemQuery.Observer(null, new ObservingSite(37.9, 23.7, 120, ObservingSite.FROM_HEADER),
                        "site from the FITS header"));
    }

    private static SolarSystemClient.Candidate candidate(String name, double ra, double dec, double raRate, double decRate) {
        return new SolarSystemClient.Candidate(name, SolarSystemClient.isComet(name), ra, dec, 15.0, raRate, decRate,
                START + 30 * 60_000L);
    }

    private static SolarSystemObjects.Sample sample(long time, double ra, double dec) {
        SolarSystemObjects.Sample sample = new SolarSystemObjects.Sample();
        sample.timeMillis = time;
        sample.ra = ra;
        sample.dec = dec;
        return sample;
    }

    private static String row(String name, String ra, String dec, String magnitude, String raRate, String decRate) {
        return "[" + String.join(",", quote(name), quote(ra), quote(dec), quote("1.E3"), quote("204."), quote("1087."),
                quote(magnitude), quote(raRate), quote(decRate)) + "]";
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** "10:00:00.00" for 150°. */
    private static String hours(double degrees) {
        double hours = degrees / 15;
        int h = (int) hours;
        int m = (int) ((hours - h) * 60);
        double s = ((hours - h) * 60 - m) * 60;
        return String.format(java.util.Locale.US, "%02d:%02d:%05.2f", h, m, s);
    }

    /** An SB-Ident answer in the format of version 1.1, numerical pass only. */
    static String fieldAnswer(List<String> rows) {
        return "{\"signature\":{\"source\":\"NASA/JPL Small-Body Identification API\",\"version\":\"1.1\"},"
                + "\"n_second_pass\":" + rows.size() + ","
                + "\"fields_second\":[\"Object name\",\"Astrometric RA (hh:mm:ss)\",\"Astrometric Dec (dd mm'ss\\\")\","
                + "\"Dist. from center RA (\\\")\",\"Dist. from center Dec (\\\")\",\"Dist. from center Norm (\\\")\","
                + "\"Visual magnitude (V)\",\"RA rate (\\\"/h)\",\"Dec rate (\\\"/h)\"],"
                + "\"data_second_pass\":[" + String.join(",", rows) + "]}";
    }

    /**
     * A Horizons observer-table answer: each row is {minutes after START, RA, Dec, magnitude, RA 3σ, Dec 3σ}, as
     * CSV with the empty flag columns Horizons writes.
     */
    static String horizonsAnswer(String target, String solution, String magnitudeColumn, List<double[]> rows) {
        StringBuilder result = new StringBuilder("*******************************************************************************\n")
                .append("Target body name: ").append(target).append("            {source: ").append(solution).append("}\n")
                .append("Center-site name: (user defined site below)\n")
                .append("*******************************************************************************\n")
                .append(" Date__(UT)__HR:MN:SS, , , R.A.___(ICRF), DEC____(ICRF), ").append(magnitudeColumn)
                .append(", S-brt, RA_3sigma, DEC_3sigma,\n")
                .append("*******************************************************************************\n")
                .append("$$SOE\n");
        java.time.format.DateTimeFormatter format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MMM-dd HH:mm:ss", java.util.Locale.US)
                .withZone(java.time.ZoneOffset.UTC);
        for (double[] row : rows) {
            result.append(' ').append(format.format(Instant.ofEpochMilli(START + (long) (row[0] * 60_000))))
                    .append(", , , ").append(row[1]).append(", ").append(row[2]).append(", ").append(row[3])
                    .append(", n.a., ").append(row[4]).append(", ").append(row[5]).append(",\n");
        }
        result.append("$$EOE\n");
        return "{\"signature\":{\"source\":\"NASA/JPL Horizons API\",\"version\":\"1.2\"},\"result\":"
                + quote(result.toString()).replace("\n", "\\n") + "}";
    }

    static Map<String, String> wcsHeader() {
        return wcsHeader(150.0, 20.0);
    }

    static Map<String, String> wcsHeader(double ra, double dec) {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("CTYPE1", "RA---TAN");
        header.put("CTYPE2", "DEC--TAN");
        header.put("CRPIX1", "1000.5");
        header.put("CRPIX2", "800.5");
        header.put("CRVAL1", Double.toString(ra));
        header.put("CRVAL2", Double.toString(dec));
        header.put("CD1_1", Double.toString(-SCALE_DEG));
        header.put("CD1_2", "0");
        header.put("CD2_1", "0");
        header.put("CD2_2", Double.toString(SCALE_DEG));
        return header;
    }
}
