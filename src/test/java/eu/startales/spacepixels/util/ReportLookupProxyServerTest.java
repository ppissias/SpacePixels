package eu.startales.spacepixels.util;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ReportLookupProxyServerTest {

    @Test
    public void resolvesSidecarPathNextToDetectionReport() throws Exception {
        Path exportDir = Files.createTempDirectory("spacepixels-sidecar");
        try {
            Path reportPath = exportDir.resolve(ImageDisplayUtils.detectionReportName);
            Files.writeString(reportPath, "<html></html>", StandardCharsets.UTF_8);

            Path sidecarPath = ReportLookupProxyServer.resolveSidecarPath(
                    reportPath.toUri().toString(),
                    "jpl_track_02_exact.json");

            assertEquals(exportDir.resolve("jpl_track_02_exact.json"), sidecarPath);
        } finally {
            deleteRecursively(exportDir);
        }
    }

    @Test
    public void resolvesLegacySidecarPathWithoutJsonExtension() throws Exception {
        Path exportDir = Files.createTempDirectory("spacepixels-sidecar");
        try {
            Path reportPath = exportDir.resolve(ImageDisplayUtils.detectionReportName);
            Files.writeString(reportPath, "<html></html>", StandardCharsets.UTF_8);

            Path sidecarPath = ReportLookupProxyServer.resolveSidecarPath(
                    reportPath.toUri().toString(),
                    "satchecker_track_01");

            assertEquals(exportDir.resolve("satchecker_track_01.json"), sidecarPath);
        } finally {
            deleteRecursively(exportDir);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTraversalInSidecarFileNames() throws Exception {
        Path exportDir = Files.createTempDirectory("spacepixels-sidecar");
        try {
            Path reportPath = exportDir.resolve(ImageDisplayUtils.detectionReportName);
            Files.writeString(reportPath, "<html></html>", StandardCharsets.UTF_8);

            ReportLookupProxyServer.resolveSidecarPath(
                    reportPath.toUri().toString(),
                    "../outside.json");
        } finally {
            deleteRecursively(exportDir);
        }
    }

    @Test
    public void readsSavedSidecarJsonResponse() throws Exception {
        Path exportDir = Files.createTempDirectory("spacepixels-sidecar");
        try {
            Path sidecarPath = exportDir.resolve("satchecker_track_01.json");
            Files.writeString(
                    sidecarPath,
                    "{\"ok\":true,\"provider\":\"satchecker\",\"sourceUrl\":\"https://satchecker.cps.iau.org/fov/satellite-passes/?foo=bar\"}",
                    StandardCharsets.UTF_8);

            JsonObject response = ReportLookupProxyServer.readSidecarResponse(sidecarPath);

            assertNotNull(response);
            assertTrue(response.get("ok").getAsBoolean());
            assertEquals("satchecker", response.get("provider").getAsString());
        } finally {
            deleteRecursively(exportDir);
        }
    }

    @Test
    public void persistsLookupResponsesInsideReportHtmlForOfflineReload() throws Exception {
        Path exportDir = Files.createTempDirectory("spacepixels-sidecar");
        try {
            Path reportPath = exportDir.resolve(ImageDisplayUtils.detectionReportName);
            Files.writeString(reportPath, "<html><body><h1>Report</h1></body></html>", StandardCharsets.UTF_8);

            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.addProperty("provider", "jpl");
            response.addProperty("sourceUrl", "https://ssd-api.jpl.nasa.gov/sb_ident.api?foo=bar");
            response.addProperty("fetchedAtUtc", "2026-04-14T12:00:00Z");

            ReportLookupProxyServer.persistResponseInReportCache(
                    reportPath,
                    "jpl_track_02_exact.json",
                    response);

            String reportHtml = Files.readString(reportPath, StandardCharsets.UTF_8);
            JsonObject persistedCache = ReportLookupProxyServer.readPersistedLookupCache(reportPath);
            JsonObject persistedEntries = persistedCache.getAsJsonObject("entries");

            assertTrue(reportHtml.contains(ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_START_MARKER));
            assertTrue(reportHtml.contains(ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_END_MARKER));
            assertTrue(reportHtml.contains(ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_SCRIPT_ID));
            assertNotNull(persistedEntries.getAsJsonObject("jpl_track_02_exact.json"));
            assertEquals("jpl", persistedEntries.getAsJsonObject("jpl_track_02_exact.json").get("provider").getAsString());
        } finally {
            deleteRecursively(exportDir);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // Best-effort cleanup for temporary test files.
                    }
                });
    }
}
