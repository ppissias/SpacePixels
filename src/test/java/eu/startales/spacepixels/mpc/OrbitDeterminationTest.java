package eu.startales.spacepixels.mpc;

import eu.startales.spacepixels.fits.ObservingSite;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for SpacePixels' clean-room built-in orbit determination engine ({@link OrbitDetermination}) and
 * analytical Earth/observer ephemeris ({@link EarthEphemeris}): multi-night main-belt and Apollo NEO recovery,
 * single-night Statistical Ranging (including fast close-NEO identification and uncertainty sample cloud),
 * hyperbolic comet recovery, Earth MOID and input validation.
 */
public class OrbitDeterminationTest {
    private static final ObservingSite SITE = new ObservingSite(37.98, 23.72, 200.0, ObservingSite.FROM_CONFIGURATION);

    /** Earth's J2000 heliocentric position, precession tilt sign, and topocentric offset match reference ephemerides. */
    @Test
    public void earthEphemerisAndTopocentricOffsetArePhysicallyAccurate() {
        double[] earthJ2000 = EarthEphemeris.earthPositionJ2000(OrbitGeometry.J2000);
        double r = norm(earthJ2000);
        // Official VSOP87D Earth at J2000.0 (JD 2451545.0 TT): L = 100.37784 deg, B = -0.00023 deg, R = 0.983328 AU.
        assertEquals(0.983328, r, 0.00002);
        assertEquals(0.0, earthJ2000[2], 1e-5);
        assertEquals(100.37784, Math.toDegrees(Math.atan2(earthJ2000[1], earthJ2000[0])), 0.001);

        // Meeus Example 25.b (1992-10-13 00:00 TD, JD 2448908.5): R = 0.997608 AU, J2000 longitude ~ 20.008 deg.
        double[] earth1992 = EarthEphemeris.earthPositionJ2000(2448908.5);
        assertEquals(0.997608, norm(earth1992), 0.00002);
        assertEquals(20.008, Math.toDegrees(Math.atan2(earth1992[1], earth1992[0])), 0.005);

        // Ecliptic precession tilt from Mean of Date back to J2000 must match Standish J2000 Z sign in 2018 & 2026.
        for (double jd : new double[]{2458364.5, 2458450.5, 2461109.5, 2461200.5}) {
            double[] vsop = EarthEphemeris.earthPositionJ2000(jd);
            double[] standish = OrbitGeometry.planetPosition3D(OrbitGeometry.EARTH_INDEX, jd);
            assertTrue("J2000 ecliptic Z precession sign must match Standish at JD " + jd,
                    Math.signum(vsop[2]) == Math.signum(standish[2]));
            assertEquals("J2000 ecliptic Z close to Standish at JD " + jd, standish[2], vsop[2], 1.5e-5);
        }

        double jd2026 = 2461109.5;
        double[] topo = EarthEphemeris.topocentricOffsetJ2000Ecliptic(jd2026, EarthEphemeris.utcToTt(jd2026), SITE);
        double topoKm = norm(topo) * OrbitGeometry.KM_PER_AU;
        assertEquals("Topocentric radius matches WGS84 Earth radius", 6370.0, topoKm, 25.0);
        assertEquals(69.184, EarthEphemeris.ttMinusUtcSeconds(jd2026), 1e-6);
    }

