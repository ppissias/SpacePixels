/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.skycatalog;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the catalogue of a field from the online TAP services: deep-sky objects from SIMBAD, variable stars from
 * AAVSO VSX and stars from Gaia DR3, all at CDS (Strasbourg), with ESA's Gaia archive as the fallback for the stars.
 * Each source that fails is noted in {@link SkyCatalogue#problems}; the others are still kept. The four SIMBAD
 * queries are asked separately, so a later failure keeps the objects already parsed. A VizieR failure is kept as
 * its own step when the ESA archive then answers. Faint depth patches that answer are kept when another patch
 * fails. Every question is also recorded on {@link SkyCatalogue#fetchSteps}. Interrupting the fetching thread
 * cancels the request in progress and marks the questions not yet sent.
 */
public final class SkyCatalogueClient {

    /**
     * Receives which source is being fetched, and the steps finished so far. {@link #stage} is the only method a
     * caller must implement.
     */
    public interface Progress {
        /** Which of the three sources is being fetched. Called on the fetching thread. */
        void stage(int number, int total, String text);

        /**
         * The steps recorded so far. The list is immutable. The default ignores it, so a progress report that only
         * names the current source keeps working. Called on the fetching thread.
         */
        default void steps(List<FetchStep> steps) {
        }
    }

    /**
     * One TAP query. Tests supply recorded answers; the desktop client sends the query over HTTP.
     * The fetching thread calls it, and interruption cancels the query.
     */
    interface QueryTransport {
        /** The CSV table of one ADQL query. */
        CsvTable query(String service, String adql, int maxRecords, Duration timeout) throws IOException, InterruptedException;
    }

    static final String VIZIER_TAP = "https://tapvizier.cds.unistra.fr/TAPVizieR/tap/sync";
    static final String ESA_GAIA_TAP = "https://gea.esac.esa.int/tap-server/tap/sync";
    static final String SIMBAD_TAP = "https://simbad.cds.unistra.fr/simbad/sim-tap/sync";
    /** More stars than this would take minutes; the depth should be lowered instead. */
    static final int MAX_STARS = 300_000;
    /** Variable stars are kept to this much fainter than the star depth, at their brightest. */
    static final double VARIABLE_MAGNITUDE_MARGIN = 1.0;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STAR_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration QUICK_TIMEOUT = Duration.ofSeconds(60);
    private static final Pattern QUERY_ERROR = Pattern.compile(
            "<INFO[^>]*name=\"QUERY_STATUS\"[^>]*value=\"ERROR\"[^>]*>(.*?)</INFO>", Pattern.DOTALL);

    /**
     * Catalogue names SIMBAD knows the classic deep-sky objects by, in the order a label prefers them, with the
     * name shown. An identifier is used only when the catalogue number is a single word: "NGC 2251" is the cluster,
     * "NGC 2251 103" one of its stars.
     */
    private static final String[][] DEEP_SKY_CATALOGUES = {
            {"M", "M"}, {"NGC", "NGC"}, {"IC", "IC"}, {"SH", "Sh"}, {"Cl Collinder", "Collinder"},
            {"Cl Melotte", "Melotte"}, {"Cl Trumpler", "Trumpler"}, {"Barnard", "Barnard"}, {"LDN", "LDN"},
            {"LBN", "LBN"}, {"VDB", "vdB"}, {"PN A66", "Abell"}, {"ACO", "Abell"}, {"UGC", "UGC"}};
    /** Galaxies without one of those names are kept from this size, in arcminutes. */
    static final double MIN_GALAXY_ARCMIN = 1.0;
    /** Stars with a proper name are kept to this V magnitude: the bright stars people know. */
    static final double NAMED_STAR_MAGNITUDE = 5.0;

    private final QueryTransport transport;
    /** Stars kept from one answer before the depth is reported as only partly fetched. Production uses {@link #MAX_STARS}. */
    private final int starCap;

    public SkyCatalogueClient() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.transport = (service, adql, maxRecords, timeout) -> httpQuery(http, service, adql, maxRecords, timeout);
        this.starCap = MAX_STARS;
    }

    /** A client whose TAP queries are answered by {@code transport}. The desktop uses {@link #SkyCatalogueClient()}. */
    SkyCatalogueClient(QueryTransport transport) {
        this(transport, MAX_STARS);
    }

    /**
     * A client with a test limit on how many stars one answer may keep. The desktop uses {@link #MAX_STARS}.
     */
    SkyCatalogueClient(QueryTransport transport, int starCap) {
        this.transport = transport;
        this.starCap = starCap;
    }

    /**
     * Fetches the field: deep-sky objects and variables first (a second or two each), then the stars to this Gaia
     * G magnitude (half a minute or more).
     *
     * @throws InterruptedException when the thread is interrupted, which cancels the fetch
     */
    public SkyCatalogue fetch(SkyField field, double magnitudeLimit, Progress progress) throws InterruptedException {
        Attempt attempt = new Attempt(field, magnitudeLimit, progress);
        try {
            fetchInto(attempt);
            return attempt.catalogue;
        } catch (InterruptedException interrupted) {
            attempt.cancel();
            throw interrupted;
        }
    }

    /** Asks each catalogue service and records the step, keeping whatever has already been parsed. */
    private void fetchInto(Attempt attempt) throws InterruptedException {
        SkyCatalogue catalogue = attempt.catalogue;
        SkyField field = catalogue.field;
        double magnitudeLimit = catalogue.starMagnitudeLimit;
        Progress progress = attempt.progress;

        progress.stage(1, 3, "deep-sky objects (SIMBAD)");
        CsvTable named = ask(attempt, "SIMBAD", "Deep-sky catalogue names", "Deep-sky catalogue names (SIMBAD)",
                SIMBAD_TAP, namedDeepSkyQuery(field), 20_000, QUICK_TIMEOUT);
        CsvTable galaxies = ask(attempt, "SIMBAD", "Large galaxies", "Large galaxies (SIMBAD)",
                SIMBAD_TAP, galaxyQuery(field), 20_000, QUICK_TIMEOUT);
        catalogue.deepSky = parseDeepSky(named, galaxies);
        CsvTable commonNames = ask(attempt, "SIMBAD", "Common names", "Common names (SIMBAD)",
                SIMBAD_TAP, namedObjectQuery(field), 20_000, QUICK_TIMEOUT);
        addNamedObjects(catalogue.deepSky, commonNames);
        CsvTable brightStars = ask(attempt, "SIMBAD", "Named bright stars", "Named bright stars (SIMBAD)",
                SIMBAD_TAP, namedStarQuery(field), 5_000, QUICK_TIMEOUT);
        catalogue.namedStars = parseNamedStars(brightStars);

        progress.stage(2, 3, "variable stars (AAVSO VSX)");
        CsvTable variables = ask(attempt, "AAVSO VSX", "Variable stars", "Variable stars (AAVSO VSX)",
                VIZIER_TAP, variableQuery(field, magnitudeLimit), 200_000, QUICK_TIMEOUT);
        catalogue.variables = parseVariables(variables);

        String depth = String.format(Locale.US, "stars to magnitude %.1f (Gaia)", magnitudeLimit);
        progress.stage(3, 3, depth);
        try {
            String[] source = new String[1];
            catalogue.stars = fetchStars(field, magnitudeLimit, source,
                    () -> progress.stage(3, 3, depth.replace("(Gaia)", "(Gaia, ESA archive)")), attempt);
            catalogue.starSource = source[0];
        } catch (IOException problem) {
            // Each Gaia service already has its own step and problem sentence.
        }
        if (catalogue.stars.size() >= starCap) {
            String message = String.format(Locale.US,
                    "Stars (Gaia): the field has more than %,d stars to this depth, so only part of them were fetched. "
                            + "Lower the star depth in the Astrometry Config tab.", starCap);
            catalogue.problems.add(message);
            attempt.fail("Gaia", "Star depth limit", "Only part of the stars were fetched", message, null, null);
        }
        if (catalogue.stars.isEmpty()) {
            attempt.notAsked("Gaia", "Field depth", "", "The star query produced no stars.");
        } else {
            fetchDepth(attempt);
        }
    }

    /**
     * One TAP query. A failure records the step, adds the problem sentence and returns an empty table, so a later
     * query still runs and objects already parsed stay. Interruption is left to the caller.
     */
    private CsvTable ask(Attempt attempt, String service, String question, String problemPrefix, String endpoint,
            String adql, int maxRecords, Duration timeout) throws InterruptedException {
        try {
            CsvTable table = query(endpoint, adql, maxRecords, timeout);
            attempt.succeed(service, question, count(table.size(), "row"), "", endpoint, adql);
            return table;
        } catch (IOException problem) {
            attempt.fail(service, question, "", problem.getMessage(), endpoint, adql);
            attempt.catalogue.problems.add(problemPrefix + ": " + problem.getMessage());
            return CsvTable.parse("");
        }
    }

    /** "0 rows", "1 row" or "12 rows". */
    private static String count(int amount, String noun) {
        return String.format(Locale.US, "%,d %s%s", amount, noun, amount == 1 ? "" : "s");
    }

    /** The faintest stars fetched for the field depth: deeper than amateur frames usually go. */
    static final double DEPTH_MAGNITUDE = 20.5;
    /** Side of a patch for the field depth, in degrees, at most; a quarter of the field for small fields. */
    static final double DEPTH_PATCH_DEG = 12.0 / 60.0;

    /**
     * Gaia stars to G {@value #DEPTH_MAGNITUDE} in four square patches, halfway from the centre of the field to each
     * corner: the middle of the field often holds a large galaxy or nebula, where faint stars cannot be found.
     * A patch that fails does not discard the patches already fetched. One step says how many answered. Interruption
     * keeps those patches and marks the step not asked.
     */
    private void fetchDepth(Attempt attempt) throws InterruptedException {
        SkyField field = attempt.catalogue.field;
        attempt.progress.stage(3, 3, String.format(Locale.US,
                "faint stars of four small patches (Gaia, to %.1f, for the field depth)", DEPTH_MAGNITUDE));
        List<SkyCatalogue.DepthSample> samples = new ArrayList<>();
        StringBuilder queries = new StringBuilder();
        IOException last = null;
        int failed = 0;
        double side = Math.min(DEPTH_PATCH_DEG, Math.min(field.widthDeg, field.heightDeg) / 4.0);
        int patches = field.cornerRa.length;
        try {
            for (int i = 0; i < patches; i++) {
                double deltaRa = ((field.cornerRa[i] - field.centerRa + 540) % 360) - 180;
                double ra = (field.centerRa + deltaRa / 2 + 360) % 360;
                double dec = (field.centerDec + field.cornerDec[i]) / 2;
                String adql = depthQuery(ra, dec, side);
                if (queries.length() > 0) {
                    queries.append("\n\n");
                }
                queries.append(adql);
                try {
                    samples.add(fetchDepthSample(ra, dec, side));
                } catch (IOException problem) {
                    last = problem;
                    failed++;
                }
            }
        } catch (InterruptedException interrupted) {
            attempt.catalogue.depthSamples = samples;
            if (!samples.isEmpty() || failed > 0) {
                attempt.notAsked("Gaia", "Field depth", samples.size() + " of " + patches + " patches answered",
                        "The fetch was cancelled.");
            }
            throw interrupted;
        }
        attempt.catalogue.depthSamples = samples;
        String outcome = samples.size() + " of " + patches + " patches";
        if (last == null) {
            attempt.succeed("Gaia", "Field depth", outcome,
                    String.format(Locale.US, "To magnitude %.1f.", DEPTH_MAGNITUDE), VIZIER_TAP, queries.toString());
        } else {
            attempt.fail("Gaia", "Field depth", outcome, last.getMessage(), VIZIER_TAP, queries.toString());
            attempt.catalogue.problems.add(samples.isEmpty()
                    ? "Faint stars for the field depth (Gaia): " + last.getMessage()
                    : "Faint stars for the field depth (Gaia): " + outcome + " answered; " + last.getMessage());
        }
    }

    private SkyCatalogue.DepthSample fetchDepthSample(double centerRa, double centerDec, double sideDeg) throws IOException, InterruptedException {
        SkyCatalogue.DepthSample sample = new SkyCatalogue.DepthSample();
        sample.centerRa = centerRa;
        sample.centerDec = centerDec;
        sample.sideDeg = sideDeg;
        sample.magnitudeLimit = DEPTH_MAGNITUDE;
        sample.stars = parseStars(query(VIZIER_TAP, depthQuery(centerRa, centerDec, sideDeg), MAX_STARS, STAR_TIMEOUT),
                "RA_ICRS", "DE_ICRS", "pmRA", "pmDE", "Gmag", "BP-RP");
        return sample;
    }

    /** The Gaia query for one faint patch, down to {@link #DEPTH_MAGNITUDE}. */
    private static String depthQuery(double centerRa, double centerDec, double sideDeg) {
        double halfDec = sideDeg / 2;
        double halfRa = halfDec / Math.max(0.05, Math.cos(Math.toRadians(centerDec)));
        String box = String.format(Locale.US, "POLYGON('ICRS', %.6f, %.6f, %.6f, %.6f, %.6f, %.6f, %.6f, %.6f)",
                centerRa - halfRa, centerDec - halfDec, centerRa + halfRa, centerDec - halfDec,
                centerRa + halfRa, centerDec + halfDec, centerRa - halfRa, centerDec + halfDec);
        return "SELECT RA_ICRS, DE_ICRS, pmRA, pmDE, Gmag, \"BP-RP\" FROM \"I/355/gaiadr3\" "
                + "WHERE 1=CONTAINS(POINT('ICRS', RA_ICRS, DE_ICRS), " + box + ") "
                + String.format(Locale.US, "AND Gmag <= %.2f", DEPTH_MAGNITUDE);
    }

    /** The Gaia DR3 stars of the field to this G magnitude, from VizieR or else from the ESA archive. */
    public List<SkyCatalogue.Star> fetchStars(SkyField field, double magnitudeLimit) throws IOException, InterruptedException {
        return fetchStars(field, magnitudeLimit, new String[1], () -> { }, null);
    }

    /**
     * The stars, recording a failed VizieR step before the ESA archive is asked. {@code attempt} is null for the
     * plate-solution fit, which only needs the stars.
     */
    private List<SkyCatalogue.Star> fetchStars(SkyField field, double magnitudeLimit, String[] source, Runnable fallingBack,
            Attempt attempt) throws IOException, InterruptedException {
        String vizierQuery = vizierStarQuery(field, magnitudeLimit);
        try {
            List<SkyCatalogue.Star> stars = parseStars(query(VIZIER_TAP, vizierQuery, starCap, STAR_TIMEOUT),
                    "RA_ICRS", "DE_ICRS", "pmRA", "pmDE", "Gmag", "BP-RP");
            if (attempt != null) {
                attempt.succeed("Gaia via VizieR", "Stars", count(stars.size(), "star"), "", VIZIER_TAP, vizierQuery);
            }
            source[0] = "Gaia DR3 via VizieR (CDS)";
            return stars;
        } catch (IOException vizierProblem) {
            if (attempt != null) {
                attempt.fail("Gaia via VizieR", "Stars", "", vizierProblem.getMessage(), VIZIER_TAP, vizierQuery);
                attempt.catalogue.problems.add("Stars (Gaia, VizieR): " + vizierProblem.getMessage());
                attempt.expectBefore(new Planned("Gaia via the ESA archive", "Stars"), new Planned("Gaia", "Field depth"));
            }
            fallingBack.run();
            String esaQuery = esaStarQuery(field, magnitudeLimit);
            try {
                List<SkyCatalogue.Star> stars = parseStars(query(ESA_GAIA_TAP, esaQuery, starCap, STAR_TIMEOUT),
                        "ra", "dec", "pmra", "pmdec", "phot_g_mean_mag", "bp_rp");
                if (attempt != null) {
                    attempt.succeed("Gaia via the ESA archive", "Stars", count(stars.size(), "star"), "", ESA_GAIA_TAP, esaQuery);
                }
                source[0] = "Gaia DR3 via the ESA Gaia archive";
                return stars;
            } catch (IOException esaProblem) {
                if (attempt != null) {
                    attempt.fail("Gaia via the ESA archive", "Stars", "", esaProblem.getMessage(), ESA_GAIA_TAP, esaQuery);
                    attempt.catalogue.problems.add("Stars (Gaia, ESA archive): " + esaProblem.getMessage());
                }
                throw new IOException("VizieR " + vizierProblem.getMessage() + "; ESA archive " + esaProblem.getMessage());
            }
        }
    }

    // ==========================================
    // QUERIES
    // ==========================================

    static String namedDeepSkyQuery(SkyField field) {
        StringBuilder ids = new StringBuilder();
        for (String[] catalogue : DEEP_SKY_CATALOGUES) {
            ids.append(ids.length() == 0 ? "" : " OR ").append("i.id LIKE '").append(catalogue[0]).append(" %'");
        }
        return "SELECT b.main_id, b.ra, b.dec, b.otype, b.galdim_majaxis, b.galdim_minaxis, b.galdim_angle, i.id "
                + "FROM basic AS b JOIN ident AS i ON i.oidref = b.oid "
                + "WHERE 1=CONTAINS(POINT('ICRS', b.ra, b.dec), " + field.adqlPolygon() + ") AND (" + ids + ")";
    }

    /**
     * The galaxies, nebulae and clusters with a "NAME" identifier, such as "Bode's Galaxy" (M 81) or the Horsehead
     * Nebula, which SIMBAD keeps as an object of its own.
     */
    static String namedObjectQuery(SkyField field) {
        return "SELECT b.main_id, b.ra, b.dec, b.otype, b.galdim_majaxis, b.galdim_minaxis, b.galdim_angle, n.id "
                + "FROM basic AS b JOIN ident AS n ON n.oidref = b.oid "
                + "WHERE 1=CONTAINS(POINT('ICRS', b.ra, b.dec), " + field.adqlPolygon() + ") AND n.id LIKE 'NAME %' "
                + "AND (b.otype = 'G..' OR b.otype = 'ISM..' OR b.otype = 'Cl*..' OR b.otype = 'GrG..' OR b.otype = 'ClG..')";
    }

    /** Stars brighter than V 5 with a "NAME" identifier. */
    static String namedStarQuery(SkyField field) {
        return "SELECT b.main_id, b.ra, b.dec, i.id, f.flux FROM basic AS b JOIN ident AS i ON i.oidref = b.oid "
                + "JOIN flux AS f ON f.oidref = b.oid WHERE 1=CONTAINS(POINT('ICRS', b.ra, b.dec), " + field.adqlPolygon() + ") "
                + String.format(Locale.US, "AND i.id LIKE 'NAME %%' AND f.filter = 'V' AND f.flux < %.1f AND b.otype = '*..'", NAMED_STAR_MAGNITUDE);
    }

    static String galaxyQuery(SkyField field) {
        return "SELECT main_id, ra, dec, otype, galdim_majaxis, galdim_minaxis, galdim_angle FROM basic "
                + "WHERE 1=CONTAINS(POINT('ICRS', ra, dec), " + field.adqlPolygon() + ") AND otype='G..' "
                + String.format(Locale.US, "AND galdim_majaxis >= %.2f", MIN_GALAXY_ARCMIN);
    }

    static String variableQuery(SkyField field, double magnitudeLimit) {
        // V: 0 variable, 1 suspected, 2 constant or not existing, 3 possible duplicate.
        return "SELECT OID, Name, V, RAJ2000, DEJ2000, Type, max, n_max, f_min, min, n_min, l_max, u_max, l_min, u_min, Period, l_Period, u_Period, n_OID FROM \"B/vsx/vsx\" "
                + "WHERE 1=CONTAINS(POINT('ICRS', RAJ2000, DEJ2000), " + field.adqlPolygon() + ") AND V < 2 "
                + String.format(Locale.US, "AND (max IS NULL OR max <= %.2f)", magnitudeLimit + VARIABLE_MAGNITUDE_MARGIN);
    }

    static String vizierStarQuery(SkyField field, double magnitudeLimit) {
        return "SELECT Source, RA_ICRS, DE_ICRS, pmRA, pmDE, Gmag, \"BP-RP\" FROM \"I/355/gaiadr3\" "
                + "WHERE 1=CONTAINS(POINT('ICRS', RA_ICRS, DE_ICRS), " + field.adqlPolygon() + ") "
                + String.format(Locale.US, "AND Gmag <= %.2f", magnitudeLimit);
    }

    static String esaStarQuery(SkyField field, double magnitudeLimit) {
        return "SELECT source_id, ra, dec, pmra, pmdec, phot_g_mean_mag, bp_rp FROM gaiadr3.gaia_source "
                + "WHERE 1=CONTAINS(POINT('ICRS', ra, dec), " + field.adqlPolygon() + ") "
                + String.format(Locale.US, "AND phot_g_mean_mag <= %.2f", magnitudeLimit);
    }

    /** Runs an ADQL query on a TAP service and returns its CSV answer. */
    private CsvTable query(String service, String adql, int maxRecords, Duration timeout) throws IOException, InterruptedException {
        return transport.query(service, adql, maxRecords, timeout);
    }

    /** Sends one TAP form post and returns its CSV answer. */
    private static CsvTable httpQuery(HttpClient http, String service, String adql, int maxRecords, Duration timeout)
            throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("REQUEST", "doQuery");
        form.put("LANG", "ADQL");
        form.put("FORMAT", "csv");
        form.put("MAXREC", Integer.toString(maxRecords));
        form.put("QUERY", adql);
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            body.append(body.length() == 0 ? "" : "&").append(entry.getKey()).append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(service))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "SpacePixels")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException("did not answer within " + timeout.getSeconds() + " s.");
        } catch (java.net.ConnectException e) {
            throw new IOException("cannot be reached (no internet connection?).");
        }
        String text = response.body() == null ? "" : response.body();
        if (response.statusCode() == 408 || response.statusCode() == 504) {
            throw new IOException("timed out (HTTP " + response.statusCode() + ").");
        }
        if (response.statusCode() != 200 || text.trim().startsWith("<")) {
            throw new IOException(errorText(response.statusCode(), text));
        }
        return CsvTable.parse(text);
    }

    /** The service's own error message from its VOTable answer, or the HTTP status. */
    static String errorText(int status, String body) {
        Matcher matcher = QUERY_ERROR.matcher(body == null ? "" : body);
        if (matcher.find()) {
            String message = matcher.group(1).replaceAll("\\s+", " ").trim();
            return "answered with an error: " + (message.length() > 200 ? message.substring(0, 200) + "…" : message);
        }
        return "answered with HTTP " + status + ".";
    }

    // ==========================================
    // ANSWERS
    // ==========================================

    static List<SkyCatalogue.Star> parseStars(CsvTable table, String ra, String dec, String pmra, String pmdec, String g, String bpRp)
            throws IOException {
        if (table.size() > 0 && !table.hasColumn(ra)) {
            throw new IOException("answered without the expected columns.");
        }
        List<SkyCatalogue.Star> stars = new ArrayList<>(table.size());
        for (int row = 0; row < table.size(); row++) {
            Double starRa = table.number(row, ra);
            Double starDec = table.number(row, dec);
            Double magnitude = table.number(row, g);
            if (starRa == null || starDec == null || magnitude == null) {
                continue;
            }
            SkyCatalogue.Star star = new SkyCatalogue.Star();
            String sourceId = table.text(row, table.hasColumn("Source") ? "Source" : "source_id");
            star.sourceId = sourceId != null && sourceId.matches("[0-9]+") ? sourceId : null;
            star.ra = starRa;
            star.dec = starDec;
            star.g = magnitude;
            star.pmra = table.number(row, pmra);
            star.pmdec = table.number(row, pmdec);
            star.bpRp = table.number(row, bpRp);
            stars.add(star);
        }
        return stars;
    }

    /**
     * The deep-sky objects: those with a classic catalogue name (one row per name, merged per object, named by the
     * preferred catalogue), then the larger galaxies that have none.
     */
    static List<SkyCatalogue.DeepSkyObject> parseDeepSky(CsvTable named, CsvTable galaxies) {
        Map<String, SkyCatalogue.DeepSkyObject> objects = new LinkedHashMap<>();
        Map<String, Integer> namePriority = new LinkedHashMap<>();
        for (int row = 0; row < named.size(); row++) {
            String mainId = named.text(row, "main_id");
            String[] name = catalogueName(named.text(row, "id"));
            if (mainId == null || name == null) {
                continue;
            }
            SkyCatalogue.DeepSkyObject object = objects.get(mainId);
            if (object == null) {
                object = deepSkyObject(named, row);
                if (object == null) {
                    continue;
                }
                objects.put(mainId, object);
            }
            int priority = Integer.parseInt(name[1]);
            if (!namePriority.containsKey(mainId) || priority < namePriority.get(mainId)) {
                namePriority.put(mainId, priority);
                object.name = name[0];
            }
        }
        for (int row = 0; row < galaxies.size(); row++) {
            String mainId = galaxies.text(row, "main_id");
            if (mainId == null || objects.containsKey(mainId)) {
                continue;
            }
            SkyCatalogue.DeepSkyObject object = deepSkyObject(galaxies, row);
            if (object != null) {
                object.name = mainId.replaceAll("\\s+", " ");
                objects.put(mainId, object);
            }
        }
        return new ArrayList<>(objects.values());
    }

    private static SkyCatalogue.DeepSkyObject deepSkyObject(CsvTable table, int row) {
        Double ra = table.number(row, "ra");
        Double dec = table.number(row, "dec");
        if (ra == null || dec == null) {
            return null;
        }
        SkyCatalogue.DeepSkyObject object = new SkyCatalogue.DeepSkyObject();
        object.ra = ra;
        object.dec = dec;
        object.type = table.text(row, "otype");
        object.majorArcmin = table.number(row, "galdim_majaxis");
        object.minorArcmin = table.number(row, "galdim_minaxis");
        object.angleDeg = table.number(row, "galdim_angle");
        object.mainId = table.text(row, "main_id");
        return object;
    }

    /**
     * The name shown for a SIMBAD identifier and its priority, as {name, priority}; null when the identifier is
     * not an object of one of the deep-sky catalogues (for example a star of a cluster, "NGC 2251 103").
     */
    static String[] catalogueName(String identifier) {
        if (identifier == null) {
            return null;
        }
        String id = identifier.replaceAll("\\s+", " ").trim();
        for (int i = 0; i < DEEP_SKY_CATALOGUES.length; i++) {
            String prefix = DEEP_SKY_CATALOGUES[i][0] + " ";
            if (id.startsWith(prefix)) {
                String number = id.substring(prefix.length());
                if (number.isEmpty() || number.contains(" ")) {
                    return null;
                }
                return new String[]{DEEP_SKY_CATALOGUES[i][1] + " " + number, Integer.toString(i)};
            }
        }
        return null;
    }

    /** SIMBAD types whose names are left out: molecular clouds, cores and the like are not seen in a picture. */
    private static final java.util.Set<String> UNSEEN_TYPES = java.util.Set.of("MoC", "Cld", "cor", "smm", "mm", "QSO", "Q?", "bub");

    /**
     * Gives the objects their common names: of an object's "NAME" identifiers, the longest that reads as a name.
     * A named object that is not in the list yet (the Horsehead Nebula has no NGC number) is added under its name.
     */
    static void addNamedObjects(List<SkyCatalogue.DeepSkyObject> objects, CsvTable named) {
        Map<String, String> names = commonNames(named);
        Map<String, SkyCatalogue.DeepSkyObject> byMainId = new LinkedHashMap<>();
        for (SkyCatalogue.DeepSkyObject object : objects) {
            if (object.mainId != null) {
                byMainId.put(object.mainId, object);
            }
        }
        for (int row = 0; row < named.size(); row++) {
            String mainId = named.text(row, "main_id");
            String name = mainId == null ? null : names.get(mainId);
            if (name == null || UNSEEN_TYPES.contains(named.text(row, "otype"))) {
                continue;
            }
            SkyCatalogue.DeepSkyObject object = byMainId.get(mainId);
            if (object != null) {
                if (object.name == null || !object.name.equals(name)) {
                    object.commonName = name;
                }
                continue;
            }
            object = deepSkyObject(named, row);
            if (object != null) {
                object.name = name;
                objects.add(object);
                byMainId.put(mainId, object);
            }
        }
    }

    static Map<String, String> commonNames(CsvTable names) {
        Map<String, String> best = new LinkedHashMap<>();
        for (int row = 0; row < names.size(); row++) {
            String mainId = names.text(row, "main_id");
            String name = properName(names.text(row, "id"));
            if (mainId != null && name != null && (!best.containsKey(mainId) || name.length() > best.get(mainId).length())) {
                best.put(mainId, name);
            }
        }
        return best;
    }

    /**
     * The name of a SIMBAD "NAME" identifier when it reads as a proper name ("Bode's Galaxy", "Betelgeuse"): no
     * digits, and a real word. Null for technical names such as "M 81*", "UMa A" or "lambda Ori X-3".
     */
    static String properName(String identifier) {
        if (identifier == null || !identifier.startsWith("NAME ")) {
            return null;
        }
        String name = identifier.substring(5).replaceAll("\\s+", " ").trim();
        if (name.isEmpty() || name.matches(".*\\d.*") || !name.matches(".*\\b[A-Z]?[a-z']{3,}.*") || name.contains(" in ")) {
            return null;
        }
        return name;
    }

    static List<SkyCatalogue.NamedStar> parseNamedStars(CsvTable table) {
        Map<String, SkyCatalogue.NamedStar> stars = new LinkedHashMap<>();
        for (int row = 0; row < table.size(); row++) {
            String mainId = table.text(row, "main_id");
            String name = properName(table.text(row, "id"));
            Double ra = table.number(row, "ra");
            Double dec = table.number(row, "dec");
            Double vmag = table.number(row, "flux");
            if (mainId == null || name == null || ra == null || dec == null || vmag == null) {
                continue;
            }
            SkyCatalogue.NamedStar star = stars.get(mainId);
            if (star == null) {
                star = new SkyCatalogue.NamedStar();
                star.ra = ra;
                star.dec = dec;
                star.vmag = vmag;
                stars.put(mainId, star);
            }
            if (star.name == null || name.length() > star.name.length()) {
                star.name = name;
            }
        }
        return new ArrayList<>(stars.values());
    }

    static List<SkyCatalogue.VariableStar> parseVariables(CsvTable table) {
        List<SkyCatalogue.VariableStar> variables = new ArrayList<>(table.size());
        for (int row = 0; row < table.size(); row++) {
            Double ra = table.number(row, "RAJ2000");
            Double dec = table.number(row, "DEJ2000");
            String name = table.text(row, "Name");
            if (ra == null || dec == null || name == null) {
                continue;
            }
            SkyCatalogue.VariableStar variable = new SkyCatalogue.VariableStar();
            Double oid = table.number(row, "OID");
            variable.oid = oid == null ? 0 : oid.longValue();
            variable.name = name.replaceAll("\\s+", " ");
            variable.type = table.text(row, "Type");
            variable.ra = ra;
            variable.dec = dec;
            variable.max = table.number(row, "max");
            variable.min = table.number(row, "min");
            String amplitudeFlag = table.text(row, "f_min");
            variable.minIsAmplitude = "Y".equalsIgnoreCase(amplitudeFlag) || "(".equals(amplitudeFlag);
            variable.band = table.text(row, "n_max");
            variable.periodDays = table.number(row, "Period");
            variable.maxBand = table.text(row, "n_max");
            variable.minBand = table.text(row, "n_min");
            variable.maxLimit = table.text(row, "l_max");
            variable.minLimit = table.text(row, "l_min");
            variable.maxUncertainty = table.text(row, "u_max");
            variable.minUncertainty = table.text(row, "u_min");
            variable.periodLimit = table.text(row, "l_Period");
            variable.periodUncertainty = table.text(row, "u_Period");
            variable.comparisonMetadataComplete = table.hasColumn("n_min") && table.hasColumn("l_max")
                    && table.hasColumn("u_max") && table.hasColumn("l_min") && table.hasColumn("u_min")
                    && table.hasColumn("l_Period") && table.hasColumn("u_Period");
            variable.bibliographyAvailable = table.text(row, "n_OID") != null;
            variables.add(variable);
        }
        return variables;
    }

    /** One service question that has not been recorded yet. */
    private record Planned(String service, String question) {
    }

    /**
     * The catalogue being fetched and the questions still to record. Steps are published as they finish, on the
     * fetching thread. The catalogue owns the step list.
     */
    private final class Attempt {
        final SkyCatalogue catalogue = new SkyCatalogue();
        final Progress progress;
        final List<Planned> pending = new ArrayList<>();

        /** Starts a catalogue for this field and the questions a complete fetch asks. */
        Attempt(SkyField field, double magnitudeLimit, Progress progress) {
            catalogue.field = field;
            catalogue.starMagnitudeLimit = magnitudeLimit;
            catalogue.fetchedAtUtc = Instant.now().toString();
            this.progress = progress;
            pending.add(new Planned("SIMBAD", "Deep-sky catalogue names"));
            pending.add(new Planned("SIMBAD", "Large galaxies"));
            pending.add(new Planned("SIMBAD", "Common names"));
            pending.add(new Planned("SIMBAD", "Named bright stars"));
            pending.add(new Planned("AAVSO VSX", "Variable stars"));
            pending.add(new Planned("Gaia via VizieR", "Stars"));
            pending.add(new Planned("Gaia", "Field depth"));
        }

        /** Inserts a question that is asked only after an earlier one fails, ahead of {@code before} when that remains. */
        void expectBefore(Planned planned, Planned before) {
            int index = pending.indexOf(before);
            pending.add(index < 0 ? pending.size() : index, planned);
        }

        /** Records a service that answered. */
        void succeed(String service, String question, String outcome, String detail, String url, String query) {
            record(FetchStep.Status.SUCCEEDED, service, question, outcome, detail, url, query);
        }

        /** Records a service that was asked and did not answer. */
        void fail(String service, String question, String outcome, String detail, String url, String query) {
            record(FetchStep.Status.FAILED, service, question, outcome, detail, url, query);
        }

        /** Records a question that was not sent. */
        void notAsked(String service, String question, String outcome, String detail) {
            record(FetchStep.Status.NOT_ASKED, service, question, outcome, detail, null, null);
        }

        /** Marks every question not yet recorded as not asked, then publishes the list. */
        void cancel() {
            boolean first = true;
            List<Planned> remaining = new ArrayList<>(pending);
            for (Planned planned : remaining) {
                notAsked(planned.service(), planned.question(), "", first ? "The fetch was cancelled." : "Not sent.");
                first = false;
            }
        }

        /** Appends one step, drops it from the questions still pending, and publishes the list. */
        private void record(FetchStep.Status status, String service, String question, String outcome, String detail,
                String url, String query) {
            pending.remove(new Planned(service, question));
            catalogue.fetchSteps.add(FetchStep.of(service, question, status, outcome, detail, url, query));
            progress.steps(List.copyOf(catalogue.fetchSteps));
        }
    }
}
