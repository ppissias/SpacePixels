package eu.startales.spacepixels.mpc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Heliocentric 3D positions and orbital mechanics in the J2000 ecliptic, in AU, for visualising and animating fitted
 * orbits alongside the planets (a visual model, not a precision ephemeris). Supports elliptical, parabolic and
 * hyperbolic orbits, 3D inclined planetary orbits from Mercury to Neptune, node/apsis geometry, and live viewing
 * telemetry.
 */
public final class OrbitGeometry {
    /** Mean distances of Mercury, Venus, Earth, Mars and Jupiter from the Sun, in AU (legacy 2D subset). */
    public static final double[] PLANET_DISTANCES = {0.387, 0.723, 1.0, 1.524, 5.203};
    /** Names of the inner-to-Jupiter planet subset matching {@link #PLANET_DISTANCES}. */
    public static final String[] PLANET_NAMES = {"Mercury", "Venus", "Earth", "Mars", "Jupiter"};
    /** All eight major planets, in order from Mercury (0) to Neptune (7). */
    public static final String[] ALL_PLANET_NAMES = {
            "Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"
    };
    /** Index of Earth in {@link #ALL_PLANET_NAMES}. */
    public static final int EARTH_INDEX = 2;
    /** Index of Saturn in {@link #ALL_PLANET_NAMES}. */
    public static final int SATURN_INDEX = 5;
    /** Number of planets supported by the 3D planetary ephemeris. */
    public static final int PLANET_COUNT = ALL_PLANET_NAMES.length;
    /** Astronomical units per mean Earth–Moon distance (1 LD ≈ 384,400 km). */
    public static final double AU_PER_LUNAR_DISTANCE = 0.00256955529;
    /** Kilometres per Astronomical Unit (IAU 2012 exact definition). */
    public static final double KM_PER_AU = 149_597_870.7;

    /** Gaussian gravitational constant in radians per day. */
    private static final double GAUSS_K = 0.01720209895;
    /** J2000.0 epoch (2000-01-01T12:00:00 TT/UTC), in Julian Days. */
    public static final double J2000 = 2_451_545.0;
    /** Unix epoch (1970-01-01T00:00:00Z), in Julian Days. */
    private static final double UNIX_EPOCH_JD = 2_440_587.5;
    /** Days per Julian century. */
    private static final double DAYS_PER_CENTURY = 36_525.0;
    /** Threshold separating near-parabolic orbits from elliptical and hyperbolic orbits. */
    private static final double PARABOLIC_EPSILON = 1e-7;

    /** Mean longitude at J2000 and daily motion of Mercury–Jupiter, in degrees (legacy circular model). */
    private static final double[] LONGITUDE_J2000 = {252.251, 181.980, 100.464, 355.453, 34.405};
    private static final double[] DAILY_MOTION = {4.0923388, 1.6021302, 0.9856474, 0.5240208, 0.0830853};

    /**
     * Approximate J2000 Keplerian elements and centurial rates for Mercury–Neptune (JPL Standish 1992 Table 1,
     * valid 1800–2050 AD). Angles in degrees, semi-major axis in AU.
     */
    private record PlanetElements(double a0, double aRate, double e0, double eRate,
                                  double i0, double iRate, double l0, double lRate,
                                  double longPeri0, double longPeriRate,
                                  double longNode0, double longNodeRate) { }