    /** Multi-night main-belt observations recover accurate orbital elements, tight uncertainties and 0% P(NEO). */
    @Test
    public void recoversMainBeltOrbitAcrossThreeNights() {
        var truth = new FindOrb.Orbit("2018-09-01T00:00:00Z", 2458362.5, 3.168, null, 0.126, null,
                11.51, null, 336.5, null, 286.0, null, 42.0, null, 3.168 * (1.0 - 0.126), null,
                null, null, 0, null, null, null, List.of(), null, 2458120.0);
        var obs = synthesize(truth, SITE, new double[]{0.0, 0.04, 0.08, 3.0, 3.04, 3.08, 6.0, 6.04, 6.08}, 0.15);
        var solution = OrbitDetermination.fit(obs, SITE);
        var fit = solution.orbit();

        assertEquals(OrbitDetermination.SOLVER_TAG, fit.version());
        assertEquals(truth.a(), fit.a(), 0.03);
        assertEquals(truth.e(), fit.e(), 0.02);
        assertEquals(truth.i(), fit.i(), 0.3);
        assertTrue("Residuals match synthetic noise", fit.rmsArcsec() < 0.35);
        assertNotNull(fit.aSigma());
        assertNotNull(fit.earthMoid());
        assertEquals(1.77, fit.earthMoid(), 0.05);
        assertTrue("Main-belt asteroid has low NEO probability", fit.neoProbability() < 5.0);
    }

    /** Multi-night Apollo NEO observations recover an Earth-crossing NEO orbit with high P(NEO) and small MOID. */
    @Test
    public void recoversApolloNeoAcrossTwoNights() {
        var truth = new FindOrb.Orbit("2026-03-10T00:00:00Z", 2461109.5, 1.0877, null, 0.6733, null,
                29.52, null, 298.65, null, 44.77, null, 97.20, null, 0.3553, null,
                null, null, 0, null, null, null, List.of(), null, 2460997.63);
        var obs = synthesize(truth, SITE, new double[]{0.0, 0.03, 0.06, 3.0, 3.03, 3.06}, 0.15);
        var fit = OrbitDetermination.fit(obs, SITE).orbit();

        assertTrue("Must classify as NEO (q <= 1.3 AU)", OrbitGeometry.isNeo(fit));
        assertEquals(truth.e(), fit.e(), 0.08);
        assertEquals(truth.i(), fit.i(), 6.0);
        assertTrue("High NEO probability expected", fit.neoProbability() > 90.0);
        assertTrue("Small Earth MOID expected", fit.earthMoid() < 0.15);
        assertTrue(fit.rmsArcsec() < 0.35);
    }

    /** Single-night Statistical Ranging produces a realistic preliminary orbit, wide uncertainties and a sample cloud. */
    @Test
    public void singleNightStatisticalRangingProducesReasonableOrbitAndCloud() {
        var truth = new FindOrb.Orbit("2018-09-01T00:00:00Z", 2458362.5, 3.168, null, 0.126, null,
                11.51, null, 336.5, null, 286.0, null, 42.0, null, 3.168 * (1.0 - 0.126), null,
                null, null, 0, null, null, null, List.of(), null, 2458120.0);
        var obs = synthesize(truth, SITE, new double[]{0.0, 0.02, 0.04, 0.06, 0.08, 0.10}, 0.15);
        var solution = OrbitDetermination.fit(obs, SITE);
        var fit = solution.orbit();

        assertTrue("Bound main-belt preliminary fit", fit.bound() && fit.a() > 1.8 && fit.a() < 4.5);
        assertTrue("Honest wide 1-night uncertainty on a", fit.aSigma() != null && fit.aSigma() > 0.2);
        assertTrue("Uncertainty cloud populated for 3D viewer", solution.sampleOrbits().size() >= 6);
        assertTrue("Small residuals on single night", fit.rmsArcsec() < 0.4);
    }

