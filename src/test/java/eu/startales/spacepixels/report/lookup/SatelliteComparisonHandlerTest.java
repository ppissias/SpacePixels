package eu.startales.spacepixels.report.lookup;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Real loopback HTTP checks for offline cache reuse, snapshot validation and cancellation admission.
 */
public class SatelliteComparisonHandlerTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    /**
     * A Matching Sidecar Returns The Saved Evidence Without Provider Work.
     */
    @Test
    public void aMatchingSidecarReturnsTheSavedEvidenceWithoutProviderWork() throws Exception {
        var observation = SatelliteTrackComparisonTest.observation(List.of(
                SatelliteTrackComparisonTest.exposure(1, 0, 100, .05), SatelliteTrackComparisonTest.exposure(2, 200, 300, .25)));
        Path report = temporary.newFile("detection_report.html").toPath();
        Files.writeString(report, "<html><body>Saved report</body></html>");
        var cached = new JsonObject();
        cached.addProperty("ok", true);
        cached.addProperty("provider", "satchecker");
        cached.addProperty("sourceUrl", "satchecker-track:" + observation.digest());
        cached.addProperty("observationDigest", observation.digest());
        var normalized = new JsonObject();
        normalized.addProperty("comparisonVersion", 1);
        normalized.addProperty("trackComparison", true);
        cached.add("normalized", normalized);
        Path sidecar = report.resolveSibling("saved_comparison.json");
        ReportLookupCacheStore.writeSidecarResponse(sidecar, cached);
        var body = new JsonObject();
        body.addProperty("requestId", "test-comparison-123456");
        body.add("observation", new Gson().toJsonTree(observation));
        body.addProperty("report", report.toUri().toString());
        body.addProperty("sidecar", sidecar.getFileName().toString());
        var result = request("POST", body);
        assertEquals("sidecar", result.get("cacheSource").getAsString());
        assertTrue(result.get("persisted").getAsBoolean());
        assertEquals(observation.digest(), result.get("observationDigest").getAsString());
        assertTrue(Files.readString(report).contains("spacepixels-persisted-live-results"));
    }

    /**
     * Invalid Snapshots Fail Before Provider Work And Cancellation Of An Absent Run Is Harmless.
     */
    @Test
    public void invalidSnapshotsFailBeforeProviderWorkAndCancellationOfAnAbsentRunIsHarmless() throws Exception {
        var body = new JsonObject();
        body.addProperty("requestId", "test-comparison-123456");
        body.add("observation", new JsonObject());
        assertFalse(request("POST", body).get("ok").getAsBoolean());
        assertTrue(request("DELETE", body).get("ok").getAsBoolean());
    }

    /**
     * A Failed Comparison Is Never Persisted And A Legacy Zero-Search Sidecar Is Ignored.
     */
    @Test
    public void failedComparisonIsNotPersistedAndLegacyZeroSearchSidecarIsIgnored() throws Exception {
        var observation = SatelliteTrackComparisonTest.observation(List.of(
                SatelliteTrackComparisonTest.exposure(1, 0, 100, .05), SatelliteTrackComparisonTest.exposure(2, 200, 300, .25)));
        Path report = temporary.newFile("detection_report.html").toPath();
        Files.writeString(report, "<html><body>Saved report</body></html>");
        // First: no sidecar exists; a total upstream failure must not write a sidecar or embed into the report.
        Path freshSidecar = report.resolveSibling("fresh_failed.json");
        var failingHandler = new SatelliteComparisonHandler(control -> new SatCheckerTrackLookup(
                url -> JsonParser.parseString("{\"ok\":false,\"message\":\"SatChecker returned HTTP 502.\"}").getAsJsonObject(), control));
        var freshBody = new JsonObject();
        freshBody.addProperty("requestId", "test-comparison-failed1");
        freshBody.add("observation", new Gson().toJsonTree(observation));
        freshBody.addProperty("report", report.toUri().toString());
        freshBody.addProperty("sidecar", freshSidecar.getFileName().toString());
        var failedResult = request(failingHandler, "POST", freshBody);
        assertFalse(failedResult.get("ok").getAsBoolean());
        assertFalse(failedResult.get("persisted").getAsBoolean());
        assertTrue(failedResult.get("message").getAsString().contains("HTTP 502"));
        assertFalse(Files.exists(freshSidecar));
        assertFalse(Files.readString(report).contains("spacepixels-persisted-live-results"));

        // Second: a legacy sidecar saved with searchedExposures == 0 must not be served as a cached hit.
        var legacyCached = new JsonObject();
        legacyCached.addProperty("ok", true);
        legacyCached.addProperty("provider", "satchecker");
        legacyCached.addProperty("sourceUrl", "satchecker-track:" + observation.digest());
        legacyCached.addProperty("observationDigest", observation.digest());
        var legacyNormalized = new JsonObject();
        legacyNormalized.addProperty("comparisonVersion", 1);
        legacyNormalized.addProperty("trackComparison", true);
        legacyNormalized.addProperty("searchedExposures", 0);
        legacyCached.add("normalized", legacyNormalized);
        Path legacySidecar = report.resolveSibling("legacy_zero.json");
        ReportLookupCacheStore.writeSidecarResponse(legacySidecar, legacyCached);
        var legacyBody = new JsonObject();
        legacyBody.addProperty("requestId", "test-comparison-failed2");
        legacyBody.add("observation", new Gson().toJsonTree(observation));
        legacyBody.addProperty("report", report.toUri().toString());
        legacyBody.addProperty("sidecar", legacySidecar.getFileName().toString());
        var retryResult = request(failingHandler, "POST", legacyBody);
        assertFalse(retryResult.get("ok").getAsBoolean());
        assertEquals("live", retryResult.get("cacheSource").getAsString());
        assertFalse(Files.readString(report).contains("spacepixels-persisted-live-results"));
    }

    /**
     * Drives the production handler at an ephemeral loopback port with temporary report files only.
     */
    private JsonObject request(String method, JsonObject body) throws Exception {
        return request(new SatelliteComparisonHandler(), method, body);
    }

    /**
     * Drives the given handler instance at an ephemeral loopback port.
     */
    private JsonObject request(SatelliteComparisonHandler handler, String method, JsonObject body) throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/compare", handler);
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            var uri = URI.create("http://localhost:" + server.getAddress().getPort() + "/compare");
            var response = client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } finally {
            handler.cancelAll();
            server.stop(0);
        }
    }
}