    private static final PlanetElements[] PLANETS = {
            // Mercury
            new PlanetElements(0.38709927, 0.00000037, 0.20563593, 0.00001906,
                    7.00497902, -0.00594749, 252.25032350, 149472.67411175,
                    77.45779628, 0.16047689, 48.33076593, -0.12534081),
            // Venus
            new PlanetElements(0.72333566, 0.00000390, 0.00677672, -0.00004107,
                    3.39467605, -0.00078890, 181.97909950, 58517.81538729,
                    131.60246718, 0.00268329, 76.67984255, -0.27769418),
            // Earth-Moon Barycenter
            new PlanetElements(1.00000261, 0.00000562, 0.01671123, -0.00004392,
                    -0.00001531, -0.01294668, 100.46457166, 35999.37244981,
                    102.93768193, 0.32327364, 0.0, 0.0),
            // Mars
            new PlanetElements(1.52371034, 0.00001847, 0.09339410, 0.00007882,
                    1.84969142, -0.00813131, -4.55343205, 19140.30268499,
                    -23.94362959, 0.44441088, 49.55953891, -0.29257343),
            // Jupiter
            new PlanetElements(5.20288700, -0.00011607, 0.04838624, -0.00013253,
                    1.30439695, -0.00183714, 34.39644051, 3034.74612775,
                    14.72847983, 0.21252668, 100.47390909, 0.20469106),
            // Saturn
            new PlanetElements(9.53667594, -0.00125060, 0.05386179, -0.00050991,
                    2.48599187, 0.00193609, 49.95424423, 1222.49362201,
                    92.59887831, -0.41897216, 113.66242448, -0.28867794),
            // Uranus
            new PlanetElements(19.18916464, -0.00196176, 0.04725744, -0.00004397,
                    0.77263783, -0.00242939, 313.23810451, 428.48202785,
                    170.95427630, 0.40805281, 74.01692503, 0.04240589),
            // Neptune
            new PlanetElements(30.06992276, 0.00026291, 0.00859048, 0.00005105,
                    1.77004347, 0.00035372, -55.12002969, 218.45945325,
                    44.96476227, -0.32241464, 131.78422574, -0.00508664)
    };

    /**
     * Live viewing geometry for an object at a given instant: heliocentric distance {@code heliocentricAu}, Earth
     * distance {@code geocentricAu}, solar elongation and phase angle in degrees, and optional 3D separation in AU
     * against a comparison (single-night) orbit at the same instant.
     */
    public record Telemetry(double heliocentricAu, double geocentricAu, double solarElongationDeg,
                            double phaseAngleDeg, Double comparisonSeparationAu) { }

    private OrbitGeometry() { }

    /** A planet's approximate position {x, y} at a Julian date, on its mean circular orbit (Mercury–Jupiter). */
    public static double[] planetAt(int planet, double julianDate) {
        double longitude = Math.toRadians(LONGITUDE_J2000[planet] + DAILY_MOTION[planet] * (julianDate - J2000));
        return new double[]{PLANET_DISTANCES[planet] * Math.cos(longitude), PLANET_DISTANCES[planet] * Math.sin(longitude)};
    }

    /** The semi-major axis of planet {@code planet} (0 = Mercury .. 7 = Neptune) at J2000, in AU. */
    public static double planetSemiMajorAxis(int planet) {
        return PLANETS[planet].a0();
    }

    /**
     * A planet's approximate 3D heliocentric position {x, y, z} in J2000 ecliptic AU at {@code julianDate},
     * for {@code planet} in 0..7 (Mercury through Neptune).
     */
    public static double[] planetPosition3D(int planet, double julianDate) {
        PlanetElements p = PLANETS[planet];
        double t = (julianDate - J2000) / DAYS_PER_CENTURY;
        double a = p.a0() + p.aRate() * t;
        double e = Math.max(0.0, Math.min(0.99, p.e0() + p.eRate() * t));
        double inc = Math.toRadians(p.i0() + p.iRate() * t);
        double l = p.l0() + p.lRate() * t;
        double longPeri = p.longPeri0() + p.longPeriRate() * t;
        double node = Math.toRadians(p.longNode0() + p.longNodeRate() * t);
        double argPeri = Math.toRadians(longPeri - (p.longNode0() + p.longNodeRate() * t));
        double meanAnomaly = normalizeRadians(Math.toRadians(l - longPeri));
        double ecc = solveEllipticKepler(meanAnomaly, e);
        double nu = trueAnomalyFromEccentric(ecc, e);
        return heliocentricPosition(a * (1.0 - e), e, inc, node, argPeri, nu);
    }

    /**
     * {@code points} 3D heliocentric positions {x, y, z} tracing the full elliptical orbit of planet {@code planet}
     * (0..7) using its osculating elements at {@code julianDate}.
     */
    public static double[][] planetOrbitPath3D(int planet, double julianDate, int points) {
        PlanetElements p = PLANETS[planet];
        double t = (julianDate - J2000) / DAYS_PER_CENTURY;
        double a = p.a0() + p.aRate() * t;
        double e = Math.max(0.0, Math.min(0.99, p.e0() + p.eRate() * t));
        double inc = Math.toRadians(p.i0() + p.iRate() * t);
        double longPeri = p.longPeri0() + p.longPeriRate() * t;
        double node = Math.toRadians(p.longNode0() + p.longNodeRate() * t);
        double argPeri = Math.toRadians(longPeri - (p.longNode0() + p.longNodeRate() * t));
        double q = a * (1.0 - e);
        double[][] ring = new double[points][];
        for (int k = 0; k < points; k++) {
            double ecc = -Math.PI + 2.0 * Math.PI * k / points;
            double nu = trueAnomalyFromEccentric(ecc, e);
            ring[k] = heliocentricPosition(q, e, inc, node, argPeri, nu);
        }
        return ring;
    }

