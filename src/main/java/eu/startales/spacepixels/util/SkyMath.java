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
     * give NaN. Used for longitudes, hour angles and differences of right ascension.
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

    /** Limits a field of view to [min, max]; NaN stays NaN. */
    public static double clampFieldOfViewDegrees(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // =========================================================================
    // Angular separation
    // =========================================================================

    /**
     * Angular separation in degrees by the haversine formula, with the coordinates converted to radians before the
     * differences are taken and the haversine clamped to [0, 1]; NaN when an input is not finite.
     */
    public static double separationDegrees(double ra1Degrees, double dec1Degrees,
                                           double ra2Degrees, double dec2Degrees) {
        if (!Double.isFinite(ra1Degrees) || !Double.isFinite(dec1Degrees)
                || !Double.isFinite(ra2Degrees) || !Double.isFinite(dec2Degrees)) {
            return Double.NaN;
        }
        double r1 = Math.toRadians(ra1Degrees);
        double d1 = Math.toRadians(dec1Degrees);
        double r2 = Math.toRadians(ra2Degrees);
        double d2 = Math.toRadians(dec2Degrees);
        double sinDd = Math.sin((d2 - d1) / 2.0);
        double sinDr = Math.sin((r2 - r1) / 2.0);
        double a = sinDd * sinDd + Math.cos(d1) * Math.cos(d2) * sinDr * sinDr;
        return Math.toDegrees(2.0 * Math.asin(Math.min(1.0, Math.sqrt(Math.max(0.0, a)))));
    }

    /** Angular separation in arcseconds by the haversine formula ({@link #separationDegrees} times 3600). */
    public static double separationArcsec(double ra1Degrees, double dec1Degrees,
                                          double ra2Degrees, double dec2Degrees) {
        return separationDegrees(ra1Degrees, dec1Degrees, ra2Degrees, dec2Degrees) * 3600.0;
    }
}
