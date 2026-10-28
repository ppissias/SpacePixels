package eu.startales.spacepixels.consumer;

import eu.startales.spacepixels.api.DefaultSpacePixelsPipelineApi;
import eu.startales.spacepixels.api.InputPreparationMode;
import eu.startales.spacepixels.api.SpacePixelsPipelineException;
import eu.startales.spacepixels.api.SpacePixelsPipelineRequest;
import eu.startales.spacepixels.api.SpacePixelsPipelineResult;
import eu.startales.spacepixels.config.DetectionRunOptions;
import eu.startales.spacepixels.config.VariableStarReportOptions;
import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.BuildInfo;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.tools.BatchDetectionCli;
import eu.startales.spacepixels.tools.ArtificialStarInjector;
import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Runs only against Maven-resolved API/dependency JARs, never the application's project classpath. */
public class HeadlessApiConsumerTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /** The published headless artifact exposes the safe default and both explicit outlier policies. */
    @Test public void conversionOutlierPolicyIsAvailableInThePublishedApi() {
        var defaults = SpacePixelsPipelineRequest.builder(folder.getRoot()).build();
        assertEquals(eu.startales.spacepixels.fits.ImageScaling.OutlierHandling.REQUIRE_DECISION, defaults.getOutlierHandling());
        for (var policy : eu.startales.spacepixels.fits.ImageScaling.OutlierHandling.values())
            assertEquals(policy, SpacePixelsPipelineRequest.builder(folder.getRoot()).outlierHandling(policy).build().getOutlierHandling());
    }

    /** The separately resolved artifact exposes warning-only and strict dataset-quality policies. */
    @Test public void datasetQualityPolicyIsAvailableInThePublishedApi() throws Exception {
        Path input = SyntheticDatasetFactory.createUncalibratedMono16FitsSequence(
                folder.getRoot().toPath(), "spikes", 5, 50);
        var warning = new DefaultSpacePixelsPipelineApi().run(SpacePixelsPipelineRequest.builder(input.toFile())
                .detectionConfig(SyntheticDatasetFactory.createSyntheticDetectionConfig())
                .generateReport(false).build());
        assertTrue(warning.hasCalibrationWarning());
        assertTrue(warning.getCalibrationWarning().contains("hot-pixel density"));
        var failure = assertThrows(eu.startales.spacepixels.api.SpacePixelsPipelineException.class,
                () -> new DefaultSpacePixelsPipelineApi().run(SpacePixelsPipelineRequest.builder(input.toFile())
                        .failIfUncalibratedOrMisaligned(true).generateReport(false).build()));
        assertTrue(failure.getMessage().startsWith("Pipeline rejected dataset:"));
    }

    /** Proves the consumer loads a real API/CLI JAR, with no desktop, workflow or look-and-feel classes. */
    @Test
    public void publishedRuntimeIsHeadlessAndHasNoDesktopClasses() throws Exception {
        assertTrue(GraphicsEnvironment.isHeadless());
        String location = DefaultSpacePixelsPipelineApi.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(location, location.endsWith(".jar") && location.contains("spacepixels-"));
        assertEquals(System.getProperty("spacepixels.expectedVersion"), BuildInfo.version("spacepixels"));
        assertNotEquals("development", BuildInfo.version("jtransient"));
        for (String className : new String[]{"eu.startales.spacepixels.gui.ApplicationWindow",
                "eu.startales.spacepixels.tasks.DetectionTask", "eu.startales.spacepixels.jobs.JobCoordinator",
                "com.formdev.flatlaf.FlatLaf"}) {
            assertThrows(className, ClassNotFoundException.class, () -> Class.forName(className));
        }
        assertNotNull(Class.forName("io.github.ppissias.jplatesolve.PlateSolveResult"));
        assertNull(DefaultSpacePixelsPipelineApi.class.getResource("/eu/startales/spacepixels/gui/icons/spacepixels-128.png"));
    }

    /** Ready FITS inputs produce a complete report, bundled scripts/catalogue data and readable headless images. */
    @Test
    public void strictFitsDetectionAndReportUseThePackagedResources() throws Exception {
        Path input = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "ready", 5);
        SpacePixelsPipelineResult result = run(input, InputPreparationMode.FAIL_IF_NOT_READY, true, false);
        assertFalse(result.isInputWasPrepared());
        assertEquals(5, result.getFilesInformation().length);
        assertNotNull(result.getPipelineResult());
        assertNotNull(result.getEffectiveConfig());
        assertTrue(result.getReportFile().isFile());
        assertTrue(Files.readString(result.getReportFile().toPath()).toLowerCase(java.util.Locale.ROOT).contains("<html"));
        assertTrue(new java.io.File(result.getExportDirectory(), "report-data.json").isFile());
        for (String resource : new String[]{"report-lookups.js", "report-interactions.js", "catalogue-comparison.js",
                "catalogue-source-notes.json"}) {
            try (InputStream stream = DefaultSpacePixelsPipelineApi.class.getResourceAsStream(
                    "/eu/startales/spacepixels/report/" + resource)) {
                assertNotNull(resource, stream);
                assertTrue(resource, stream.readAllBytes().length > 100);
            }
        }
        try (var files = Files.walk(result.getExportDirectory().toPath())) {
            Path image = files.filter(path -> path.getFileName().toString().endsWith(".png")).findFirst().orElseThrow();
            assertNotNull(ImageIO.read(image.toFile()));
        }
    }

    /** A consumer's own visualization settings travel in the run options into the report. */
    @Test
    public void visualizationSettingsReachTheReport() throws Exception {
        Path input = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "visualization", 5);
        VisualizationSettings visualization = VisualizationSettings.defaults().withAutoStretchWhiteSigma(4.0)
                .withGifBlinkSpeedMs(200).withVariableStarReportOptions(
                        new VariableStarReportOptions(13, VariableStarReportOptions.SortOrder.SIGNIFICANCE));
        SpacePixelsPipelineRequest request = SpacePixelsPipelineRequest.builder(input.toFile())
                .detectionConfig(SyntheticDatasetFactory.createSyntheticDetectionConfig())
                .runOptions(DetectionRunOptions.defaults().withVisualization(visualization))
                .inputPreparationMode(InputPreparationMode.FAIL_IF_NOT_READY)
                .generateReport(true)
                .build();
        assertEquals(visualization, request.getRunOptions().visualization());
        SpacePixelsPipelineResult result = new DefaultSpacePixelsPipelineApi().run(request);
        String profile = Files.readString(result.getExportDirectory().toPath().resolve("detection_config.json"));
        assertTrue(profile, profile.matches("(?s).*\"variableStarMaxCandidateCards\"\\s*:\\s*13\\b.*"));
    }

    /** Strict preparation refuses floating-point inputs without writing conversions or report folders. */
    @Test
    public void strictPreparationRejectsFloatFitsWithoutSideEffects() throws Exception {
        Path input = SyntheticDatasetFactory.createMono32FloatFitsSequence(folder.getRoot().toPath(), "strict-float", 5);
        assertThrows(SpacePixelsPipelineException.class, () -> run(input, InputPreparationMode.FAIL_IF_NOT_READY, false, false));
        try (var files = Files.list(input)) {
            assertEquals(5, files.count());
        }
    }

    /** Both supported automatic preparation families remain usable without desktop code or extra dependencies. */
    @Test
    public void automaticPreparationHandlesFloatFitsAndXisf() throws Exception {
        Path fits = SyntheticDatasetFactory.createMono32FloatFitsSequence(folder.getRoot().toPath(), "float", 5);
        Path xisf = SyntheticDatasetFactory.createGrayFloatXisfSequence(folder.getRoot().toPath(), "xisf", 5);
        for (Path input : new Path[]{fits, xisf}) {
            SpacePixelsPipelineResult result = run(input, InputPreparationMode.AUTO_PREPARE_TO_16BIT_MONO, false, false);
            assertTrue(result.isInputWasPrepared());
            assertNotEquals(input.toFile().getCanonicalPath(), result.getPreparedInputDirectory().getCanonicalPath());
            assertEquals(5, result.getFilesInformation().length);
            assertNotNull(result.getPipelineResult());
            assertNull(result.getReportFile());
        }
    }

    /** Exercises the public API shape needed for calibrated Auto-Tune and its exposed engine DTOs. */
    @Test
    public void calibratedAutoTuneWorksOnThePublishedRuntime() throws Exception {
        Path input = ConsumerDatasetFactory.createTuningSequence(folder.getRoot().toPath());
        SpacePixelsPipelineResult result = run(input, InputPreparationMode.FAIL_IF_NOT_READY, false, true);
        assertTrue(result.isAutoTuneApplied());
        assertEquals(AutoTuneProfile.MEDIUM, result.getAutoTuneProfileUsed());
        assertNotNull(result.getAutoTuneTelemetryReport());
        assertFalse(result.getAutoTuneTelemetryReport().isBlank());
        assertNotNull(result.getBaseConfig());
        assertNotNull(result.getEffectiveConfig());
        assertNotNull(result.getPipelineResult());
    }

    /** Executes the packaged batch CLI on prepared FITS inputs using only its published runtime graph. */
    @Test
    public void batchCliRunsFromTheApiArtifact() throws Exception {
        Path input = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "cli", 5);
        Path profile = SyntheticDatasetFactory.writeDetectionProfile(folder.getRoot().toPath(),
                "profile.json", SyntheticDatasetFactory.createSyntheticDetectionConfig());
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Duser.home=" + System.getProperty("user.home"),
                "-cp", System.getProperty("spacepixels.consumerClasspath"), BatchDetectionCli.class.getName(),
                input.toString(), profile.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.waitFor());
        assertTrue(output, output.contains("Report file:"));
    }

    /** Loads the packaged injector and checks its usage entrypoint without importing any desktop classes. */
    @Test
    public void artificialStarInjectorIsAvailableHeadlessly() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (PrintStream stdout = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(stdout);
            ArtificialStarInjector.main(new String[0]);
        } finally {
            System.setOut(original);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8), output.toString(StandardCharsets.UTF_8).contains("Usage:"));
    }

    /** Verifies the SP-11A migration contract: immutable FitsFileInformation, unmodifiable header, copy replacement, and removed mutators. */
    @Test
    public void immutableFitsFileInformationEnforcesImmutabilityAndCopyReplacement() throws Exception {
        Map<String, String> header = new HashMap<>();
        header.put("DATE-OBS", "2026-10-01T00:02:03.250");
        header.put("CRVAL1", "150.0");
        header.put("COMMENT", null);

        FitsFileInformation info = new FitsFileInformation("test.fit", "test.fit", true, 640, 480, header);
        header.put("CRVAL1", "999.0");
        assertEquals("150.0", info.getFitsHeader().get("CRVAL1"));

        // Header map is unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> info.getFitsHeader().put("NEWKEY", "val"));
        assertThrows(UnsupportedOperationException.class, () -> info.getFitsHeader().remove("CRVAL1"));

        // withFitsHeader creates a new descriptor without mutating the original
        FitsFileInformation updated = info.withFitsHeader(Map.of("CRVAL1", "210.0"));
        assertNotSame(info, updated);
        assertEquals("150.0", info.getFitsHeader().get("CRVAL1"));
        assertEquals("210.0", updated.getFitsHeader().get("CRVAL1"));

        // Verify mutators and snapshot() were removed
        for (String removedMethod : new String[]{"setFilePath", "setFileName", "setMonochrome", "setSizeWidth", "setSizeHeight", "snapshot"}) {
            for (java.lang.reflect.Method m : FitsFileInformation.class.getDeclaredMethods()) {
                assertNotEquals("Method " + removedMethod + " should be removed", removedMethod, m.getName());
            }
        }

        // Pipeline execution returns immutable descriptors
        Path input = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "meta-immutability", 3);
        SpacePixelsPipelineResult result = run(input, InputPreparationMode.FAIL_IF_NOT_READY, false, false);
        FitsFileInformation[] files = result.getFilesInformation();
        assertEquals(3, files.length);
        assertThrows(UnsupportedOperationException.class, () -> files[0].getFitsHeader().put("MUTATED", "true"));
    }

    /** Creates detached synthetic requests and invokes only the supported public pipeline API. */
    private static SpacePixelsPipelineResult run(Path input, InputPreparationMode preparation,
                                                 boolean report, boolean tune) throws SpacePixelsPipelineException {
        SpacePixelsPipelineRequest request = SpacePixelsPipelineRequest.builder(input.toFile())
                .detectionConfig(SyntheticDatasetFactory.createSyntheticDetectionConfig())
                .inputPreparationMode(preparation)
                .generateReport(report)
                .autoTuneProfile(tune ? AutoTuneProfile.MEDIUM : null)
                .autoTuneMaxCandidateFrames(5)
                .build();
        return new DefaultSpacePixelsPipelineApi().run(request);
    }
}
