package eu.startales.spacepixels.detection;

import com.google.gson.*;
import eu.startales.spacepixels.fits.FitsFileInformation;
import io.github.ppissias.jtransient.execution.CancellationScope;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.Assert.*;

/** Checks report restoration boundaries, legacy reference semantics and import-worker cancellation. */
public class SavedReportOverlaysTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    /** A broken newest report cannot break import or hide an older compatible completed report. */
    @Test public void newestMalformedOrIncompatibleReportFallsBack() throws Exception {
        var files = files();var older = report("detections_older", document(files, true));
        var newest = report("detections_newest", new JsonObject());
        assertTrue(newest.setLastModified(older.lastModified() + 10000));
        var loaded = SavedReportOverlays.latest(temporary.getRoot(),files);
        assertNotNull(loaded.overlays());
        assertEquals("T1",loaded.overlays().tracks().getTracks().getFirst().label);
        var changed = files.clone();changed[0] = files[0].withFitsHeader(Map.of("DATE-OBS","2026-11-07T00:00:00Z","EXPTIME","60"));
        assertNull(SavedReportOverlays.latest(temporary.getRoot(),changed).overlays());
        Files.writeString(newest.toPath().resolveSibling("report-data.json"),"{broken");
        assertNotNull(SavedReportOverlays.latest(temporary.getRoot(),files).overlays());
    }

    /** Old exports never acquire invented per-frame tracks: their representative positions remain reference squares. */
    @Test public void legacyPositionsAreExplicitReferences() throws Exception {
        var files = files();var data = document(files, false);
        var report = report("detections_legacy",data);
        var loaded = SavedReportOverlays.read(report,files);
        assertTrue(loaded.overlays().tracks().getTracks().isEmpty());
        var marker = loaded.overlays().findings().markers().getFirst();
        assertEquals("T1",marker.id());assertTrue(marker.positions().isEmpty());
        assertTrue(marker.description().contains("reference"));
        assertTrue(loaded.notice().contains("Older report"));
        assertEquals(Integer.valueOf(2),loaded.overlays().findings().frameNumber(files[1]));
        var namesake = new FitsFileInformation(new File(temporary.newFolder(),"a.fit").toString(),"a.fit",true,100,100);
        assertNull(loaded.overlays().findings().frameNumber(namesake));
    }

    /** Cancellation remains cancellation rather than becoming an empty restored overlay or an import warning. */
    @Test public void cancellationPropagates() throws Exception {
        var files = files();report("detections_complete",document(files,true));
        try (var scope = CancellationScope.open(() -> true)) {
            assertThrows(CancellationException.class, () -> SavedReportOverlays.latest(temporary.getRoot(),files));
        }
    }

    /** Creates two metadata-only inputs; image buffers and application settings are not needed by restoration. */
    private FitsFileInformation[] files() {
        return new FitsFileInformation[]{new FitsFileInformation(new File(temporary.getRoot(),"a.fit").toString(),"a.fit",true,100,100,
                Map.of("DATE-OBS","2026-11-06T00:00:00Z","EXPTIME","60")),
                new FitsFileInformation(new File(temporary.getRoot(),"b.fit").toString(),"b.fit",true,100,100,
                        Map.of("DATE-OBS","2026-11-06T00:02:00Z","EXPTIME","60"))};
    }

    /** Builds a small export-contract fixture, with measured geometry only when its provenance is present. */
    private JsonObject document(FitsFileInformation[] files, boolean provenance) {
        var data = JsonParser.parseString("{\"schemaVersion\":1,\"frameCount\":2,\"findings\":[{\"id\":\"T1\",\"x\":20,\"y\":30,\"frames\":[1,2]}]}").getAsJsonObject();
        if (provenance) {
            data.add("inputFrames",new Gson().toJsonTree(SavedReportOverlays.identities(files)));
            data.getAsJsonArray("findings").get(0).getAsJsonObject().add("positions",JsonParser.parseString("[{\"frameNumber\":1,\"x\":20,\"y\":30},{\"frameNumber\":2,\"x\":25,\"y\":32}]"));
        }
        return data;
    }

    /** Writes an isolated completed-report fixture; timestamps order candidates without relying on the clock. */
    private File report(String name, JsonObject data) throws Exception {
        var directory = temporary.newFolder(name);var report = new File(directory,"detection_report.html");
        Files.writeString(report.toPath(),"completed report");
        Files.writeString(new File(directory,"report-data.json").toPath(),new Gson().toJson(data));
        return report;
    }
}
