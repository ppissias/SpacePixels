package eu.startales.spacepixels.mpc;

import eu.startales.spacepixels.fits.ObservingSite;
import eu.startales.spacepixels.util.SkyMath;

/**
 * Clean-room analytical Earth heliocentric and topocentric observer ephemeris in J2000 ecliptic AU. Uses abridged
 * VSOP87D Earth series (Bretagnon &amp; Francou 1988; Meeus 1998, Appendix III) precessed from the mean ecliptic of
 * date back to J2000.0 (Lieske et al. 1979), WGS84 geocentric observatory coordinates rotated via IAU 1982 GMST and
 * equatorial precession, and exact UTC-to-TT leap-second offsets. Intended for short- to medium-arc orbit determination
 * without external ephemeris files.
 */
final class EarthEphemeris {
    /** Mean obliquity of the ecliptic at J2000.0 (IAU 1976 / 2000), in radians. */
    static final double OBLIQUITY_J2000_RAD = Math.toRadians(23.43929111111111);
    static final double COS_EPS0 = Math.cos(OBLIQUITY_J2000_RAD);
    static final double SIN_EPS0 = Math.sin(OBLIQUITY_J2000_RAD);

    /** Speed of light in AU per day (IAU 2012 exact AU definition). */
    static final double SPEED_OF_LIGHT_AU_PER_DAY = 173.14463267424034;

    /** WGS84 equatorial Earth radius in AU. */
    private static final double EARTH_RADIUS_AU = 6378.137 / OrbitGeometry.KM_PER_AU;
    /** WGS84 flattening factor. */
    private static final double EARTH_FLATTENING = 1.0 / 298.257223563;
    private static final double EARTH_E2 = EARTH_FLATTENING * (2.0 - EARTH_FLATTENING);

    private static final double J2000 = OrbitGeometry.J2000;
    private static final double DAYS_PER_CENTURY = 36_525.0;
    private static final double DAYS_PER_MILLENNIUM = 365_250.0;
    private static final double ARCSEC_TO_RAD = Math.PI / (180.0 * 3600.0);

    // Abridged VSOP87D Earth heliocentric spherical series (Bretagnon & Francou 1988; Meeus 1998 Appendix III).
    // Each row is {A * 1e8, B (rad), C (rad / Julian millennium)}.
    private static final double[][] L0 = {
            {175347046, 0.0, 0.0},
            {3341656, 4.6692568, 6283.07585},
            {34894, 4.6261, 12566.1517},
            {3497, 2.7441, 5753.3849},
            {3418, 2.8289, 3.5231},
            {3136, 3.6277, 77713.7715},
            {2676, 4.4181, 7860.4194},
            {2343, 6.1352, 3930.2097},
            {1324, 0.7425, 11506.7698},
            {1273, 2.0371, 529.6910},
            {1199, 1.1096, 1577.3435},
            {990, 5.233, 5884.927},
            {902, 2.045, 26.298},
            {857, 3.508, 398.149},
            {780, 1.179, 5223.694},
            {753, 2.533, 5507.553},
            {505, 4.583, 18849.228},
            {492, 4.205, 775.523},
            {357, 2.92, 0.067},
            {317, 5.849, 11790.629},
            {284, 1.899, 796.298},
            {271, 0.315, 10977.079},
            {243, 0.345, 5486.778},
            {206, 4.806, 2544.314},
            {205, 1.869, 5573.143},
            {202, 2.458, 6069.777}
    };

    private static final double[][] L1 = {
            {628331966747.0, 0.0, 0.0},
            {206059, 2.678235, 6283.07585},
            {4303, 2.6351, 12566.1517}
    };

    private static final double[][] L2 = {
            {52919, 0.0, 0.0},
            {8720, 1.0721, 6283.0758}
    };

    private static final double[][] L3 = {
            {289, 5.844, 6283.076}
    };

    private static final double[][] B0 = {
            {280, 3.199, 84334.662},
            {102, 5.422, 5507.553},
            {80, 3.88, 5223.69},
            {44, 3.7, 2352.87},
            {32, 4.0, 1577.34}
    };

    private static final double[][] B1 = {
            {9, 3.9, 5507.55},
            {6, 1.73, 5223.69}
    };

    private static final double[][] R0 = {
            {100013989, 0.0, 0.0},
            {1670700, 3.0984635, 6283.07585},
            {13956, 3.05525, 12566.1517},
            {3084, 5.1985, 77713.7715},
            {1628, 1.1739, 5753.3849},
            {1576, 2.8469, 7860.4194},
            {925, 5.453, 11506.77},
            {542, 4.564, 3930.21},
            {472, 3.661, 5884.927},
            {346, 0.964, 5507.553},
            {329, 5.9, 5223.694},
            {307, 0.299, 5573.143},
            {243, 4.273, 11790.629},
            {212, 5.847, 1577.344},
            {186, 5.022, 10977.079}
    };

