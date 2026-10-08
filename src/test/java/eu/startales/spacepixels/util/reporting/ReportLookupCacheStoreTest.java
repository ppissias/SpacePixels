package eu.startales.spacepixels.util.reporting;

import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ReportLookupCacheStoreTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void concurrentLookupsKeepEveryEmbeddedResultAndTheOriginalHtml() throws Exception {
        Path directory = temporaryFolder.newFolder().toPath();
        Path report = directory.resolve("detection_report.html");
        String prefix = "<html><body>" + "report content ".repeat(10000);
        Files.writeString(report, prefix + "</body></html>");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int entryIndex = 0; entryIndex < 24; entryIndex++) {
                final int identifier = entryIndex;
                futures.add(executor.submit(() -> {
                    start.await();
                    String filename = "lookup-" + identifier + ".json";
                    JsonObject response = response(identifier);
                    ReportLookupCacheStore.writeSidecarResponse(directory.resolve(filename), response);
                    Path reportAlias = identifier % 2 == 0 ? report : directory.resolve(".").resolve(report.getFileName());
                    ReportLookupCacheStore.persistResponseInReportCache(reportAlias, filename, response);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        JsonObject entries = ReportLookupCacheStore.readPersistedLookupCache(report).getAsJsonObject("entries");
        assertEquals(24, entries.size());
        for (int entryIndex = 0; entryIndex < 24; entryIndex++) {
            String filename = "lookup-" + entryIndex + ".json";
            assertEquals(entryIndex, entries.getAsJsonObject(filename).get("identifier").getAsInt());
            assertEquals(entryIndex, ReportLookupCacheStore.readSidecarResponse(directory.resolve(filename))
                    .get("identifier").getAsInt());
        }
        String html = Files.readString(report);
        assertTrue(html.startsWith(prefix));
        assertTrue(html.endsWith("</body></html>"));
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertEquals(25, files.count());
        }
    }

    @Test
    public void concurrentSidecarReplacementsNeverExposePartialJson() throws Exception {
        Path sidecar = temporaryFolder.newFolder().toPath().resolve("lookup.json");
        ReportLookupCacheStore.writeSidecarResponse(sidecar, response(0));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int writerIndex = 1; writerIndex <= 3; writerIndex++) {
                final int identifier = writerIndex;
                futures.add(executor.submit(() -> {
                    for (int iteration = 0; iteration < 30; iteration++) {
                        ReportLookupCacheStore.writeSidecarResponse(sidecar, response(identifier));
                    }
                }));
            }
            futures.add(executor.submit(() -> {
                for (int iteration = 0; iteration < 100; iteration++) {
                    JsonObject cached = ReportLookupCacheStore.readSidecarResponse(sidecar);
                    assertNotNull(cached);
                    assertTrue(cached.get("ok").getAsBoolean());
                    assertEquals("payload".repeat(2000), cached.get("payload").getAsString());
                }
            }));
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static JsonObject response(int identifier) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("provider", "vsx");
        response.addProperty("sourceUrl", "https://www.aavso.org/vsx/?id=" + identifier);
        response.addProperty("identifier", identifier);
        response.addProperty("payload", "payload".repeat(2000));
        return response;
    }
}
