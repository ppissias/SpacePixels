package eu.startales.spacepixels.report.lookup;

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
public final class ReportLookupUpstreamClient {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int SATCHECKER_READ_TIMEOUT_MS = 60_000;
    private static final int JPL_READ_TIMEOUT_MS = 300_000;
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    /** VizieR TAP service hosting the AAVSO International Variable Star Index (VSX), which is queried as JSON. */
    public static final String VSX_TAP_HOST = "tapvizier.cds.unistra.fr";
    public static final String VSX_TAP_PATH = "/TAPVizieR/tap/sync";
    public static final String VSX_TABLE = "B/vsx/vsx";

    private ReportLookupUpstreamClient() {
    }

    static JsonObject proxyLookup(String provider, String targetUrl) {
        return proxyLookup(provider, targetUrl, null);
    }

    /** Registers the current connection with its comparison so cancellation disconnects network I/O. */
    static JsonObject proxyLookup(String provider, String targetUrl, SatCheckerTrackLookup.Control control) {
        HttpURLConnection connection = null;
        try {
            validateTarget(provider, targetUrl);
            connection = (HttpURLConnection) new URL(targetUrl).openConnection();
            if ("satchecker".equals(provider)) connection.setInstanceFollowRedirects(false);
            if (control != null) control.bind(connection);
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
                boolean ephemeris = "satchecker".equals(provider) && URI.create(targetUrl).getPath().equals("/ephemeris/catalog-number-jdstep/");
                if (ephemeris) validateEphemerisPayload(payload); else validatePayload(provider, payload);
                response.addProperty("ok", true);
                response.add("payload", payload);
                response.add("normalized", ephemeris ? new JsonObject() : ReportLookupPayloadNormalizer.normalize(provider, payload));
            } else {
                response.addProperty("ok", false);
                String detail = payload.isJsonObject() && payload.getAsJsonObject().has("message")
                        && payload.getAsJsonObject().get("message").isJsonPrimitive()
                        ? " " + payload.getAsJsonObject().get("message").getAsString() : "";
                response.addProperty("message", "Upstream service returned HTTP " + upstreamStatus + "." + detail);
                response.add("payload", payload);
            }
            return vsxOrMirror(provider, targetUrl, control, response);
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("provider", provider);
            error.addProperty("sourceUrl", targetUrl);
            error.addProperty("cacheSource", "live");
            error.addProperty("message", "Report lookup failed: " + e.getMessage());
            return vsxOrMirror(provider, targetUrl, control, error);
        } finally {
            if (control != null) control.release(connection);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * A failed VSX lookup is asked of Harvard, then IDIA. A successful Strasbourg answer, including an empty circle,
     * is kept. The mirror address is built here from the circle in the already checked Strasbourg query.
     */
    private static JsonObject vsxOrMirror(String provider, String targetUrl, SatCheckerTrackLookup.Control control,
            JsonObject response) {
        if (!"vsx".equals(provider) || response.has("ok") && response.get("ok").getAsBoolean()) {
            return response;
        }
        JsonObject mirror = VsxMirrorLookup.lookup(targetUrl, (url, expectedHost) -> open(url, expectedHost, control));
        return mirror != null ? mirror : response;
    }

    /** Fetches one HTTPS page and refuses a redirect onto a different host. */
    private static VsxMirrorLookup.Fetched open(String url, String expectedHost, SatCheckerTrackLookup.Control control)
            throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            if (control != null) {
                control.bind(connection);
            }
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(SATCHECKER_READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "SpacePixels-ReportLookupProxy/1.0");
            int status = connection.getResponseCode();
            if (connection.getURL() == null || !expectedHost.equalsIgnoreCase(connection.getURL().getHost())) {
                throw new IOException("The mirror redirected away from " + expectedHost + ".");
            }
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = stream != null ? readLimitedUtf8(stream, MAX_RESPONSE_BYTES) : "";
            if (status < 200 || status >= 300) {
                throw new IOException("Upstream service returned HTTP " + status + ".");
            }
            return new VsxMirrorLookup.Fetched(status, body);
        } finally {
            if (control != null) {
                control.release(connection);
            }
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
        if (uri.getFragment() != null || uri.getRawUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("Report lookup target contains unsupported URL parts.");
        }

        String host = uri.getHost();
        String path = uri.getPath();
        if ("satchecker".equals(provider)) {
            if (!"satchecker.cps.iau.org".equalsIgnoreCase(host)
                    || !"/fov/satellite-passes/".equals(path) && !"/ephemeris/catalog-number-jdstep/".equals(path)) {
                throw new IllegalArgumentException("Only SatChecker candidate and catalogue-number ephemeris lookups are allowed.");
            }
        } else if ("jpl".equals(provider)) {
            if (!"ssd-api.jpl.nasa.gov".equalsIgnoreCase(host) || !"/sb_ident.api".equals(path)) {
                throw new IllegalArgumentException("Only JPL small-body identification lookups are allowed.");
            }
        } else if ("vsx".equals(provider)) {
            String query = uri.getQuery();
            if (!VSX_TAP_HOST.equalsIgnoreCase(host) || !VSX_TAP_PATH.equals(path)
                    || query == null || !query.contains("FROM \"" + VSX_TABLE + "\"")) {
                throw new IllegalArgumentException("Only VizieR VSX variable-star lookups are allowed.");
            }
        }

        return uri;
    }

    static void validatePayload(String provider, JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) throw new IllegalArgumentException("The catalogue returned an invalid response, not a search result.");
        JsonObject root = payload.getAsJsonObject();
        if (root.has("error") && !root.get("error").isJsonNull()) throw new IllegalArgumentException("The catalogue reported an error: " + root.get("error"));
        boolean valid = switch (provider) {
            case "vsx" -> root.has("metadata") && root.get("metadata").isJsonArray()
                    && !root.getAsJsonArray("metadata").isEmpty() && root.has("data") && root.get("data").isJsonArray();
            case "satchecker" -> root.has("data") && root.get("data").isJsonObject()
                    && root.getAsJsonObject("data").has("satellites") && root.getAsJsonObject("data").get("satellites").isJsonObject();
            case "jpl" -> root.has("signature") && root.get("signature").isJsonObject()
                    && (root.has("n_second_pass") || root.has("n_first_pass"));
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("The catalogue response is incomplete; no identification conclusion can be drawn. Retry the lookup.");
    }

    /** Ephemerides use a different tabular shape; pending tasks and errors are not empty trajectories. */
    static void validateEphemerisPayload(JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) throw new IllegalArgumentException("Invalid satellite ephemeris response.");
        var root = payload.getAsJsonObject();
        if (!root.has("fields") || !root.get("fields").isJsonArray() || !root.has("data") || !root.get("data").isJsonArray()
                || root.has("error")) throw new IllegalArgumentException("Satellite ephemeris is incomplete or unavailable.");
        var fields = root.getAsJsonArray("fields");
        for (String field : new String[]{"catalog_id", "julian_date", "right_ascension_deg", "declination_deg"})
            if (!fields.contains(new JsonPrimitive(field))) throw new IllegalArgumentException("Satellite ephemeris lacks " + field + ".");
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
