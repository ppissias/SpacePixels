package eu.startales.spacepixels.report;

import io.github.ppissias.jtransient.engine.PipelineResult;
import eu.startales.spacepixels.config.VisualizationSettings;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
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

/** Checks maximum-stack review evidence, availability and cautious track-overlap presentation. */
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

    @Test
    public void candidateOnAReportedTrackIsMarkedAndLinkedToThatTrack() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.masterSlowMoverMinPixels = 10;
        config.masterSlowMoverSigmaMultiplier = 4.0;
        config.masterSlowMoverGrowSigmaMultiplier = 3.5;
        config.slowMoverMedianSupportMaxOverlapFraction = 1.0;
        SlowMoverAnalysis analysis = SlowMoverAnalyzer.analyze(
                MasterMapGenerator.createMaximumMasterStack(createFrames()), MasterMapGenerator.createMedianMasterStack(createFrames()), config);
        assertEquals(1, analysis.candidates.size());
        assertFalse(render(analysis, config).contains("track-link"));

        Track track = new Track();
        for (int frame = 0; frame < 3; frame++) {
            DetectedObject point = new DetectedObject(59 + frame, 48, 1000, 9);
            point.sourceFrameIndex = frame;
            track.points.add(point);
        }
        String html = render(analysis, config, List.of(track));
        assertTrue(html.contains("<a class='track-link-badge' href='#finding-t1'>Track overlap: T1</a>"));
        assertTrue(html.contains("<div class='track-link-note'><strong>Nearby reported track <a href='#finding-t1'>T1</a>.</strong>"));
        assertTrue(html.contains("T1 (moving object track) has 3 detections near this footprint"));
        assertTrue(html.contains("Common-frame centroid comparison unavailable; spatial overlap only."));
        assertFalse(html.contains("Already tracked"));
    }

    /** Timed supported positions retain original frame numbering in the exported CSV and measured comparison. */
    @Test public void exportsTimedCentroidsAndCommonFrameDistancesWithoutClaimingIdentity() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.masterSlowMoverMinPixels = 10;
        config.masterSlowMoverSigmaMultiplier = 4;
        config.masterSlowMoverGrowSigmaMultiplier = 3.5;
        config.slowMoverMedianSupportMaxOverlapFraction = 1;
        config.slowMoverMinFrameSupport = 0;
        config.slowMoverMaxStationaryLikelihood = 100;
        List<ImageFrame> frames = new ArrayList<>();
        var originals = createFrames();
        for (int i = 0; i < originals.size(); i++) frames.add(new ImageFrame(100 + 40 * i,
                "timed-" + i, originals.get(i).pixelData, i * 60_000L, 1000));
        var analysis = SlowMoverAnalyzer.analyze(MasterMapGenerator.createMaximumMasterStack(frames),
                MasterMapGenerator.createMedianMasterStack(frames), frames, config);
        assertEquals(1, analysis.candidates.size());
        var evidence = analysis.candidates.get(0).diagnostics.motionEvidence;
        assertTrue(evidence.fitAvailable);
        Track track = new Track();
        for (var p : evidence.positions) {
            var point = new DetectedObject(p.x(), p.y(), 1000, 9);
            point.sourceFrameIndex = p.sourceFrameIndex();
            point.timestamp = p.timestamp();
            track.points.add(point);
        }
        String html = render(analysis, config, List.of(track));
        assertTrue(html.contains("Timed fit over 8.00 of 8.00 min"));
        assertTrue(html.contains("9 common supported frames: RMS centroid separation 0.00 px"));
        assertTrue(html.contains("Overlap alone does not establish"));
        assertTrue(html.contains("The fit and correlation do not confirm a moving source."));
        assertFalse(html.contains("Already tracked"));
        try (var files = java.nio.file.Files.walk(temporaryFolder.getRoot().toPath())) {
            var csv = files.filter(p -> p.getFileName().toString().endsWith("_positions.csv")).findFirst().orElseThrow();
            var lines = java.nio.file.Files.readAllLines(csv);
            assertEquals(10, lines.size());
            assertEquals("original_frame_number,retained_frame_number,timestamp_ms,x_px,y_px,aperture_snr", lines.get(0));
            assertTrue(lines.get(1).startsWith("101,1,0,"));
            assertTrue(lines.get(9).startsWith("421,9,480000,"));
        }
    }

    private String render(SlowMoverAnalysis analysis, DetectionConfig config) throws Exception {
        return render(analysis, config, Collections.emptyList());
    }

    private String render(SlowMoverAnalysis analysis, DetectionConfig config, List<Track> movingTargets) throws Exception {
        DetectionReportContext context = ReportContextFixtures.create(VisualizationSettings.defaults().withAutoStretchBlackSigma(3.0).withAutoStretchWhiteSigma(10.0).withGifBlinkSpeedMs(100)
                        .withTrackCropPadding(10).withTrackObjectCentricCropSize(100).withTargetCircleRadius(12).withTargetCircleStrokeWidth(1.0f),
                temporaryFolder.newFolder(),
                Collections.emptyList(),
                null,
                config,
                PipelineResult.builder().tracks(movingTargets).slowMoverAnalysis(new io.github.ppissias.jtransient.core.SlowMoverAnalysis(analysis.maximumStackData, analysis.medianMask, analysis.candidates, analysis.telemetry)).build());
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
