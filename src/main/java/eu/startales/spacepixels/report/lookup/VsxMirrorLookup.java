package eu.startales.spacepixels.report.lookup;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import eu.startales.spacepixels.util.DebugOutput;
import eu.startales.spacepixels.util.SkyMath;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When the Strasbourg VSX query does not answer, the same circle is read from the Harvard and IDIA VizieR copies.
 * The page is a VOTable; it is turned into the TAP JSON shape the report already reads, with the separation filled in.
 */
final class VsxMirrorLookup {

    static final String HARVARD = "https://vizier.cfa.harvard.edu/viz-bin/votable";
    static final String IDIA = "https://vizier.idia.ac.za/viz-bin/votable";
    private static final int CLOSEST = 10;
    private static final Pattern CIRCLE = Pattern.compile(
            "CIRCLE\\(\\s*'ICRS'\\s*,\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*,\\s*"
                    + "([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*,\\s*"
                    + "([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*\\)");
    private static final String[] COLUMNS = {
            "OID", "Name", "Type", "max", "n_max", "f_min", "min", "n_min", "l_max", "u_max", "l_min", "u_min",
            "Period", "l_Period", "u_Period", "n_OID", "RAJ2000", "DEJ2000"};

    private VsxMirrorLookup() {
    }

    /** One mirror that answered, or null when neither copy produced a table. */
    static JsonObject lookup(String tapUrl, PageFetch fetch) {
        Circle circle = circle(tapUrl);
        if (circle == null) {
            return null;
        }
        Mirror[] mirrors = {
                new Mirror(HARVARD, "vizier.cfa.harvard.edu", "AAVSO VSX via Harvard"),
                new Mirror(IDIA, "vizier.idia.ac.za", "AAVSO VSX via IDIA")};
        for (Mirror mirror : mirrors) {
            String url = mirror.address(circle);
            try {
                Fetched page = fetch.get(url, mirror.host);
                JsonObject payload = tapJson(page.body, circle);
                if (payload == null) {
                    continue;
                }
                JsonObject normalized = ReportLookupPayloadNormalizer.normalize("vsx", payload);
                stamp(normalized, mirror.label);
                JsonObject response = new JsonObject();
                response.addProperty("provider", "vsx");
                response.addProperty("sourceUrl", url);
                response.addProperty("fetchedAtUtc", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
                response.addProperty("upstreamStatus", page.status);
                response.addProperty("cacheSource", "live");
                response.addProperty("ok", true);
                response.add("payload", payload);
                response.add("normalized", normalized);
                return response;
            } catch (Exception problem) {
                DebugOutput.println("VSX mirror " + mirror.host + " did not answer: " + problem);
            }
        }
        return null;
    }

    /** The search circle written into a Strasbourg VSX query, or null when that circle is not there. */
    static Circle circle(String tapUrl) {
        if (tapUrl == null) {
            return null;
        }
        String query;
        try {
            query = java.net.URI.create(tapUrl).getQuery();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (query == null) {
            return null;
        }
        Matcher matcher = CIRCLE.matcher(query);
        if (!matcher.find()) {
            return null;
        }
        try {
            double ra = Double.parseDouble(matcher.group(1));
            double dec = Double.parseDouble(matcher.group(2));
            double radius = Double.parseDouble(matcher.group(3));
            if (!Double.isFinite(ra) || !Double.isFinite(dec) || !Double.isFinite(radius) || radius <= 0 || radius > 1) {
                return null;
            }
            return new Circle(ra, dec, radius);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * TAP JSON for one VOTable page: the report columns, closest ten first, with {@code dist_arcsec} filled in.
     * Null when the page is not a table.
     */
    static JsonObject tapJson(String xml, Circle circle) {
        if (xml == null) {
            return null;
        }
        String start = xml.stripLeading().toLowerCase(Locale.US);
        if (start.startsWith("<html") || start.startsWith("<!doctype")) {
            return null;
        }
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
            List<String> fields = new ArrayList<>();
            List<String[]> rows = new ArrayList<>();
            List<String> row = null;
            StringBuilder text = new StringBuilder();
            boolean table = false;
            boolean tableData = false;
            boolean error = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("TABLE".equals(name)) {
                        table = true;
                    } else if ("FIELD".equals(name) && !tableData) {
                        fields.add(attribute(reader, "name"));
                    } else if ("INFO".equals(name) && ("ERROR".equals(attribute(reader, "value"))
                            || "Error".equalsIgnoreCase(attribute(reader, "name"))
                            || "Error".equalsIgnoreCase(attribute(reader, "ID")))) {
                        error = true;
                    } else if ("TABLEDATA".equals(name)) {
                        tableData = true;
                    } else if ("TR".equals(name) && tableData) {
                        row = new ArrayList<>();
                    } else if ("TD".equals(name) && row != null) {
                        text.setLength(0);
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && row != null) {
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("TD".equals(name) && row != null) {
                        row.add(text.toString().trim());
                        text.setLength(0);
                    } else if ("TR".equals(name) && row != null) {
                        rows.add(row.toArray(String[]::new));
                        row = null;
                    }
                }
            }
            reader.close();
            if (error || !table || !fields.contains("RAJ2000") || !fields.contains("DEJ2000") || !fields.contains("Name")) {
                return null;
            }
            int raIndex = fields.indexOf("RAJ2000");
            int decIndex = fields.indexOf("DEJ2000");
            List<JsonArray> ranked = new ArrayList<>();
            for (String[] values : rows) {
                double ra = number(values, raIndex);
                double dec = number(values, decIndex);
                double separation = SkyMath.separationArcsec(circle.ra, circle.dec, ra, dec);
                if (!Double.isFinite(separation)) {
                    continue;
                }
                JsonArray json = new JsonArray();
                for (String column : COLUMNS) {
                    if ("RAJ2000".equals(column) || "DEJ2000".equals(column)) {
                        continue;
                    }
                    int index = fields.indexOf(column);
                    json.add(cell(values, index));
                }
                json.add(new JsonPrimitive(separation));
                ranked.add(json);
            }
            ranked.sort(Comparator.comparingDouble(values -> values.get(values.size() - 1).getAsDouble()));
            JsonArray data = new JsonArray();
            for (int i = 0; i < Math.min(CLOSEST, ranked.size()); i++) {
                data.add(ranked.get(i));
            }
            JsonArray metadata = new JsonArray();
            for (String column : COLUMNS) {
                if ("RAJ2000".equals(column) || "DEJ2000".equals(column)) {
                    continue;
                }
                JsonObject name = new JsonObject();
                name.addProperty("name", column);
                metadata.add(name);
            }
            JsonObject distance = new JsonObject();
            distance.addProperty("name", "dist_arcsec");
            metadata.add(distance);
            JsonObject payload = new JsonObject();
            payload.add("metadata", metadata);
            payload.add("data", data);
            return payload;
        } catch (Exception problem) {
            DebugOutput.println("Could not read a VSX mirror page: " + problem);
            return null;
        }
    }

    private static void stamp(JsonObject normalized, String source) {
        if (normalized == null || !normalized.has("matches") || !normalized.get("matches").isJsonArray()) {
            return;
        }
        for (var match : normalized.getAsJsonArray("matches")) {
            if (match.isJsonObject()) {
                match.getAsJsonObject().addProperty("source", source);
            }
        }
    }

    private static String attribute(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    private static double number(String[] values, int index) {
        if (index < 0 || index >= values.length || values[index] == null || values[index].isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(values[index]);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static com.google.gson.JsonElement cell(String[] values, int index) {
        if (index < 0 || index >= values.length || values[index] == null || values[index].isBlank()) {
            return JsonNull.INSTANCE;
        }
        return new JsonPrimitive(values[index]);
    }

    record Circle(double ra, double dec, double radiusDeg) {
    }

    private record Mirror(String endpoint, String host, String label) {
        String address(Circle circle) {
            String columns = String.join(",", COLUMNS);
            String target = String.format(Locale.US, "%.6f %+.6f", circle.ra, circle.dec);
            return endpoint + "?-source=B/vsx/vsx&-c=" + URLEncoder.encode(target, StandardCharsets.UTF_8)
                    + "&-c.rd=" + String.format(Locale.US, "%.6f", circle.radiusDeg)
                    + "&-out.max=500&-out=" + columns;
        }
    }

    /** One fetched page whose final host is the mirror that was asked. */
    record Fetched(int status, String body) {
    }

    /** Reads one mirror page. The final host must be {@code expectedHost}. */
    interface PageFetch {
        Fetched get(String url, String expectedHost) throws Exception;
    }
}
