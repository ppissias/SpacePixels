package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.engine.PipelineResult;
import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ReportInclusion;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;
import java.io.File;
import java.util.*;
import static org.junit.Assert.*;

/** Verifies complete report-model sampling and diagnostic labels. */
public class ReportDataTest {
    /** Builds a complete context with the supplied quality decisions. */
    private DetectionReportContext context(PipelineTelemetry telemetry) {
        return ReportContextFixtures.create(VisualizationSettings.defaults().withAutoStretchBlackSigma(3).withAutoStretchWhiteSigma(10).withGifBlinkSpeedMs(100)
                .withTrackCropPadding(10).withTrackObjectCentricCropSize(100).withTargetCircleRadius(12).withTargetCircleStrokeWidth(1),
                new File("."),
                Collections.nCopies(20, new short[20][30]),
                new FitsFileInformation[0],
                new DetectionConfig(),
                PipelineResult.builder().telemetry(telemetry).build());
    }

    @Test public void samplesAndNeighboursShareQualityRejection() {
        var telemetry = new PipelineTelemetry();
        for (int index : List.of(1, 6, 9, 18)) {
            var frame = new PipelineTelemetry.FrameQualityStat(); frame.frameIndex = index; frame.rejected = true;
            telemetry.frameQualityStats.add(frame);
        }
        var data = context(telemetry).data;
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
