package eu.startales.spacepixels.report;

import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.fits.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.telemetry.FaintStreakSearchTelemetry;
import org.junit.Test;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** How the report states the faint-streak search's contribution: per finding, per card and in Diagnostics. */
public class FaintStreakReportTest {

    /** A finding counts its faint-search detections; a finding without any carries no count (absent from the JSON). */
    @Test public void findingsCountTheirFaintSearchDetections() {
        Track mixed = track(point(0, 10, false), point(1, 20, true), point(2, 30, true));
        Track normal = track(point(0, 100, false), point(1, 110, false));
        Track faintSingle = track(point(3, 200, true));
        var context = ReportContextFixtures.create(VisualizationSettings.defaults(), new File("."), List.of(),
                new FitsFileInformation[0], new DetectionConfig(),
                PipelineResult.builder().tracks(List.of(mixed, normal, faintSingle)).build());
        var findings = context.data.findings;
        assertEquals(2, findings.get(0).faintSearchDetections().intValue());
        assertNull(findings.get(1).faintSearchDetections());
        assertEquals(1, findings.get(2).faintSearchDetections().intValue());
        String json = new com.google.gson.Gson().toJson(findings.get(1));
        assertTrue(!json.contains("faintSearchDetections"));
    }

    /** Cards say "found by" only when every detection is the search's, and claim nothing about extension. */
    @Test public void cardBadgesStateOnlyWhatTheDetectionsShow() {
        assertEquals("", TargetVisualizationSectionWriter.faintSearchBadge(track(point(0, 1, false))));
        assertTrue(TargetVisualizationSectionWriter.faintSearchBadge(track(point(0, 1, true), point(1, 2, true)))
                .contains("Found by the faint-streak search"));
        assertTrue(TargetVisualizationSectionWriter.faintSearchBadge(track(point(0, 1, false), point(1, 2, true)))
                .contains("Includes 1 detection from the faint-streak search"));
    }

    /** Diagnostics lists the search's width and counts, and nothing when it did not run. */
    @Test public void diagnosticsListTheSearchOnlyWhenItRan() {
        StringWriter off = new StringWriter();
        PipelineDiagnosticsSectionWriter.writeFaintStreakSearch(new PrintWriter(off), null);
        assertEquals("", off.toString());
        FaintStreakSearchTelemetry telemetry = new FaintStreakSearchTelemetry();
        telemetry.smoothingSigma = 1.1;
        telemetry.widthFromStars = true;
        telemetry.widthFrames = 28;
        telemetry.extractedStreaks = 263;
        telemetry.tracksWithFaintDetections = 15;
        telemetry.faintOnlyTracks = 8;
        StringWriter on = new StringWriter();
        try (PrintWriter writer = new PrintWriter(on)) {
            PipelineDiagnosticsSectionWriter.writeFaintStreakSearch(writer, telemetry);
        }
        assertTrue(on.toString().contains("sigma 1.10 px (from the star size of 28 frames)"));
        assertTrue(on.toString().contains("<td>263</td>"));
        assertTrue(on.toString().contains("<td>15 (8)</td>"));
    }

    private static DetectedObject point(int frame, double x, boolean faint) {
        DetectedObject point = new DetectedObject(x, 50, 100, 30);
        point.sourceFrameIndex = frame;
        point.sourceFilename = "f" + frame + ".fit";
        point.isStreak = true;
        point.fromFaintStreakSearch = faint;
        return point;
    }

    private static Track track(DetectedObject... points) {
        Track track = new Track();
        track.isStreakTrack = true;
        for (DetectedObject point : points) track.addPoint(point);
        return track;
    }
}
