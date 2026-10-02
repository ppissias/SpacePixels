package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.MasterMapGenerator;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.core.SlowMoverAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverAnalyzer;
import io.github.ppissias.jtransient.engine.ImageFrame;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeepStackReportSectionWriterTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void candidateCardDistinguishesMeasuredPercentagesFromUnavailableFrameEvidence() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.masterSlowMoverMinPixels = 10;
        config.masterSlowMoverSigmaMultiplier = 4.0;
        config.masterSlowMoverGrowSigmaMultiplier = 3.5;
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        config.edgeMarginPixels = 4;
        config.voidProximityRadius = 4;
        // Disable the frame-evidence gates so the stationary synthetic source survives and its diagnostics render.
        config.slowMoverMinFrameSupport = 0.0;
        config.slowMoverMaxStationaryLikelihood = 100.0;

        List<ImageFrame> frames = createFrames();
        short[][] maximumStack = MasterMapGenerator.createMaximumMasterStack(frames);
        short[][] medianStack = MasterMapGenerator.createMedianMasterStack(frames);

        SlowMoverAnalysis measured = SlowMoverAnalyzer.analyze(maximumStack, medianStack, frames, config);
        assertEquals(1, measured.candidates.size());
        assertTrue(measured.candidates.get(0).diagnostics.frameSupportAvailable);
        assertTrue(measured.candidates.get(0).diagnostics.stationaryLikelihoodAvailable);
        assertEquals(100.0, measured.candidates.get(0).diagnostics.frameSupportPercentage, 0.0);
        assertEquals(100.0, measured.candidates.get(0).diagnostics.stationaryLikelihoodPercentage, 0.0);

        String measuredHtml = render(measured, config);
        assertTrue(measuredHtml.contains("Frame Support Measured"));
        assertTrue(measuredHtml.contains("Frame Support Unavailable"));
        assertTrue(measuredHtml.contains("Rejected Low Frame Support"));
        assertTrue(measuredHtml.contains("Rejected High Stationary Likelihood"));
        assertTrue(measuredHtml.contains("Min Frame Support"));
        assertTrue(measuredHtml.contains("Max Stationary Likelihood"));
        String measuredCard = candidateCardText(measuredHtml);
        assertTrue(measuredCard.contains("Frame Support: 100.0%"));
        assertTrue(measuredCard.contains("Stationary Likelihood (heuristic): 100.0%"));
        assertFalse(measuredCard.contains("10000.0%"));

        SlowMoverAnalysis unavailable = SlowMoverAnalyzer.analyze(maximumStack, medianStack, config);
        assertEquals(1, unavailable.candidates.size());
        assertFalse(unavailable.candidates.get(0).diagnostics.frameSupportAvailable);
        assertFalse(unavailable.candidates.get(0).diagnostics.stationaryLikelihoodAvailable);

        String unavailableCard = candidateCardText(render(unavailable, config));
        assertTrue(unavailableCard.contains("Frame Support: Unavailable"));
        assertTrue(unavailableCard.contains("Stationary Likelihood (heuristic): Unavailable"));
        assertFalse(unavailableCard.contains("Frame Support: 0.0%"));
        assertFalse(unavailableCard.contains("Stationary Likelihood (heuristic): 0.0%"));

        SlowMoverAnalysis oneFrame = SlowMoverAnalyzer.analyze(
                maximumStack, medianStack, Collections.singletonList(frames.get(0)), config);
        assertEquals(1, oneFrame.candidates.size());
        assertFalse(oneFrame.candidates.get(0).diagnostics.frameSupportAvailable);
        assertEquals(100.0, oneFrame.candidates.get(0).diagnostics.frameSupportPercentage, 0.0);
        String oneFrameCard = candidateCardText(render(oneFrame, config));
        assertTrue(oneFrameCard.contains("Frame Support: Unavailable"));
        assertFalse(oneFrameCard.contains("Frame Support: 100.0%"));
    }

    private String render(SlowMoverAnalysis analysis, DetectionConfig config) throws Exception {
        DetectionReportContext context = new DetectionReportContext(
                new ExportVisualizationSettings(3.0, 10.0, 100, 10, 100, false, 12, 1.0f),
                temporaryFolder.newFolder(),
                Collections.emptyList(),
                null,
                config,
                null,
                null,
                null,
                null,
                analysis.maximumStackData,
                analysis.medianMask,
                analysis.candidates,
                Collections.singletonList(analysis.candidates.get(0).object),
                analysis.telemetry,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        StringWriter output = new StringWriter();
        DeepStackReportSectionWriter.writeSection(new PrintWriter(output), context);
        return output.toString();
    }

    private static String candidateCardText(String html) {
        int cardStart = html.indexOf("<div class='detection-card'");
        assertTrue(cardStart >= 0);
        return html.substring(cardStart).replaceAll("<[^>]+>", "").replaceAll("\\s+", " ");
    }

    private static List<ImageFrame> createFrames() {
        List<ImageFrame> frames = new ArrayList<>();
        for (int frameIndex = 0; frameIndex < 9; frameIndex++) {
            short[][] pixels = new short[96][96];
            for (int row = 0; row < pixels.length; row++) {
                for (int column = 0; column < pixels[row].length; column++) {
                    int background = 1000 + ((column * 17 + row * 13) % 7) - 3;
                    double offsetX = (column - 60.0) / 3.0;
                    double offsetY = (row - 48.0) / 1.3;
                    int signal = (int) Math.round(900.0 * Math.exp(-0.5 * (offsetX * offsetX + offsetY * offsetY)));
                    pixels[row][column] = PixelEncoding.fromShiftedPositiveInt(background + signal);
                }
            }
            frames.add(new ImageFrame(frameIndex, "frame_" + frameIndex + ".fit", pixels, -1L, -1L));
        }
        return frames;
    }
}
