/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util.skycatalog;

import eu.startales.spacepixels.util.WcsCoordinateTransformer;

import java.util.regex.Pattern;

/** Places catalogue objects on a frame, for the viewers and the report. */
public final class SkyProjection {

    private static final double MILLIS_PER_YEAR = 365.25 * 86_400_000.0;
    /**
     * A designation of the General Catalogue of Variable Stars, as VSX writes it: letters ("LL UMa", "R Leo"), a
     * V number ("V0640 Mon") or a Greek letter ("bet Per"), then the constellation.
     */
    private static final Pattern CLASSIC_VARIABLE_NAME = Pattern.compile(
            "^(?:[A-Z]{1,2}|V\\d{3,4}|(?:alf|bet|gam|del|eps|zet|eta|the|iot|kap|lam|mu\\.?|nu\\.?|xi\\.?|omi|pi\\.?|rho|sig|tau|ups|phi|chi|psi|ome)\\d{0,2})"
                    + " [A-Z][A-Za-z]{2}$");

    private SkyProjection() {
    }

    /**
     * The pixel of a Gaia star on a frame taken at this time: the star is moved from the Gaia epoch by its proper
     * motion (not at all when the time is unknown). Null when the position is outside the projection.
     */
    public static double[] starPixel(WcsCoordinateTransformer transformer, SkyCatalogue.Star star, long timestampMillis) {
        double ra = star.ra;
        double dec = star.dec;
        double years = timestampMillis > 0 ? (timestampMillis - SkyCatalogue.GAIA_EPOCH_MILLIS) / MILLIS_PER_YEAR : 0;
        if (years != 0 && star.pmra != null && star.pmdec != null) {
            dec += star.pmdec * years / 3.6e6;
            ra += star.pmra * years / 3.6e6 / Math.max(1e-6, Math.cos(Math.toRadians(star.dec)));
        }
        return transformer.skyToPixel(ra, dec);
    }

    /**
     * A deep-sky object as {x, y, semi-major axis, semi-minor axis, angle}: pixels, and the direction of the major
     * axis in radians (frame x towards y). The axes are 0 when the size is unknown; without a position angle the
     * object is drawn as a circle. Null when the position is outside the projection.
     */
    public static double[] deepSkyShape(WcsCoordinateTransformer transformer, SkyCatalogue.DeepSkyObject object) {
        double[] centre = transformer.skyToPixel(object.ra, object.dec);
        if (centre == null) {
            return null;
        }
        double semiMajor = 0;
        double semiMinor = 0;
        double angle = 0;
        if (object.majorArcmin != null && object.majorArcmin > 0) {
            double radiusDeg = object.majorArcmin / 120.0;
            double positionAngle = Math.toRadians(object.angleDeg == null ? 0 : object.angleDeg);
            double[] edge = transformer.skyToPixel(
                    object.ra + radiusDeg * Math.sin(positionAngle) / Math.max(1e-6, Math.cos(Math.toRadians(object.dec))),
                    object.dec + radiusDeg * Math.cos(positionAngle));
            if (edge != null) {
                semiMajor = Math.hypot(edge[0] - centre[0], edge[1] - centre[1]);
                angle = Math.atan2(edge[1] - centre[1], edge[0] - centre[0]);
                double minor = object.minorArcmin != null && object.minorArcmin > 0 && object.angleDeg != null
                        ? object.minorArcmin : object.majorArcmin;
                semiMinor = semiMajor * Math.min(1.0, minor / object.majorArcmin);
            }
        }
        return new double[]{centre[0], centre[1], semiMajor, semiMinor, angle};
    }