    /** A fast close-approaching NEO observed on a single night is immediately recognized as a Near-Earth Object. */
    @Test
    public void singleNightCloseFastNeoScoresHighNeoProbability() {
        double jd = 2461109.5;
        double[] earth = EarthEphemeris.earthPositionJ2000(EarthEphemeris.utcToTt(jd));
        double earthLon = Math.toDegrees(Math.atan2(earth[1], earth[0]));
        double q = norm(earth) - 0.05; // passes ~0.05 AU inside Earth's orbit
        var fastNeo = new FindOrb.Orbit("2026-03-10T00:00:00Z", jd, q / (1.0 - 0.35), null, 0.35, null,
                18.0, null, earthLon, null, 0.0, null, 1.5, null, q, null,
                null, null, 0, null, null, null, List.of(), null, jd - 1.5);
        var obs = synthesize(fastNeo, SITE, new double[]{0.0, 0.015, 0.03, 0.045, 0.06}, 0.15);
        var fit = OrbitDetermination.fit(obs, SITE).orbit();

        assertTrue("Fast close NEO must have high P(NEO), got " + fit.neoProbability(), fit.neoProbability() > 80.0);
        assertTrue("Nominal fit should stay in NEO zone, got q=" + fit.q(), fit.q() <= 1.35);
    }

    /** Multi-night hyperbolic comet observations recover e > 1 and accurate perihelion q and inclination. */
    @Test
    public void recoversHyperbolicCometOrbit() {
        double q = 0.85, e = 1.20;
        var truth = new FindOrb.Orbit("2026-06-08T12:00:00Z", 2461200.0, -q / (e - 1.0), null, e, null,
                38.0, null, 115.0, null, 62.0, null, Double.NaN, null, q, null,
                null, null, 0, null, null, null, List.of(), null, 2461225.0);
        var obs = synthesize(truth, SITE, new double[]{0.0, 0.03, 4.0, 4.03, 9.0, 9.03, 14.0, 14.03}, 0.15);
        var fit = OrbitDetermination.fit(obs, SITE).orbit();

        assertTrue("Hyperbolic eccentricity recovered", fit.e() > 1.15 && fit.e() < 1.25);
        assertEquals(q, fit.q(), 0.02);
        assertEquals(38.0, fit.i(), 0.3);
        assertNotNull("Perihelion time Tp populated for hyperbolic time animation", fit.perihelionJd());
        assertTrue(fit.rmsArcsec() < 0.35);
    }

    /** Linked nights observed from two widely separated sites fit cleanly via per-observation site coordinates. */
    @Test
    public void multiSiteLinkedNightsUseEachObservationsOwnSite() {
        ObservingSite siteNorth = new ObservingSite(37.98, 23.72, 200.0, ObservingSite.FROM_HEADER);
        ObservingSite siteSouth = new ObservingSite(-30.17, -70.81, 2200.0, ObservingSite.FROM_HEADER);
        var truth = new FindOrb.Orbit("2026-03-10T00:00:00Z", 2461109.5, 1.0877, null, 0.6733, null,
                29.52, null, 298.65, null, 44.77, null, 97.20, null, 0.3553, null,
                null, null, 0, null, null, null, List.of(), null, 2460997.63);
        var night1 = synthesize(truth, siteNorth, new double[]{0.0, 0.03, 0.06}, 0.15);
        var night2 = synthesize(truth, siteSouth, new double[]{3.0, 3.03, 3.06}, 0.15);
        var sited = new ArrayList<OrbitDetermination.SitedObservation>();
        for (var o : night1) sited.add(new OrbitDetermination.SitedObservation(o, siteNorth));
        for (var o : night2) sited.add(new OrbitDetermination.SitedObservation(o, siteSouth));

        var fit = OrbitDetermination.fitSited(sited).orbit();
        assertTrue("Multi-site Apollo NEO fits with sub-arcsecond residuals", fit.rmsArcsec() < 0.35);
        assertTrue(OrbitGeometry.isNeo(fit));
        assertTrue(fit.neoProbability() > 90.0);
    }

