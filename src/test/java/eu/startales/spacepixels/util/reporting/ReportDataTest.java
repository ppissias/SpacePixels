package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ReportInclusion;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;
import java.io.File;
import java.util.*;
import static org.junit.Assert.*;

public class ReportDataTest {
    private DetectionReportContext context() {
        return new DetectionReportContext(new ExportVisualizationSettings(3, 10, 100, 10, 100, false, 12, 1), new File("."),
                Collections.nCopies(20, new short[20][30]), new FitsFileInformation[0], new DetectionConfig(), null,
                null, null, null, null, null, List.of(), List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test public void samplesAndNeighboursShareQualityRejection() {
        var context = context();
        var telemetry = new PipelineTelemetry();
        for (int index : List.of(1, 6, 9, 18)) {
            var frame = new PipelineTelemetry.FrameQualityStat(); frame.frameIndex = index; frame.rejected = true;
            telemetry.frameQualityStats.add(frame);
        }
        var data = ReportData.create(context, telemetry, null, List.of(), ReportInclusion.all());
        var sample = data.sample(Set.of(6, 7, 10), 12);
        assertTrue(sample.containsAll(List.of(7, 10)));
        assertTrue(Collections.disjoint(sample, data.rejectedFrames));
        assertEquals(List.of(4, 5, 7), data.neighbours(5));
        assertEquals(List.of(5, 7), data.neighbours(6));
    }

    @Test public void catalogueLimitedDepthIsClearlyALowerBound() {
        FieldDepth depth = new FieldDepth(); depth.frames.add(new FieldDepth.Frame(0, "one.fit", 20.5, true));
        assertEquals("G ≥ 20.5", depth.medianLabel());
    }
}