    /** Plain names for the SIMBAD object types a deep-sky label is likely to have. */
    public static String deepSkyTypeName(String type) {
        if (type == null) {
            return "object";
        }
        switch (type) {
            case "G": return "galaxy";
            case "OpC": return "open cluster";
            case "GlC": return "globular cluster";
            case "Cl*": return "star cluster";
            case "As*": return "stellar association";
            case "PN": return "planetary nebula";
            case "HII": return "HII region";
            case "RNe": return "reflection nebula";
            case "DNe": return "dark nebula";
            case "GNe": return "nebula";
            case "SNR": return "supernova remnant";
            case "MoC": return "molecular cloud";
            case "ClG": return "galaxy cluster";
            case "GrG": return "group of galaxies";
            case "CGG": return "compact group of galaxies";
            case "PaG": return "pair of galaxies";
            case "IG": return "interacting galaxies";
            case "Sy1": case "Sy2": case "SyG": return "Seyfert galaxy";
            case "AGN": case "LIN": return "active galaxy";
            case "SBG": return "starburst galaxy";
            case "EmG": return "emission-line galaxy";
            case "LSB": return "low surface brightness galaxy";
            case "BiC": return "brightest galaxy of a cluster";
            case "GiC": case "GiG": case "GiP": return "galaxy in a group";
            case "rG": return "radio galaxy";
            case "H2G": return "HII galaxy";
            case "bCG": return "blue compact galaxy";
            case "AG?": case "G?": return "possible galaxy";
            default: return "SIMBAD type " + type;
        }
    }

    /** "M 81 (Bode's Galaxy)", or the catalogue name alone. */
    public static String deepSkyLabel(SkyCatalogue.DeepSkyObject object) {
        String name = object.name == null ? "?" : object.name;
        return object.commonName == null ? name : name + " (" + object.commonName + ")";
    }

    /** Whether a deep-sky object name is a Messier, NGC, IC or Sharpless number (not a part of one, like "NGC 2024S"). */
    public static boolean isWellKnownDeepSky(String name) {
        return name != null && (name.matches("(M|NGC|IC) \\d+") || name.matches("Sh 2-\\d+"));
    }

    /** The old letter and Greek-letter designations ("RU Ori", "LL UMa", "zet Ori"), not the numbered "V0931 Ori". */
    private static final Pattern NOTABLE_VARIABLE_NAME = Pattern.compile(
            "^(?:[A-Z]{1,2}|(?:alf|bet|gam|del|eps|zet|eta|the|iot|kap|lam|mu\\.?|nu\\.?|xi\\.?|omi|pi\\.?|rho|sig|tau|ups|phi|chi|psi|ome)\\d{0,2})"
                    + " [A-Z][A-Za-z]{2}$");

    /**
     * The variable stars worth naming on a map of the whole field: those with an old letter or Greek-letter
     * designation, the brightest first, at most {@code limit}, and none where a named star already is.
     */
    public static java.util.List<SkyCatalogue.VariableStar> notableVariables(SkyCatalogue catalogue, int limit) {
        java.util.List<SkyCatalogue.VariableStar> notable = new java.util.ArrayList<>();
        for (SkyCatalogue.VariableStar variable : catalogue.variables) {
            if (variable.name == null || !NOTABLE_VARIABLE_NAME.matcher(variable.name).matches()) {
                continue;
            }
            boolean namedStar = false;
            for (SkyCatalogue.NamedStar star : catalogue.namedStars) {
                if (Math.abs(star.dec - variable.dec) < 0.02
                        && Math.abs((star.ra - variable.ra) * Math.cos(Math.toRadians(star.dec))) < 0.02) {
                    namedStar = true;
                    break;
                }
            }
            if (!namedStar) {
                notable.add(variable);
            }
        }
        notable.sort(java.util.Comparator.comparingDouble(v -> v.max == null ? Double.MAX_VALUE : v.max));
        return notable.size() > limit ? new java.util.ArrayList<>(notable.subList(0, limit)) : notable;
    }

    /**
     * Whether people know a deep-sky object: a Messier, NGC, IC or Sharpless number, or a common name such as the
     * Horsehead Nebula. Survey and catalogue numbers like "UGC 5139" or "LEDA 28731" are not.
     */
    public static boolean isWellKnownDeepSky(SkyCatalogue.DeepSkyObject object) {
        if (object.name == null) {
            return false;
        }
        return isWellKnownDeepSky(object.name) || object.commonName != null
                || (!object.name.matches(".*\\d.*") && !object.name.startsWith("NAME "));
    }

    /**
     * Whether a variable star has a name people know (a GCVS designation such as "LL UMa", "V0640 Mon" or
     * "bet Per") rather than a survey number such as "Gaia DR3 3131…" or "ZTF J0639…".
     */
    public static boolean isClassicVariableName(String name) {
        return name != null && CLASSIC_VARIABLE_NAME.matcher(name).matches();
    }
}
