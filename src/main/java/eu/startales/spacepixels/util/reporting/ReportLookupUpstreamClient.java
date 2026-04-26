package eu.startales.spacepixels.util.reporting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Validates and executes trusted upstream report lookups, returning the normalized proxy response shape.
 */
final class ReportLookupUpstreamClient {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int SATCHECKER_READ_TIMEOUT_MS = 60_000;
    private static final int JPL_READ_TIMEOUT_MS = 300_000;
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    private ReportLookupUpstreamClient() {
    }

    static JsonObject proxyLookup(String provider, String targetUrl) {
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
            response.addProperty("cacheSource", "live");

            JsonElement payload = tryParseJson(body);
            if (payload == null) {
                payload = new JsonPrimitive(body);
            }

            if (upstreamStatus >= 200 && upstreamStatus < 300) {
                response.addProperty("ok", true);
                response.add("payload", payload);
                response.add("normalized", ReportLookupPayloadNormalizer.normalize(provider, payload));
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
            error.addProperty("cacheSource", "live");
            error.addProperty("message", "Report lookup failed: " + e.getMessage());
            return error;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static URI validateTarget(String provider, String targetUrl) {
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