    /**
     * {@code points} positions {x, y, z} around the orbit: the whole ellipse (sampled smoothly in eccentric anomaly
     * so high-eccentricity comets and NEOs remain well resolved near aphelion), or for an unbound orbit the part
     * within {@code reach} AU of the Sun.
     */
    public static double[][] path(FindOrb.Orbit orbit, int points, double reach) {
        double[][] path = new double[points][];
        if (orbit.bound()) {
            for (int k = 0; k < points; k++) {
                double ecc = -Math.PI + 2.0 * Math.PI * k / points;
                double nu = trueAnomalyFromEccentric(ecc, orbit.e());
                path[k] = position(orbit, nu);
            }
            return path;
        }
        double safeReach = Math.max(reach, orbit.q() * 1.05);
        double cosLimit = (orbit.q() * (1.0 + orbit.e()) / safeReach - 1.0) / orbit.e();
        double maxTrueAnomaly = Math.acos(Math.max(-1.0, Math.min(1.0, cosLimit)));
        if (orbit.e() > 1.0) {
            maxTrueAnomaly = Math.min(maxTrueAnomaly, Math.acos(-1.0 / orbit.e()) - 1e-5);
        } else {
            maxTrueAnomaly = Math.min(maxTrueAnomaly, Math.PI - 1e-4);
        }
        // Sample smoothly in tan(nu/2) so perihelion curvature and outer legs are evenly resolved.
        double maxTanHalf = Math.tan(maxTrueAnomaly / 2.0);
        for (int k = 0; k < points; k++) {
            double frac = -1.0 + 2.0 * k / (points - 1);
            double nu = 2.0 * Math.atan(frac * maxTanHalf);
            path[k] = position(orbit, nu);
        }
        return path;
    }

    /**
     * Orbital period in days for a bound elliptical orbit, or {@code Double.NaN} for parabolic/hyperbolic orbits.
     */
    public static double orbitalPeriodDays(FindOrb.Orbit orbit) {
        if (orbit == null || !orbit.bound() || !Double.isFinite(orbit.a()) || orbit.a() <= 0) return Double.NaN;
        return 2.0 * Math.PI * Math.pow(orbit.a(), 1.5) / GAUSS_K;
    }

    /** The object's position {x, y, z} at the elements' epoch, from the mean anomaly; null for an unbound orbit. */
    public static double[] atEpoch(FindOrb.Orbit orbit) {
        if (!orbit.bound()) return null;
        return positionAt(orbit, orbit.epochJd());
    }

