package eu.startales.spacepixels.mpc;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Reads Find_Orb's total.json, refuses a result for other observations, and draws orbits where they belong. */
public class FindOrbTest {
    /** Shaped like Find_Orb's total.json for six DECam observations (values from a real fit, trimmed). */
    private static final String TOTAL = """
            {"num": 1, "ids": ["S082401"], "objects": {"S082401": {"object": "S082401", "Find_Orb_version_iso": "2025-11-17T00:00:00Z",
              "elements": {"central body": "Sun", "frame": "J2000 ecliptic", "epoch_iso": "2022-08-24T00:00:00Z", "epoch": 2459815.5,
                "M": 147.5649415278671, "M sigma": 120, "a": 2.3582387873187, "a sigma": 1.09, "e": 0.5497717331111, "e sigma": 0.269,
                "q": 1.0617457621246, "q sigma": 1.45, "i": 6.0912041934883, "i sigma": 60, "arg_per": 188.5175831499691, "arg_per sigma": 60,
                "asc_node": 352.4405741533652, "asc_node sigma": 21, "rms_residual": 0.011903, "n_resids": 12, "U": 11.6889, "p_NEO": 59.1876,
                "MOIDs": {"Earth": 0.067368}},
              "observations": {"count": 2, "residuals": [
                {"iso date": "2022-08-24T05:30:40.143Z", "obscode": "W84", "dRA": 0.00556, "dDec": 0.00206, "incl": 1},
                {"iso date": "2022-08-24T07:39:51.374Z", "obscode": "W84", "dRA": -0.004, "dDec": 0.001, "incl": 0}]}}}}
            """;

    /** Elements, uncertainties and residuals come through; a result for these observations is accepted. */
    @Test
    public void readsTheOrbitAndResiduals() throws IOException {
        var orbit = FindOrb.parse(TOTAL, List.of("2022-08-24T05:30:40.143Z", "2022-08-24T07:39:51.374Z"));
        assertEquals(2.3582387873187, orbit.a(), 0);
        assertEquals(1.09, orbit.aSigma(), 0);
        assertEquals(0.5497717331111, orbit.e(), 0);
        assertEquals(59.1876, orbit.neoProbability(), 0);
        assertEquals(0.067368, orbit.earthMoid(), 0);
        assertNull("not in this result", orbit.absoluteMagnitude());
        assertEquals(2, orbit.residuals().size());
        assertTrue(orbit.residuals().get(0).used());
        assertTrue("incl 0 means the fit left it out", !orbit.residuals().get(1).used());
        assertTrue(orbit.bound());
    }

    /** A result whose residuals are for other times is someone else's: refused, never shown. */
    @Test
    public void refusesAResultForOtherObservations() {
        try {
            FindOrb.parse(TOTAL, List.of("2026-02-19T18:26:19.478Z", "2026-02-19T18:46:41.260Z"));
            fail("a stale result must not be taken for this fit");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not for the observations sent"));
        }
    }

    /** A circular orbit at 1 AU stays at 1 AU; Earth's mean longitude at J2000 is 100.46°; a hyperbola stays within reach. */
    @Test
    public void orbitGeometry() throws IOException {
        var circle = new FindOrb.Orbit("", 2_451_545.0, 1, null, 0, null, 0, null, 0, null, 0, null, 90, null, 1, null,
                null, null, 0, null, null, null, List.of(), null);
        for (double[] p : OrbitGeometry.path(circle, 36, 10)) assertEquals(1, Math.hypot(p[0], p[1]), 1e-12);
        double[] atEpoch = OrbitGeometry.atEpoch(circle);
        assertEquals(0, atEpoch[0], 1e-9);
        assertEquals("90° from perihelion", 1, atEpoch[1], 1e-9);
        double[] earth = OrbitGeometry.planetAt(2, 2_451_545.0);
        assertEquals(100.464, Math.toDegrees(Math.atan2(earth[1], earth[0])), 1e-9);
        var hyperbola = new FindOrb.Orbit("", 2_451_545.0, -1, null, 2, null, 0, null, 0, null, 0, null, 0, null, 1, null,
                null, null, 0, null, null, null, List.of(), null);
        assertNull(OrbitGeometry.atEpoch(hyperbola));
        for (double[] p : OrbitGeometry.path(hyperbola, 50, 6)) assertTrue(Math.hypot(p[0], p[1]) <= 6 + 1e-6);
    }
}
