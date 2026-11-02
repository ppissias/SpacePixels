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
     * Drives the production handler at an ephemeral loopback port with temporary report files only.
     */
    private JsonObject request(String method, JsonObject body) throws Exception {
        var handler = new SatelliteComparisonHandler();
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
