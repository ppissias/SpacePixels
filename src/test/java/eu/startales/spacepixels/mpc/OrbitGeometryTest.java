package eu.startales.spacepixels.mpc;

import org.junit.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for 3D orbital mechanics, Keplerian time propagation (elliptic, parabolic, hyperbolic), planetary 3D
 * ephemerides, nodes, observation-night grouping, classification and live telemetry in {@link OrbitGeometry}.
 */
public class OrbitGeometryTest {

    /** A bound elliptical orbit returns to its exact epoch position after one full orbital period. */
    @Test
    public void ellipticalPropagationCompletesFullPeriod() {
        var neo = new FindOrb.Orbit("2026-03-10T00:00:00Z", 2461109.5, 1.0876642, null, 0.6733024, null,
                29.518276, null, 298.65056, null, 44.770727, null, 97.203032, null, 0.3553373, null,
                null, 0.79, 6, 97.9, 0.0698, 12.5, List.of(), null, 2460997.628877);
        double period = OrbitGeometry.orbitalPeriodDays(neo);
        assertEquals(414.32, period, 0.15);

        double[] atEpoch = OrbitGeometry.atEpoch(neo);
        double[] atEpochByTime = OrbitGeometry.positionAt(neo, neo.epochJd());
        double[] afterOnePeriod = OrbitGeometry.positionAt(neo, neo.epochJd() + period);
        assertNotNull(atEpoch);
        assertNotNull(atEpochByTime);
        assertNotNull(afterOnePeriod);
        for (int axis = 0; axis < 3; axis++) {
            assertEquals(atEpoch[axis], atEpochByTime[axis], 1e-12);
            assertEquals(atEpoch[axis], afterOnePeriod[axis], 1e-10);
        }

        // At perihelion passage Tp, heliocentric distance must equal q.
        double[] atPeri = OrbitGeometry.positionAt(neo, neo.perihelionJd());
        assertNotNull(atPeri);
        assertEquals(neo.q(), norm(atPeri), 1e-3);
    }

    /** High-eccentricity comets (e = 0.96) solve Kepler's equation cleanly at perihelion and aphelion. */
    @Test
    public void highEccentricityCometConvergesCleanly() {
        double a = 15.0, e = 0.96, q = a * (1.0 - e);
        var comet = new FindOrb.Orbit("2026-01-01T00:00:00Z", 2461041.5, a, null, e, null,
                42.0, null, 120.0, null, 60.0, null, 0.0, null, q, null,
                null, null, 0, null, null, null, List.of(), null, 2461041.5);
        double period = OrbitGeometry.orbitalPeriodDays(comet);
        double[] peri = OrbitGeometry.positionAt(comet, comet.epochJd());
        double[] aph = OrbitGeometry.positionAt(comet, comet.epochJd() + 0.5 * period);
        assertNotNull(peri);
        assertNotNull(aph);
        assertEquals(q, norm(peri), 1e-10);
        assertEquals(a * (1.0 + e), norm(aph), 1e-10);
    }

