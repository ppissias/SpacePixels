package eu.startales.spacepixels.util;

import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverAnalysis;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import io.github.ppissias.jtransient.telemetry.TrackerTelemetry;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The few-frame rule of {@link DetectionPipelineSupport}: with fewer than three frames left after quality control, the
 * late-phase results are dropped and the stacks kept. The rule itself is evaluated separately (SP-04).
 */
public class DetectionPipelineSupportTest {

    @Test
    public void suppressesLatePhaseOutputsWhenFewerThanThreeFramesSurviveQualityControl() {
        PipelineTelemetry telemetry = new PipelineTelemetry();
        telemetry.totalFramesKept = 2;
        telemetry.totalTracksFound = 4;
        telemetry.totalAnomaliesFound = 2;
        telemetry.trackerTelemetry = new TrackerTelemetry();
        telemetry.slowMoverTelemetry = new PipelineTelemetry.SlowMoverTelemetry();

        short[][] masterStack = new short[][]{{1}};
        boolean[][] vetoMask = new boolean[][]{{true}};
        short[][] maximumStack = new short[][]{{2}};

        PipelineResult original = PipelineResult.builder()
                .tracks(Collections.singletonList(null))
                .telemetry(telemetry)
                .masterStackData(masterStack)
                .masterStars(Collections.emptyList())
                .slowMoverAnalysis(SlowMoverAnalysis.empty())
                .slowMoverStackData(new short[][]{{3}})
                .slowMoverMedianVetoMask(new boolean[][]{{false}})
                .slowMoverCandidates(Collections.emptyList())
                .anomalies(Collections.singletonList(null))
                .allTransients(Collections.emptyList())
                .unclassifiedTransients(Collections.emptyList())
                .residualTransientAnalysis(ResidualTransientAnalysis.empty())
                .masterVetoMask(vetoMask)
                .driftPoints(Collections.emptyList())
                .maximumStackData(maximumStack)
                .build();

        PipelineResult sanitized = DetectionPipelineSupport.suppressLatePhaseOutputsWhenTooFewFramesRemain(original);

        assertTrue(sanitized.tracks.isEmpty());
        assertTrue(sanitized.anomalies.isEmpty());
        assertTrue(sanitized.slowMoverCandidates.isEmpty());
        assertTrue(sanitized.slowMoverAnalysis.candidates.isEmpty());
        assertNull(sanitized.slowMoverStackData);
        assertNull(sanitized.slowMoverMedianVetoMask);
        assertTrue(sanitized.residualTransientAnalysis.localRescueCandidates.isEmpty());
        assertTrue(sanitized.residualTransientAnalysis.localActivityClusters.isEmpty());
        assertNull(sanitized.telemetry.trackerTelemetry);
        assertNull(sanitized.telemetry.slowMoverTelemetry);
        assertEquals(0, sanitized.telemetry.totalTracksFound);
        assertEquals(0, sanitized.telemetry.totalAnomaliesFound);
        assertSame(masterStack, sanitized.masterStackData);
        assertSame(vetoMask, sanitized.masterVetoMask);
        assertSame(maximumStack, sanitized.maximumStackData);
    }

    @Test
    public void keepsLatePhaseOutputsWhenEnoughFramesSurviveQualityControl() {
        PipelineTelemetry telemetry = new PipelineTelemetry();
        telemetry.totalFramesKept = 3;

        PipelineResult original = PipelineResult.builder()
                .tracks(Collections.emptyList())
                .telemetry(telemetry)
                .masterStars(Collections.emptyList())
                .slowMoverAnalysis(SlowMoverAnalysis.empty())
                .slowMoverCandidates(Collections.emptyList())
                .anomalies(Collections.emptyList())
                .allTransients(Collections.emptyList())
                .unclassifiedTransients(Collections.emptyList())
                .residualTransientAnalysis(ResidualTransientAnalysis.empty())
                .driftPoints(Collections.emptyList())
                .build();

        assertSame(original, DetectionPipelineSupport.suppressLatePhaseOutputsWhenTooFewFramesRemain(original));
    }
}
