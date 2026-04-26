package eu.startales.spacepixels.util.reporting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Handles sidecar JSON persistence and embedded report cache storage for live lookup responses.
 */
final class ReportLookupCacheStore {

    private static final int MAX_SIDECAR_BYTES = (10 * 1024 * 1024) + (256 * 1024);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Logger LOGGER = Logger.getLogger(ReportLookupCacheStore.class.getName());

    private ReportLookupCacheStore() {
    }

    static Path resolveReportPath(String reportUrl) {
        String trimmedReportUrl = trimToNull(reportUrl);
        if (trimmedReportUrl == null) {
            return null;
        }

        final URI reportUri;
        try {
            reportUri = URI.create(trimmedReportUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Report URL could not be parsed.");
        }

        if (!"file".equalsIgnoreCase(reportUri.getScheme())
                || reportUri.getRawQuery() != null
                || reportUri.getRawFragment() != null) {
            throw new IllegalArgumentException("Report sidecar caching only supports local file reports.");
        }

        final Path reportPath;
        try {
            reportPath = Paths.get(reportUri).normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Report path could not be resolved.");
        }

        Path reportFileName = reportPath.getFileName();
        if (reportFileName == null || !DetectionReportGenerator.detectionReportName.equals(reportFileName.toString())) {
            throw new IllegalArgumentException("Report sidecar caching only supports detection_report.html.");
        }
        if (!Files.isRegularFile(reportPath)) {
            throw new IllegalArgumentException("Report file could not be found for sidecar caching.");
        }
        return reportPath;
    }

    static Path resolveSidecarPath(String reportUrl, String sidecarFileName) {
        String trimmedSidecarFileName = normalizeSidecarFileNameForPath(sidecarFileName);
        Path reportPath = resolveReportPath(reportUrl);
        if (reportPath == null && trimmedSidecarFileName == null) {
            return null;
        }
        if (reportPath == null || trimmedSidecarFileName == null) {
            throw new IllegalArgumentException("Report sidecar caching requires both report and sidecar parameters.");
        }

        Path reportDirectory = reportPath.getParent();
        if (reportDirectory == null) {
            throw new IllegalArgumentException("Report sidecar caching requires a report directory.");
        }

        Path sidecarPath = reportDirectory.resolve(trimmedSidecarFileName).normalize();
        if (!reportDirectory.equals(sidecarPath.getParent())) {
            throw new IllegalArgumentException("Report sidecar file name must stay within the report folder.");
        }
        return sidecarPath;
    }

    static JsonObject readPersistedLookupCache(Path reportPath) {
        if (reportPath == null || !Files.isRegularFile(reportPath)) {
            return createEmptyPersistedLookupCache();
        }
        try {
            return extractPersistedLookupCache(Files.readString(reportPath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return createEmptyPersistedLookupCache();
        }
    }

    static JsonObject readSidecarResponse(Path sidecarPath) {
        if (sidecarPath == null || !Files.isRegularFile(sidecarPath)) {
            return null;
        }
        try {
            long fileSize = Files.size(sidecarPath);
            if (fileSize > MAX_SIDECAR_BYTES) {
                return null;
            }
            try (InputStream inputStream = Files.newInputStream(sidecarPath)) {
                JsonElement parsed = tryParseJson(readLimitedUtf8(inputStream, MAX_SIDECAR_BYTES));
                return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            }
        } catch (IOException e) {
            return null;
        }
    }

    static JsonObject tryLoadMatchingSidecarResponse(Path sidecarPath, String provider, String sourceUrl) {
        JsonObject cachedResponse = readSidecarResponse(sidecarPath);
        if (cachedResponse == null) {
            return null;
        }
        if (!provider.equals(getString(cachedResponse, "provider"))
                || !sourceUrl.equals(getString(cachedResponse, "sourceUrl"))) {
            return null;
        }

        JsonObject response = cachedResponse.deepCopy();
        response.addProperty("cacheSource", "sidecar");
        return response;
    }

    static void writeSidecarResponse(Path sidecarPath, JsonObject response) {
        if (sidecarPath == null || !isCacheableSidecarResponse(response)) {
            return;
        }
        try {
            Files.createDirectories(sidecarPath.getParent());
            Files.writeString(sidecarPath, GSON.toJson(response), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warning("Failed to write report sidecar JSON " + sidecarPath + ": " + e.getMessage());
        }
    }

    static void persistResponseInReportCache(Path reportPath, String sidecarFileName, JsonObject response) {
        String trimmedSidecarFileName = trimToNull(sidecarFileName);
        if (reportPath == null || trimmedSidecarFileName == null || !isCacheableSidecarResponse(response)) {
            return;
        }

        try {
            String reportHtml = Files.readString(reportPath, StandardCharsets.UTF_8);
            JsonObject cache = extractPersistedLookupCache(reportHtml);
            JsonObject entries = getObject(cache, "entries");
            if (entries == null) {
                entries = new JsonObject();
                cache.add("entries", entries);
            }
            entries.add(trimmedSidecarFileName, response.deepCopy());
            Files.writeString(
                    reportPath,
                    upsertPersistedLookupCacheBlock(reportHtml, cache),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warning("Failed to persist report lookup cache in " + reportPath + ": " + e.getMessage());
        }
    }

    private static String normalizeSidecarFileNameForPath(String sidecarFileName) {
        String trimmed = trimToNull(sidecarFileName);
        if (trimmed == null) {
            return null;
        }
        if (!trimmed.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}(\\.json)?$")) {
            throw new IllegalArgumentException("Report sidecar file name is invalid.");
        }
        return trimmed.toLowerCase(Locale.US).endsWith(".json") ? trimmed : trimmed + ".json";
    }

    private static boolean isCacheableSidecarResponse(JsonObject response) {
        return response != null
                && getBoolean(response, "ok", false)
                && trimToNull(getString(response, "provider")) != null
                && trimToNull(getString(response, "sourceUrl")) != null;
    }

    private static JsonObject createEmptyPersistedLookupCache() {
        JsonObject cache = new JsonObject();
        cache.addProperty("version", 1);
        cache.add("entries", new JsonObject());
        return cache;
    }

    private static JsonObject extractPersistedLookupCache(String reportHtml) {
        if (reportHtml == null || reportHtml.isEmpty()) {
            return createEmptyPersistedLookupCache();
        }

        int markerStart = reportHtml.indexOf(ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_START_MARKER);
        int markerEnd = markerStart >= 0
                ? reportHtml.indexOf(
                        ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_END_MARKER,
                        markerStart + ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_START_MARKER.length())
                : -1;
        if (markerStart < 0 || markerEnd < 0) {
            return createEmptyPersistedLookupCache();
        }

        int scriptStart = reportHtml.indexOf("<script", markerStart);
        int contentStart = scriptStart >= 0 ? reportHtml.indexOf('>', scriptStart) : -1;
        int scriptEnd = contentStart >= 0 ? reportHtml.indexOf("</script>", contentStart) : -1;
        if (scriptStart < 0 || contentStart < 0 || scriptEnd < 0 || scriptEnd > markerEnd) {
            return createEmptyPersistedLookupCache();
        }

        JsonElement parsed = tryParseJson(reportHtml.substring(contentStart + 1, scriptEnd).trim());
        JsonObject cache = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : createEmptyPersistedLookupCache();
        if (!cache.has("version")) {
            cache.addProperty("version", 1);
        }
        if (!cache.has("entries") || !cache.get("entries").isJsonObject()) {
            cache.add("entries", new JsonObject());
        }
        return cache;
    }

    private static String upsertPersistedLookupCacheBlock(String reportHtml, JsonObject cache) {
        String block = buildPersistedLookupCacheBlock(cache);
        int markerStart = reportHtml.indexOf(ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_START_MARKER);
        int markerEnd = markerStart >= 0
                ? reportHtml.indexOf(
                        ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_END_MARKER,
                        markerStart + ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_START_MARKER.length())
                : -1;
        if (markerStart >= 0 && markerEnd >= 0) {
            return reportHtml.substring(0, markerStart)
                    + block
                    + reportHtml.substring(markerEnd + ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_END_MARKER.length());
        }

        int bodyEnd = reportHtml.lastIndexOf("</body>");
        if (bodyEnd >= 0) {
            return reportHtml.substring(0, bodyEnd)
                    + block
                    + System.lineSeparator()
                    + reportHtml.substring(bodyEnd);
        }
        return reportHtml + System.lineSeparator() + block;
    }

    private static String buildPersistedLookupCacheBlock(JsonObject cache) {
        return ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_START_MARKER
                + System.lineSeparator()
                + "<script type='application/json' id='"
                + ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_SCRIPT_ID
                + "'>"
                + escapeHtmlUnsafeJsonForScriptTag(GSON.toJson(cache))
                + "</script>"
                + System.lineSeparator()
                + ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_END_MARKER;
    }

    private static String escapeHtmlUnsafeJsonForScriptTag(String json) {
        return json == null ? "" : json.replace("<", "\\u003c");
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

    private static String getString(JsonObject parent, String fieldName) {
        if (parent == null || fieldName == null || !parent.has(fieldName) || parent.get(fieldName).isJsonNull()) {
            return null;
        }
        try {
            return parent.get(fieldName).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean getBoolean(JsonObject parent, String fieldName, boolean defaultValue) {
        if (parent == null || fieldName == null || !parent.has(fieldName) || parent.get(fieldName).isJsonNull()) {
            return defaultValue;
        }
        try {
            return parent.get(fieldName).getAsBoolean();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