    /** Parabolic and hyperbolic orbits propagate symmetrically around Tp, and return null without Tp. */
    @Test
    public void parabolicAndHyperbolicPropagation() {
        double tp = 2461200.0;
        var parabolic = new FindOrb.Orbit("2026-06-08T12:00:00Z", tp, Double.NaN, null, 1.0, null,
                35.0, null, 45.0, null, 90.0, null, Double.NaN, null, 0.85, null,
                null, null, 0, null, null, null, List.of(), null, tp);
        assertTrue(Double.isNaN(OrbitGeometry.orbitalPeriodDays(parabolic)));
        double[] p0 = OrbitGeometry.positionAt(parabolic, tp);
        double[] pBefore = OrbitGeometry.positionAt(parabolic, tp - 60.0);
        double[] pAfter = OrbitGeometry.positionAt(parabolic, tp + 60.0);
        assertNotNull(p0);
        assertEquals(0.85, norm(p0), 1e-10);
        assertEquals("Symmetric radii before and after perihelion", norm(pBefore), norm(pAfter), 1e-10);
        assertTrue(norm(pAfter) > 0.85);

        var hyperbolic = new FindOrb.Orbit("2026-06-08T12:00:00Z", tp, -2.5, null, 1.4, null,
                22.0, null, 10.0, null, 30.0, null, Double.NaN, null, 1.0, null,
                null, null, 0, null, null, null, List.of(), null, tp);
        double[] h0 = OrbitGeometry.positionAt(hyperbolic, tp);
        double[] hBefore = OrbitGeometry.positionAt(hyperbolic, tp - 90.0);
        double[] hAfter = OrbitGeometry.positionAt(hyperbolic, tp + 90.0);
        assertNotNull(h0);
        assertEquals(1.0, norm(h0), 1e-10);
        assertEquals(norm(hBefore), norm(hAfter), 1e-10);
        assertTrue(norm(hAfter) > 1.5);

        var noTp = new FindOrb.Orbit("2026-06-08T12:00:00Z", tp, -2.5, null, 1.4, null,
                22.0, null, 10.0, null, 30.0, null, Double.NaN, null, 1.0, null,
                null, null, 0, null, null, null, List.of(), null, null);
        assertNull(OrbitGeometry.positionAt(noTp, tp));
    }

    /** All eight planets have realistic 3D heliocentric distances and inclinations. */
    @Test
    public void planetsIn3DHaveRealisticDistancesAndInclinations() {
        double[] earth = OrbitGeometry.planetPosition3D(OrbitGeometry.EARTH_INDEX, OrbitGeometry.J2000);
        assertEquals(1.0, norm(earth), 0.02);
        assertEquals("Earth stays in the ecliptic plane", 0.0, earth[2], 1e-3);
        double earthLon = Math.toDegrees(Math.atan2(earth[1], earth[0]));
        assertEquals(100.46, earthLon, 2.5);

        double[][] mercuryOrbit = OrbitGeometry.planetOrbitPath3D(0, OrbitGeometry.J2000, 96);
        double maxMercuryZ = 0;
        for (double[] p : mercuryOrbit) maxMercuryZ = Math.max(maxMercuryZ, Math.abs(p[2]));
        assertTrue("Mercury's 7 deg inclination produces vertical excursion", maxMercuryZ > 0.04);

        double[] neptune = OrbitGeometry.planetPosition3D(7, OrbitGeometry.J2000);
        assertEquals(30.07, norm(neptune), 0.6);
    }

    /** Ascending and descending nodes sit on the ecliptic plane (z = 0) at longitudes Omega and Omega + 180 deg. */
    @Test
    public void nodesLieOnEclipticAtExpectedLongitudes() {
        var orbit = new FindOrb.Orbit("", OrbitGeometry.J2000, 2.5, null, 0.25, null,
                18.0, null, 40.0, null, 65.0, null, 0.0, null, 2.5 * 0.75, null,
                null, null, 0, null, null, null, List.of(), null);
        double[] asc = OrbitGeometry.ascendingNodePosition(orbit, 20.0);
        double[] desc = OrbitGeometry.descendingNodePosition(orbit, 20.0);
        assertNotNull(asc);
        assertNotNull(desc);
        assertEquals(0.0, asc[2], 1e-12);
        assertEquals(0.0, desc[2], 1e-12);
        assertEquals(40.0, Math.toDegrees(Math.atan2(asc[1], asc[0])), 1e-9);
        assertEquals(-140.0, Math.toDegrees(Math.atan2(desc[1], desc[0])), 1e-9);
    }

