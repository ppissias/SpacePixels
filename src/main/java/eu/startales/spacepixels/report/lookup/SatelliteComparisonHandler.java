package eu.startales.spacepixels.report.lookup;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Local report endpoint with two owned comparison slots, explicit cancellation and observation-bound caching.
 */
final class SatelliteComparisonHandler implements HttpHandler {
    private final ConcurrentMap<String, SatCheckerTrackLookup.Control> active = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(2);
    private final Gson gson = new Gson();
    private final Function<SatCheckerTrackLookup.Control, SatCheckerTrackLookup> lookupFactory;

    /**
     * Creates a handler backed by live SatChecker requests.
     */
    SatelliteComparisonHandler() {
        this(SatCheckerTrackLookup::new);
    }

    /**
     * Supplies a deterministic lookup factory for loopback HTTP tests.
     */
    SatelliteComparisonHandler(Function<SatCheckerTrackLookup.Control, SatCheckerTrackLookup> lookupFactory) {
        this.lookupFactory = lookupFactory;
    }

    /**
     * Handles bounded snapshot requests; provider URLs are generated on the server, never supplied by the browser.
     */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (exchange.getRequestMethod().equals("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        var control = new SatCheckerTrackLookup.Control();
        String id = null;
        boolean admitted = false;
        try {
            byte[] bytes;
            try (var input = exchange.getRequestBody()) {
                bytes = input.readNBytes(1024 * 1024 + 1);
            }
            if (bytes.length > 1024 * 1024)
                throw new IllegalArgumentException("The observation snapshot exceeds the request limit.");
            var body = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            id = body.get("requestId").getAsString();
            if (!id.matches("[A-Za-z0-9-]{16,80}"))
                throw new IllegalArgumentException("Invalid comparison request identity.");
            if (exchange.getRequestMethod().equals("DELETE")) {
                var running = active.get(id);
                if (running != null) running.cancel();
                var response = new JsonObject();
                response.addProperty("ok", true);
                write(exchange, 200, response);
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                write(exchange, 405, error("Only comparison requests and cancellation are supported."));
                return;
            }
            var observation = gson.fromJson(body.get("observation"), StreakTrackObservation.class);
            observation.validate();
            String reportUrl = body.has("report") ? body.get("report").getAsString() : null;
            String sidecarName = body.has("sidecar") ? body.get("sidecar").getAsString() : null;
            var report = ReportLookupCacheStore.resolveReportPath(reportUrl);
            var sidecar = ReportLookupCacheStore.resolveSidecarPath(reportUrl, sidecarName);
            String source = "satchecker-track:" + observation.digest();
            var cached = body.has("refresh") && body.get("refresh").getAsBoolean() ? null :
                    ReportLookupCacheStore.tryLoadMatchingSidecarResponse(sidecar, "satchecker", source);
            if (isReusableCachedComparison(cached, observation)) {
                cached.addProperty("persisted", ReportLookupCacheStore.persistResponseInReportCache(report, sidecarName, cached));
                write(exchange, 200, cached);
                return;
            }
            if (!slots.tryAcquire()) {
                write(exchange, 429, error("Two satellite comparisons are already running. Retry when one finishes."));
                return;
            }
            admitted = true;
            if (active.putIfAbsent(id, control) != null)
                throw new IllegalArgumentException("This comparison request is already running.");
            CompletableFuture.delayedExecutor(300, TimeUnit.SECONDS).execute(control::expire);
            var response = lookupFactory.apply(control).compare(observation);
            control.checkCancelled();
            response.addProperty("sourceUrl", source);
            boolean ok = response.has("ok") && response.get("ok").getAsBoolean();
            if (ok) {
                ReportLookupCacheStore.writeSidecarResponse(sidecar, response);
                response.addProperty("persisted", ReportLookupCacheStore.persistResponseInReportCache(report, sidecarName, response));
            } else {
                response.addProperty("persisted", false);
            }
            write(exchange, 200, response);
        } catch (CancellationException e) {
            write(exchange, 200, error("Satellite comparison cancelled or timed out. No identity conclusion was drawn."));
        } catch (RuntimeException e) {
            write(exchange, 400, error("Satellite comparison unavailable: " + e.getMessage()));
        } finally {
            if (id != null) active.remove(id, control);
            control.cancel();
            if (admitted) slots.release();
        }
    }

    /**
     * Stops only comparisons owned by this proxy, including their active network connections.
     */
    void cancelAll() {
        active.values().forEach(SatCheckerTrackLookup.Control::cancel);
    }

    /**
     * Reuses a saved sidecar only when it succeeded for these observations and searched at least one exposure.
     */
    private static boolean isReusableCachedComparison(JsonObject cached, StreakTrackObservation observation) {
        if (cached == null || !cached.has("ok") || !cached.get("ok").getAsBoolean()) return false;
        if (!cached.has("observationDigest") || !observation.digest().equals(cached.get("observationDigest").getAsString())) return false;
        if (!cached.has("normalized") || !cached.get("normalized").isJsonObject()) return false;
        var normalized = cached.getAsJsonObject("normalized");
        if (!normalized.has("comparisonVersion") || normalized.get("comparisonVersion").getAsInt() != 1) return false;
        return !normalized.has("searchedExposures") || normalized.get("searchedExposures").getAsInt() > 0;
    }

    /**
     * Structured failure distinct from a successful search with no returned candidate.
     */
    private static JsonObject error(String message) {
        var out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("message", message);
        return out;
    }

    /**
     * Writes UTF-8 JSON and releases the local exchange.
     */
    private void write(HttpExchange exchange, int status, JsonObject value) throws IOException {
        byte[] bytes = gson.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }
}
