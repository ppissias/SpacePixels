package eu.startales.spacepixels.mpc;

/**
 * Heliocentric positions in the J2000 ecliptic, in AU, for drawing an orbit seen from above the ecliptic: points along a
 * fitted orbit (an ellipse, or the near part of a hyperbola), the object's place at the elements' epoch, and the
 * approximate places of the planets on circles (a picture of where things are, not an ephemeris).
 */
public final class OrbitGeometry {
    /** Mean distances of Mercury, Venus, Earth, Mars and Jupiter from the Sun, in AU. */
    public static final double[] PLANET_DISTANCES = {0.387, 0.723, 1.0, 1.524, 5.203};
    public static final String[] PLANET_NAMES = {"Mercury", "Venus", "Earth", "Mars", "Jupiter"};
    /** Mean longitude at J2000 and daily motion of the same planets, in degrees (mean circular orbits). */
    private static final double[] LONGITUDE_J2000 = {252.251, 181.980, 100.464, 355.453, 34.405};
    private static final double[] DAILY_MOTION = {4.0923388, 1.6021302, 0.9856474, 0.5240208, 0.0830853};
    private static final double J2000 = 2_451_545.0;

    private OrbitGeometry() { }

    /** A planet's approximate position {x, y} at a Julian date, on its mean circular orbit. */
    public static double[] planetAt(int planet, double julianDate) {
        double longitude = Math.toRadians(LONGITUDE_J2000[planet] + DAILY_MOTION[planet] * (julianDate - J2000));
        return new double[]{PLANET_DISTANCES[planet] * Math.cos(longitude), PLANET_DISTANCES[planet] * Math.sin(longitude)};
    }

    /**
     * {@code points} positions {x, y, z} around the orbit: the whole ellipse, or for an unbound orbit the part within
     * {@code reach} AU of the Sun.
     */
    public static double[][] path(FindOrb.Orbit orbit, int points, double reach) {
        double[][] path = new double[points][];
        double limit = orbit.bound() ? Math.PI : Math.min(Math.acos(-1 / orbit.e()) - 1e-6,
                Math.acos(Math.max(-1, Math.min(1, (orbit.q() * (1 + orbit.e()) / reach - 1) / orbit.e()))));
        for (int k = 0; k < points; k++) {
            double nu = -limit + 2 * limit * k / (points - 1);
            path[k] = position(orbit, nu);
        }
        return path;
    }

    /** The object's position {x, y, z} at the elements' epoch, from the mean anomaly; null for an unbound orbit. */
    public static double[] atEpoch(FindOrb.Orbit orbit) {
        if (!orbit.bound()) return null;
        double m = Math.toRadians(orbit.meanAnomaly());
        double eccentric = m;
        for (int k = 0; k < 50; k++) eccentric -= (eccentric - orbit.e() * Math.sin(eccentric) - m) / (1 - orbit.e() * Math.cos(eccentric));
        double nu = 2 * Math.atan2(Math.sqrt(1 + orbit.e()) * Math.sin(eccentric / 2), Math.sqrt(1 - orbit.e()) * Math.cos(eccentric / 2));
        return position(orbit, nu);
    }

    /** The position {x, y, z} at a true anomaly, from the perihelion distance, which serves ellipse and hyperbola alike. */
    static double[] position(FindOrb.Orbit orbit, double trueAnomaly) {
        double r = orbit.q() * (1 + orbit.e()) / (1 + orbit.e() * Math.cos(trueAnomaly));
        double node = Math.toRadians(orbit.ascendingNode()), inclination = Math.toRadians(orbit.i());
        double u = Math.toRadians(orbit.argumentOfPerihelion()) + trueAnomaly;
        return new double[]{
                r * (Math.cos(node) * Math.cos(u) - Math.sin(node) * Math.sin(u) * Math.cos(inclination)),
                r * (Math.sin(node) * Math.cos(u) + Math.cos(node) * Math.sin(u) * Math.cos(inclination)),
                r * Math.sin(u) * Math.sin(inclination)};
    }
}
