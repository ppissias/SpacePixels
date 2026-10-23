package eu.startales.spacepixels.util;

import com.google.gson.JsonParser;
import eu.startales.spacepixels.api.DefaultSpacePixelsPipelineApi;
import eu.startales.spacepixels.api.SpacePixelsPipelineRequest;
import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.config.DetectionRunOptions;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import eu.startales.spacepixels.config.SpacePixelsVisualizationPreferences;
import eu.startales.spacepixels.config.VariableStarReportOptions;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.reporting.ExportVisualizationSettings;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Exercises real pipeline/export boundaries while progress callbacks change application defaults. */
public class DetectionRunIsolationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** An admitted request retains rendering and profile metadata while another request uses its own options. */
    @Test public void apiRunsUseExplicitOptionsWithoutChangingRuntimeDefaults() throws Exception {
        File directory = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "api", 5).toFile();
        var preferences = SpacePixelsVisualizationPreferences.captureCurrent();
        int originalLimit = SpacePixelsDetectionProfileIO.getActiveAutoTuneMaxCandidateFrames();
        var admitted = new java.util.concurrent.CountDownLatch(1);
        var continueFirst = new java.util.concurrent.CountDownLatch(1);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var firstVisual = new ExportVisualizationSettings(0.8, 4.2, 170, 21, 80, false, 9, 1.5f,
                    new VariableStarReportOptions(13, VariableStarReportOptions.SortOrder.SIGNIFICANCE));
            var secondVisual = new ExportVisualizationSettings(1.2, 7, 430, 51, 110, false, 17, 2.5f);
            var firstOptions = new DetectionRunOptions("500", "37.5", "23.5", firstVisual, 17);
            var secondOptions = new DetectionRunOptions("J95", "-24", "-70", secondVisual, 29);
            AtomicBoolean changed = new AtomicBoolean();
            var firstRequest = SpacePixelsPipelineRequest.builder(directory)
                    .detectionConfig(SyntheticDatasetFactory.createSyntheticDetectionConfig()).runOptions(firstOptions)
                    .progressListener((percent, message) -> {
                        if (changed.compareAndSet(false, true)) {
                            DisplayImageRenderer.autoStretchBlackSigma = 3;
                            DisplayImageRenderer.autoStretchWhiteSigma = 12;
                            SpacePixelsDetectionProfileIO.setActiveAutoTuneMaxCandidateFrames(111);
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
            var expectedImage = DisplayImageRenderer.createDisplayImage(first.getPipelineResult().masterStackData, 0.8, 4.2);
            var actualImage = javax.imageio.ImageIO.read(new File(first.getExportDirectory(), "master_stack.png"));
            assertArrayEquals(expectedImage.getRGB(0, 0, expectedImage.getWidth(), expectedImage.getHeight(), null, 0, expectedImage.getWidth()),
                    actualImage.getRGB(0, 0, actualImage.getWidth(), actualImage.getHeight(), null, 0, actualImage.getWidth()));
            assertEquals(VariableStarReportOptions.SortOrder.SIGNIFICANCE, firstRequest.getVariableStarReportOptions().sortOrder());
            assertEquals(3, DisplayImageRenderer.autoStretchBlackSigma, 0);
            assertEquals(12, DisplayImageRenderer.autoStretchWhiteSigma, 0);
            assertEquals(111, SpacePixelsDetectionProfileIO.getActiveAutoTuneMaxCandidateFrames());
            assertEquals(0.5, SpacePixelsPipelineRequest.builder(directory).build().getRunOptions().visualization().getAutoStretchBlackSigma(), 0);
        } finally {
            continueFirst.countDown();
            worker.shutdownNow();
            preferences.applyToRuntime();
            SpacePixelsDetectionProfileIO.setActiveAutoTuneMaxCandidateFrames(originalLimit);
        }
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
            var execution = processor.runDetectionPipeline(config, (percent, message) -> {
                config.detectionSigmaMultiplier = 20;
                processor.getAppConfig().siteLat = "-24";
            }, options);
            assertEquals(admittedSigma, execution.getEffectiveConfig().detectionSigmaMultiplier, 0);
            assertSame(options, execution.getReportContext().options());
            File report = processor.exportDetectionReport(execution);
            var profile = JsonParser.parseString(Files.readString(new File(report.getParentFile(), "detection_config.json").toPath())).getAsJsonObject();
            assertEquals(19, profile.get("autoTuneMaxCandidateFrames").getAsInt());
            assertEquals(admittedSigma, profile.get("detectionSigmaMultiplier").getAsDouble(), 0);
        }
    }
}
