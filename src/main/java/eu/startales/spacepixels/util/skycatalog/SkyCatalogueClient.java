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
 * Each source that fails is noted in {@link SkyCatalogue#problems}; the others are still kept. Interrupting the
 * fetching thread cancels the request in progress.
 */
public final class SkyCatalogueClient {

    /** Receives which of the three sources is being fetched. */
    public interface Progress {
        void stage(int number, int total, String text);
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

    private final HttpClient http;

    public SkyCatalogueClient() {
        http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fetches the field: deep-sky objects and variables first (a second or two each), then the stars to this Gaia
     * G magnitude (half a minute or more).
     *
     * @throws InterruptedException when the thread is interrupted, which cancels the fetch
     */
    public SkyCatalogue fetch(SkyField field, double magnitudeLimit, Progress progress) throws InterruptedException {
        SkyCatalogue catalogue = new SkyCatalogue();
        catalogue.field = field;
        catalogue.starMagnitudeLimit = magnitudeLimit;
        catalogue.fetchedAtUtc = Instant.now().toString();

        progress.stage(1, 3, "deep-sky objects (SIMBAD)");
        try {
            CsvTable named = query(SIMBAD_TAP, namedDeepSkyQuery(field), 20_000, QUICK_TIMEOUT);
            CsvTable galaxies = query(SIMBAD_TAP, galaxyQuery(field), 20_000, QUICK_TIMEOUT);
            catalogue.deepSky = parseDeepSky(named, galaxies);
            addNamedObjects(catalogue.deepSky, query(SIMBAD_TAP, namedObjectQuery(field), 20_000, QUICK_TIMEOUT));
            catalogue.namedStars = parseNamedStars(query(SIMBAD_TAP, namedStarQuery(field), 5_000, QUICK_TIMEOUT));
        } catch (IOException e) {
            catalogue.problems.add("Deep-sky objects (SIMBAD): " + e.getMessage());
        }

        progress.stage(2, 3, "variable stars (AAVSO VSX)");
        try {
            catalogue.variables = parseVariables(query(VIZIER_TAP, variableQuery(field, magnitudeLimit), 200_000, QUICK_TIMEOUT));
        } catch (IOException e) {
            catalogue.problems.add("Variable stars (AAVSO VSX): " + e.getMessage());
        }

        String depth = String.format(Locale.US, "stars to magnitude %.1f (Gaia)", magnitudeLimit);
        progress.stage(3, 3, depth);
        try {
            String[] source = new String[1];
            catalogue.stars = fetchStars(field, magnitudeLimit, source,
                    () -> progress.stage(3, 3, depth.replace("(Gaia)", "(Gaia, ESA archive)")));
            catalogue.starSource = source[0];
        } catch (IOException problem) {
            catalogue.problems.add("Stars (Gaia): " + problem.getMessage());
        }
        if (!catalogue.stars.isEmpty()) {
            progress.stage(3, 3, String.format(Locale.US, "faint stars of four small patches (Gaia, to %.1f, for the field depth)", DEPTH_MAGNITUDE));
            try {
                catalogue.depthSamples = fetchDepthSamples(field);
            } catch (IOException e) {
                catalogue.problems.add("Faint stars for the field depth (Gaia): " + e.getMessage());
            }
        }
        if (catalogue.stars.size() >= MAX_STARS) {
            catalogue.problems.add(String.format(Locale.US,
                    "Stars (Gaia): the field has more than %,d stars to this depth, so only part of them were fetched. "
                            + "Lower the star depth in the Astrometry Config tab.", MAX_STARS));
        }
        return catalogue;
    }

    /** The faintest stars fetched for the field depth: deeper than amateur frames usually go. */
    static final double DEPTH_MAGNITUDE = 20.5;
    /** Side of a patch for the field depth, in degrees, at most; a quarter of the field for small fields. */
    static final double DEPTH_PATCH_DEG = 12.0 / 60.0;

    /**
     * Gaia stars to G {@value #DEPTH_MAGNITUDE} in four square patches, halfway from the centre of the field to each
     * corner: the middle of the field often holds a large galaxy or nebula, where faint stars cannot be found.
     */
    List<SkyCatalogue.DepthSample> fetchDepthSamples(SkyField field) throws IOException, InterruptedException {
        List<SkyCatalogue.DepthSample> samples = new ArrayList<>();
        double side = Math.min(DEPTH_PATCH_DEG, Math.min(field.widthDeg, field.heightDeg) / 4.0);
        for (int i = 0; i < field.cornerRa.length; i++) {
            double deltaRa = ((field.cornerRa[i] - field.centerRa + 540) % 360) - 180;
            double ra = (field.centerRa + deltaRa / 2 + 360) % 360;
            double dec = (field.centerDec + field.cornerDec[i]) / 2;
            samples.add(fetchDepthSample(ra, dec, side));
        }
        return samples;
    }

    private SkyCatalogue.DepthSample fetchDepthSample(double centerRa, double centerDec, double sideDeg) throws IOException, InterruptedException {
        SkyCatalogue.DepthSample sample = new SkyCatalogue.DepthSample();
        sample.centerRa = centerRa;
        sample.centerDec = centerDec;
        sample.sideDeg = sideDeg;
        sample.magnitudeLimit = DEPTH_MAGNITUDE;
        double halfDec = sample.sideDeg / 2;
        double halfRa = halfDec / Math.max(0.05, Math.cos(Math.toRadians(centerDec)));
        String box = String.format(Locale.US, "POLYGON('ICRS', %.6f, %.6f, %.6f, %.6f, %.6f, %.6f, %.6f, %.6f)",
                centerRa - halfRa, centerDec - halfDec, centerRa + halfRa, centerDec - halfDec,
                centerRa + halfRa, centerDec + halfDec, centerRa - halfRa, centerDec + halfDec);
        String query = "SELECT RA_ICRS, DE_ICRS, pmRA, pmDE, Gmag, \"BP-RP\" FROM \"I/355/gaiadr3\" "
                + "WHERE 1=CONTAINS(POINT('ICRS', RA_ICRS, DE_ICRS), " + box + ") "
                + String.format(Locale.US, "AND Gmag <= %.2f", DEPTH_MAGNITUDE);
        sample.stars = parseStars(query(VIZIER_TAP, query, MAX_STARS, STAR_TIMEOUT), "RA_ICRS", "DE_ICRS", "pmRA", "pmDE", "Gmag", "BP-RP");
        return sample;
    }

    /** The Gaia DR3 stars of the field to this G magnitude, from VizieR or else from the ESA archive. */
    public List<SkyCatalogue.Star> fetchStars(SkyField field, double magnitudeLimit) throws IOException, InterruptedException {
        return fetchStars(field, magnitudeLimit, new String[1], () -> { });
    }

    private List<SkyCatalogue.Star> fetchStars(SkyField field, double magnitudeLimit, String[] source, Runnable fallingBack)
            throws IOException, InterruptedException {
        try {
            List<SkyCatalogue.Star> stars = parseStars(query(VIZIER_TAP, vizierStarQuery(field, magnitudeLimit), MAX_STARS, STAR_TIMEOUT),
                    "RA_ICRS", "DE_ICRS", "pmRA", "pmDE", "Gmag", "BP-RP");
            source[0] = "Gaia DR3 via VizieR (CDS)";
            return stars;
        } catch (IOException vizierProblem) {
            fallingBack.run();
            try {
                List<SkyCatalogue.Star> stars = parseStars(query(ESA_GAIA_TAP, esaStarQuery(field, magnitudeLimit), MAX_STARS, STAR_TIMEOUT),
                        "ra", "dec", "pmra", "pmdec", "phot_g_mean_mag", "bp_rp");
                source[0] = "Gaia DR3 via the ESA Gaia archive";
                return stars;
            } catch (IOException esaProblem) {
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
        return "SELECT OID, Name, V, RAJ2000, DEJ2000, Type, max, n_max, f_min, min, Period FROM \"B/vsx/vsx\" "
                + "WHERE 1=CONTAINS(POINT('ICRS', RAJ2000, DEJ2000), " + field.adqlPolygon() + ") AND V < 2 "
                + String.format(Locale.US, "AND (max IS NULL OR max <= %.2f)", magnitudeLimit + VARIABLE_MAGNITUDE_MARGIN);
    }

    static String vizierStarQuery(SkyField field, double magnitudeLimit) {
        return "SELECT RA_ICRS, DE_ICRS, pmRA, pmDE, Gmag, \"BP-RP\" FROM \"I/355/gaiadr3\" "
                + "WHERE 1=CONTAINS(POINT('ICRS', RA_ICRS, DE_ICRS), " + field.adqlPolygon() + ") "
                + String.format(Locale.US, "AND Gmag <= %.2f", magnitudeLimit);
    }

    static String esaStarQuery(SkyField field, double magnitudeLimit) {
        return "SELECT ra, dec, pmra, pmdec, phot_g_mean_mag, bp_rp FROM gaiadr3.gaia_source "
                + "WHERE 1=CONTAINS(POINT('ICRS', ra, dec), " + field.adqlPolygon() + ") "
                + String.format(Locale.US, "AND phot_g_mean_mag <= %.2f", magnitudeLimit);
    }

    /** Runs an ADQL query on a TAP service and returns its CSV answer. */
    private CsvTable query(String service, String adql, int maxRecords, Duration timeout) throws IOException, InterruptedException {
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
            variable.minIsAmplitude = table.text(row, "f_min") != null;
            variable.band = table.text(row, "n_max");
            variable.periodDays = table.number(row, "Period");
            variables.add(variable);
        }
        return variables;
    }
}
