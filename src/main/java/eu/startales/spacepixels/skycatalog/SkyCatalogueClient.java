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
import eu.startales.spacepixels.util.DebugOutput;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * Fetches the catalogue of a field from the online TAP services: deep-sky objects from SIMBAD, variable stars from
 * AAVSO VSX and stars from Gaia DR3. SIMBAD is asked at Strasbourg, then Harvard, then IDIA. The stars and the
 * faint field-depth patches are asked of VizieR at CDS, then ESA's Gaia archive, then ARI Heidelberg. Variable stars
 * are asked of VizieR at CDS, then the Harvard and IDIA copies of VSX. Each source that
 * fails is noted in {@link SkyCatalogue#problems}; the others are still kept. The four
 * SIMBAD queries are asked separately, so a later failure keeps the objects already parsed. A Gaia service that
 * fails is kept as its own step when a later one answers. Faint depth patches that answer are kept when another patch
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

    /** One cone-search GET. Tests supply a recorded page; the desktop client fetches it. */
    interface ConeSearch {
        /** The response body of one cone search. */
        String get(String url, Duration timeout) throws IOException, InterruptedException;
    }

    static final String VIZIER_TAP = "https://tapvizier.cds.unistra.fr/TAPVizieR/tap/sync";
    /** VSX table query at the CfA Harvard VizieR mirror. */
    static final String VSX_HARVARD = "https://vizier.cfa.harvard.edu/viz-bin/votable";
    /** VSX table query at the IDIA VizieR mirror. */
    static final String VSX_IDIA = "https://vizier.idia.ac.za/viz-bin/votable";
    /** Shown when a copy is left unasked because it already failed to answer in this fetch. */
    private static final String SKIPPED = "Not sent, because this copy did not answer earlier.";
    static final String ESA_GAIA_TAP = "https://gea.esac.esa.int/tap-server/tap/sync";
    /** Gaia DR3 at the Astronomisches Rechen-Institut, asked when VizieR and the ESA archive do not answer. */
    static final String ARI_GAIA_TAP = "https://gaia.ari.uni-heidelberg.de/tap/sync";
    static final String SIMBAD_TAP = "https://simbad.cds.unistra.fr/simbad/sim-tap/sync";
    /** SIMBAD TAP at the CfA Harvard mirror, asked when Strasbourg does not answer. */
    static final String SIMBAD_HARVARD = "https://simbad.cfa.harvard.edu/simbad/sim-tap/sync";
    /** SIMBAD TAP at the IDIA mirror, asked when Strasbourg and Harvard do not answer. */
    static final String SIMBAD_IDIA = "https://simbad.idia.ac.za/simbad/sim-tap/sync";
    /** More stars than this would take minutes; the depth should be lowered instead. */
    static final int MAX_STARS = 300_000;
    /** Variable stars are kept to this much fainter than the star depth, at their brightest. */
    static final double VARIABLE_MAGNITUDE_MARGIN = 1.0;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STAR_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration QUICK_TIMEOUT = Duration.ofSeconds(60);
    private static final Pattern QUERY_ERROR = Pattern.compile(
            "<INFO[^>]*name=\"QUERY_STATUS\"[^>]*value=\"ERROR\"[^>]*>(.*?)</INFO>", Pattern.DOTALL);
    /** The message on a TAP error page, such as ESA's full synchronous queue. */
    private static final Pattern SERVICE_MESSAGE = Pattern.compile(
            "<b>\\s*Message:\\s*</b>\\s*(.*?)</li>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

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
    private final ConeSearch cones;
    /** Stars kept from one answer before the depth is reported as only partly fetched. Production uses {@link #MAX_STARS}. */
    private final int starCap;

    public SkyCatalogueClient() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.transport = (service, adql, maxRecords, timeout) -> httpQuery(http, service, adql, maxRecords, timeout);
        this.cones = (url, timeout) -> httpGet(http, url, timeout);
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
        this(transport, (url, timeout) -> {
            throw new IOException("answered with HTTP 503.");
        }, starCap);
    }

    /** A client whose TAP queries and VSX cone searches are both supplied by the test. */
    SkyCatalogueClient(QueryTransport transport, ConeSearch cones, int starCap) {
        this.transport = transport;
        this.cones = cones;
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
        CsvTable named = askSimbad(attempt, progress, "Deep-sky catalogue names", "Deep-sky catalogue names (SIMBAD)",
                namedDeepSkyQuery(field), 20_000);
        CsvTable galaxies = askSimbad(attempt, progress, "Large galaxies", "Large galaxies (SIMBAD)",
                galaxyQuery(field), 20_000);
        catalogue.deepSky = parseDeepSky(named, galaxies);
        CsvTable commonNames = askSimbad(attempt, progress, "Common names", "Common names (SIMBAD)",
                namedObjectQuery(field), 20_000);
        addNamedObjects(catalogue.deepSky, commonNames);
        CsvTable brightStars = askSimbad(attempt, progress, "Named bright stars", "Named bright stars (SIMBAD)",
                namedStarQuery(field), 5_000);
        catalogue.namedStars = parseNamedStars(brightStars);

        progress.stage(2, 3, "variable stars (AAVSO VSX)");
        catalogue.variables = parseVariables(fetchVariables(attempt, field, magnitudeLimit, progress));

        String depth = String.format(Locale.US, "stars to magnitude %.1f (Gaia)", magnitudeLimit);
        progress.stage(3, 3, depth);
        try {
            String[] source = new String[1];
            catalogue.stars = fetchStars(field, magnitudeLimit, source,
                    () -> progress.stage(3, 3, depth.replace("(Gaia)", "(Gaia, ESA archive)")),
                    () -> progress.stage(3, 3, depth.replace("(Gaia)", "(Gaia, ARI Heidelberg)")), attempt);
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
            attempt.notAsked("Gaia via VizieR", "Field depth", "", "The star query produced no stars.");
        } else {
            fetchDepth(attempt);
        }
    }

    /**
     * One SIMBAD question, asked of Strasbourg, then Harvard, then IDIA. A failure stays recorded when a later copy
     * answers. An empty table is returned when every copy fails, so objects already parsed stay.
     */
    private CsvTable askSimbad(Attempt attempt, Progress progress, String question, String problemStem, String adql,
            int maxRecords) throws InterruptedException {
        String[] endpoints = {SIMBAD_TAP, SIMBAD_HARVARD, SIMBAD_IDIA};
        String[] services = {"SIMBAD", "SIMBAD via Harvard", "SIMBAD via IDIA"};
        String[] places = {"", "Harvard", "IDIA"};
        IOException last = null;
        for (int i = 0; i < endpoints.length; i++) {
            if (i > 0) {
                progress.stage(1, 3, question + " (SIMBAD, " + places[i] + ")");
                attempt.expectNext(new Planned(services[i], question));
            }
            if (attempt.alreadyDown(endpoints[i])) {
                attempt.notAsked(services[i], question, "", SKIPPED);
                continue;
            }
            try {
                CsvTable table = query(endpoints[i], adql, maxRecords, QUICK_TIMEOUT);
                attempt.succeed(services[i], question, count(table.size(), "row"), "", endpoints[i], adql);
                return table;
            } catch (IOException problem) {
                last = problem;
                attempt.remember(endpoints[i], problem);
                attempt.fail(services[i], question, "", problem.getMessage(), endpoints[i], adql);
            }
        }
        attempt.catalogue.problems.add(problemStem + ": " + (last == null ? "did not answer earlier." : last.getMessage()));
        return CsvTable.parse("");
    }

    /**
     * Variable stars from VizieR, then the Harvard mirror, then the IDIA mirror. A failure stays recorded when a
     * later copy answers. The mirror search is a circle covering the frame; stars outside the frame, constants and
     * stars fainter than the depth are left out.
     */
    private CsvTable fetchVariables(Attempt attempt, SkyField field, double magnitudeLimit, Progress progress)
            throws InterruptedException {
        String adql = variableQuery(field, magnitudeLimit);
        IOException last = null;
        if (attempt.alreadyDown(VIZIER_TAP)) {
            attempt.notAsked("AAVSO VSX via VizieR", "Variable stars", "", SKIPPED);
        } else {
            try {
                CsvTable table = query(VIZIER_TAP, adql, 200_000, QUICK_TIMEOUT);
                attempt.succeed("AAVSO VSX via VizieR", "Variable stars", count(table.size(), "row"), "", VIZIER_TAP, adql);
                attempt.catalogue.variableSource = "AAVSO VSX via VizieR";
                return table;
            } catch (IOException vizierProblem) {
                last = vizierProblem;
                noteVariableFailure(attempt, "AAVSO VSX via VizieR", vizierProblem, VIZIER_TAP, adql);
            }
        }
        progress.stage(2, 3, "variable stars (AAVSO VSX, Harvard)");
        attempt.expectNext(new Planned("AAVSO VSX via Harvard", "Variable stars"));
        if (attempt.alreadyDown(VSX_HARVARD)) {
            attempt.notAsked("AAVSO VSX via Harvard", "Variable stars", "", SKIPPED);
        } else {
            try {
                return variablesFromCone(attempt, "AAVSO VSX via Harvard", "Variable stars (AAVSO VSX, Harvard)",
                        VSX_HARVARD, field, magnitudeLimit);
            } catch (IOException harvardProblem) {
                last = harvardProblem;
                noteVariableFailure(attempt, "AAVSO VSX via Harvard", harvardProblem, VSX_HARVARD, null);
            }
        }
        progress.stage(2, 3, "variable stars (AAVSO VSX, IDIA)");
        attempt.expectNext(new Planned("AAVSO VSX via IDIA", "Variable stars"));
        if (attempt.alreadyDown(VSX_IDIA)) {
            attempt.notAsked("AAVSO VSX via IDIA", "Variable stars", "", SKIPPED);
        } else {
            try {
                return variablesFromCone(attempt, "AAVSO VSX via IDIA", "Variable stars (AAVSO VSX, IDIA)",
                        VSX_IDIA, field, magnitudeLimit);
            } catch (IOException idiaProblem) {
                last = idiaProblem;
                noteVariableFailure(attempt, "AAVSO VSX via IDIA", idiaProblem, VSX_IDIA, null);
            }
        }
        attempt.catalogue.problems.add("Variable stars (AAVSO VSX): "
                + (last == null ? "did not answer earlier." : last.getMessage()));
        return CsvTable.parse("");
    }

    /** One mirror cone search, kept when the circle covers the frame and the answer can be read. */
    private CsvTable variablesFromCone(Attempt attempt, String service, String problemLabel, String endpoint,
            SkyField field, double magnitudeLimit) throws IOException, InterruptedException {
        String url = coneUrl(endpoint, field);
        boolean truncated = false;
        CsvTable received;
        try {
            received = parseVotable(cones.get(url, QUICK_TIMEOUT));
        } catch (TruncatedAnswer cut) {
            received = cut.table;
            truncated = true;
        }
        CsvTable kept = variablesInside(received, field, magnitudeLimit);
        String outcome = count(kept.size(), "row") + (truncated ? " (truncated)" : "");
        if (truncated) {
            attempt.fail(service, "Variable stars", outcome, "The answer was cut off.", url, null);
            attempt.catalogue.problems.add(problemLabel + ": the answer was cut off.");
        } else {
            attempt.succeed(service, "Variable stars", outcome, "", url, null);
        }
        attempt.catalogue.variableSource = service;
        return kept;
    }

    /** Records a failed variable-star copy. The warning is added only when every copy has failed. */
    private void noteVariableFailure(Attempt attempt, String service, IOException problem, String endpoint, String query) {
        attempt.remember(endpoint, problem);
        String address = query == null ? coneUrl(endpoint, attempt.catalogue.field) : endpoint;
        attempt.fail(service, "Variable stars", "", problem.getMessage(), address, query);
    }

    /**
     * The VizieR table address for this field. The mirror selects variable and suspected stars, and the columns the
     * catalogue keeps, including bibliography. The magnitude limit stays on the client, because an empty brightest
     * magnitude must be kept.
     */
    static String coneUrl(String endpoint, SkyField field) {
        String columns = "OID,Name,V,RAJ2000,DEJ2000,Type,max,n_max,f_min,min,n_min,l_max,u_max,l_min,u_min,"
                + "Period,l_Period,u_Period,n_OID";
        String target = String.format(Locale.US, "%.6f %+.6f", field.centerRa, field.centerDec);
        return endpoint + "?-source=B/vsx/vsx&-c=" + URLEncoder.encode(target, StandardCharsets.UTF_8)
                + "&-c.rd=" + String.format(Locale.US, "%.6f", field.coveringRadiusDeg())
                + "&V=" + URLEncoder.encode("<2", StandardCharsets.UTF_8)
                + "&-out.max=200000&-out=" + columns;
    }

    /**
     * Rows of a cone-search answer that lie inside the frame, are variable or suspected ({@code V} below 2), and are
     * no fainter than the depth. The brightest magnitude may be absent.
     */
    static CsvTable variablesInside(CsvTable table, SkyField field, double magnitudeLimit) throws IOException {
        if (table.size() > 0 && (!table.hasColumn("Name") || !table.hasColumn("RAJ2000") || !table.hasColumn("DEJ2000"))) {
            throw new IOException("answered without the expected columns.");
        }
        double faintest = magnitudeLimit + VARIABLE_MAGNITUDE_MARGIN;
        String[] columns = {"OID", "Name", "V", "RAJ2000", "DEJ2000", "Type", "max", "n_max", "f_min", "min", "n_min",
                "l_max", "u_max", "l_min", "u_min", "Period", "l_Period", "u_Period", "n_OID"};
        StringBuilder csv = new StringBuilder();
        for (String column : columns) {
            if (table.hasColumn(column)) {
                csv.append(csv.length() == 0 ? "" : ",").append(column);
            }
        }
        csv.append('\n');
        for (int row = 0; row < table.size(); row++) {
            Double ra = table.number(row, "RAJ2000");
            Double dec = table.number(row, "DEJ2000");
            Double flag = table.number(row, "V");
            Double brightest = table.number(row, "max");
            if (ra == null || dec == null || flag == null || flag >= 2 || !field.containsSky(ra, dec)) {
                continue;
            }
            if (brightest != null && brightest > faintest) {
                continue;
            }
            boolean first = true;
            for (String column : columns) {
                if (!table.hasColumn(column)) {
                    continue;
                }
                csv.append(first ? "" : ",");
                first = false;
                csv.append(csvField(table.text(row, column)));
            }
            csv.append('\n');
        }
        return CsvTable.parse(csv.toString());
    }

    /** One CSV field, quoted when it contains a comma or a quote. */
    private static String csvField(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /** The table inside a VOTable cone-search answer. An error page or an error status is a failure. */
    static CsvTable parseVotable(String xml) throws IOException {
        if (xml == null || xml.isBlank()) {
            throw new IOException("answered with an empty page.");
        }
        String start = xml.stripLeading().toLowerCase(Locale.US);
        if (start.startsWith("<html") || start.startsWith("<!doctype html")) {
            Matcher message = SERVICE_MESSAGE.matcher(xml);
            if (message.find()) {
                throw new IOException("answered with an error: " + shorten(message.group(1)));
            }
            throw new IOException("answered with a page that could not be read.");
        }
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader reader = factory.createXMLStreamReader(new java.io.StringReader(xml));
            List<String> fields = new ArrayList<>();
            List<String[]> rows = new ArrayList<>();
            List<String> row = null;
            StringBuilder text = new StringBuilder();
            boolean tableData = false;
            boolean error = false;
            boolean overflow = false;
            String errorText = "";
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("FIELD".equals(name) && !tableData) {
                        fields.add(attribute(reader, "name"));
                    } else if ("INFO".equals(name) && "QUERY_STATUS".equals(attribute(reader, "name"))) {
                        String value = attribute(reader, "value");
                        if ("ERROR".equals(value)) {
                            error = true;
                            text.setLength(0);
                        } else if ("OVERFLOW".equals(value)) {
                            overflow = true;
                        }
                    } else if ("TABLEDATA".equals(name)) {
                        tableData = true;
                    } else if ("TR".equals(name) && tableData) {
                        row = new ArrayList<>();
                    } else if ("TD".equals(name) && row != null) {
                        text.setLength(0);
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("TD".equals(name) && row != null) {
                        row.add(text.toString().trim());
                        text.setLength(0);
                    } else if ("TR".equals(name) && row != null) {
                        rows.add(row.toArray(String[]::new));
                        row = null;
                    } else if ("INFO".equals(name) && error && errorText.isEmpty()) {
                        errorText = text.toString().trim();
                    }
                }
            }
            reader.close();
            if (error) {
                throw new IOException("answered with an error: " + (errorText.isEmpty() ? "the service rejected the query." : errorText));
            }
            CsvTable table = csvTable(fields, rows);
            if (overflow) {
                throw new TruncatedAnswer(table);
            }
            return table;
        } catch (TruncatedAnswer cut) {
            throw cut;
        } catch (IOException problem) {
            throw problem;
        } catch (Exception problem) {
            DebugOutput.println("Could not read a catalogue page: " + problem);
            IOException failure = new IOException("answered with a page that could not be read.");
            failure.initCause(problem);
            throw failure;
        }
    }

    /** A CSV table from the VOTable fields and rows already read. */
    private static CsvTable csvTable(List<String> fields, List<String[]> rows) {
            StringBuilder csv = new StringBuilder();
            for (int i = 0; i < fields.size(); i++) {
                csv.append(i == 0 ? "" : ",").append(fields.get(i) == null ? "" : fields.get(i));
            }
            csv.append('\n');
            for (String[] values : rows) {
                for (int i = 0; i < fields.size(); i++) {
                    csv.append(i == 0 ? "" : ",");
                    csv.append(csvField(i < values.length ? values[i] : ""));
                }
                csv.append('\n');
            }
            return CsvTable.parse(csv.toString());
    }

    /** Rows were returned, and the service said the answer was cut off. */
    static final class TruncatedAnswer extends IOException {
        final CsvTable table;

        TruncatedAnswer(CsvTable table) {
            super("the answer was cut off");
            this.table = table;
        }
    }

    /** An attribute of the current element, or null when it is absent. */
    private static String attribute(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
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
     * A patch that fails on one copy is asked of the next: VizieR, then the ESA archive, then ARI Heidelberg. Each copy
     * has its own step. Interruption keeps the patches already fetched and marks the copy in progress not asked.
     */
    private void fetchDepth(Attempt attempt) throws InterruptedException {
        SkyField field = attempt.catalogue.field;
        attempt.progress.stage(3, 3, String.format(Locale.US,
                "faint stars of four small patches (Gaia, to %.1f, for the field depth)", DEPTH_MAGNITUDE));
        double side = Math.min(DEPTH_PATCH_DEG, Math.min(field.widthDeg, field.heightDeg) / 4.0);
        List<double[]> remaining = new ArrayList<>();
        for (int i = 0; i < field.cornerRa.length; i++) {
            double deltaRa = ((field.cornerRa[i] - field.centerRa + 540) % 360) - 180;
            remaining.add(new double[] {(field.centerRa + deltaRa / 2 + 360) % 360,
                    (field.centerDec + field.cornerDec[i]) / 2, side});
        }
        int patches = remaining.size();
        List<SkyCatalogue.DepthSample> samples = new ArrayList<>();
        String current = "Gaia via VizieR";
        try {
            samples.addAll(depthFrom(attempt, current, "VizieR", VIZIER_TAP, true, remaining, patches));
            if (!remaining.isEmpty()) {
                current = "Gaia via the ESA archive";
                attempt.progress.stage(3, 3, "faint stars of the remaining patches (Gaia, ESA archive)");
                attempt.expectNext(new Planned(current, "Field depth"));
                samples.addAll(depthFrom(attempt, current, "ESA archive", ESA_GAIA_TAP, false, remaining, patches));
            }
            if (!remaining.isEmpty()) {
                current = "Gaia via ARI Heidelberg";
                attempt.progress.stage(3, 3, "faint stars of the remaining patches (Gaia, ARI Heidelberg)");
                attempt.expectNext(new Planned(current, "Field depth"));
                samples.addAll(depthFrom(attempt, current, "ARI Heidelberg", ARI_GAIA_TAP, false, remaining, patches));
            }
        } catch (InterruptedException interrupted) {
            attempt.catalogue.depthSamples = samples;
            attempt.notAsked(current, "Field depth", samples.size() + " of " + patches + " patches answered",
                    "The fetch was cancelled.");
            throw interrupted;
        }
        attempt.catalogue.depthSamples = samples;
        if (!remaining.isEmpty()) {
            attempt.catalogue.problems.add("Faint stars for the field depth (Gaia): " + samples.size() + " of "
                    + patches + " patches answered.");
        }
    }

    /**
     * Asks one Gaia copy for the patches still missing. Patches that answer are removed from {@code remaining}.
     * One step records how many of the patches asked of this copy came back.
     */
    private List<SkyCatalogue.DepthSample> depthFrom(Attempt attempt, String service, String place, String endpoint,
            boolean vizier, List<double[]> remaining, int patches) throws InterruptedException {
        if (attempt.alreadyDown(endpoint)) {
            attempt.notAsked(service, "Field depth", "", SKIPPED);
            return List.of();
        }
        List<SkyCatalogue.DepthSample> answered = new ArrayList<>();
        StringBuilder queries = new StringBuilder();
        IOException last = null;
        boolean truncated = false;
        boolean stop = false;
        int asked = remaining.size();
        List<double[]> still = new ArrayList<>();
        for (int i = 0; i < remaining.size(); i++) {
            double[] patch = remaining.get(i);
            if (stop) {
                still.add(patch);
                continue;
            }
            String adql = vizier ? depthQuery(patch[0], patch[1], patch[2]) : depthArchiveQuery(patch[0], patch[1], patch[2]);
            if (queries.length() > 0) {
                queries.append("\n\n");
            }
            queries.append(adql);
            try {
                TapPage page = tapPage(endpoint, adql, MAX_STARS, STAR_TIMEOUT);
                answered.add(depthSample(patch[0], patch[1], patch[2], page.table, vizier));
                truncated = truncated || page.truncated;
            } catch (IOException problem) {
                last = problem;
                still.add(patch);
                if (attempt.remember(endpoint, problem)) {
                    stop = true;
                }
            }
        }
        remaining.clear();
        remaining.addAll(still);
        String outcome = (asked - still.size()) + " of " + (vizier ? patches : asked) + " patches";
        if (last == null && !truncated) {
            attempt.succeed(service, "Field depth", outcome,
                    String.format(Locale.US, "To magnitude %.1f.", DEPTH_MAGNITUDE), endpoint, queries.toString());
        } else if (truncated && last == null) {
            attempt.fail(service, "Field depth", outcome + " (truncated)", "The answer was cut off.", endpoint, queries.toString());
            attempt.catalogue.problems.add("Faint stars for the field depth (Gaia, " + place + "): the answer was cut off.");
        } else {
            attempt.fail(service, "Field depth", outcome, last.getMessage(), endpoint, queries.toString());
        }
        return answered;
    }

    /** One TAP answer, including a table the service said was cut off. */
    private TapPage tapPage(String endpoint, String adql, int maxRecords, Duration timeout)
            throws IOException, InterruptedException {
        try {
            return new TapPage(query(endpoint, adql, maxRecords, timeout), false);
        } catch (TruncatedAnswer cut) {
            return new TapPage(cut.table, true);
        }
    }

    private record TapPage(CsvTable table, boolean truncated) {
    }

    /** One faint patch from a table already fetched. */
    private static SkyCatalogue.DepthSample depthSample(double centerRa, double centerDec, double sideDeg, CsvTable table,
            boolean vizier) throws IOException {
        SkyCatalogue.DepthSample sample = new SkyCatalogue.DepthSample();
        sample.centerRa = centerRa;
        sample.centerDec = centerDec;
        sample.sideDeg = sideDeg;
        sample.magnitudeLimit = DEPTH_MAGNITUDE;
        sample.stars = vizier
                ? parseStars(table, "RA_ICRS", "DE_ICRS", "pmRA", "pmDE", "Gmag", "BP-RP")
                : parseStars(table, "ra", "dec", "pmra", "pmdec", "phot_g_mean_mag", "bp_rp");
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

    /** The same faint patch, in the column names used by the ESA archive and ARI Heidelberg. */
    private static String depthArchiveQuery(double centerRa, double centerDec, double sideDeg) {
        double halfDec = sideDeg / 2;
        double halfRa = halfDec / Math.max(0.05, Math.cos(Math.toRadians(centerDec)));
        String box = String.format(Locale.US, "POLYGON('ICRS', %.6f, %.6f, %.6f, %.6f, %.6f, %.6f, %.6f, %.6f)",
                centerRa - halfRa, centerDec - halfDec, centerRa + halfRa, centerDec - halfDec,
                centerRa + halfRa, centerDec + halfDec, centerRa - halfRa, centerDec + halfDec);
        return "SELECT ra, dec, pmra, pmdec, phot_g_mean_mag, bp_rp FROM gaiadr3.gaia_source "
                + "WHERE 1=CONTAINS(POINT('ICRS', ra, dec), " + box + ") "
                + String.format(Locale.US, "AND phot_g_mean_mag <= %.2f", DEPTH_MAGNITUDE);
    }

    /** The Gaia DR3 stars of the field to this G magnitude, from VizieR, then the ESA archive, then ARI Heidelberg. */
    public List<SkyCatalogue.Star> fetchStars(SkyField field, double magnitudeLimit) throws IOException, InterruptedException {
        return fetchStars(field, magnitudeLimit, new String[1], () -> { }, () -> { }, null);
    }

    /**
     * The stars. A service that fails is recorded before the next one is asked. {@code attempt} is null for the
     * plate-solution fit, which only needs the stars.
     */
    private List<SkyCatalogue.Star> fetchStars(SkyField field, double magnitudeLimit, String[] source,
            Runnable fallingBackToEsa, Runnable fallingBackToHeidelberg, Attempt attempt)
            throws IOException, InterruptedException {
        String vizierQuery = vizierStarQuery(field, magnitudeLimit);
        String vizierMessage = skippedOrFailedStars(attempt, "Gaia via VizieR", "Gaia DR3 via VizieR (CDS)",
                VIZIER_TAP, vizierQuery, true, source);
        if (vizierMessage == null && source[0] != null) {
            return lastStars;
        }
        if (attempt != null) {
            attempt.expectNext(new Planned("Gaia via the ESA archive", "Stars"));
        }
        fallingBackToEsa.run();
        String gaiaQuery = esaStarQuery(field, magnitudeLimit);
        String esaMessage = skippedOrFailedStars(attempt, "Gaia via the ESA archive", "Gaia DR3 via the ESA Gaia archive",
                ESA_GAIA_TAP, gaiaQuery, false, source);
        if (esaMessage == null && source[0] != null) {
            return lastStars;
        }
        if (attempt != null) {
            attempt.expectNext(new Planned("Gaia via ARI Heidelberg", "Stars"));
        }
        fallingBackToHeidelberg.run();
        String heidelbergMessage = skippedOrFailedStars(attempt, "Gaia via ARI Heidelberg", "Gaia DR3 via ARI Heidelberg",
                ARI_GAIA_TAP, gaiaQuery, false, source);
        if (heidelbergMessage == null && source[0] != null) {
            return lastStars;
        }
        if (attempt != null) {
            attempt.catalogue.problems.add("Stars (Gaia): " + (heidelbergMessage != null ? heidelbergMessage
                    : esaMessage != null ? esaMessage : vizierMessage != null ? vizierMessage : "did not answer earlier."));
        }
        throw new IOException("VizieR " + mentioned(vizierMessage)
                + "; ESA archive " + mentioned(esaMessage)
                + "; ARI Heidelberg " + mentioned(heidelbergMessage));
    }

    /** The stars returned by the copy that answered, including a cut-off answer. */
    private List<SkyCatalogue.Star> lastStars = List.of();

    /**
     * Asks one star copy. Returns null when it answered, and the failure message when it did not. A skipped copy
     * returns the skip sentence. {@code source[0]} is set when the copy answered.
     */
    private String skippedOrFailedStars(Attempt attempt, String service, String sourceName, String endpoint, String query,
            boolean vizier, String[] source) throws InterruptedException {
        if (attempt != null && attempt.alreadyDown(endpoint)) {
            attempt.notAsked(service, "Stars", "", SKIPPED);
            return SKIPPED;
        }
        try {
            TapPage page = tapPage(endpoint, query, starCap, STAR_TIMEOUT);
            List<SkyCatalogue.Star> stars = vizier
                    ? parseStars(page.table, "RA_ICRS", "DE_ICRS", "pmRA", "pmDE", "Gmag", "BP-RP")
                    : parseStars(page.table, "ra", "dec", "pmra", "pmdec", "phot_g_mean_mag", "bp_rp");
            lastStars = stars;
            source[0] = sourceName;
            if (attempt == null) {
                return null;
            }
            if (page.truncated) {
                attempt.fail(service, "Stars", count(stars.size(), "star") + " (truncated)", "The answer was cut off.",
                        endpoint, query);
                attempt.catalogue.problems.add("Stars (" + service + "): the answer was cut off.");
            } else {
                attempt.succeed(service, "Stars", count(stars.size(), "star"), "", endpoint, query);
            }
            return null;
        } catch (IOException problem) {
            if (attempt != null) {
                attempt.remember(endpoint, problem);
                attempt.fail(service, "Stars", "", problem.getMessage(), endpoint, query);
            }
            return problem.getMessage();
        }
    }

    /** A failure message for the combined exception, including a copy that was not asked. */
    private static String mentioned(String message) {
        return message == null ? "did not answer earlier." : message;
    }

    /** A timeout, a connection failure, or HTTP 404. A query error, including a full queue, is asked again. */
    static boolean gaveUp(IOException problem) {
        String message = problem.getMessage() == null ? "" : problem.getMessage();
        return message.startsWith("did not answer within")
                || message.startsWith("cannot be reached")
                || message.startsWith("timed out (HTTP")
                || message.contains("HTTP 404");
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

    /** The {@code gaiadr3.gaia_source} query sent to the ESA archive and, if that fails, to ARI Heidelberg. */
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
        if (response.statusCode() != 200) {
            throw new IOException(errorText(response.statusCode(), text));
        }
        if (text.trim().startsWith("<")) {
            return parseVotable(text);
        }
        if (reportsOverflow(text)) {
            throw new TruncatedAnswer(CsvTable.parse(text));
        }
        return CsvTable.parse(text);
    }

    /** Whether this answer says the service cut it off, rather than merely returning fewer rows than requested. */
    private static boolean reportsOverflow(String text) {
        return text != null && text.contains("QUERY_STATUS") && text.contains("OVERFLOW");
    }

    /** Fetches one cone-search page. A VOTable starts with {@code <} and is returned as it arrived. */
    static String httpGet(HttpClient http, String url, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", "SpacePixels")
                .GET()
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
        if (response.statusCode() != 200 || text.isBlank()) {
            throw new IOException(errorText(response.statusCode(), text));
        }
        return text;
    }

    /** The service's own error message from its VOTable answer or its error page, or the HTTP status. */
    static String errorText(int status, String body) {
        String text = body == null ? "" : body;
        Matcher matcher = QUERY_ERROR.matcher(text);
        if (matcher.find()) {
            return "answered with an error: " + shorten(matcher.group(1));
        }
        Matcher message = SERVICE_MESSAGE.matcher(text);
        if (message.find()) {
            return "answered with HTTP " + status + ": " + shorten(message.group(1));
        }
        return "answered with HTTP " + status + ".";
    }

    /** One line, at most 200 characters, from a service's own error text. */
    private static String shorten(String message) {
        String cleaned = message.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) + "…" : cleaned;
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
            pending.add(new Planned("AAVSO VSX via VizieR", "Variable stars"));
            pending.add(new Planned("Gaia via VizieR", "Stars"));
            pending.add(new Planned("Gaia via VizieR", "Field depth"));
        }

        /** Inserts a fallback at the front of the questions still to record, so a cancel names it next. */
        void expectNext(Planned planned) {
            pending.add(0, planned);
        }

        /** Whether this copy already timed out, could not be reached, or answered HTTP 404 in this fetch. */
        boolean alreadyDown(String endpoint) {
            return unavailable.contains(endpoint);
        }

        /** Remembers a copy that should not be asked again. Returns whether this failure is one of those. */
        boolean remember(String endpoint, IOException problem) {
            if (!gaveUp(problem)) {
                return false;
            }
            unavailable.add(endpoint);
            return true;
        }

        private final Set<String> unavailable = new HashSet<>();

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
