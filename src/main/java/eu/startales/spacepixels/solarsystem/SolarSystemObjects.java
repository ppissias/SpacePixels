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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /**
     * "IAU code J95", or where the site coordinates came from, with the name of the Astrometry Config site when one
     * was used ("IAU code J95, site Home"); never the coordinates themselves.
     */
    public String observer;
    /** The services and their API versions, for example "JPL SB-Ident API 1.1, JPL Horizons API 1.2". */
    public String source;
    public List<Body> bodies = new ArrayList<>();
    /** One line per part that could not be fetched; empty when everything answered. The status warning still reads this. */
    public List<String> problems = new ArrayList<>();
    /**
     * Every service question from the fetch, including those that succeeded or were not sent.
     * Gson leaves this null in a file written before it existed; {@link SolarSystemStore#load} replaces null
     * with an empty list. The problem sentences stay beside it. A saved JPL address includes the observing site.
     */
    public List<FetchStep> fetchSteps = new ArrayList<>();

    /** An asteroid or comet with its predicted positions. */
    public static final class Body {
        /** As JPL names it, for example "194 Prokne (A879 FA)" or "C/2018 Y1 (Iwamoto)". */
        public String name;
        public boolean comet;
        /** JPL's orbit solution, for example "JPL#172"; null when only the field search's straight-line motion is known. */
        public String orbitSolution;
        /**
         * What added the object after the fetch of the field, for example "Identify all"; null for an object of that
         * fetch. Such an object can be fainter than {@link SolarSystemObjects#magnitudeLimit}: the field is complete
         * only to that limit.
         */
        public String addedBy;
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

    private static final Pattern NUMBERED_COMET = Pattern.compile("^(\\d+[PDI](?:-[A-Z]+)?)/");
    private static final Pattern PAREN_NUMBERED_COMET = Pattern.compile("\\((\\d+[PDI](?:-[A-Z]+)?)\\)");
    private static final Pattern PROVISIONAL_COMET = Pattern.compile("^([PCDXI]/\\d{4} [A-Z]{1,2}\\d*(?:-[A-Z]+)?)");
    private static final Pattern PAREN_PROVISIONAL_COMET = Pattern.compile("\\(([PCDXI]/\\d{4} [A-Z]{1,2}\\d*(?:-[A-Z]+)?)\\)");
    private static final Pattern A_DESIGNATION = Pattern.compile("^(A/\\d{4} [A-Z]{1,2}\\d*)");
    private static final String PROVISIONAL_ASTEROID_CORE = "(?:\\d{4} (?:[A-Z]{2}\\d*|[PT]-[L123])|A\\d{3} [A-Z]{2})";
    private static final Pattern PROVISIONAL_ASTEROID = Pattern.compile("^\\(?(" + PROVISIONAL_ASTEROID_CORE + ")\\)?$");
    private static final Pattern PAREN_PROVISIONAL_ASTEROID = Pattern.compile("\\((" + PROVISIONAL_ASTEROID_CORE + ")\\)");
    private static final Pattern NUMBERED_ASTEROID = Pattern.compile("^(\\d+)(?: .*)?$");

    /**
     * All designation keys that identify one asteroid or comet across JPL SB-Ident, IMCCE SkyBoT and JPL Horizons:
     * a numbered asteroid yields both its permanent number and any parenthesized provisional designation (so
     * {@code "841917 (2015 RS113)"} matches {@code "(2015 RS113)"} when one catalogue has not yet numbered it), an
     * unnumbered asteroid yields its provisional designation, and a comet yields its designation without its name.
     */
    public static List<String> designationKeys(String name) {
        if (name == null) {
            return List.of();
        }
        String trimmed = name.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        Matcher matcher = NUMBERED_COMET.matcher(trimmed);
        if (matcher.find()) {
            keys.add(matcher.group(1));
            Matcher parenComet = PAREN_PROVISIONAL_COMET.matcher(trimmed);
            if (parenComet.find()) {
                keys.add(parenComet.group(1));
            }
            return List.copyOf(keys);
        }
        matcher = PROVISIONAL_COMET.matcher(trimmed);
        if (matcher.find()) {
            keys.add(matcher.group(1));
            return List.copyOf(keys);
        }
        matcher = PAREN_PROVISIONAL_COMET.matcher(trimmed);
        if (matcher.find()) {
            keys.add(matcher.group(1));
            return List.copyOf(keys);
        }
        matcher = PAREN_NUMBERED_COMET.matcher(trimmed);
        if (matcher.find()) {
            keys.add(matcher.group(1));
            return List.copyOf(keys);
        }
        matcher = A_DESIGNATION.matcher(trimmed);
        if (matcher.find()) {
            keys.add(matcher.group(1));
            return List.copyOf(keys);
        }
        matcher = PROVISIONAL_ASTEROID.matcher(trimmed);
        if (matcher.matches()) {
            keys.add(matcher.group(1));
            return List.copyOf(keys);
        }
        matcher = NUMBERED_ASTEROID.matcher(trimmed);
        if (matcher.matches()) {
            keys.add(matcher.group(1));
            Matcher parenProv = PAREN_PROVISIONAL_ASTEROID.matcher(trimmed);
            while (parenProv.find()) {
                keys.add(parenProv.group(1));
            }
            return List.copyOf(keys);
        }
        return List.of(trimmed);
    }

    /** The primary designation key (first entry of {@link #designationKeys(String)}), or empty for blank input. */
    public static String primaryDesignationKey(String name) {
        List<String> keys = designationKeys(name);
        return keys.isEmpty() ? "" : keys.getFirst();
    }

    /** Whether {@code name} is a permanently numbered asteroid (not a comet or provisional-only designation). */
    public static boolean isNumberedAsteroid(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty() || NUMBERED_COMET.matcher(trimmed).find() || PROVISIONAL_COMET.matcher(trimmed).find()
                || PAREN_PROVISIONAL_COMET.matcher(trimmed).find() || PAREN_NUMBERED_COMET.matcher(trimmed).find()
                || A_DESIGNATION.matcher(trimmed).find() || PROVISIONAL_ASTEROID.matcher(trimmed).matches()) {
            return false;
        }
        return NUMBERED_ASTEROID.matcher(trimmed).matches();
    }

    /** Whether {@code candidateName} should replace {@code existingName} for the same physical object. */
    public static boolean prefersName(String candidateName, String existingName) {
        return !isNumberedAsteroid(existingName) && isNumberedAsteroid(candidateName);
    }

    /**
     * Deduplicates predicted bodies that refer to the same physical object under different designations (for example
     * {@code "841917 (2015 RS113)"} from JPL and {@code "(2015 RS113)"} from SkyBoT in an existing saved file).
     * Prefers an entry with a Horizons orbit solution over straight-line motion and a permanently numbered asteroid
     * name over an unnumbered provisional designation, without mutating the caller's {@link Body} instances.
     */
    public static List<Body> deduplicateBodies(List<Body> bodies) {
        if (bodies == null || bodies.isEmpty()) {
            return List.of();
        }
        List<Body> unique = new ArrayList<>(bodies.size());
        Map<String, Integer> keyToIndex = new HashMap<>();
        for (Body body : bodies) {
            if (body == null) {
                continue;
            }
            List<String> keys = designationKeys(body.name);
            Integer existingIndex = null;
            for (String key : keys) {
                Integer found = keyToIndex.get(key);
                if (found != null) {
                    existingIndex = found;
                    break;
                }
            }
            if (existingIndex == null) {
                int idx = unique.size();
                unique.add(body);
                for (String key : keys) {
                    keyToIndex.put(key, idx);
                }
            } else {
                Body existing = unique.get(existingIndex);
                Body merged = mergeBodies(existing, body);
                unique.set(existingIndex, merged);
                for (String key : keys) {
                    keyToIndex.putIfAbsent(key, existingIndex);
                }
                for (String key : designationKeys(merged.name)) {
                    keyToIndex.putIfAbsent(key, existingIndex);
                }
            }
        }
        return List.copyOf(unique);
    }

    private static Body mergeBodies(Body existing, Body incoming) {
        boolean existingHasOrbit = existing.orbitSolution != null && !existing.orbitSolution.isBlank();
        boolean incomingHasOrbit = incoming.orbitSolution != null && !incoming.orbitSolution.isBlank();
        Body chosen;
        Body other;
        if (incomingHasOrbit && !existingHasOrbit) {
            chosen = incoming;
            other = existing;
        } else if (existingHasOrbit && !incomingHasOrbit) {
            chosen = existing;
            other = incoming;
        } else if (prefersName(incoming.name, existing.name)) {
            chosen = incoming;
            other = existing;
        } else if (!prefersName(existing.name, incoming.name)
                && incoming.samples != null && existing.samples != null
                && incoming.samples.size() > existing.samples.size()) {
            chosen = incoming;
            other = existing;
        } else {
            chosen = existing;
            other = incoming;
        }
        if (prefersName(other.name, chosen.name)) {
            Body copy = new Body();
            copy.name = other.name;
            copy.comet = chosen.comet;
            copy.orbitSolution = chosen.orbitSolution;
            copy.samples = chosen.samples;
            return copy;
        }
        return chosen;
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
    /**
     * These predictions with the objects of {@code extra} that they do not have yet (by any designation), each marked
     * as added by {@code addedBy}: a new object, so saved predictions are never changed in place. The field, span,
     * magnitude limit, observer and source stay those of this fetch, which is complete only to its limit.
     */
    public SolarSystemObjects withAdded(SolarSystemObjects extra, String addedBy) {
        SolarSystemObjects merged = new SolarSystemObjects();
        merged.version = version;
        merged.fetchedAtUtc = fetchedAtUtc;
        merged.field = field;
        merged.startMillis = startMillis;
        merged.endMillis = endMillis;
        merged.magnitudeLimit = magnitudeLimit;
        merged.observer = observer;
        merged.source = source;
        merged.bodies = new ArrayList<>(bodies == null ? List.of() : bodies);
        merged.problems = problems == null ? new ArrayList<>() : new ArrayList<>(problems);
        merged.fetchSteps = fetchSteps == null ? new ArrayList<>() : new ArrayList<>(fetchSteps);
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Body body : merged.bodies) keys.addAll(designationKeys(body.name));
        if (extra != null && extra.bodies != null) {
            for (Body body : extra.bodies) {
                if (body == null || body.name == null) continue;
                List<String> bodyKeys = designationKeys(body.name);
                if (bodyKeys.stream().anyMatch(keys::contains)) continue;
                Body added = new Body();
                added.name = body.name;
                added.comet = body.comet;
                added.orbitSolution = body.orbitSolution;
                added.samples = new ArrayList<>(body.samples);
                added.addedBy = addedBy;
                merged.bodies.add(added);
                keys.addAll(bodyKeys);
            }
        }
        return merged;
    }

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
