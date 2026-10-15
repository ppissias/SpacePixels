package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import io.github.ppissias.jtransient.telemetry.TrackerTelemetry;
import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StageTimingsReportTest {
    @Test
    public void showsElapsedTimesAndLabelsTheNestedFragmentCost() {
        PipelineTelemetry telemetry = new PipelineTelemetry();
        telemetry.processingTimeMs = 2000;
        telemetry.stageTimeMs.put(PipelineTelemetry.Stage.SOURCE_EXTRACTION, 500L);
        telemetry.stageTimeMs.put(PipelineTelemetry.Stage.TRACKING, 1000L);
        telemetry.trackerTelemetry = new TrackerTelemetry();
        telemetry.trackerTelemetry.faintStreakFragmentTimeMs = 250;
        String html = render(telemetry);
        assertTrue(html.contains("Detection Stage Timings"));
        assertTrue(html.contains("0.500 s</td><td>25.0%"));
        assertTrue(html.contains("1.000 s</td><td>50.0%"));
        assertTrue(html.contains("Faint streak fragment assembly (included in tracking)"));
        assertTrue(html.contains("0.250 s</td><td>12.5%"));
        assertFalse(html.contains("Variable-star photometry"));
    }

    @Test
    public void omitsUnavailableSubstepsAndHandlesZeroOrMissingTiming() {
        PipelineTelemetry telemetry = new PipelineTelemetry();
        assertTrue(render(telemetry).isEmpty());
        telemetry.stageTimeMs.put(PipelineTelemetry.Stage.TRACKING, 0L);
        telemetry.trackerTelemetry = new TrackerTelemetry();
        String html = render(telemetry);
        assertFalse(html.contains("Faint streak fragment assembly"));
        assertFalse(html.contains("NaN"));
        assertTrue(html.contains("0.000 s</td><td>&ndash;"));
    }

    private static String render(PipelineTelemetry telemetry) {
        StringWriter output = new StringWriter();
        try (PrintWriter writer = new PrintWriter(output)) {
            PipelineDiagnosticsSectionWriter.writeStageTimings(writer, telemetry);
        }
        return output.toString();
    }
}
