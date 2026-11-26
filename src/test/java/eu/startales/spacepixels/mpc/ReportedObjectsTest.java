package eu.startales.spacepixels.mpc;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Reads a report's moving objects, their statuses and positions, from its report data. */
public class ReportedObjectsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final String DATA = """
            {"inputFrames":[{"fileName":"a.fit","width":100,"height":80,"timestampMillis":1000,"exposureMillis":120000},
                            {"fileName":"b.fit","width":100,"height":80,"timestampMillis":200000,"exposureMillis":120000}],
             "findings":[{"id":"T1","category":"Moving target","positions":[{"frameNumber":1,"x":10.5,"y":20.25,"fwhm":3.1,"snr":25.0},
                                                                             {"frameNumber":2,"x":11.5,"y":21.25}]},
                         {"id":"T2","category":"Moving target","positions":[{"frameNumber":2,"x":50,"y":60}]},
                         {"id":"ST1","category":"Streak track","positions":[{"frameNumber":1,"x":1,"y":1}]}],
             "faintMovers":[{"id":"FM1","x":31.5,"y":40.5,"referenceTimeMillis":61000,"vxPixelsPerMinute":0.4,"vyPixelsPerMinute":-0.2,
                           "cutout":"faint_movers/fm1_stack.png","review":{"data":"faint_movers/fm1_view.js"},
                           "fittedPositions":[{"frameNumber":1,"x":30.0,"y":40.0,"midpointMillis":61000}]}],
             "faintMoverSearch":{"fwhmPixels":3.2},
             "knownSolarSystem":{
               "bodies":[{"name":"649341 (2011 DK30)","comet":false,"minimumMagnitude":18.4,"maximumMagnitude":18.5,
                          "informationUrl":"https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr=649341"}],
               "identifications":[
                 {"id":"T1","kind":"track","status":"known","names":["649341 (2011 DK30)"],"note":"agrees",
                  "speedArcsecPerHour":31.7,"positionAngleDegrees":251.0},
                 {"id":"FM1","kind":"faint mover","status":"no-known-match","names":[],"note":"none"}]}}
            """;

    /** Tracks with their measured positions, faint movers with their fitted ones, each with the report's status. */
    @Test
    public void readsTracksAndFaintMoversWithTheirStatus() throws IOException {
        Files.writeString(folder.getRoot().toPath().resolve("report-data.json"), DATA);
        var report = ReportedObjects.load(folder.getRoot());
        assertEquals(List.of("a.fit", "b.fit"), report.frames().stream().map(ReportedObjects.Frame::fileName).toList());
        assertEquals(1000, report.sessionStartMillis());
        assertEquals("streak tracks are not asteroids to report", List.of("T1", "T2", "FM1"),
                report.objects().stream().map(ReportedObjects.ReportedObject::id).toList());
        var t1 = report.objects().get(0);
        assertEquals(ReportedObjects.KNOWN, t1.status());
        assertEquals(List.of("649341 (2011 DK30)"), t1.names());
        assertTrue(t1.measured());
        assertEquals(3.1, t1.positions().get(0).fwhm(), 0);
        assertEquals(25.0, t1.positions().get(0).snr(), 0);
        assertNull("not kept by older reports", t1.positions().get(1).fwhm());
        assertEquals(31.7, t1.speedArcsecPerHour(), 0);
        assertEquals("no identification row: not compared", ReportedObjects.NOT_COMPARED, report.objects().get(1).status());
        var fm1 = report.objects().get(2);
        assertEquals(ReportedObjects.NO_KNOWN_MATCH, fm1.status());
        assertEquals(ReportedObjects.FAINT_MOVER, fm1.kind());
        assertEquals(30.0, fm1.positions().get(0).x(), 0);
        assertEquals(new ReportedObjects.Motion(31.5, 40.5, 61000, 0.4, -0.2), fm1.motion());
        assertNull("tracks have measured positions, not a fitted motion", t1.motion());
        assertEquals(3.2, report.faintMoverFwhm(), 0);
        var body = report.knownBodies().get("649341 (2011 DK30)");
        assertEquals("https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr=649341", body.informationUrl());
        assertEquals(18.4, body.minimumMagnitude(), 0);
        assertEquals("faint_movers/fm1_stack.png", report.faintMoverAssets().get("FM1").cutout());
        assertEquals("faint_movers/fm1_view.js", report.faintMoverAssets().get("FM1").reviewData());
    }

    /** A folder without report data is a clear error. */
    @Test
    public void aFolderWithoutReportDataIsAnError() {
        try {
            ReportedObjects.load(folder.getRoot());
            fail("no report data");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("report data cannot be read"));
        }
    }
}
