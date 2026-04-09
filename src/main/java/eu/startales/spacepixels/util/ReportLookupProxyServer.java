/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Tiny localhost helper that allows static HTML reports to ask the running desktop application to
 * fetch and normalize trusted external JSON lookups. The report itself remains a plain file on
 * disk; only the live enrichment path goes through this local service.
 */
public final class ReportLookupProxyServer {

    public static final int PORT = 47831;
    public static final String BASE_URL = "http://127.0.0.1:" + PORT;

    private static final String FETCH_PATH = "/api/report/fetch";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int SATCHECKER_READ_TIMEOUT_MS = 60_000;
    private static final int JPL_READ_TIMEOUT_MS = 300_000;
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final ReportLookupProxyServer INSTANCE = new ReportLookupProxyServer();

    private final Logger logger = Logger.getLogger(ReportLookupProxyServer.class.getName());

    private volatile HttpServer server;
    private volatile ExecutorService executor;

    private ReportLookupProxyServer() {
    }

    public static ReportLookupProxyServer getInstance() {
        return INSTANCE;
    }

    public static String getServiceBaseUrl() {
        return BASE_URL;
    }

    public static int getPort() {
        return PORT;
    }

    public synchronized boolean start() {
        if (server != null) {
            return true;
        }

        try {
            HttpServer httpServer = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT),
                    0);
            ExecutorService workerPool = Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "SpacePixels-ReportLookupProxy");
                thread.setDaemon(true);
                return thread;
            });
            httpServer.setExecutor(workerPool);
            httpServer.createContext(FETCH_PATH, new FetchHandler());
            httpServer.start();

            server = httpServer;
            executor = workerPool;
            logger.info("Report lookup proxy listening on " + BASE_URL + FETCH_PATH);
            return true;
        } catch (BindException e) {
            logger.warning("Report lookup proxy could not bind to " + BASE_URL + ": " + e.getMessage());
            return false;
        } catch (IOException e) {
            logger.warning("Failed to start report lookup proxy: " + e.getMessage());
            return false;
        }
    }

    public synchronized void stop() {
        HttpServer runningServer = server;
        server = null;
        if (runningServer != null) {
            runningServer.stop(0);
        }

        ExecutorService runningExecutor = executor;
        executor = null;
        if (runningExecutor != null) {
            runningExecutor.shutdownNow();
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    private final class FetchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange.getResponseHeaders());

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, createError("Only GET is supported by the report lookup proxy."));
                return;
            }

            Map<String, String> queryParameters = parseQuery(exchange.getRequestURI().getRawQuery());
            String provider = trimToNull(queryParameters.get("provider"));
            String encodedTarget = trimToNull(queryParameters.get("target"));

            if (provider == null || encodedTarget == null) {
                writeJson(exchange, 400, createError("Missing report lookup parameters."));
                return;
            }

            if (!"satchecker".equals(provider) && !"jpl".equals(provider)) {
                writeJson(exchange, 400, createError("Unsupported report lookup provider."));
                return;
            }

            if (encodedTarget.length() > 16_384) {
                writeJson(exchange, 400, createError("Encoded lookup target is too large."));
                return;
            }

            final String targetUrl;
            try {
                byte[] decodedBytes = Base64.getUrlDecoder().decode(encodedTarget);
                targetUrl = new String(decodedBytes, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, createError("Lookup target could not be decoded."));
                return;
            }

            final URI validatedUri;
            try {
                validatedUri = validateTarget(provider, targetUrl);
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, createError(e.getMessage()));
                return;
            }

            JsonObject response = proxyLookup(provider, validatedUri.toString());
            writeJson(exchange, 200, response);
        }
    }

    private JsonObject proxyLookup(String provider, String targetUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(targetUrl).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(resolveReadTimeoutMillis(provider));
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "SpacePixels-ReportLookupProxy/1.0");

            int upstreamStatus = connection.getResponseCode();
            InputStream upstreamStream = upstreamStatus >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = upstreamStream != null ? readLimitedUtf8(upstreamStream, MAX_RESPONSE_BYTES) : "";

            JsonObject response = new JsonObject();
            response.addProperty("provider", provider);
            response.addProperty("sourceUrl", targetUrl);
            response.addProperty("fetchedAtUtc", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            response.addProperty("upstreamStatus", upstreamStatus);

            JsonElement payload = tryParseJson(body);
            if (payload == null) {
                payload = new JsonPrimitive(body);
            }

            if (upstreamStatus >= 200 && upstreamStatus < 300) {
                response.addProperty("ok", true);
                response.add("payload", payload);
                response.add("normalized", "satchecker".equals(provider)
                        ? normalizeSatCheckerPayload(payload)
                        : normalizeJplPayload(payload));
            } else {
                response.addProperty("ok", false);
                response.addProperty("message", "Upstream service returned HTTP " + upstreamStatus + ".");
                response.add("payload", payload);
            }
            return response;
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("provider", provider);
            error.addProperty("sourceUrl", targetUrl);
            error.addProperty("message", "Report lookup failed: " + e.getMessage());
            return error;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static URI validateTarget(String provider, String targetUrl) {
        URI uri = URI.create(targetUrl);

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Report lookups only allow HTTPS targets.");
        }
        if (uri.getFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Report lookup target contains unsupported URL parts.");
        }

        String host = uri.getHost();
        String path = uri.getPath();
        if ("satchecker".equals(provider)) {
            if (!"satchecker.cps.iau.org".equalsIgnoreCase(host) || !"/fov/satellite-passes/".equals(path)) {
                throw new IllegalArgumentException("Only SatChecker candidate lookups are allowed.");
            }
        } else if ("jpl".equals(provider)) {
            if (!"ssd-api.jpl.nasa.gov".equalsIgnoreCase(host) || !"/sb_ident.api".equals(path)) {
                throw new IllegalArgumentException("Only JPL small-body identification lookups are allowed.");
            }
        }

        return uri;
    }

    private static int resolveReadTimeoutMillis(String provider) {
        return "jpl".equals(provider) ? JPL_READ_TIMEOUT_MS : SATCHECKER_READ_TIMEOUT_MS;
    }

    private static String normalizeSbdbLookupSearchString(String objectName) {
        String trimmed = trimToNull(objectName);
        if (trimmed == null) {
            return "";
        }
        if (trimmed.length() >= 2 && trimmed.startsWith("(") && trimmed.endsWith(")")) {
            String unwrapped = trimToNull(trimmed.substring(1, trimmed.length() - 1));
            if (unwrapped != null) {
                return unwrapped;
            }
        }
        if (trimmed.matches("^\\d+\\s+.*[a-z].*$")) {
            int firstSpace = trimmed.indexOf(' ');
            if (firstSpace > 0) {
                return trimmed.substring(0, firstSpace);
            }
        }
        int lastOpenParen = trimmed.lastIndexOf('(');
        int lastCloseParen = trimmed.endsWith(")") ? trimmed.length() - 1 : -1;
        if (lastOpenParen > 0 && lastCloseParen > lastOpenParen) {
            String trailingDesignation = trimToNull(trimmed.substring(lastOpenParen + 1, lastCloseParen));
            if (trailingDesignation != null) {
                return trailingDesignation;
            }
        }
        return trimmed;
    }

    private static JsonObject normalizeSatCheckerPayload(JsonElement payload) {
        JsonObject normalized = new JsonObject();
        if (payload == null || !payload.isJsonObject()) {
            return normalized;
        }

        JsonObject root = payload.getAsJsonObject();
        JsonObject data = getObject(root, "data");
        JsonObject performance = getObject(root, "performance");
        JsonObject satellites = getObject(data, "satellites");

        normalized.addProperty("source", getString(root, "source"));
        normalized.addProperty("version", getString(root, "version"));
        normalized.addProperty("totalSatellites", getInt(data, "total_satellites", satellites != null ? satellites.size() : 0));
        normalized.addProperty("totalPositionResults", getInt(data, "total_position_results", 0));
        addFiniteProperty(normalized, "totalTimeSeconds", getDouble(performance, "total_time"));
        addFiniteProperty(normalized, "calculationTimeSeconds", getDouble(performance, "calculation_time"));

        JsonArray candidates = new JsonArray();
        if (satellites != null) {
            for (Map.Entry<String, JsonElement> entry : satellites.entrySet()) {
                JsonObject satellite = asObject(entry.getValue());
                if (satellite == null) {
                    continue;
                }

                JsonArray positions = getArray(satellite, "positions");
                String firstUtc = null;
                String lastUtc = null;
                String tleEpochUtc = null;
                double minAngle = Double.NaN;
                double maxAngle = Double.NaN;
                double minRangeKm = Double.NaN;
                double maxRangeKm = Double.NaN;

                if (positions != null) {
                    for (JsonElement positionElement : positions) {
                        JsonObject position = asObject(positionElement);
                        if (position == null) {
                            continue;
                        }

                        String utc = getString(position, "date_time");
                        if (firstUtc == null && utc != null) {
                            firstUtc = utc;
                        }
                        if (utc != null) {
                            lastUtc = utc;
                        }

                        if (tleEpochUtc == null) {
                            tleEpochUtc = getString(position, "tle_epoch");
                        }

                        double angle = getDouble(position, "angle");
                        double rangeKm = getDouble(position, "range_km");
                        minAngle = minFinite(minAngle, angle);
                        maxAngle = maxFinite(maxAngle, angle);
                        minRangeKm = minFinite(minRangeKm, rangeKm);
                        maxRangeKm = maxFinite(maxRangeKm, rangeKm);
                    }
                }

                JsonObject candidate = new JsonObject();
                String displayName = getString(satellite, "name");
                if (displayName == null || displayName.isEmpty()) {
                    displayName = entry.getKey();
                }
                candidate.addProperty("displayName", displayName);

                int noradId = getInt(satellite, "norad_id", -1);
                if (noradId > 0) {
                    candidate.addProperty("noradId", noradId);
                    candidate.addProperty("n2yoUrl", "https://www.n2yo.com/satellite/?s=" + noradId);
                    candidate.addProperty("celestrakUrl", "https://celestrak.org/satcat/records.php?CATNR=" + noradId);
                }
                candidate.addProperty("positionCount", positions != null ? positions.size() : 0);
                addStringProperty(candidate, "firstUtc", firstUtc);
                addStringProperty(candidate, "lastUtc", lastUtc);
                addStringProperty(candidate, "tleEpochUtc", tleEpochUtc);
                addFiniteProperty(candidate, "minAngleDeg", minAngle);
                addFiniteProperty(candidate, "maxAngleDeg", maxAngle);
                addFiniteProperty(candidate, "minRangeKm", minRangeKm);
                addFiniteProperty(candidate, "maxRangeKm", maxRangeKm);
                candidates.add(candidate);
            }
        }

        normalized.add("candidates", candidates);
        return normalized;
    }

    private static JsonObject normalizeJplPayload(JsonElement payload) {
        JsonObject normalized = new JsonObject();
        if (payload == null || !payload.isJsonObject()) {
            return normalized;
        }

        JsonObject root = payload.getAsJsonObject();
        JsonObject signature = getObject(root, "signature");
        JsonObject observer = getObject(root, "observer");

        normalized.addProperty("source", getString(signature, "source"));
        normalized.addProperty("version", getString(signature, "version"));
        addStringProperty(normalized, "observerLocation", getString(observer, "location"));
        addStringProperty(normalized, "observationUtc", getString(observer, "obs_date"));
        addStringProperty(normalized, "fovCenter", getString(observer, "fov_center"));

        JsonArray fields = null;
        JsonArray rows = null;
        String resultSetLabel = null;

        if (hasArray(root, "fields_second") && hasArray(root, "data_second_pass")) {
            fields = getArray(root, "fields_second");
            rows = getArray(root, "data_second_pass");
            resultSetLabel = "Second Pass Candidates";
        } else if (hasArray(root, "fields_first") && hasArray(root, "data_first_pass")) {
            fields = getArray(root, "fields_first");
            rows = getArray(root, "data_first_pass");
            resultSetLabel = "First Pass Candidates";
        } else if (hasArray(root, "fields_second") && hasArray(root, "elem_second_pass")) {
            fields = getArray(root, "fields_second");
            rows = getArray(root, "elem_second_pass");
            resultSetLabel = "Second Pass Elements";
        } else if (hasArray(root, "fields_first") && hasArray(root, "elem_first_pass")) {
            fields = getArray(root, "fields_first");
            rows = getArray(root, "elem_first_pass");
            resultSetLabel = "First Pass Elements";
        }

        addStringProperty(normalized, "resultSetLabel", resultSetLabel);
        normalized.addProperty("matchCount", rows != null ? rows.size() : 0);
        normalized.add("fields", fields != null ? fields.deepCopy() : new JsonArray());

        JsonArray normalizedRows = new JsonArray();
        if (rows != null) {
            for (JsonElement rowElement : rows) {
                JsonArray row = rowElement != null && rowElement.isJsonArray() ? rowElement.getAsJsonArray() : null;
                if (row == null) {
                    continue;
                }

                JsonObject rowObject = new JsonObject();
                String objectName = getString(row, 0);
                addStringProperty(rowObject, "objectName", objectName);
                rowObject.add("values", row.deepCopy());
                if (objectName != null && !objectName.isEmpty()) {
                    String lookupSearchString = normalizeSbdbLookupSearchString(objectName);
                    rowObject.addProperty(
                            "sbdbLookupUrl",
                            "https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr="
                                    + URLEncoder.encode(lookupSearchString, StandardCharsets.UTF_8));
                    rowObject.addProperty(
                            "sbdbUrl",
                            "https://ssd.jpl.nasa.gov/sbdb.cgi?sstr="
                                    + URLEncoder.encode(lookupSearchString, StandardCharsets.UTF_8));
                }
                normalizedRows.add(rowObject);
            }
        }

        normalized.add("rows", normalizedRows);
        return normalized;
    }

    private static void addCorsHeaders(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
        headers.set("Cache-Control", "no-store");
    }

    private static JsonObject createError(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("ok", false);
        error.addProperty("message", message);
        return error;
    }

    private static void writeJson(HttpExchange exchange, int statusCode, JsonObject payload) throws IOException {
        byte[] jsonBytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, jsonBytes.length);
        try (OutputStream responseStream = exchange.getResponseBody()) {
            responseStream.write(jsonBytes);
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }

        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            if (pair == null || pair.isEmpty()) {
                continue;
            }
            int separatorIndex = pair.indexOf('=');
            String rawKey = separatorIndex >= 0 ? pair.substring(0, separatorIndex) : pair;
            String rawValue = separatorIndex >= 0 ? pair.substring(separatorIndex + 1) : "";
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private static String readLimitedUtf8(InputStream inputStream, int maxBytes) throws IOException {
        try (InputStream stream = inputStream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            int total = 0;
            while ((read = stream.read(chunk)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Upstream response exceeded the report lookup size limit.");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private static JsonElement tryParseJson(String body) {
        String trimmed = trimToNull(body);
        if (trimmed == null) {
            return JsonNull.INSTANCE;
        }

        try {
            return JsonParser.parseString(trimmed);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonObject getObject(JsonObject parent, String fieldName) {
        if (parent == null || fieldName == null || !parent.has(fieldName) || !parent.get(fieldName).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(fieldName);
    }

    private static JsonArray getArray(JsonObject parent, String fieldName) {
        if (parent == null || fieldName == null || !parent.has(fieldName) || !parent.get(fieldName).isJsonArray()) {
            return null;
        }
        return parent.getAsJsonArray(fieldName);
    }

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static boolean hasArray(JsonObject parent, String fieldName) {
        return getArray(parent, fieldName) != null;
    }

    private static String getString(JsonObject parent, String fieldName) {
        return getString(parent, fieldName, null);
    }

    private static String getString(JsonObject parent, String fieldName, String defaultValue) {
        if (parent == null || fieldName == null || !parent.has(fieldName) || parent.get(fieldName).isJsonNull()) {
            return defaultValue;
        }
        try {
            return parent.get(fieldName).getAsString();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static String getString(JsonArray array, int index) {
        if (array == null || index < 0 || index >= array.size() || array.get(index).isJsonNull()) {
            return null;
        }
        try {
            return array.get(index).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int getInt(JsonObject parent, String fieldName, int defaultValue) {
        if (parent == null || fieldName == null || !parent.has(fieldName) || parent.get(fieldName).isJsonNull()) {
            return defaultValue;
        }
        try {
            return parent.get(fieldName).getAsInt();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static double getDouble(JsonObject parent, String fieldName) {
        if (parent == null || fieldName == null || !parent.has(fieldName) || parent.get(fieldName).isJsonNull()) {
            return Double.NaN;
        }
        try {
            return parent.get(fieldName).getAsDouble();
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    private static void addStringProperty(JsonObject object, String fieldName, String value) {
        if (object != null && fieldName != null && value != null && !value.isEmpty()) {
            object.addProperty(fieldName, value);
        }
    }

    private static void addFiniteProperty(JsonObject object, String fieldName, double value) {
        if (object != null && fieldName != null && Double.isFinite(value)) {
            object.addProperty(fieldName, value);
        }
    }

    private static double minFinite(double current, double candidate) {
        if (!Double.isFinite(candidate)) {
            return current;
        }
        if (!Double.isFinite(current)) {
            return candidate;
        }
        return Math.min(current, candidate);
    }

    private static double maxFinite(double current, double candidate) {
        if (!Double.isFinite(candidate)) {
            return current;
        }
        if (!Double.isFinite(current)) {
            return candidate;
        }
        return Math.max(current, candidate);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
