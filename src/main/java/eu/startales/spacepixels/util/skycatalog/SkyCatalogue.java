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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The stars, deep-sky objects and variable stars in the field of a session, fetched once by
 * {@link SkyCatalogueClient} and kept next to the frames as {@value #FILE_NAME}. Positions are in degrees (ICRS).
 */
public final class SkyCatalogue {

    /** The file in the session folder. */
    public static final String FILE_NAME = "sky_catalogue.json";
    /** Gaia DR3 positions are for epoch J2016.0; the stars are moved from there to the date of each frame. */
    public static final long GAIA_EPOCH_MILLIS = Instant.parse("2016-01-01T12:00:00Z").toEpochMilli();
    static final int CURRENT_VERSION = 1;

    public int version = CURRENT_VERSION;
    public String fetchedAtUtc;
    public SkyField field;
    /** The faintest Gaia G magnitude fetched. */
    public double starMagnitudeLimit;
    /** Where the stars came from, for example "VizieR (CDS)". */
    public String starSource;
    public List<Star> stars = new ArrayList<>();
    public List<DeepSkyObject> deepSky = new ArrayList<>();
    public List<VariableStar> variables = new ArrayList<>();
    /** Stars brighter than about magnitude 5 that have a proper name, such as Betelgeuse; usually none in a telescope field. */
    public List<NamedStar> namedStars = new ArrayList<>();
    /** Faint Gaia stars of four small patches, one in each quarter of the field, to measure how deep the frames go. */
    public List<DepthSample> depthSamples = new ArrayList<>();
    /** One line per source that could not be fetched; empty when all answered. */
    public List<String> problems = new ArrayList<>();

    /** A Gaia DR3 star at epoch J2016.0. */
    public static final class Star {
        public double ra;
        public double dec;
        /** Proper motion in mas/yr; in RA it includes the cos(Dec) factor, as Gaia gives it. Null when unknown. */
        public Double pmra;
        public Double pmdec;
        /** Gaia G magnitude. */
        public double g;
        /** Gaia BP−RP colour, or null. */
        public Double bpRp;
    }

    /** A galaxy, nebula or cluster from SIMBAD. */
    public static final class DeepSkyObject {
        public String name;
        /** SIMBAD object type, for example "G", "OpC" or "PN". */
        public String type;
        public double ra;
        public double dec;
        /** Major and minor axis in arcminutes, and position angle in degrees (north through east); null when unknown. */
        public Double majorArcmin;
        public Double minorArcmin;
        public Double angleDeg;
        /** The common name, such as "Bode's Galaxy", or null. */
        public String commonName;
        /** SIMBAD's main identifier, which links the common name to the object. */
        public transient String mainId;
    }

    /** The Gaia stars of a square patch of the field, down to {@link #magnitudeLimit}. */
    public static final class DepthSample {
        public double centerRa;
        public double centerDec;
        /** Side of the patch in degrees (along Dec; along RA it is this on the sky). */
        public double sideDeg;
        public double magnitudeLimit;
        public List<Star> stars = new ArrayList<>();
    }

    /** A bright star with a proper name, from SIMBAD. */
    public static final class NamedStar {
        public String name;
        public double ra;
        public double dec;
        /** V magnitude. */
        public double vmag;
    }

    /** A variable star from the AAVSO International Variable Star Index (VSX). */
    public static final class VariableStar {
        public long oid;
        public String name;
        public String type;
        public double ra;
        public double dec;
        /** Brightest magnitude, and faintest magnitude or (when {@link #minIsAmplitude}) the amplitude. */
        public Double max;
        public Double min;
        public boolean minIsAmplitude;
        /** Passband of the magnitudes, for example "V". */
        public String band;
        public Double periodDays;
    }

    /** "21,475 stars · 54 deep-sky · 3,713 variables". */
    public String summary() {
        return String.format(java.util.Locale.US, "%,d stars · %,d deep-sky · %,d variables",
                stars.size(), deepSky.size(), variables.size());
    }

    /** True when nothing at all was fetched. */
    public boolean isEmpty() {
        return stars.isEmpty() && deepSky.isEmpty() && variables.isEmpty();
    }
}