    /**
     * Propagates the orbit to {@code julianDate} using two-body Keplerian motion and returns its heliocentric 3D
     * position {x, y, z} in J2000 ecliptic AU, or null if the orbit cannot be propagated (e.g., an unbound orbit
     * without a perihelion passage time {@code Tp}).
     */
    public static double[] positionAt(FindOrb.Orbit orbit, double julianDate) {
        if (orbit == null || !Double.isFinite(julianDate) || !Double.isFinite(orbit.e()) || !Double.isFinite(orbit.q())
                || orbit.q() <= 0) {
            return null;
        }
        double e = orbit.e();
        if (e < 1.0 - PARABOLIC_EPSILON && Double.isFinite(orbit.a()) && orbit.a() > 0) {
            double meanMotion = GAUSS_K / Math.pow(orbit.a(), 1.5);
            double m;
            if (Double.isFinite(orbit.meanAnomaly()) && Double.isFinite(orbit.epochJd())) {
                m = Math.toRadians(orbit.meanAnomaly()) + meanMotion * (julianDate - orbit.epochJd());
            } else if (orbit.perihelionJd() != null && Double.isFinite(orbit.perihelionJd())) {
                m = meanMotion * (julianDate - orbit.perihelionJd());
            } else {
                return null;
            }
            double ecc = solveEllipticKepler(normalizeRadians(m), e);
            return position(orbit, trueAnomalyFromEccentric(ecc, e));
        }
        // Parabolic or hyperbolic orbit requires a known perihelion passage time Tp.
        Double tp = orbit.perihelionJd();
        if (tp == null || !Double.isFinite(tp)) return null;
        double dt = julianDate - tp;
        if (Math.abs(e - 1.0) <= PARABOLIC_EPSILON) {
            // Barker's equation: D + D^3/3 = W
            double w = GAUSS_K * dt / (Math.sqrt(2.0) * Math.pow(orbit.q(), 1.5));
            double halfThreeW = 1.5 * w;
            double y = Math.cbrt(halfThreeW + Math.sqrt(1.0 + halfThreeW * halfThreeW));
            double d = y - 1.0 / y;
            return position(orbit, 2.0 * Math.atan(d));
        }
        // Hyperbolic orbit: e * sinh(H) - H = M_h
        double aAbs = orbit.q() / (e - 1.0);
        double mh = GAUSS_K * dt / Math.pow(aAbs, 1.5);
        double h = solveHyperbolicKepler(mh, e);
        double nu = 2.0 * Math.atan2(Math.sqrt(e + 1.0) * Math.sinh(h / 2.0), Math.sqrt(e - 1.0) * Math.cosh(h / 2.0));
        return position(orbit, nu);
    }

    /**
     * Generates a trailing arc of {@code samples} 3D positions ending at {@code julianDate} and going back by
     * {@code spanDays} days, for rendering a directional motion trail during animation.
     */
    public static double[][] trailBefore(FindOrb.Orbit orbit, double julianDate, double spanDays, int samples) {
        if (orbit == null || samples < 2 || spanDays <= 0) return new double[0][];
        double[] now = positionAt(orbit, julianDate);
        if (now == null) return new double[0][];
        double[][] pts = new double[samples][];
        for (int k = 0; k < samples; k++) {
            double t = julianDate - spanDays * (samples - 1 - k) / (samples - 1);
            double[] p = positionAt(orbit, t);
            if (p == null) return new double[0][];
            pts[k] = p;
        }
        return pts;
    }

    /** Perihelion 3D position {x, y, z} in AU, or null when the elements are incomplete. */
    public static double[] perihelion(FindOrb.Orbit orbit) {
        if (orbit == null || !Double.isFinite(orbit.q()) || orbit.q() <= 0) return null;
        return position(orbit, 0.0);
    }

    /** Aphelion 3D position {x, y, z} in AU for a bound orbit, or null for an unbound orbit. */
    public static double[] aphelion(FindOrb.Orbit orbit) {
        if (orbit == null || !orbit.bound()) return null;
        return position(orbit, Math.PI);
    }

    /**
     * Ascending node 3D position {x, y, z} (where the orbit crosses the ecliptic heading north, {@code z = 0}),
     * or null if the node is beyond {@code reach} AU or the orbit is unbound on that side.
     */
    public static double[] ascendingNodePosition(FindOrb.Orbit orbit, double reach) {
        return nodePosition(orbit, -Math.toRadians(orbit.argumentOfPerihelion()), reach);
    }

    /**
     * Descending node 3D position {x, y, z} (where the orbit crosses the ecliptic heading south, {@code z = 0}),
     * or null if the node is beyond {@code reach} AU or the orbit is unbound on that side.
     */
    public static double[] descendingNodePosition(FindOrb.Orbit orbit, double reach) {
        return nodePosition(orbit, Math.PI - Math.toRadians(orbit.argumentOfPerihelion()), reach);
    }

    private static double[] nodePosition(FindOrb.Orbit orbit, double trueAnomaly, double reach) {
        if (orbit == null || !Double.isFinite(orbit.q()) || !Double.isFinite(orbit.e())) return null;
        double denom = 1.0 + orbit.e() * Math.cos(trueAnomaly);
        if (denom <= 1e-6) return null;
        double r = orbit.q() * (1.0 + orbit.e()) / denom;
        if (!Double.isFinite(r) || r <= 0 || r > reach) return null;
        return position(orbit, trueAnomaly);
    }

