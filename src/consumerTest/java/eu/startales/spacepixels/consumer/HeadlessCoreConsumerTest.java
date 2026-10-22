package eu.startales.spacepixels.consumer;

import eu.startales.spacepixels.api.DefaultSpacePixelsPipelineApi;
import eu.startales.spacepixels.api.InputPreparationMode;
import eu.startales.spacepixels.api.SpacePixelsPipelineException;
import eu.startales.spacepixels.api.SpacePixelsPipelineRequest;
import eu.startales.spacepixels.api.SpacePixelsPipelineResult;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.BuildInfo;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** Runs only against Maven-resolved core/dependency JARs, never the application's project classpath. */
public class HeadlessCoreConsumerTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /** Proves the consumer loads a real core JAR, with no desktop, workflow or look-and-feel classes. */
    @Test
    public void publishedRuntimeIsHeadlessAndHasNoDesktopClasses() {
        assertTrue(GraphicsEnvironment.isHeadless());
        String location = DefaultSpacePixelsPipelineApi.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(location, location.endsWith(".jar") && location.contains("spacepixels-core-"));
        assertEquals(System.getProperty("spacepixels.expectedVersion"), BuildInfo.version("spacepixels"));
        assertNotEquals("development", BuildInfo.version("jtransient"));
        for (String className : new String[]{"eu.startales.spacepixels.gui.ApplicationWindow",
                "eu.startales.spacepixels.tasks.DetectionTask", "eu.startales.spacepixels.jobs.JobCoordinator",
                "eu.startales.spacepixels.tools.BatchDetectionCli", "com.formdev.flatlaf.FlatLaf"}) {
            assertThrows(className, ClassNotFoundException.class, () -> Class.forName(className));
        }
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
                    "/eu/startales/spacepixels/util/reporting/" + resource)) {
                assertNotNull(resource, stream);
                assertTrue(resource, stream.readAllBytes().length > 100);
            }
        }
        try (var files = Files.walk(result.getExportDirectory().toPath())) {
            Path image = files.filter(path -> path.getFileName().toString().endsWith(".png")).findFirst().orElseThrow();
            assertNotNull(ImageIO.read(image.toFile()));
        }
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
        assertEquals(JTransientAutoTuner.AutoTuneProfile.MEDIUM, result.getAutoTuneProfileUsed());
        assertNotNull(result.getAutoTuneTelemetryReport());
        assertFalse(result.getAutoTuneTelemetryReport().isBlank());
        assertNotNull(result.getBaseConfig());
        assertNotNull(result.getEffectiveConfig());
        assertNotNull(result.getPipelineResult());
    }

    /** Creates detached synthetic requests and invokes only the supported public pipeline API. */
    private static SpacePixelsPipelineResult run(Path input, InputPreparationMode preparation,
                                                 boolean report, boolean tune) throws SpacePixelsPipelineException {
        SpacePixelsPipelineRequest request = SpacePixelsPipelineRequest.builder(input.toFile())
                .detectionConfig(SyntheticDatasetFactory.createSyntheticDetectionConfig())
                .inputPreparationMode(preparation)
                .generateReport(report)
                .autoTuneProfile(tune ? JTransientAutoTuner.AutoTuneProfile.MEDIUM : null)
                .autoTuneMaxCandidateFrames(5)
                .build();
        return new DefaultSpacePixelsPipelineApi().run(request);
    }
}
