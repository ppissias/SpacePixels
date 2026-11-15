package eu.startales.spacepixels.mpc;

import java.util.Locale;

/**
 * Whether an object's sky motion is usual for a main-belt asteroid at its place and time, the "Check" rule for unusual
 * motion. A main-belt asteroid on a circular orbit between {@value #INNER_AU} and {@value #OUTER_AU} AU, seen along the
 * object's line of sight, moves at a rate and in a direction set by its distance and Earth's motion; an object much
 * faster may be near Earth, one much slower may be far beyond the main belt, and one moving well off the main-belt
 * direction may have a high inclination or be near Earth. The Sun's place comes from the Astronomical Almanac's
 * low-precision formula (about 0.01°), which is ample here.
 * <p>
 * This is a screening rule, not an orbit: real orbits are eccentric and inclined, one night's motion has errors, and
 * near the stationary points every main-belt asteroid slows and turns. {@link Assessment#note} says so.
 */
public final class MainBeltMotion {
    /** The main belt's inner and outer edges used, in AU. */
    public static final double INNER_AU = 2.1, OUTER_AU = 3.3;
    /** How much faster than the fastest main-belt case counts as unusual. */
    public static final double FAST_FACTOR = 1.5;
    /** How much slower than the slowest main-belt case counts as unusual. */
    public static final double SLOW_FACTOR = 0.5;
    /** Below this slowest main-belt rate (arcsec per hour) the field is near a stationary point and slowness says nothing. */
    public static final double STATIONARY_RATE = 10;
    /** Inclination allowed for main-belt orbits when judging the direction, in degrees. */
    public static final double INCLINATION_DEG = 20;
    /** Smallest direction tolerance, in degrees. */
    public static final double MIN_DIRECTION_TOLERANCE_DEG = 25;

    private static final double GAUSS_K = 0.01720209895;
    private static final double ARCSEC_PER_RADIAN = 206_264.806;

    /**
     * The main-belt range at a place and time: the slowest and fastest rates in arcseconds per hour, the position angles
     * (east of north, degrees) of the inner and outer edge, the direction tolerance in degrees, and the solar elongation.
     */
    public record Expected(double minRate, double maxRate, double innerAngle, double outerAngle, double tolerance, double elongation) { }

    /**
     * The verdict: whether the motion is unusual, a short reason when it is (else null), and a note for the report that
     * gives the expected range and the rule's limits.
     */
    public record Assessment(boolean unusual, String reason, String note, Expected expected) { }

    private MainBeltMotion() { }