    /**
     * Groups an orbit's used observation residuals into observing nights (gaps longer than 10.8 hours start a new
     * night) and returns the midpoint Julian Date of each night, in chronological order.
     */
    public static double[] observationNightJds(FindOrb.Orbit orbit) {
        if (orbit == null || orbit.residuals() == null || orbit.residuals().isEmpty()) return new double[0];
        List<Double> jds = new ArrayList<>();
        for (var r : orbit.residuals()) {
            if (!r.used() || r.isoTime() == null) continue;
            try {
                jds.add(instantToJd(Instant.parse(r.isoTime())));
            } catch (RuntimeException ignored) {
                // Ignore unparseable timestamps.
            }
        }
        if (jds.isEmpty()) return new double[0];
        jds.sort(Double::compare);
        List<Double> midpoints = new ArrayList<>();
        double start = jds.getFirst(), end = start;
        for (int k = 1; k < jds.size(); k++) {
            double jd = jds.get(k);
            if (jd - end > 0.45) {
                midpoints.add(0.5 * (start + end));
                start = jd;
            }
            end = jd;
        }
        midpoints.add(0.5 * (start + end));
        return midpoints.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * Computes live heliocentric/geocentric distances, solar elongation, phase angle and optional comparison-orbit
     * separation for the given 3D heliocentric positions (all in AU). Returns null if {@code objectPos} or
     * {@code earthPos} is null.
     */
    public static Telemetry telemetry(double[] objectPos, double[] earthPos, double[] comparisonPos) {
        if (objectPos == null || earthPos == null) return null;
        double r = norm(objectPos);
        double dx = objectPos[0] - earthPos[0];
        double dy = objectPos[1] - earthPos[1];
        double dz = objectPos[2] - earthPos[2];
        double delta = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double rEarth = norm(earthPos);
        // Solar elongation: angle at Earth between Sun (-earthPos) and Object (objectPos - earthPos).
        double cosElong = (rEarth > 0 && delta > 0)
                ? (-earthPos[0] * dx - earthPos[1] * dy - earthPos[2] * dz) / (rEarth * delta)
                : 0.0;
        double elongDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosElong))));
        // Phase angle: angle at Object between Sun (-objectPos) and Earth (earthPos - objectPos).
        double cosPhase = (r > 0 && delta > 0)
                ? (objectPos[0] * dx + objectPos[1] * dy + objectPos[2] * dz) / (r * delta)
                : 0.0;
        double phaseDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosPhase))));
        Double sep = null;
        if (comparisonPos != null) {
            double sx = objectPos[0] - comparisonPos[0];
            double sy = objectPos[1] - comparisonPos[1];
            double sz = objectPos[2] - comparisonPos[2];
            sep = Math.sqrt(sx * sx + sy * sy + sz * sz);
        }
        return new Telemetry(r, delta, elongDeg, phaseDeg, sep);
    }

    /**
     * A concise dynamical classification of the fitted elements (e.g., Apollo NEO, Main-belt, Comet-like, Hyperbolic).
     * Intended as a visual label alongside the preliminary elements.
     */
    public static String orbitClass(FindOrb.Orbit orbit) {
        if (orbit == null || !Double.isFinite(orbit.e()) || !Double.isFinite(orbit.q())) return "Preliminary";
        double e = orbit.e(), q = orbit.q(), a = orbit.a();
        if (e > 1.001) return "Hyperbolic";
        if (e >= 0.999 || !orbit.bound()) return "Near-parabolic";
        double aphelion = a * (1.0 + e);
        if (q <= 1.3) {
            if (a < 1.0) return aphelion < 0.983 ? "Atira NEO" : "Aten NEO";
            return q <= 1.017 ? "Apollo NEO" : "Amor NEO";
        }
        if (e >= 0.5 && aphelion > 5.0) return "Comet-like";
        if (q <= 1.666 && a < 3.2) return "Mars-crosser";
        if (a >= 1.8 && a <= 3.5 && e < 0.45) return "Main-belt";
        if (a > 4.8 && a < 5.5 && e < 0.3) return "Jupiter-region";
        if (a >= 30.1) return "Trans-Neptunian";
        if (a > 5.5) return "Centaur / Outer";
        return "Inner Solar System";
    }

    /** Whether the fitted elements place the object in the Near-Earth Object zone ({@code q <= 1.3 AU}). */
    public static boolean isNeo(FindOrb.Orbit orbit) {
        return orbit != null && Double.isFinite(orbit.q()) && orbit.q() <= 1.3;
    }

    /** Converts a UTC {@link Instant} to a Julian Date. */
    public static double instantToJd(Instant instant) {
        return UNIX_EPOCH_JD + instant.toEpochMilli() / 86_400_000.0;
    }

    /** Converts a Julian Date to a UTC {@link Instant}. */
    public static Instant jdToInstant(double jd) {
        return Instant.ofEpochMilli(Math.round((jd - UNIX_EPOCH_JD) * 86_400_000.0));
    }

    /** The position {x, y, z} at a true anomaly, from the perihelion distance, which serves ellipse and hyperbola alike. */
    static double[] position(FindOrb.Orbit orbit, double trueAnomaly) {
        return heliocentricPosition(orbit.q(), orbit.e(), Math.toRadians(orbit.i()),
                Math.toRadians(orbit.ascendingNode()), Math.toRadians(orbit.argumentOfPerihelion()), trueAnomaly);
    }

    private static double[] heliocentricPosition(double q, double e, double inclination,
                                                 double node, double argPeri, double trueAnomaly) {
        double r = q * (1.0 + e) / (1.0 + e * Math.cos(trueAnomaly));
        double u = argPeri + trueAnomaly;
        double cosNode = Math.cos(node), sinNode = Math.sin(node);
        double cosU = Math.cos(u), sinU = Math.sin(u);
        double cosI = Math.cos(inclination), sinI = Math.sin(inclination);
        return new double[]{
                r * (cosNode * cosU - sinNode * sinU * cosI),
                r * (sinNode * cosU + cosNode * sinU * cosI),
                r * sinU * sinI
        };
    }

    /** Solves M = E - e sin(E) for E in [-pi, pi] using Halley's method, robust up to high eccentricities. */
    private static double solveEllipticKepler(double m, double e) {
        if (e < 1e-12) return m;
        double ecc = e < 0.8 ? m + e * Math.sin(m) : (m < 0 ? -Math.PI : Math.PI);
        if (e >= 0.8 && Math.abs(m) < 0.5) {
            ecc = m + Math.copySign(0.85 * e, m);
        }
        for (int k = 0; k < 40; k++) {
            double sinE = Math.sin(ecc), cosE = Math.cos(ecc);
            double f = ecc - e * sinE - m;
            if (Math.abs(f) < 1e-13) break;
            double fp = 1.0 - e * cosE;
            double fpp = e * sinE;
            double denom = fp - 0.5 * f * fpp / fp;
            double delta = Math.abs(denom) > 1e-12 ? f / denom : f / Math.max(1e-12, fp);
            ecc -= delta;
            if (Math.abs(delta) < 1e-13) break;
        }
        return ecc;
    }

    /** Solves M_h = e sinh(H) - H for hyperbolic eccentric anomaly H. */
    private static double solveHyperbolicKepler(double mh, double e) {
        if (Math.abs(mh) < 1e-14) return 0.0;
        double h = Math.copySign(Math.log(2.0 * Math.abs(mh) / e + 1.8), mh);
        for (int k = 0; k < 50; k++) {
            double sinhH = Math.sinh(h), coshH = Math.cosh(h);
            double f = e * sinhH - h - mh;
            if (Math.abs(f) < 1e-13) break;
            double fp = e * coshH - 1.0;
            double fpp = e * sinhH;
            double denom = fp - 0.5 * f * fpp / fp;
            double delta = Math.abs(denom) > 1e-12 ? f / denom : f / Math.max(1e-12, fp);
            h -= delta;
            if (Math.abs(delta) < 1e-13) break;
        }
        return h;
    }

    private static double trueAnomalyFromEccentric(double ecc, double e) {
        return 2.0 * Math.atan2(Math.sqrt(1.0 + e) * Math.sin(ecc / 2.0), Math.sqrt(1.0 - e) * Math.cos(ecc / 2.0));
    }

    private static double normalizeRadians(double angle) {
        double wrapped = (angle + Math.PI) % (2.0 * Math.PI);
        if (wrapped < 0) wrapped += 2.0 * Math.PI;
        return wrapped - Math.PI;
    }

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