    /** Multi-night observations split cleanly into chronological night midpoints, ignoring unused residuals. */
    @Test
    public void observationNightGroupingSplitsLinkedNights() {
        var residuals = List.of(
                new FindOrb.Residual("2026-11-10T21:00:00Z", 0.1, -0.1, true),
                new FindOrb.Residual("2026-11-10T22:00:00Z", -0.1, 0.1, true),
                new FindOrb.Residual("2026-11-12T20:00:00Z", 0.2, 0.0, true),
                new FindOrb.Residual("2026-11-12T22:00:00Z", 0.0, 0.2, true),
                new FindOrb.Residual("2026-11-15T21:00:00Z", 5.0, 5.0, false)
        );
        var orbit = new FindOrb.Orbit("", OrbitGeometry.J2000, 2.2, null, 0.1, null, 5, null, 0, null, 0, null,
                0, null, 1.98, null, null, null, 4, null, null, null, residuals, null);
        double[] nights = OrbitGeometry.observationNightJds(orbit);
        assertEquals(2, nights.length);
        assertEquals(Instant.parse("2026-11-10T21:30:00Z"), OrbitGeometry.jdToInstant(nights[0]));
        assertEquals(Instant.parse("2026-11-12T21:00:00Z"), OrbitGeometry.jdToInstant(nights[1]));
    }

    /** Telemetry reports opposition geometry, distances, and single-night vs revised 3D separation. */
    @Test
    public void telemetryComputesOppositionAndComparisonSeparation() {
        double[] earth = {1.0, 0.0, 0.0};
        double[] objectAtOpposition = {2.5, 0.0, 0.0};
        double[] singleNight = {2.5, 0.3, 0.4};
        var tel = OrbitGeometry.telemetry(objectAtOpposition, earth, singleNight);
        assertNotNull(tel);
        assertEquals(2.5, tel.heliocentricAu(), 1e-12);
        assertEquals(1.5, tel.geocentricAu(), 1e-12);
        assertEquals(180.0, tel.solarElongationDeg(), 1e-9);
        assertEquals(0.0, tel.phaseAngleDeg(), 1e-9);
        assertEquals(0.5, tel.comparisonSeparationAu(), 1e-12);
    }

    /** Orbit classes accurately distinguish NEO subtypes, main-belt asteroids, comets, TNOs and hyperbolas. */
    @Test
    public void classifiesNeosMainBeltAndOuterOrbits() {
        var apollo = stubOrbit(1.09, 0.67, 0.36);
        assertEquals("Apollo NEO", OrbitGeometry.orbitClass(apollo));
        assertTrue(OrbitGeometry.isNeo(apollo));

        var aten = stubOrbit(0.85, 0.30, 0.595);
        assertEquals("Aten NEO", OrbitGeometry.orbitClass(aten));
        assertTrue(OrbitGeometry.isNeo(aten));

        var amor = stubOrbit(1.6, 0.25, 1.2);
        assertEquals("Amor NEO", OrbitGeometry.orbitClass(amor));
        assertTrue(OrbitGeometry.isNeo(amor));

        var mainBelt = stubOrbit(2.6, 0.12, 2.288);
        assertEquals("Main-belt", OrbitGeometry.orbitClass(mainBelt));
        assertFalse(OrbitGeometry.isNeo(mainBelt));

        var comet = stubOrbit(8.0, 0.80, 1.6);
        assertEquals("Comet-like", OrbitGeometry.orbitClass(comet));

        var tno = stubOrbit(39.5, 0.25, 29.625);
        assertEquals("Trans-Neptunian", OrbitGeometry.orbitClass(tno));

        var hyper = stubOrbit(-1.5, 1.3, 1.1);
        assertEquals("Hyperbolic", OrbitGeometry.orbitClass(hyper));
    }

    private static FindOrb.Orbit stubOrbit(double a, double e, double q) {
        return new FindOrb.Orbit("", OrbitGeometry.J2000, a, null, e, null, 10, null, 0, null, 0, null,
                0, null, q, null, null, null, 0, null, null, null, List.of(), null);
    }

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