    /** Noisy 2-night main-belt arc with a 2" endpoint outlier stays regularized in the main belt without runaway eccentricity. */
    @Test
    public void noisyTwoNightMainBeltWithEndpointOutlierStaysRegularized() {
        var truth = new FindOrb.Orbit("2018-09-01T00:00:00Z", 2458362.5, 3.168, null, 0.126, null,
                11.51, null, 117.1, null, 225.0, null, 355.0, null, 3.168 * (1.0 - 0.126), null,
                null, null, 0, null, null, null, List.of(), null, 2458380.0);
        double[] offsets = {0.0, 0.015, 0.030, 0.045, 0.060, 0.095,
                3.0, 3.015, 3.030, 3.050, 3.070, 3.100};
        List<MpcObservation> clean = synthesize(truth, SITE, offsets, 0.55);
        // Inject realistic end-of-night airmass/guiding outliers on the last frame of each night.
        List<MpcObservation> obs = new ArrayList<>(clean);
        MpcObservation lastN1 = obs.get(5);
        obs.set(5, new MpcObservation(lastN1.frameNumber(), lastN1.midpointMillis(), lastN1.exposureMillis(),
                0, 0, lastN1.raDegrees() - 1.5 / 3600.0, lastN1.decDegrees() + 0.5 / 3600.0, 0.5, 0.5, "Gaia3", ""));
        MpcObservation lastN2 = obs.get(11);
        obs.set(11, new MpcObservation(lastN2.frameNumber(), lastN2.midpointMillis(), lastN2.exposureMillis(),
                0, 0, lastN2.raDegrees() + 2.0 / 3600.0, lastN2.decDegrees() + 0.8 / 3600.0, 0.5, 0.5, "Gaia3", ""));

        var solution = OrbitDetermination.fit(obs, SITE);
        var fit = solution.orbit();
        assertTrue("Semi-major axis stays in main belt (not runaway >4 AU), got " + fit.a(), fit.a() > 2.2 && fit.a() < 4.0);
        assertTrue("Eccentricity stays realistic (not runaway >0.5), got " + fit.e(), fit.e() < 0.48);
        assertTrue("Main-belt P(NEO) stays low, got " + fit.neoProbability(), fit.neoProbability() < 10.0);
        assertTrue("3D uncertainty cloud populated for weak 2-night arc", solution.sampleOrbits().size() >= 6);
    }

    /** Rejects fewer than three observations cleanly. */
    @Test
    public void rejectsTooFewObservations() {
        try {
            OrbitDetermination.fit(List.of(), SITE);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("At least three"));
        }
    }

    private static List<MpcObservation> synthesize(FindOrb.Orbit truth, ObservingSite site, double[] dayOffsets, double noiseArcsec) {
        Random rng = new Random(99L);
        List<MpcObservation> obs = new ArrayList<>(dayOffsets.length);
        for (int k = 0; k < dayOffsets.length; k++) {
            double jdUtc = truth.epochJd() + dayOffsets[k];
            double jdTt = EarthEphemeris.utcToTt(jdUtc);
            double[] rObs = EarthEphemeris.observerPositionJ2000(jdUtc, jdTt, site);
            double lt = 0.0;
            double[] rho = null;
            for (int it = 0; it < 3; it++) {
                double[] pos = OrbitGeometry.positionAt(truth, jdTt - lt);
                rho = new double[]{pos[0] - rObs[0], pos[1] - rObs[1], pos[2] - rObs[2]};
                lt = norm(rho) / EarthEphemeris.SPEED_OF_LIGHT_AU_PER_DAY;
            }
            double[] eq = EarthEphemeris.eclipticToEquatorial(rho);
            double r = norm(eq);
            double ra = Math.toDegrees(Math.atan2(eq[1], eq[0]));
            if (ra < 0) ra += 360.0;
            double dec = Math.toDegrees(Math.asin(eq[2] / r));
            ra += (rng.nextGaussian() * noiseArcsec / 3600.0) / Math.cos(Math.toRadians(dec));
            dec += rng.nextGaussian() * noiseArcsec / 3600.0;
            long ms = OrbitGeometry.jdToInstant(jdUtc).toEpochMilli();
            obs.add(new MpcObservation(k + 1, ms, 30_000, 0, 0, ra, dec, noiseArcsec, noiseArcsec, "Gaia3", ""));
        }
        return obs;
    }

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
