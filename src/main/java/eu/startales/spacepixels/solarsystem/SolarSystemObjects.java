/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.solarsystem;

import eu.startales.spacepixels.skycatalog.FetchStep;
import eu.startales.spacepixels.skycatalog.SkyField;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The known asteroids and comets predicted in the field of a session, fetched once by {@link SolarSystemClient} and
 * kept next to the frames as {@value #FILE_NAME}. Each object has positions sampled over the observing span;
 * {@link #positionAt} interpolates them to a frame's time. These are predictions, not detections. Positions are
 * astrometric ICRF degrees as seen from the observer; times are UTC milliseconds.
 */
public final class SolarSystemObjects {

    /** The file in the session folder. */
    public static final String FILE_NAME = "solar_system.json";
    /** Motion/path margin around the observing span, in milliseconds; extended clock samples do not widen that span. */
    public static final long OBSERVING_SPAN_MARGIN_MILLIS = 10 * 60_000L;
    static final int CURRENT_VERSION = 1;

    public int version = CURRENT_VERSION;
    public String fetchedAtUtc;
    /** The field searched (the frame outline, before the margins for motion). */
    public SkyField field;
    /** The observing span the positions cover: the first exposure's start to the last exposure's end. */
    public long startMillis;
    public long endMillis;
    /** The faintest predicted V magnitude asked for (the total magnitude for comets). */
    public double magnitudeLimit;
    /** "IAU code J95", or where the site coordinates came from; never the coordinates themselves. */
    public String observer;
    /** The services and their API versions, for example "JPL SB-Ident API 1.1, JPL Horizons API 1.2". */
    public String source;
    public List<Body> bodies = new ArrayList<>();
    /** One line per part that could not be fetched; empty when everything answered. The status warning still reads this. */
    public List<String> problems = new ArrayList<>();
    /**
     * Every service question from the fetch, including those that succeeded or were not sent.
     * Gson leaves this null in a file written before it existed; {@link SolarSystemStore#load} replaces null
     * with an empty list. The problem sentences stay beside it. Saved addresses leave out the observing site.
     */
    public List<FetchStep> fetchSteps = new ArrayList<>();

    /** An asteroid or comet with its predicted positions. */
    public static final class Body {
        /** As JPL names it, for example "194 Prokne (A879 FA)" or "C/2018 Y1 (Iwamoto)". */
        public String name;
        public boolean comet;
        /** JPL's orbit solution, for example "JPL#172"; null when only the field search's straight-line motion is known. */
        public String orbitSolution;
        /** Positions in time order. */
        public List<Sample> samples = new ArrayList<>();
    }

    /** A predicted position at one time. */
    public static final class Sample {
        public long timeMillis;
        public double ra;
        public double dec;
        /** Predicted V magnitude, or a comet's total magnitude; null when unknown. */
        public Double magnitude;
        /** 3-sigma position uncertainty in arcseconds on the sky, along RA and Dec; null when unknown. */
        public Double raSigmaArcsec;
        public Double decSigmaArcsec;
    }

    /** A position interpolated to one time; the uncertainties are null when unknown. */
    public record Position(double ra, double dec, Double magnitude, Double raSigmaArcsec, Double decSigmaArcsec) {
    }

    /**
     * Where {@code body} is predicted at this time, interpolated between its samples (cubic between four samples, so
     * hourly samples are good to milliarcseconds over a night); null outside the sampled times.
     */
    public static Position positionAt(Body body, long timeMillis) {
        List<Sample> samples = body.samples;
        int count = samples.size();
        if (count == 0 || timeMillis < samples.get(0).timeMillis || timeMillis > samples.get(count - 1).timeMillis) {
            return null;
        }
        if (count == 1) {
            Sample only = samples.get(0);
            return new Position(only.ra, only.dec, only.magnitude, only.raSigmaArcsec, only.decSigmaArcsec);
        }
        int after = 1;
        while (after < count - 1 && samples.get(after).timeMillis < timeMillis) {
            after++;
        }
        Sample before = samples.get(after - 1);
        Sample next = samples.get(after);
        int first = Math.max(0, Math.min(after - 2, count - 4));
        int last = Math.min(count - 1, first + 3);
        double[] times = new double[last - first + 1];
        double[] ras = new double[times.length];
        double[] decs = new double[times.length];
        for (int i = 0; i < times.length; i++) {
            Sample sample = samples.get(first + i);
            times[i] = sample.timeMillis - before.timeMillis;
            // RA continues across 0°/360° instead of jumping.
            double ra = sample.ra;
            if (i > 0) {
                ra = ras[i - 1] + wrap180(ra - ras[i - 1]);
            }
            ras[i] = ra;
            decs[i] = sample.dec;
        }
        double t = timeMillis - before.timeMillis;
        double ra = ((lagrange(times, ras, t) % 360.0) + 360.0) % 360.0;
        double dec = lagrange(times, decs, t);
        double fraction = next.timeMillis == before.timeMillis ? 0
                : (double) (timeMillis - before.timeMillis) / (next.timeMillis - before.timeMillis);
        return new Position(ra, dec, linear(before.magnitude, next.magnitude, fraction),
                linear(before.raSigmaArcsec, next.raSigmaArcsec, fraction),
                linear(before.decSigmaArcsec, next.decSigmaArcsec, fraction));
    }

    /** The polynomial through the points, evaluated at {@code t}. */
    private static double lagrange(double[] times, double[] values, double t) {
        double sum = 0;
        for (int j = 0; j < times.length; j++) {
            double weight = 1;
            for (int k = 0; k < times.length; k++) {
                if (k != j) {
                    weight *= (t - times[k]) / (times[j] - times[k]);
                }
            }
            sum += weight * values[j];
        }
        return sum;
    }

    private static Double linear(Double before, Double after, double fraction) {
        if (before == null || after == null) {
            return before != null ? before : after;
        }
        return before + (after - before) * fraction;
    }

    private static double wrap180(double degrees) {
        double wrapped = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return wrapped == -180.0 ? 180.0 : wrapped;
    }

    /** The short name drawn on the frame: "194 Prokne", "(2014 QV125)" or "C/2018 Y1 (Iwamoto)". */
    public static String label(Body body) {
        String name = body.name == null ? "?" : body.name.trim();
        // A numbered asteroid's provisional designation adds nothing on the frame.
        if (!body.comet && name.matches("\\d+ .+ \\(.+\\)")) {
            name = name.substring(0, name.lastIndexOf(" (")).trim();
        }
        return name;
    }

    /** "7 asteroids and 1 comet", or "no asteroids or comets". */
    public String summary() {
        long comets = bodies.stream().filter(body -> body.comet).count();
        long asteroids = bodies.size() - comets;
        if (bodies.isEmpty()) {
            return "no asteroids or comets";
        }
        String asteroidText = asteroids == 0 ? "" : String.format(Locale.US, "%,d %s", asteroids, asteroids == 1 ? "asteroid" : "asteroids");
        String cometText = comets == 0 ? "" : String.format(Locale.US, "%,d %s", comets, comets == 1 ? "comet" : "comets");
        return asteroidText.isEmpty() ? cometText : cometText.isEmpty() ? asteroidText : asteroidText + " and " + cometText;
    }
}