    private static final double[][] R1 = {
            {103019, 1.10749, 6283.07585},
            {1721, 1.0644, 12566.1517},
            {702, 3.142, 0.0},
            {32, 1.02, 18849.23},
            {31, 2.84, 5507.55}
    };

    private static final double[][] R2 = {
            {4359, 5.7846, 6283.07585}
    };

    /** Leap-second table: {JD_UTC effective date, TAI - UTC in seconds}. */
    private static final double[][] LEAP_SECONDS = {
            {2457754.5, 37.0}, // 2017-01-01
            {2457204.5, 36.0}, // 2015-07-01
            {2456109.5, 35.0}, // 2012-07-01
            {2454832.5, 34.0}, // 2009-01-01
            {2453736.5, 33.0}, // 2006-01-01
            {2451179.5, 32.0}, // 1999-01-01
            {2450630.5, 31.0}, // 1997-07-01
            {2450083.5, 30.0}, // 1996-01-01
            {2449534.5, 29.0}, // 1994-07-01
            {2449169.5, 28.0}, // 1993-07-01
            {2448804.5, 27.0}, // 1992-07-01
            {2448257.5, 26.0}, // 1991-01-01
            {2447892.5, 25.0}  // 1990-01-01
    };

    private EarthEphemeris() { }

    /** Returns TT minus UTC in seconds for a UTC Julian Date (exact for 1990–present). */
    static double ttMinusUtcSeconds(double jdUtc) {
        for (double[] entry : LEAP_SECONDS) {
            if (jdUtc >= entry[0]) return entry[1] + 32.184;
        }
        return 56.184;
    }

    /** Converts a UTC Julian Date to Terrestrial Time (TT) Julian Date. */
    static double utcToTt(double jdUtc) {
        return jdUtc + ttMinusUtcSeconds(jdUtc) / 86_400.0;
    }

    /**
     * Computes Earth's heliocentric geocenter position {x, y, z} in J2000 ecliptic AU at Terrestrial Time {@code jdTt}.
     */
    static double[] earthPositionJ2000(double jdTt) {
        double tau = (jdTt - J2000) / DAYS_PER_MILLENNIUM;
        double tau2 = tau * tau;
        double tau3 = tau2 * tau;

        double l = (sumSeries(L0, tau) + sumSeries(L1, tau) * tau + sumSeries(L2, tau) * tau2 + sumSeries(L3, tau) * tau3) * 1e-8;
        double b = (sumSeries(B0, tau) + sumSeries(B1, tau) * tau) * 1e-8;
        double r = (sumSeries(R0, tau) + sumSeries(R1, tau) * tau + sumSeries(R2, tau) * tau2) * 1e-8;

        // Small dynamical-to-FK5 longitude adjustment (-0.09033 arcsec, Meeus Ch. 32).
        l -= 0.09033 * ARCSEC_TO_RAD;

        double cosB = Math.cos(b);
        double xDate = r * cosB * Math.cos(l);
        double yDate = r * cosB * Math.sin(l);
        double zDate = r * Math.sin(b);

        // Precess from Mean Ecliptic of Date back to J2000 Ecliptic (Lieske et al. 1979; Meeus Ch. 21).
        double t = (jdTt - J2000) / DAYS_PER_CENTURY;
        double t2 = t * t, t3 = t2 * t;
        double eta = (47.0029 * t - 0.03302 * t2 + 0.000060 * t3) * ARCSEC_TO_RAD;
        double pi = Math.toRadians(174.876384) - (869.8089 * t - 0.03536 * t2) * ARCSEC_TO_RAD;
        double p = (5029.0966 * t + 1.11113 * t2 - 0.000006 * t3) * ARCSEC_TO_RAD;

        // Active-vector rotation from J2000 -> Date (Meeus Ch. 21.7) is Rz(+(pi + p)) * Rx(-eta) * Rz(-pi);
        // therefore inverse Date -> J2000 is Rz(+pi) * Rx(+eta) * Rz(-(pi + p)).
        double[] v = rotateZ(xDate, yDate, zDate, -(pi + p));
        v = rotateX(v[0], v[1], v[2], eta);
        return rotateZ(v[0], v[1], v[2], pi);
    }

    /**
     * Computes Earth's heliocentric velocity {vx, vy, vz} in J2000 ecliptic AU/day via central finite difference.
     */
    static double[] earthVelocityJ2000(double jdTt) {
        double dt = 1.0 / 24.0;
        double[] p1 = earthPositionJ2000(jdTt - dt);
        double[] p2 = earthPositionJ2000(jdTt + dt);
        return new double[]{(p2[0] - p1[0]) / (2.0 * dt), (p2[1] - p1[1]) / (2.0 * dt), (p2[2] - p1[2]) / (2.0 * dt)};
    }