    /** The main-belt range for an object seen at {@code raDeg, decDeg} at {@code utcMillis}. */
    public static Expected expected(double raDeg, double decDeg, long utcMillis) {
        double[] earth = earthPosition(utcMillis);
        double[] earthVelocity = earthVelocity(utcMillis);
        double obliquity = Math.toRadians(obliquityDeg(utcMillis));
        double[] u = equatorialToEcliptic(unit(raDeg, decDeg), obliquity);
        double minRate = Double.POSITIVE_INFINITY, maxRate = 0, smallestRelative = Double.POSITIVE_INFINITY;
        double innerAngle = Double.NaN, outerAngle = Double.NaN;
        int steps = 12;
        for (int k = 0; k <= steps; k++) {
            double r = INNER_AU + (OUTER_AU - INNER_AU) * k / steps;
            double[] m = motion(u, earth, earthVelocity, r, obliquity, raDeg, decDeg);
            minRate = Math.min(minRate, m[0]);
            maxRate = Math.max(maxRate, m[0]);
            smallestRelative = Math.min(smallestRelative, m[2]);
            if (k == 0) innerAngle = m[1];
            if (k == steps) outerAngle = m[1];
        }
        // An inclined orbit adds a velocity across the ecliptic of up to v sin i: the direction may turn by that much.
        double crossVelocity = GAUSS_K / Math.sqrt(INNER_AU) * Math.sin(Math.toRadians(INCLINATION_DEG));
        double tolerance = Math.max(MIN_DIRECTION_TOLERANCE_DEG, Math.toDegrees(Math.atan2(crossVelocity, smallestRelative)));
        double elongation = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, -dot(u, earth) / norm(earth)))));
        return new Expected(minRate, maxRate, innerAngle, outerAngle, Math.min(180, tolerance), elongation);
    }

    /**
     * Judges an object's measured motion: {@code speedArcsecPerHour} and {@code positionAngleDeg} (east of north) at
     * {@code raDeg, decDeg} and {@code utcMillis}.
     */
    public static Assessment assess(double raDeg, double decDeg, long utcMillis, double speedArcsecPerHour, double positionAngleDeg) {
        Expected e = expected(raDeg, decDeg, utcMillis);
        double span = signedDifference(e.outerAngle(), e.innerAngle());
        double low = span >= 0 ? e.innerAngle() : e.outerAngle(), high = (low + Math.abs(span)) % 360;
        String angles = Math.abs(span) < 1 ? String.format(Locale.US, "%.0f°", e.innerAngle())
                : String.format(Locale.US, "%.0f–%.0f°", low, high);
        String range = String.format(Locale.US, "a main-belt asteroid here (elongation %.0f°) would move %.0f–%.0f″/h toward PA %s ± %.0f°",
                e.elongation(), e.minRate(), e.maxRate(), angles, e.tolerance());
        String measured = String.format(Locale.US, "it moves %.1f″/h toward PA %.0f°", speedArcsecPerHour, positionAngleDeg);
        String reason = null;
        if (speedArcsecPerHour > FAST_FACTOR * e.maxRate()) {
            reason = "faster than a main-belt asteroid: possibly near Earth";
        } else if (e.minRate() >= STATIONARY_RATE && speedArcsecPerHour < SLOW_FACTOR * e.minRate()) {
            reason = "slower than a main-belt asteroid: possibly beyond the main belt (a Jupiter Trojan, Centaur or trans-Neptunian object)";
        } else if (Double.isFinite(positionAngleDeg) && directionOff(positionAngleDeg, e) > e.tolerance()) {
            reason = String.format(Locale.US, "its direction is %.0f° off the main-belt direction: possibly a high inclination or near Earth",
                    directionOff(positionAngleDeg, e));
        }
        String limits = "This screens one night's motion against circular, low-inclination main-belt orbits; eccentric or "
                + "inclined orbits and the motion's own error widen the range"
                + (e.minRate() < STATIONARY_RATE ? ", and near a stationary point, as here, every main-belt asteroid slows and turns" : "")
                + ". Only an orbit from more nights tells what the object is.";
        String note = reason == null
                ? "Motion usual for a main-belt asteroid: " + measured + "; " + range + ". " + limits
                : "Unusual motion, " + reason + ": " + measured + ", while " + range + ". " + limits;
        return new Assessment(reason != null, reason, note, e);
    }

    /** How far, in degrees, a position angle lies outside the main-belt angles (zero inside them). */
    static double directionOff(double angle, Expected e) {
        double a = e.innerAngle(), b = e.outerAngle();
        double span = Math.abs(signedDifference(b, a));
        double fromA = Math.abs(signedDifference(angle, a)), fromB = Math.abs(signedDifference(angle, b));
        if (fromA <= span && fromB <= span) return 0;
        return Math.min(fromA, fromB);
    }

    private static double signedDifference(double a, double b) {
        double d = (a - b) % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    /** Rate (arcsec/hour), position angle (degrees) and the relative velocity across the line of sight (AU/day) at heliocentric distance r. */
    private static double[] motion(double[] u, double[] earth, double[] earthVelocity, double r, double obliquity, double raDeg, double decDeg) {
        double b = dot(earth, u);
        double delta = -b + Math.sqrt(Math.max(0, b * b - dot(earth, earth) + r * r));
        double[] p = {earth[0] + delta * u[0], earth[1] + delta * u[1], earth[2] + delta * u[2]};
        // A prograde circular orbit: velocity along z × p, of the circular speed.
        double[] along = {-p[1], p[0], 0};
        double scale = GAUSS_K / Math.sqrt(r) / norm(along);
        double[] v = {along[0] * scale - earthVelocity[0], along[1] * scale - earthVelocity[1], along[2] * scale - earthVelocity[2]};
        double radial = dot(v, u);
        double[] across = {v[0] - radial * u[0], v[1] - radial * u[1], v[2] - radial * u[2]};
        double[] eq = eclipticToEquatorial(across, obliquity);
        double ra = Math.toRadians(raDeg), dec = Math.toRadians(decDeg);
        double east = -Math.sin(ra) * eq[0] + Math.cos(ra) * eq[1];
        double north = -Math.sin(dec) * Math.cos(ra) * eq[0] - Math.sin(dec) * Math.sin(ra) * eq[1] + Math.cos(dec) * eq[2];
        double rate = norm(across) / delta * ARCSEC_PER_RADIAN / 24;
        double angle = (Math.toDegrees(Math.atan2(east, north)) + 360) % 360;
        return new double[]{rate, angle, norm(across)};
    }

    /** Earth's heliocentric ecliptic position in AU: the Sun's geocentric place reversed. */
    static double[] earthPosition(long utcMillis) {
        double n = utcMillis / 86_400_000.0 + 2_440_587.5 - 2_451_545.0;
        double g = Math.toRadians(357.528 + 0.9856003 * n);
        double lambda = Math.toRadians(280.460 + 0.9856474 * n + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double distance = 1.00014 - 0.01671 * Math.cos(g) - 0.00014 * Math.cos(2 * g);
        return new double[]{-distance * Math.cos(lambda), -distance * Math.sin(lambda), 0};
    }

    private static double[] earthVelocity(long utcMillis) {
        long half = 43_200_000L;
        double[] a = earthPosition(utcMillis - half), b = earthPosition(utcMillis + half);
        return new double[]{b[0] - a[0], b[1] - a[1], b[2] - a[2]};
    }

    private static double obliquityDeg(long utcMillis) {
        double n = utcMillis / 86_400_000.0 + 2_440_587.5 - 2_451_545.0;
        return 23.439 - 0.0000004 * n;
    }

    private static double[] unit(double raDeg, double decDeg) {
        double ra = Math.toRadians(raDeg), dec = Math.toRadians(decDeg);
        return new double[]{Math.cos(dec) * Math.cos(ra), Math.cos(dec) * Math.sin(ra), Math.sin(dec)};
    }

    private static double[] equatorialToEcliptic(double[] v, double e) {
        return new double[]{v[0], v[1] * Math.cos(e) + v[2] * Math.sin(e), -v[1] * Math.sin(e) + v[2] * Math.cos(e)};
    }

    private static double[] eclipticToEquatorial(double[] v, double e) {
        return new double[]{v[0], v[1] * Math.cos(e) - v[2] * Math.sin(e), v[1] * Math.sin(e) + v[2] * Math.cos(e)};
    }

    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    private static double norm(double[] a) { return Math.sqrt(dot(a, a)); }
}
