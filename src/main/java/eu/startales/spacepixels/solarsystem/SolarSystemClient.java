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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.startales.spacepixels.fits.ObservingSite;
import eu.startales.spacepixels.skycatalog.FetchStep;
import eu.startales.spacepixels.skycatalog.SkyField;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Predicts the known asteroids and comets in a session's field with JPL's online services. JPL's Small-Body
 * Identification API finds the objects in the field at the middle of the observing span; a second search, for
 * near-Earth objects only, covers {@value #NEO_MARGIN_DEG}° around the field. That catches fast ones that cross the
 * field during the night, and older frames need it too: the search picks its candidates by moving current orbits as
 * simple two-body orbits, which for a near-Earth object with a close approach in between can be degrees off (Apophis
 * on 2021-03-06: 4.4°, more than the search's own error estimate), and an object it does not pick is never computed
 * precisely. The near-Earth population is small, so the wide search stays quick. JPL Horizons then
 * gives each object that passes over the frame its positions, predicted magnitude and 3-sigma uncertainty every few
 * minutes over the span plus fourteen hours on each side for clock review. Both services integrate current orbits
 * numerically, so the predictions also hold for frames taken years ago. When Horizons cannot answer for an object, its position and rate from the field search are kept as
 * straight-line motion. Every question is recorded on {@link SolarSystemObjects#fetchSteps}. The Horizons summary
 * succeeded only when at least one asked object received positions; an object that failed is kept, and its step says
 * it uses the field search's straight-line motion. Interrupting the fetching thread cancels the request in progress
 * and marks the questions not yet sent.
 */
public final class SolarSystemClient {

    /**
     * Receives what is being fetched, and the steps finished so far. {@link #stage} is the only method a caller
     * must implement.
     */
    public interface Progress {
        /** The question in progress. Called on the fetching thread. */
        void stage(String text);

        /**
         * The steps recorded so far. The list is immutable. The default ignores it, so a progress report that only
         * names the current question keeps working. Called on the fetching thread.
         */
        default void steps(List<FetchStep> steps) {
        }
    }

    /** One HTTP GET that returns the body of a successful answer; tests replace it with recorded answers. */
    interface Http {
        String get(URI uri, Duration timeout) throws IOException, InterruptedException;
    }

    /** Fastest motion allowed for when widening the field for all objects, in degrees per day (fast main-belt asteroids). */
    static final double GENERAL_RATE_DEG_PER_DAY = 1.0;
    static final double MIN_MARGIN_DEG = 0.02;
    /** How far around the field near-Earth objects are searched, in degrees (see the class description). */
    static final double NEO_MARGIN_DEG = 5.0;
    /** More objects than this get the field search's straight-line motion instead of a Horizons request each. */
    static final int MAX_REFINED = 300;
    /** Motion margin for the field search and straight-line fallback around the observing span. */
    static final long PAD_MILLIS = SolarSystemObjects.OBSERVING_SPAN_MARGIN_MILLIS;
    /** Horizons samples bracket possible local-time/UTC clock mistakes without extrapolating the report comparison. */
    static final long CLOCK_CHECK_PAD_MILLIS = 14 * 60 * 60_000L;
    static final long STEP_MILLIS = 10 * 60_000L;
    /** Objects faster than this (arcseconds per hour on the sky) are close, and their paths bend within minutes. */
    static final double FAST_RATE_ARCSEC_PER_HOUR = 1000;
    static final long FAST_STEP_MILLIS = 2 * 60_000L;
    /** Room around the frame, as a fraction of its size, in which a path still counts as on the frame. */
    static final double FRAME_ROOM = 0.05;

    static final String HORIZONS = "https://ssd.jpl.nasa.gov/api/horizons.api";
    private static final Duration SB_IDENT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration HORIZONS_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final DateTimeFormatter HORIZONS_INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HORIZONS_OUTPUT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("yyyy-MMM-dd HH:mm")
            .optionalStart().appendPattern(":ss").optionalEnd()
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .toFormatter(Locale.US);

    private final Http http;
    /** How many crossing objects are asked of Horizons. Production uses {@link #MAX_REFINED}. */
    private final int maxRefined;

    public SolarSystemClient() {
        this(defaultHttp(), MAX_REFINED);
    }

    /** A client whose HTTP GET is answered by {@code http}. The desktop uses {@link #SolarSystemClient()}. */
    SolarSystemClient(Http http) {
        this(http, MAX_REFINED);
    }

    /**
     * A client with a test limit on how many objects Horizons is asked for. The desktop uses {@link #MAX_REFINED}.
     */
    SolarSystemClient(Http http, int maxRefined) {
        this.http = http;
        this.maxRefined = maxRefined;
    }

    /** An object found by the field search: its position at {@code epochMillis} and its rates on the sky. */
    record Candidate(String name, boolean comet, double ra, double dec, Double magnitude,
                     double raRateArcsecPerHour, double decRateArcsecPerHour, long epochMillis) {
    }

    /** The objects of one field search and the API version that answered. */
    record Search(List<Candidate> candidates, String version) {
    }

    /**
     * Fetches the predictions. A failing service is noted in {@link SolarSystemObjects#problems}; what the others
     * answered is kept. When the main field search fails, nothing else is asked and {@link SolarSystemObjects#source}
     * is empty: the fetch failed.
     *
     * @throws InterruptedException when the thread is interrupted, which cancels the fetch
     */
    public SolarSystemObjects fetch(SolarSystemQuery query, Progress progress) throws InterruptedException {
        SolarSystemObjects result = new SolarSystemObjects();
        result.fetchedAtUtc = Instant.now().toString();
        result.field = SkyField.of(query.transformer(), query.width(), query.height());
        result.startMillis = query.startMillis();
        result.endMillis = query.endMillis();
        result.magnitudeLimit = query.magnitudeLimit();
        result.observer = query.observer().description();

        boolean fieldDone = false;
        boolean neoDone = false;
        boolean horizonsDone = false;
        List<Candidate> crossing = new ArrayList<>();
        int refined = 0;
        int nextHorizons = 0;
        int horizonsOk = 0;
        List<FetchStep> objectFailures = new ArrayList<>();
        try {
            long middle = query.startMillis() + (query.endMillis() - query.startMillis()) / 2;
            double halfSpanDays = ((query.endMillis() - query.startMillis()) / 2.0 + PAD_MILLIS) / 86_400_000.0;
            Map<String, Candidate> found = new LinkedHashMap<>();
            String fieldVersion = null;
            progress.stage("asteroids and comets in the field (JPL)");
            String fieldUrl = fieldSearchUrl(query, result.field, middle,
                    Math.max(MIN_MARGIN_DEG, GENERAL_RATE_DEG_PER_DAY * halfSpanDays), null);
            try {
                Search search = loadField(fieldUrl, middle);
                search.candidates().forEach(candidate -> found.putIfAbsent(candidate.name(), candidate));
                fieldVersion = search.version();
                addStep(result, progress, FetchStep.of("JPL SB-Ident", "Field search", FetchStep.Status.SUCCEEDED,
                        count(search.candidates().size(), "object"), "", fieldUrl, null));
            } catch (IOException problem) {
                // Without the main search the result would be incomplete: it is a failure, with no source.
                addStep(result, progress, FetchStep.of("JPL SB-Ident", "Field search", FetchStep.Status.FAILED,
                        "", problem.getMessage(), fieldUrl, null));
                addStep(result, progress, FetchStep.of("JPL SB-Ident", "Near-Earth objects", FetchStep.Status.NOT_ASKED,
                        "", "Not sent, because the field search failed.", null, null));
                addStep(result, progress, FetchStep.of("JPL Horizons", "Positions", FetchStep.Status.NOT_ASKED,
                        "", "Not sent, because the field search failed.", null, null));
                result.problems.add("Field search (JPL SB-Ident): " + problem.getMessage());
                result.source = "";
                return result;
            }
            fieldDone = true;
            progress.stage("fast near-Earth objects that may cross the field (JPL)");
            String neoUrl = fieldSearchUrl(query, result.field, middle, NEO_MARGIN_DEG, "neo");
            try {
                Search search = loadField(neoUrl, middle);
                search.candidates().forEach(candidate -> found.putIfAbsent(candidate.name(), candidate));
                fieldVersion = fieldVersion == null ? search.version() : fieldVersion;
                addStep(result, progress, FetchStep.of("JPL SB-Ident", "Near-Earth objects", FetchStep.Status.SUCCEEDED,
                        count(search.candidates().size(), "object"), "", neoUrl, null));
            } catch (IOException problem) {
                addStep(result, progress, FetchStep.of("JPL SB-Ident", "Near-Earth objects", FetchStep.Status.FAILED,
                        "", problem.getMessage(), neoUrl, null));
                result.problems.add("Near-Earth object search (JPL SB-Ident): " + problem.getMessage());
            }
            neoDone = true;

            for (Candidate candidate : found.values()) {
                if (crossesFrame(query, straightLine(candidate, query).samples)) {
                    crossing.add(candidate);
                }
            }
            crossing.sort(Comparator.comparingDouble(candidate -> candidate.magnitude() == null ? Double.MAX_VALUE : candidate.magnitude()));
            refined = Math.min(maxRefined, crossing.size());
            String horizonsVersion = null;
            for (int i = 0; i < crossing.size(); i++) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                Candidate candidate = crossing.get(i);
                SolarSystemObjects.Body body = null;
                if (i < refined) {
                    progress.stage(String.format(Locale.US, "positions of object %d of %d (JPL Horizons): %s", i + 1, refined, candidate.name()));
                    String url = horizonsUri(query, candidate).toString();
                    try {
                        String answer = http.get(URI.create(url), HORIZONS_TIMEOUT);
                        body = parseHorizons(answer, candidate);
                        horizonsVersion = version(answer);
                        horizonsOk++;
                    } catch (IOException problem) {
                        objectFailures.add(FetchStep.of("JPL Horizons", candidate.name(), FetchStep.Status.FAILED,
                                "Kept with the field search's straight-line motion, without an uncertainty.",
                                problem.getMessage(), url, null));
                        publish(result, progress, objectFailures);
                    }
                    nextHorizons = i + 1;
                }
                if (body == null) {
                    body = straightLine(candidate, query);
                }
                if (crossesFrame(query, body.samples.stream().filter(sample -> sample.timeMillis >= query.startMillis() - PAD_MILLIS
                        && sample.timeMillis <= query.endMillis() + PAD_MILLIS).toList())) {
                    result.bodies.add(body);
                }
            }
            result.fetchSteps.add(horizonsSummary(horizonsOk, refined, false));
            result.fetchSteps.addAll(objectFailures);
            if (!objectFailures.isEmpty()) {
                result.problems.add(String.format(Locale.US, "JPL Horizons could not give positions for %d of %d objects; "
                        + "they use the field search's position and rate as straight-line motion, without an uncertainty.",
                        objectFailures.size(), refined));
            }
            if (crossing.size() > refined) {
                result.problems.add(fainterThanLimit(refined, crossing.size()));
                result.fetchSteps.add(FetchStep.of("JPL Horizons", "Fainter objects", FetchStep.Status.NOT_ASKED,
                        count(crossing.size() - refined, "object") + " not asked",
                        fainterThanLimit(refined, crossing.size()), null, null));
            }
            horizonsDone = true;
            progress.steps(List.copyOf(result.fetchSteps));
            List<String> sources = new ArrayList<>();
            if (fieldVersion != null) sources.add("JPL SB-Ident API " + fieldVersion);
            if (horizonsVersion != null) sources.add("JPL Horizons API " + horizonsVersion);
            result.source = String.join(", ", sources);
            return result;
        } catch (InterruptedException interrupted) {
            if (!fieldDone) {
                addStep(result, progress, FetchStep.of("JPL SB-Ident", "Field search", FetchStep.Status.NOT_ASKED,
                        "", "The fetch was cancelled.", null, null));
                addStep(result, progress, FetchStep.of("JPL SB-Ident", "Near-Earth objects", FetchStep.Status.NOT_ASKED,
                        "", "Not sent.", null, null));
                addStep(result, progress, FetchStep.of("JPL Horizons", "Positions", FetchStep.Status.NOT_ASKED,
                        "", "Not sent.", null, null));
            } else if (!neoDone) {
                addStep(result, progress, FetchStep.of("JPL SB-Ident", "Near-Earth objects", FetchStep.Status.NOT_ASKED,
                        "", "The fetch was cancelled.", null, null));
                addStep(result, progress, FetchStep.of("JPL Horizons", "Positions", FetchStep.Status.NOT_ASKED,
                        "", "Not sent.", null, null));
            } else if (!horizonsDone) {
                result.fetchSteps.add(horizonsSummary(horizonsOk, nextHorizons, true));
                result.fetchSteps.addAll(objectFailures);
                int remaining = refined - nextHorizons;
                if (remaining > 0) {
                    result.fetchSteps.add(FetchStep.of("JPL Horizons", "Remaining objects", FetchStep.Status.NOT_ASKED,
                            count(remaining, "object"), "The fetch was cancelled before these were asked.", null, null));
                }
                if (crossing.size() > refined) {
                    result.fetchSteps.add(FetchStep.of("JPL Horizons", "Fainter objects", FetchStep.Status.NOT_ASKED,
                            count(crossing.size() - refined, "object") + " not asked",
                            fainterThanLimit(refined, crossing.size()), null, null));
                }
                progress.steps(List.copyOf(result.fetchSteps));
            }
            throw interrupted;
        }
    }

    /** Appends one step and publishes the list so far. The result owns the list. */
    private static void addStep(SolarSystemObjects result, Progress progress, FetchStep step) {
        result.fetchSteps.add(step);
        progress.steps(List.copyOf(result.fetchSteps));
    }

    /** Publishes the recorded steps plus object failures that are not on the result yet. */
    private static void publish(SolarSystemObjects result, Progress progress, List<FetchStep> extra) {
        List<FetchStep> shown = new ArrayList<>(result.fetchSteps);
        shown.addAll(extra);
        progress.steps(List.copyOf(shown));
    }

    /** The Horizons summary. It succeeded only when at least one asked object received positions. */
    private static FetchStep horizonsSummary(int received, int asked, boolean cancelledBeforeAny) {
        if (asked == 0) {
            return FetchStep.of("JPL Horizons", "Positions", FetchStep.Status.NOT_ASKED, "",
                    cancelledBeforeAny ? "The fetch was cancelled." : "No object crosses the frame.", null, null);
        }
        String outcome = String.format(Locale.US, "%d of %d objects received positions", received, asked);
        if (received > 0) {
            return FetchStep.of("JPL Horizons", "Positions", FetchStep.Status.SUCCEEDED, outcome,
                    received < asked ? "The objects that failed are listed below." : "", null, null);
        }
        return FetchStep.of("JPL Horizons", "Positions", FetchStep.Status.FAILED, outcome,
                "Every asked object failed.", null, null);
    }

    /** The existing note that objects past the Horizons limit keep the field search's straight-line motion. */
    private static String fainterThanLimit(int asked, int found) {
        return String.format(Locale.US, "Only the %d brightest of %d objects were asked of JPL Horizons; "
                + "the others use the field search's straight-line motion. Lower the magnitude limit for fewer objects.",
                asked, found);
    }

    /** "0 objects", "1 object" or "2 objects". */
    private static String count(int amount, String noun) {
        return String.format(Locale.US, "%,d %s%s", amount, noun, amount == 1 ? "" : "s");
    }

    // ==========================================
    // FIELD SEARCH (SB-IDENT)
    // ==========================================

    /** The SB-Ident address for this search, or null when the field, time or observer cannot form one. */
    private static String fieldSearchUrl(SolarSystemQuery query, SkyField field, long epochMillis, double marginDeg, String group) {
        double[] box = searchBox(field, marginDeg);
        SolarSystemQuery.Observer observer = query.observer();
        ObservingSite site = observer.site();
        return JplSbIdentUrlBuilder.buildField(observer.observatoryCode(),
                site == null ? null : site.latitudeDeg(), site == null ? null : site.longitudeDeg(),
                site == null ? null : site.altitudeMeters(), epochMillis, box[0], box[1], box[2], box[3],
                query.magnitudeLimit(), group);
    }

    /** The objects of one SB-Ident request. A missing address is the same failure as a search that cannot be formed. */
    private Search loadField(String url, long epochMillis) throws IOException, InterruptedException {
        if (url == null) {
            throw new IOException("the search could not be formed from the field, time and observer.");
        }
        return parseFieldSearch(http.get(URI.create(url), SB_IDENT_TIMEOUT), epochMillis);
    }

    /**
     * The search box of SB-Ident around the field widened by {@code marginDeg} on the sky: {RA centre, Dec centre, RA
     * half-width, Dec half-width} in degrees of RA and Dec. A field that reaches a pole is searched all round in RA.
     */
    static double[] searchBox(SkyField field, double marginDeg) {
        double decMin = Double.MAX_VALUE;
        double decMax = -Double.MAX_VALUE;
        double raOffset = 0;
        for (int i = 0; i < field.cornerRa.length; i++) {
            decMin = Math.min(decMin, field.cornerDec[i]);
            decMax = Math.max(decMax, field.cornerDec[i]);
            raOffset = Math.max(raOffset, Math.abs(wrap180(field.cornerRa[i] - field.centerRa)));
        }
        decMin = Math.max(-90, decMin - marginDeg);
        decMax = Math.min(90, decMax + marginDeg);
        double widest = Math.max(Math.abs(decMin), Math.abs(decMax));
        double raHalf = raOffset + marginDeg / Math.max(1e-3, Math.cos(Math.toRadians(widest)));
        if (raHalf >= 180 || widest >= 89.99) {
            raHalf = 180;
        }
        return new double[]{field.centerRa, (decMin + decMax) / 2, raHalf, (decMax - decMin) / 2};
    }

    /** The objects of an SB-Ident answer, at {@code epochMillis}. */
    static Search parseFieldSearch(String answer, long epochMillis) throws IOException {
        JsonObject root = json(answer);
        String version = version(root);
        if (!root.has("fields_second") || !root.has("data_second_pass")) {
            // A field without objects comes back with a count of 0, or only with "warning": "no matching records".
            if (root.has("n_second_pass") || root.has("n_first_pass") || root.has("warning") || root.has("observer")) {
                return new Search(List.of(), version);
            }
            throw new IOException("answered without a list of objects.");
        }
        JsonArray fields = root.getAsJsonArray("fields_second");
        int name = column(fields, "Object name");
        int ra = column(fields, "Astrometric RA");
        int dec = column(fields, "Astrometric Dec");
        int magnitude = column(fields, "Visual magnitude");
        int raRate = column(fields, "RA rate");
        int decRate = column(fields, "Dec rate");
        if (name < 0 || ra < 0 || dec < 0) {
            throw new IOException("answered without the expected columns.");
        }
        List<Candidate> candidates = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("data_second_pass")) {
            JsonArray row = element.getAsJsonArray();
            String objectName = text(row, name);
            Double raDeg = parseSexagesimal(text(row, ra), 15);
            Double decDeg = parseSexagesimal(text(row, dec), 1);
            if (objectName == null || raDeg == null || decDeg == null) {
                continue;
            }
            Double raRateValue = leadingNumber(text(row, raRate));
            Double decRateValue = leadingNumber(text(row, decRate));
            candidates.add(new Candidate(objectName.trim(), isComet(objectName), raDeg, decDeg, leadingNumber(text(row, magnitude)),
                    raRateValue == null ? 0 : raRateValue, decRateValue == null ? 0 : decRateValue, epochMillis));
        }
        return new Search(candidates, version);
    }

    // ==========================================
    // POSITIONS (HORIZONS)
    // ==========================================

    /** The candidate's Horizons table over the observing span plus clock-review padding, with the admitted observer. */
    static URI horizonsUri(SolarSystemQuery query, Candidate candidate) {
        double rate = Math.hypot(candidate.raRateArcsecPerHour(), candidate.decRateArcsecPerHour());
        long step = rate > FAST_RATE_ARCSEC_PER_HOUR ? FAST_STEP_MILLIS : STEP_MILLIS;
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("format", "json");
        parameters.put("COMMAND", quoted(horizonsCommand(candidate.name())));
        parameters.put("OBJ_DATA", quoted("NO"));
        parameters.put("MAKE_EPHEM", quoted("YES"));
        parameters.put("EPHEM_TYPE", quoted("OBSERVER"));
        SolarSystemQuery.Observer observer = query.observer();
        if (observer.observatoryCode() != null) {
            parameters.put("CENTER", quoted(observer.observatoryCode() + "@399"));
        } else {
            ObservingSite site = observer.site();
            parameters.put("CENTER", quoted("coord@399"));
            parameters.put("COORD_TYPE", quoted("GEODETIC"));
            parameters.put("SITE_COORD", quoted(String.format(Locale.US, "%.6f,%.6f,%.4f",
                    site.longitudeDeg(), site.latitudeDeg(), site.altitudeMeters() / 1000.0)));
        }
        parameters.put("START_TIME", quoted(HORIZONS_INPUT.format(Instant.ofEpochMilli(query.startMillis() - CLOCK_CHECK_PAD_MILLIS))));
        parameters.put("STOP_TIME", quoted(HORIZONS_INPUT.format(Instant.ofEpochMilli(query.endMillis() + CLOCK_CHECK_PAD_MILLIS))));
        parameters.put("STEP_SIZE", quoted((step / 60_000L) + " m"));
        parameters.put("QUANTITIES", quoted("1,9,36"));
        parameters.put("ANG_FORMAT", quoted("DEG"));
        parameters.put("CSV_FORMAT", quoted("YES"));
        parameters.put("EXTRA_PREC", quoted("YES"));
        parameters.put("TIME_DIGITS", quoted("SECONDS"));
        StringBuilder url = new StringBuilder(HORIZONS).append('?');
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            url.append(url.charAt(url.length() - 1) == '?' ? "" : "&").append(parameter.getKey()).append('=')
                    .append(URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8));
        }
        return URI.create(url.toString());
    }

    private static final Pattern NUMBERED_COMET = Pattern.compile("^(\\d+[PDI](-[A-Z]+)?)/");
    private static final Pattern PROVISIONAL_COMET = Pattern.compile("^([PCDXI]/\\d{4} [A-Z]{1,2}\\d*(-[A-Z]+)?)");
    private static final Pattern A_DESIGNATION = Pattern.compile("^(A/\\d{4} [A-Z]{1,2}\\d*)");
    private static final Pattern PROVISIONAL_ASTEROID = Pattern.compile("^\\(?(\\d{4} (?:[A-Z]{2}\\d*|[PT]-[L123]))\\)?$");
    private static final Pattern NUMBERED_ASTEROID = Pattern.compile("^(\\d+)(?: .*)?$");

    /** Whether a JPL object name is a comet's, such as "41P/Tuttle-Giacobini-Kresak" or "C/2018 Y1 (Iwamoto)". */
    static boolean isComet(String name) {
        String trimmed = name == null ? "" : name.trim();
        return NUMBERED_COMET.matcher(trimmed).find() || PROVISIONAL_COMET.matcher(trimmed).find();
    }

    /**
     * The Horizons COMMAND for a JPL object name: "194;" for a numbered asteroid (the semicolon keeps 301 Bavaria from
     * being the Moon), "DES=2014 QV125;" for an unnumbered one, and for a comet its designation with the
     * closest-apparition orbit, without fragments for a parent comet.
     */
    static String horizonsCommand(String name) {
        String trimmed = name.trim();
        Matcher matcher = NUMBERED_COMET.matcher(trimmed);
        if (matcher.find()) {
            return "DES=" + matcher.group(1) + ";CAP" + (matcher.group(2) == null ? ";NOFRAG" : "");
        }
        matcher = PROVISIONAL_COMET.matcher(trimmed);
        if (matcher.find()) {
            return "DES=" + matcher.group(1) + ";CAP" + (matcher.group(2) == null ? ";NOFRAG" : "");
        }
        matcher = A_DESIGNATION.matcher(trimmed);
        if (matcher.find()) {
            return "DES=" + matcher.group(1) + ";";
        }
        matcher = PROVISIONAL_ASTEROID.matcher(trimmed);
        if (matcher.matches()) {
            return "DES=" + matcher.group(1) + ";";
        }
        matcher = NUMBERED_ASTEROID.matcher(trimmed);
        if (matcher.matches()) {
            return matcher.group(1) + ";";
        }
        return trimmed + ";";
    }

    private static final Pattern SOLUTION = Pattern.compile("Target body name:.*\\{source:\\s*([^}]+)}");

    /** The body of a Horizons answer: its positions, magnitudes and uncertainties, and the orbit solution. */
    static SolarSystemObjects.Body parseHorizons(String answer, Candidate candidate) throws IOException {
        JsonObject root = json(answer);
        if (!root.has("result")) {
            throw new IOException("Horizons answered without a result.");
        }
        String result = root.get("result").getAsString();
        int startOfTable = result.indexOf("$$SOE");
        int endOfTable = result.indexOf("$$EOE");
        if (startOfTable < 0 || endOfTable < startOfTable) {
            throw new IOException("Horizons gave no positions for " + candidate.name() + ".");
        }
        String[] head = result.substring(0, startOfTable).split("\\R");
        List<String> columns = null;
        for (int i = head.length - 1; i >= 0; i--) {
            if (head[i].contains("R.A.") && head[i].contains("DEC")) {
                columns = new ArrayList<>();
                for (String column : head[i].split(",")) {
                    columns.add(column.trim());
                }
                break;
            }
        }
        if (columns == null) {
            throw new IOException("Horizons answered without the expected columns.");
        }
        int ra = indexStartingWith(columns, "R.A.");
        int dec = indexStartingWith(columns, "DEC_");
        int magnitude = Math.max(indexStartingWith(columns, "APmag"), indexStartingWith(columns, "T-mag"));
        int raSigma = indexStartingWith(columns, "RA_3sigma");
        int decSigma = indexStartingWith(columns, "DEC_3sigma");
        if (ra < 0 || dec < 0) {
            throw new IOException("Horizons answered without positions.");
        }
        SolarSystemObjects.Body body = new SolarSystemObjects.Body();
        body.name = candidate.name();
        body.comet = candidate.comet();
        Matcher solution = SOLUTION.matcher(result);
        body.orbitSolution = solution.find() ? solution.group(1).trim() : "JPL Horizons";
        for (String line : result.substring(startOfTable + 5, endOfTable).split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] values = line.split(",", -1);
            Double raValue = number(values, ra);
            Double decValue = number(values, dec);
            if (raValue == null || decValue == null) {
                continue;
            }
            SolarSystemObjects.Sample sample = new SolarSystemObjects.Sample();
            try {
                sample.timeMillis = LocalDateTime.parse(values[0].trim(), HORIZONS_OUTPUT).toInstant(ZoneOffset.UTC).toEpochMilli();
            } catch (RuntimeException unreadable) {
                throw new IOException("Horizons answered with an unreadable time: " + values[0].trim());
            }
            sample.ra = raValue;
            sample.dec = decValue;
            sample.magnitude = number(values, magnitude);
            sample.raSigmaArcsec = number(values, raSigma);
            sample.decSigmaArcsec = number(values, decSigma);
            body.samples.add(sample);
        }
        if (body.samples.isEmpty()) {
            throw new IOException("Horizons gave no positions for " + candidate.name() + ".");
        }
        return body;
    }

    // ==========================================
    // STRAIGHT-LINE MOTION AND THE FRAME
    // ==========================================

    /** The candidate moving in a straight line at its rates over the padded span, sampled like a Horizons answer. */
    static SolarSystemObjects.Body straightLine(Candidate candidate, SolarSystemQuery query) {
        SolarSystemObjects.Body body = new SolarSystemObjects.Body();
        body.name = candidate.name();
        body.comet = candidate.comet();
        long start = query.startMillis() - PAD_MILLIS;
        long end = query.endMillis() + PAD_MILLIS;
        double cosDec = Math.max(1e-6, Math.cos(Math.toRadians(candidate.dec())));
        for (long time = start; ; time = Math.min(end, time + STEP_MILLIS)) {
            double hours = (time - candidate.epochMillis()) / 3_600_000.0;
            SolarSystemObjects.Sample sample = new SolarSystemObjects.Sample();
            sample.timeMillis = time;
            sample.ra = ((candidate.ra() + candidate.raRateArcsecPerHour() * hours / 3600.0 / cosDec) % 360.0 + 360.0) % 360.0;
            sample.dec = Math.max(-90, Math.min(90, candidate.dec() + candidate.decRateArcsecPerHour() * hours / 3600.0));
            sample.magnitude = candidate.magnitude();
            body.samples.add(sample);
            if (time >= end) {
                break;
            }
        }
        return body;
    }

    /** Whether the path through these samples passes over the frame, or within {@link #FRAME_ROOM} of it. */
    static boolean crossesFrame(SolarSystemQuery query, List<SolarSystemObjects.Sample> samples) {
        double roomX = query.width() * FRAME_ROOM;
        double roomY = query.height() * FRAME_ROOM;
        double left = -0.5 - roomX;
        double top = -0.5 - roomY;
        double right = query.width() - 0.5 + roomX;
        double bottom = query.height() - 0.5 + roomY;
        double[] previous = null;
        for (SolarSystemObjects.Sample sample : samples) {
            double[] pixel = query.transformer().skyToPixel(sample.ra, sample.dec);
            if (pixel == null || !Double.isFinite(pixel[0]) || !Double.isFinite(pixel[1])) {
                previous = null;
                continue;
            }
            if (pixel[0] >= left && pixel[0] <= right && pixel[1] >= top && pixel[1] <= bottom) {
                return true;
            }
            if (previous != null && segmentCrosses(previous, pixel, left, top, right, bottom)) {
                return true;
            }
            previous = pixel;
        }
        return false;
    }

    /** Whether the segment from a to b passes through the rectangle (Liang–Barsky clipping). */
    private static boolean segmentCrosses(double[] a, double[] b, double left, double top, double right, double bottom) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {a[0] - left, right - a[0], a[1] - top, bottom - a[1]};
        double enter = 0;
        double leave = 1;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) {
                    return false;
                }
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) {
                    enter = Math.max(enter, t);
                } else {
                    leave = Math.min(leave, t);
                }
            }
        }
        return enter <= leave;
    }

    // ==========================================
    // HTTP AND PARSING HELPERS
    // ==========================================

    private static Http defaultHttp() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
        return (uri, timeout) -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Accept", "application/json").header("User-Agent", "SpacePixels").GET().build();
            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (java.net.http.HttpTimeoutException e) {
                throw new IOException("JPL did not answer within " + timeout.toSeconds() + " s.");
            } catch (java.net.ConnectException e) {
                throw new IOException("JPL cannot be reached (no internet connection?).");
            }
            String body = response.body() == null ? "" : response.body();
            if (response.statusCode() != 200) {
                throw new IOException("JPL answered HTTP " + response.statusCode() + messageOf(body));
            }
            return body;
        };
    }

    /** ": " and the message of a JPL error answer, or "." when it has none. */
    private static String messageOf(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            for (String key : new String[]{"message", "error"}) {
                if (root.has(key) && root.get(key).isJsonPrimitive()) {
                    return ": " + root.get(key).getAsString();
                }
            }
        } catch (RuntimeException notJson) {
            // fall through
        }
        return ".";
    }

    private static JsonObject json(String answer) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(answer).getAsJsonObject();
            if (root.has("error") && !root.get("error").isJsonNull()) {
                throw new IOException(root.get("error").getAsString());
            }
            return root;
        } catch (RuntimeException notJson) {
            throw new IOException("answered with something other than the expected JSON.");
        }
    }

    private static String version(JsonObject root) {
        if (root.has("signature") && root.get("signature").isJsonObject()) {
            JsonObject signature = root.getAsJsonObject("signature");
            if (signature.has("version")) {
                return signature.get("version").getAsString();
            }
        }
        return "?";
    }

    private static String version(String answer) throws IOException {
        return version(json(answer));
    }

    private static int column(JsonArray fields, String prefix) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).getAsString().startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexStartingWith(List<String> columns, String prefix) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static String text(JsonArray row, int index) {
        return index < 0 || index >= row.size() || row.get(index).isJsonNull() ? null : row.get(index).getAsString();
    }

    private static Double number(String[] values, int index) {
        return index < 0 || index >= values.length ? null : leadingNumber(values[index]);
    }

    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*([-+]?\\d+(?:\\.\\d*)?(?:[eE][-+]?\\d+)?|[-+]?\\.\\d+)");

    /** The number at the start of a value, as in "13.0T" or "4.444E+01"; null for "n.a." or nothing. */
    static Double leadingNumber(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = LEADING_NUMBER.matcher(value);
        return matcher.find() ? Double.valueOf(matcher.group(1)) : null;
    }

    private static final Pattern SEXAGESIMAL = Pattern.compile("^\\s*([-+]?)(\\d+)\\D+(\\d+)\\D+(\\d+(?:\\.\\d*)?)");

    /** "10:47:15.06" (hours, {@code unit} 15) or "+04 57'11.7\"" (degrees, {@code unit} 1) in degrees; null if unreadable. */
    static Double parseSexagesimal(String value, double unit) {
        if (value == null) {
            return null;
        }
        Matcher matcher = SEXAGESIMAL.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        double magnitude = Integer.parseInt(matcher.group(2)) + Integer.parseInt(matcher.group(3)) / 60.0
                + Double.parseDouble(matcher.group(4)) / 3600.0;
        return ("-".equals(matcher.group(1)) ? -magnitude : magnitude) * unit;
    }

    private static String quoted(String value) {
        return "'" + value + "'";
    }

    private static double wrap180(double degrees) {
        return ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }
}
