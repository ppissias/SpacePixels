package eu.startales.spacepixels.mpc;

import org.junit.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Checks the ADES PSV text against the rules of the ADES description and the submission schema. */
public class AdesPsvTest {
    private static final long TIME = Instant.parse("2022-08-24T05:13:22.149Z").toEpochMilli();
    private static final AdesPsv.Context CONTEXT = new AdesPsv.Context("W84", "P. Smith", List.of("A. Observer"),
            List.of("P. Smith"), "Reflector", 4.0, "CCD", "CCD", "Archival data");

    /** The context, the keyword line and the data lines, with schema-valid numbers. */
    @Test
    public void writesTheContextAndOneLinePerObservation() {
        var observation = new MpcObservation(1, TIME, 120_000, 10, 20, 357.207083, -1.805139, 0.123, 0.123, "Gaia3", "");
        var unidentified = new AdesPsv.Tracklet(null, null, AdesPsv.trkSub(TIME, 3), List.of(observation));
        var known = new AdesPsv.Tracklet("649341", null, null, List.of(observation));
        String psv = AdesPsv.write(CONTEXT, List.of(unidentified, known));
        String[] lines = psv.split("\n");
        assertEquals("# version=2022", lines[0]);
        assertTrue(psv, psv.contains("# observatory\n! mpcCode W84\n# submitter\n! name P. Smith\n# observers\n! name A. Observer\n"
                + "# measurers\n! name P. Smith\n# telescope\n! design Reflector\n! aperture 4.0\n! detector CCD\n"
                + "# software\n! astrometry SpacePixels\n# comment\n! line Archival data\n"));
        String keywords = lines[lines.length - 3];
        assertEquals(List.of("permID", "provID", "trkSub", "mode", "stn", "obsTime", "ra", "dec", "rmsRA", "rmsDec", "astCat", "exp", "notes"),
                List.of(keywords.split("\\|")).stream().map(String::trim).toList());
        String[] first = lines[lines.length - 2].split("\\|", -1);
        assertEquals("", first[0].trim());
        assertEquals("S082403", first[2].trim());
        assertEquals("CCD", first[3].trim());
        assertEquals("2022-08-24T05:13:22.149Z", first[5].trim());
        assertEquals("357.2070830", first[6].trim());
        assertEquals("-1.8051390", first[7].trim());
        assertEquals("0.123", first[8].trim());
        assertEquals("Gaia3", first[10].trim());
        assertEquals("120", first[11].trim());
        String[] second = lines[lines.length - 1].split("\\|", -1);
        assertEquals("649341", second[0].trim());
        assertEquals("", second[2].trim());
        // The ADES default widths line the columns up.
        assertEquals(keywords.indexOf("|obsTime"), lines[lines.length - 1].indexOf("|2022-"));
    }

    /** The checks name each missing or invalid detail, and accept a complete submission. */
    @Test
    public void problemsNameWhatIsMissing() {
        var observation = new MpcObservation(1, TIME, 120_000, 10, 20, 1, 1, 0.1, 0.1, "Gaia3", "");
        assertEquals(List.of(), AdesPsv.problems(CONTEXT, List.of(new AdesPsv.Tracklet(null, null, "S082401", List.of(observation, observation)))));
        var empty = new AdesPsv.Context("", " ", List.of(), List.of(), "", 0, "", "PHO", "a|b");
        var problems = AdesPsv.problems(empty, List.of(new AdesPsv.Tracklet(null, null, "TOO-LONG-ID", List.of()),
                new AdesPsv.Tracklet(null, null, "S1", List.of(observation)), new AdesPsv.Tracklet(null, null, "S1", List.of(observation))));
        String all = String.join("\n", problems);
        for (String expected : List.of("IAU observatory code", "Name the submitter", "Name the measurer", "Describe the telescope",
                "CCD or CMOS", "character |", "must be 1 to 7 letters or digits", "S1 is used twice", "TOO-LONG-ID has no observations",
                "S1 has a single observation")) {
            assertTrue(expected + " in\n" + all, all.contains(expected));
        }
    }

    /** Tracklet IDs carry the night and stay within the MPC's seven characters; RA never rounds up to 360. */
    @Test
    public void trackletIdsAndRaLimits() {
        assertEquals("S082400", AdesPsv.trkSub(TIME, 0));
        assertEquals("S08240Z", AdesPsv.trkSub(TIME, 35));
        assertEquals("S082410", AdesPsv.trkSub(TIME, 36));
        assertEquals("S0824ZZ", AdesPsv.trkSub(TIME, 36 * 36 - 1));
        assertEquals("0.0000000", AdesPsv.ra(359.99999999));
        assertEquals("7.1234567", AdesPsv.ra(7.12345671));
    }

    /** The station of the code must be where the frames were taken: Cerro Tololo agrees with itself, not with the unsigned site. */
    @Test
    public void stationMustMatchTheSite() {
        var w84 = new eu.startales.spacepixels.solarsystem.SolarSystemClient.Station("W84", "Cerro Tololo-DECam", -70.8064, -30.1695802, 2211.12);
        var ctio = new eu.startales.spacepixels.fits.ObservingSite(-30.16966, -70.80653, 2207, eu.startales.spacepixels.fits.ObservingSite.FROM_CONFIGURATION);
        assertEquals(null, AdesPsv.stationProblem(w84, ctio));
        var unsigned = new eu.startales.spacepixels.fits.ObservingSite(30.16966, 70.80653, 2280, eu.startales.spacepixels.fits.ObservingSite.FROM_CONFIGURATION);
        String problem = AdesPsv.stationProblem(w84, unsigned);
        assertTrue(problem, problem.startsWith("Observatory code W84 is Cerro Tololo-DECam (30.170° S, 70.806° W)")
                && problem.contains("km from the observing site (30.170° N, 70.807° E)"));
        assertEquals("no site, nothing to compare", null, AdesPsv.stationProblem(w84, null));
    }
}
