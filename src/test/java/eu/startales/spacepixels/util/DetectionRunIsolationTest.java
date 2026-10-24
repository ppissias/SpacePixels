package eu.startales.spacepixels.util;

import com.google.gson.JsonParser;
import eu.startales.spacepixels.api.DefaultSpacePixelsPipelineApi;
import eu.startales.spacepixels.api.SpacePixelsPipelineRequest;
import eu.startales.spacepixels.api.SpacePixelsPipelineResult;
import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.config.DetectionRunOptions;
import eu.startales.spacepixels.config.VariableStarReportOptions;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.config.VisualizationSettings;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Exercises real pipeline/export boundaries while runs overlap or callers edit their inputs mid-run. */
public class DetectionRunIsolationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** An admitted request retains rendering and profile metadata while another request runs with its own options. */
    @Test public void overlappingApiRunsKeepTheirOwnOptions() throws Exception {
        File directory = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "api", 5).toFile();
        var admitted = new java.util.concurrent.CountDownLatch(1);
        var continueFirst = new java.util.concurrent.CountDownLatch(1);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var firstVisual = VisualizationSettings.defaults().withAutoStretchBlackSigma(0.8).withAutoStretchWhiteSigma(4.2).withGifBlinkSpeedMs(170)
                    .withTrackCropPadding(21).withTrackObjectCentricCropSize(80).withTargetCircleRadius(9).withTargetCircleStrokeWidth(1.5f)
                    .withVariableStarReportOptions(new VariableStarReportOptions(13, VariableStarReportOptions.SortOrder.SIGNIFICANCE));
            var secondVisual = VisualizationSettings.defaults().withAutoStretchBlackSigma(1.2).withAutoStretchWhiteSigma(7).withGifBlinkSpeedMs(430)
                    .withTrackCropPadding(51).withTrackObjectCentricCropSize(110).withTargetCircleRadius(17).withTargetCircleStrokeWidth(2.5f);
            var firstOptions = new DetectionRunOptions("500", "37.5", "23.5", firstVisual, 17);
            var secondOptions = new DetectionRunOptions("J95", "-24", "-70", secondVisual, 29);
            AtomicBoolean changed = new AtomicBoolean();
            var firstRequest = SpacePixelsPipelineRequest.builder(directory)
                    .detectionConfig(SyntheticDatasetFactory.createSyntheticDetectionConfig()).runOptions(firstOptions)
                    .progressListener((percent, message) -> {
                        if (changed.compareAndSet(false, true)) {
                            admitted.countDown();
                            await(continueFirst);
                        }
                    }).build();
            var api = new DefaultSpacePixelsPipelineApi();
            var pendingFirst = worker.submit(() -> api.run(firstRequest));
            await(admitted);
            var second = api.run(SpacePixelsPipelineRequest.builder(directory)
                    .detectionConfig(SyntheticDatasetFactory.createSyntheticDetectionConfig()).runOptions(secondOptions).build());
            continueFirst.countDown();
            var first = pendingFirst.get(30, java.util.concurrent.TimeUnit.SECONDS);
            var firstProfile = JsonParser.parseString(Files.readString(new File(first.getExportDirectory(), "detection_config.json").toPath())).getAsJsonObject();
            var secondProfile = JsonParser.parseString(Files.readString(new File(second.getExportDirectory(), "detection_config.json").toPath())).getAsJsonObject();
            assertEquals(17, firstProfile.get("autoTuneMaxCandidateFrames").getAsInt());
            assertEquals(29, secondProfile.get("autoTuneMaxCandidateFrames").getAsInt());
            assertEquals(13, firstProfile.get("variableStarMaxCandidateCards").getAsInt());
            assertStretchedWith(first, 0.8, 4.2);
            assertStretchedWith(second, 1.2, 7);
            assertEquals(VariableStarReportOptions.SortOrder.SIGNIFICANCE, firstRequest.getVariableStarReportOptions().sortOrder());
            assertEquals(VisualizationSettings.defaults(), SpacePixelsPipelineRequest.builder(directory).build().getRunOptions().visualization());
        } finally {
            continueFirst.countDown();
            worker.shutdownNow();
        }
    }

    /** The run's master stack picture uses the display stretch of the run's own settings. */
    private static void assertStretchedWith(SpacePixelsPipelineResult run, double black, double white)
            throws java.io.IOException {
        var expected = DisplayImageRenderer.createDisplayImage(run.getPipelineResult().masterStackData, black, white);
        var actual = javax.imageio.ImageIO.read(new File(run.getExportDirectory(), "master_stack.png"));
        assertArrayEquals(expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0, expected.getWidth()),
                actual.getRGB(0, 0, actual.getWidth(), actual.getHeight(), null, 0, actual.getWidth()));
    }

    /** Waits for a deterministic overlap boundary, failing rather than leaving a hung test worker. */
    private static void await(java.util.concurrent.CountDownLatch gate) {
        try {
            assertTrue("Run overlap boundary timed out", gate.await(30, java.util.concurrent.TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    /** Detection execution owns its config and rendering even when the caller edits both from a loading callback. */
    @Test public void pipelineCarriesItsAdmissionContextIntoLaterExport() throws Exception {
        File directory = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "facade", 5).toFile();
        AppConfig application = new AppConfig();
        application.siteLat = "37";
        application.siteLong = "23";
        var config = SyntheticDatasetFactory.createSyntheticDetectionConfig();
        double admittedSigma = config.detectionSigmaMultiplier;
        var options = DetectionRunOptions.defaults().withAutoTuneMaxCandidateFrames(19);
        try (ImageProcessing processor = ImageProcessing.openProcessor(directory, ApplicationSettings.inMemory(application))) {
            var detection = new StandardDetectionPipelineService();
            var execution = detection.runDetectionPipeline(config, processor.readFiles(), (percent, message) -> {
                config.detectionSigmaMultiplier = 20;
                processor.getAppConfig().siteLat = "-24";
            }, options, null);
            assertEquals(admittedSigma, execution.getEffectiveConfig().detectionSigmaMultiplier, 0);
            assertSame(options, execution.getReportContext().options());
            File report = detection.exportDetectionReport(execution, null);
            var profile = JsonParser.parseString(Files.readString(new File(report.getParentFile(), "detection_config.json").toPath())).getAsJsonObject();
            assertEquals(19, profile.get("autoTuneMaxCandidateFrames").getAsInt());
            assertEquals(admittedSigma, profile.get("detectionSigmaMultiplier").getAsDouble(), 0);
        }
    }
}