    /**
     * Computes the heliocentric J2000 ecliptic position {x, y, z} in AU of an observing site at UTC time {@code jdUtc}
     * (and corresponding TT {@code jdTt}). When {@code site} is null, returns Earth's geocenter.
     */
    static double[] observerPositionJ2000(double jdUtc, double jdTt, ObservingSite site) {
        double[] earth = earthPositionJ2000(jdTt);
        if (site == null) return earth;
        double[] topoEcl = topocentricOffsetJ2000Ecliptic(jdUtc, jdTt, site);
        return new double[]{earth[0] + topoEcl[0], earth[1] + topoEcl[1], earth[2] + topoEcl[2]};
    }

    /**
     * Computes the geocentric offset of {@code site} in J2000 ecliptic AU, using WGS84 ellipsoid coordinates, IAU 1982
     * GMST rotation and Lieske equatorial precession back to J2000.0.
     */
    static double[] topocentricOffsetJ2000Ecliptic(double jdUtc, double jdTt, ObservingSite site) {
        double lat = Math.toRadians(site.latitudeDeg());
        double sinLat = Math.sin(lat), cosLat = Math.cos(lat);
        double n = EARTH_RADIUS_AU / Math.sqrt(1.0 - EARTH_E2 * sinLat * sinLat);
        double altAu = (site.altitudeMeters() / 1000.0) / OrbitGeometry.KM_PER_AU;
        double rhoCos = (n + altAu) * cosLat;
        double rhoSin = (n * (1.0 - EARTH_E2) + altAu) * sinLat;

        long utcMillis = OrbitGeometry.jdToInstant(jdUtc).toEpochMilli();
        double lst = Math.toRadians(SkyMath.localSiderealTimeDegrees(utcMillis, site.longitudeDeg()));
        // True-of-date equatorial geocentric position of site.
        double xEqDate = rhoCos * Math.cos(lst);
        double yEqDate = rhoCos * Math.sin(lst);
        double zEqDate = rhoSin;

        // Equatorial precession from Mean of Date back to J2000 (Lieske et al. 1979):
        // Forward J2000 -> Date is Rz(+z) * Ry(-theta) * Rz(+zeta); inverse Date -> J2000 is Rz(-zeta) * Ry(+theta) * Rz(-z).
        double t = (jdTt - J2000) / DAYS_PER_CENTURY;
        double t2 = t * t, t3 = t2 * t;
        double zeta = (2306.2181 * t + 0.30188 * t2 + 0.017998 * t3) * ARCSEC_TO_RAD;
        double z = (2306.2181 * t + 1.09468 * t2 + 0.018203 * t3) * ARCSEC_TO_RAD;
        double theta = (2004.3109 * t - 0.42665 * t2 - 0.041833 * t3) * ARCSEC_TO_RAD;

        double[] eqJ2000 = rotateZ(xEqDate, yEqDate, zEqDate, -z);
        eqJ2000 = rotateY(eqJ2000[0], eqJ2000[1], eqJ2000[2], theta);
        eqJ2000 = rotateZ(eqJ2000[0], eqJ2000[1], eqJ2000[2], -zeta);
        return equatorialToEcliptic(eqJ2000);
    }

    /** Converts a J2000 equatorial vector to J2000 ecliptic coordinates. */
    static double[] equatorialToEcliptic(double[] eq) {
        return new double[]{
                eq[0],
                eq[1] * COS_EPS0 + eq[2] * SIN_EPS0,
                -eq[1] * SIN_EPS0 + eq[2] * COS_EPS0
        };
    }

    /** Converts a J2000 ecliptic vector to J2000 equatorial coordinates. */
    static double[] eclipticToEquatorial(double[] ecl) {
        return new double[]{
                ecl[0],
                ecl[1] * COS_EPS0 - ecl[2] * SIN_EPS0,
                ecl[1] * SIN_EPS0 + ecl[2] * COS_EPS0
        };
    }

    /** Unit line-of-sight vector in J2000 ecliptic coordinates for J2000 equatorial RA/Dec in degrees. */
    static double[] lineOfSightEcliptic(double raDeg, double decDeg) {
        double ra = Math.toRadians(raDeg), dec = Math.toRadians(decDeg);
        double cosDec = Math.cos(dec);
        return equatorialToEcliptic(new double[]{cosDec * Math.cos(ra), cosDec * Math.sin(ra), Math.sin(dec)});
    }

    private static double sumSeries(double[][] terms, double tau) {
        double sum = 0.0;
        for (double[] term : terms) {
            sum += term[0] * Math.cos(term[1] + term[2] * tau);
        }
        return sum;
    }

    private static double[] rotateX(double x, double y, double z, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return new double[]{x, y * c - z * s, y * s + z * c};
    }

    private static double[] rotateY(double x, double y, double z, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return new double[]{x * c + z * s, y, -x * s + z * c};
    }

    private static double[] rotateZ(double x, double y, double z, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return new double[]{x * c - y * s, x * s + y * c, z};
    }
}
