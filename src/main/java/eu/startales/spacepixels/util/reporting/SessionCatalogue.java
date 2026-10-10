/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogueStore;
import eu.startales.spacepixels.util.skycatalog.SkyField;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The sky catalogue saved with the session (by Fetch Sky Catalogue in SpacePixels), as the report uses it: the VSX
 * variable stars and Gaia stars near a position. Only a catalogue fetched for these frames is used.
 */
final class SessionCatalogue {

    private static final double MILLIS_PER_YEAR = 365.25 * 86_400_000.0;

    final SkyCatalogue catalogue;
    private final long timestampMillis;

    private SessionCatalogue(SkyCatalogue catalogue, long timestampMillis) {
        this.catalogue = catalogue;
        this.timestampMillis = timestampMillis;
    }

    /** The catalogue of the session in the report's context; its {@link #catalogue} is null when there is none. */
    static SessionCatalogue of(DetectionReportContext context) {
        FitsFileInformation[] files = context.fitsFiles;
        WcsCoordinateTransformer transformer = context.astrometryContext != null ? context.astrometryContext.getTransformer() : null;
        if (files == null || files.length == 0 || files[0] == null || files[0].getFilePath() == null || transformer == null) {
            return new SessionCatalogue(null, 0);
        }
        File folder = new File(files[0].getFilePath()).getAbsoluteFile().getParentFile();
        SkyCatalogue catalogue = folder == null ? null : SkyCatalogueStore.load(folder);
        if (catalogue == null || catalogue.field == null
                || !SkyField.of(transformer, files[0].getSizeWidth(), files[0].getSizeHeight()).isSameFieldAs(catalogue.field)) {
            catalogue = null;
        }
        return new SessionCatalogue(catalogue, files[0].getObservationTimestamp());
    }

    boolean isAvailable() {
        return catalogue != null;
    }

    /** A catalogued variable star near a position, and how far from it. */
    static final class VariableMatch {
        final SkyCatalogue.VariableStar star;
        final double separationArcsec;

        VariableMatch(SkyCatalogue.VariableStar star, double separationArcsec) {
            this.star = star;
            this.separationArcsec = separationArcsec;
        }
    }

    /** The VSX variable stars within this radius of a position, closest first. */
    List<VariableMatch> variablesNear(double ra, double dec, double radiusArcsec) {
        List<VariableMatch> matches = new ArrayList<>();
        if (catalogue == null) {
            return matches;
        }
        for (SkyCatalogue.VariableStar variable : catalogue.variables) {
            if (Math.abs(variable.dec - dec) * 3600 > radiusArcsec) {
                continue;
            }
            double separation = separationArcsec(ra, dec, variable.ra, variable.dec);
            if (separation <= radiusArcsec) {
                matches.add(new VariableMatch(variable, separation));
            }
        }
        matches.sort(Comparator.comparingDouble(match -> match.separationArcsec));
        return matches;
    }

    /** The Gaia star nearest a position within this radius (moved to the date of the session), or null. */
    SkyCatalogue.Star gaiaStarNear(double ra, double dec, double radiusArcsec) {
        if (catalogue == null) {
            return null;
        }
        double years = timestampMillis > 0 ? (timestampMillis - SkyCatalogue.GAIA_EPOCH_MILLIS) / MILLIS_PER_YEAR : 0;
        SkyCatalogue.Star nearest = null;
        double nearestSeparation = radiusArcsec;
        for (SkyCatalogue.Star star : catalogue.stars) {
            double starDec = star.dec;
            double starRa = star.ra;
            if (years != 0 && star.pmra != null && star.pmdec != null) {
                starDec += star.pmdec * years / 3.6e6;
                starRa += star.pmra * years / 3.6e6 / Math.max(1e-6, Math.cos(Math.toRadians(star.dec)));
            }
            if (Math.abs(starDec - dec) * 3600 > nearestSeparation) {
                continue;
            }
            double separation = separationArcsec(ra, dec, starRa, starDec);
            if (separation <= nearestSeparation) {
                nearest = star;
                nearestSeparation = separation;
            }
        }
        return nearest;
    }

    static double separationArcsec(double ra1, double dec1, double ra2, double dec2) {
        double d1 = Math.toRadians(dec1);
        double d2 = Math.toRadians(dec2);
        double a = Math.pow(Math.sin((d2 - d1) / 2), 2)
                + Math.cos(d1) * Math.cos(d2) * Math.pow(Math.sin(Math.toRadians(ra2 - ra1) / 2), 2);
        return Math.toDegrees(2 * Math.asin(Math.min(1, Math.sqrt(a)))) * 3600;
    }

    /** "EW (W UMa-type eclipsing binary)", or the VSX type as it is when it is not one of the common types. */
    static String describeVsxType(String type) {
        if (type == null || type.isEmpty()) {
            return "type not given";
        }
        String main = type.split("[|+/ ]")[0].replace(":", "").trim();
        String meaning = vsxTypeMeaning(main);
        return meaning == null ? type : type + " (" + meaning + ")";
    }

    private static String vsxTypeMeaning(String type) {
        switch (type.toUpperCase(Locale.ROOT)) {
            case "EA": return "Algol-type eclipsing binary";
            case "EB": return "beta Lyrae-type eclipsing binary";
            case "EW": return "W UMa-type eclipsing binary";
            case "E": return "eclipsing binary";
            case "ELL": return "ellipsoidal variable";
            case "DSCT": return "delta Scuti variable";
            case "HADS": return "high-amplitude delta Scuti variable";
            case "GDOR": return "gamma Doradus variable";
            case "SXPHE": return "SX Phoenicis variable";
            case "RRAB": case "RRC": case "RRD": case "RR": return "RR Lyrae variable";
            case "CEP": case "DCEP": case "DCEPS": case "CW": case "CWA": case "CWB": return "Cepheid";
            case "M": return "Mira (long-period variable)";
            case "SR": case "SRA": case "SRB": case "SRC": case "SRD": case "SRS": return "semiregular variable";
            case "L": case "LB": case "LC": return "slow irregular variable";
            case "ROT": return "rotating variable (starspots)";
            case "BY": return "BY Draconis variable (starspots)";
            case "RS": return "RS Canum Venaticorum variable";
            case "ACV": return "alpha2 Canum Venaticorum variable";
            case "UV": case "UVN": return "flare star";
            case "UG": case "UGSU": case "UGSS": case "UGZ": case "UGWZ": return "dwarf nova";
            case "NL": return "nova-like variable";
            case "N": case "NA": case "NB": case "NC": case "NR": return "nova";
            case "SN": return "supernova";
            case "YSO": return "young stellar object";
            case "INS": case "IN": case "INT": return "Orion-type (young) variable";
            case "BCEP": return "beta Cephei variable";
            case "SPB": return "slowly pulsating B star";
            case "GCAS": return "gamma Cassiopeiae variable";
            case "VAR": return "variable, type not known";
            case "MISC": return "miscellaneous variable";
            case "CST": return "constant star (listed in VSX)";
            default: return null;
        }
    }

    /** "7.1 h" or "2.5 d". */
    static String formatPeriod(double days) {
        return days < 2 ? String.format(Locale.US, "%.1f h", days * 24) : String.format(Locale.US, "%.2f d", days);
    }
}
