/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util;

/**
 * Angles and angular distances on the sky. Angles are in degrees unless a name says otherwise.
 *
 * <p>Some quantities exist here in more than one variant. They come from code written separately, and their results
 * differ in the last bits, at the boundaries or for unusual input. Each variant keeps its exact arithmetic so that the
 * reports stay byte-identical, and its name says how it differs. Whether they need to differ is an open question
 * (DESIGN-IMPROVEMENTS.md, SP-10).</p>
 */
public final class SkyMath {

    private SkyMath() {
    }

    // =========================================================================
    // Wrapping angles
    // =========================================================================

    /** Wraps an angle into [0, 360) with one remainder step; NaN and infinities give NaN. */
    public static double normalizeDegrees(double degrees) {
        double normalized = degrees % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    /**
     * Wraps an angle into [-180, 180] with one remainder step. 180 and -180 stay as they are; NaN and infinities
     * give NaN. Used for longitudes and for differences of right ascension.
     */
    public static double normalizeSignedDegrees(double degrees) {
        if (!Double.isFinite(degrees)) {
            return Double.NaN;
        }
        double normalized = degrees % 360.0d;
        if (normalized > 180.0d) {
            normalized -= 360.0d;
        }
        if (normalized < -180.0d) {
            normalized += 360.0d;
        }
        return normalized;
    }

    /**
     * Wraps an angle into (-180, 180] by adding or subtracting 360 until it fits, so -180 becomes 180. Unlike
     * {@link #normalizeSignedDegrees}, it repeats the step instead of taking a remainder, and an infinite input never
     * returns. Used for hour angles and for differences of right ascension.
     */
    public static double wrapSignedDegrees(double degrees) {
        while (degrees <= -180.0d) {
            degrees += 360.0d;
        }
        while (degrees > 180.0d) {
            degrees -= 360.0d;
        }
        return degrees;
    }

    /** Limits a field of view to [min, max]; NaN stays NaN. */
    public static double clampFieldOfViewDegrees(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // =========================================================================
    // Angular separation: one quantity, six variants
    // =========================================================================

    /**
     * Separation in degrees by the spherical law of cosines, with the cosine limited to [-1, 1]; NaN when an input is
     * not finite. Less precise than the haversine variants below one arcsecond.
     */
    public static double separationDegreesCosineRule(double ra1Degrees, double dec1Degrees,
                                                     double ra2Degrees, double dec2Degrees) {
        if (!Double.isFinite(ra1Degrees) || !Double.isFinite(dec1Degrees)
                || !Double.isFinite(ra2Degrees) || !Double.isFinite(dec2Degrees)) {
            return Double.NaN;
        }
        double ra1Rad = Math.toRadians(ra1Degrees);
        double dec1Rad = Math.toRadians(dec1Degrees);
        double ra2Rad = Math.toRadians(ra2Degrees);
        double dec2Rad = Math.toRadians(dec2Degrees);
        double sinDec1 = Math.sin(dec1Rad);
        double sinDec2 = Math.sin(dec2Rad);
        double cosDec1 = Math.cos(dec1Rad);
        double cosDec2 = Math.cos(dec2Rad);
        double cosDeltaRa = Math.cos(ra2Rad - ra1Rad);
        double cosine = (sinDec1 * sinDec2) + (cosDec1 * cosDec2 * cosDeltaRa);
        cosine = Math.max(-1.0d, Math.min(1.0d, cosine));
        return Math.toDegrees(Math.acos(cosine));
    }

    /**
     * Separation in degrees by the haversine formula, with the coordinates converted to radians before the
     * differences are taken and the haversine root limited to 1.
     */
    public static double separationDegreesHaversine(double ra1, double dec1, double ra2, double dec2) {
        double r1 = Math.toRadians(ra1);
        double d1 = Math.toRadians(dec1);
        double r2 = Math.toRadians(ra2);
        double d2 = Math.toRadians(dec2);
        double sinDd = Math.sin((d2 - d1) / 2);
        double sinDr = Math.sin((r2 - r1) / 2);
        double a = sinDd * sinDd + Math.cos(d1) * Math.cos(d2) * sinDr * sinDr;
        return Math.toDegrees(2 * Math.asin(Math.min(1.0, Math.sqrt(a))));
    }

    /**
     * As {@link #separationDegreesHaversine}, without limiting the root to 1: points almost opposite on the sky can
     * give NaN.
     */
    public static double separationDegreesHaversineUnclamped(double raDegrees1, double decDegrees1,
                                                             double raDegrees2, double decDegrees2) {
        double ra1 = Math.toRadians(raDegrees1);
        double ra2 = Math.toRadians(raDegrees2);
        double de1 = Math.toRadians(decDegrees1);
        double de2 = Math.toRadians(decDegrees2);
        double sinDe = Math.sin((de2 - de1) / 2);
        double sinRa = Math.sin((ra2 - ra1) / 2);
        return Math.toDegrees(2 * Math.asin(Math.sqrt(sinDe * sinDe + Math.cos(de1) * Math.cos(de2) * sinRa * sinRa)));
    }

    /**
     * Separation in arcseconds by the haversine formula, with the declinations converted to radians first but the
     * right-ascension difference taken in degrees, squares by {@code Math.pow}, and the root limited to 1.
     */
    public static double separationArcsecHaversineRaDeltaInDegrees(double ra1, double dec1, double ra2, double dec2) {
        double d1 = Math.toRadians(dec1);
        double d2 = Math.toRadians(dec2);
        double a = Math.pow(Math.sin((d2 - d1) / 2), 2)
                + Math.cos(d1) * Math.cos(d2) * Math.pow(Math.sin(Math.toRadians(ra2 - ra1) / 2), 2);
        return Math.toDegrees(2 * Math.asin(Math.min(1, Math.sqrt(a)))) * 3600;
    }

    /**
     * Separation in arcseconds by the haversine formula, with both differences taken in degrees before conversion,
     * squares by {@code Math.pow}, and the root limited to 1.
     */
    public static double separationArcsecHaversineDeltasInDegrees(double ra1, double dec1, double ra2, double dec2) {
        double a = Math.pow(Math.sin(Math.toRadians(dec2 - dec1) / 2), 2)
                + Math.cos(Math.toRadians(dec1)) * Math.cos(Math.toRadians(dec2))
                * Math.pow(Math.sin(Math.toRadians(ra2 - ra1) / 2), 2);
        return Math.toDegrees(2 * Math.asin(Math.min(1, Math.sqrt(a)))) * 3600;
    }

    /**
     * Separation in arcseconds by the haversine formula, with the coordinates converted to radians first and the
     * haversine limited to [0, 1] before the root.
     */
    public static double separationArcsecHaversine(double ra1Deg, double dec1Deg, double ra2Deg, double dec2Deg) {
        double ra1 = Math.toRadians(ra1Deg);
        double dec1 = Math.toRadians(dec1Deg);
        double ra2 = Math.toRadians(ra2Deg);
        double dec2 = Math.toRadians(dec2Deg);
        double sinHalfDec = Math.sin((dec2 - dec1) / 2.0);
        double sinHalfRa = Math.sin((ra2 - ra1) / 2.0);
        double a = (sinHalfDec * sinHalfDec) + (Math.cos(dec1) * Math.cos(dec2) * sinHalfRa * sinHalfRa);
        double c = 2.0 * Math.asin(Math.min(1.0, Math.sqrt(Math.max(0.0, a))));
        return Math.toDegrees(c) * 3600.0;
    }

    /**
     * Separation in degrees by the haversine formula in its {@code atan2} form, with the right-ascension difference
     * taken in degrees before conversion.
     */
    public static double separationDegreesAtan2(double ra1, double dec1, double ra2, double dec2) {
        double phi1 = Math.toRadians(dec1);
        double phi2 = Math.toRadians(dec2);
        double deltaPhi = phi2 - phi1;
        double deltaLambda = Math.toRadians(ra2 - ra1);
        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        return Math.toDegrees(2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }
}
