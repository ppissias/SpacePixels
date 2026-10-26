package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.DetectionRunOptions;
import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.detection.IterativeDetectionPlan;
import eu.startales.spacepixels.detection.IterativeDetectionRequest;
import eu.startales.spacepixels.fits.*;
import eu.startales.spacepixels.jobs.*;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Exercises the actual import and detection entry points, including their warning-only and decline paths. */
public class CalibrationSafetyTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    /** Import cancellation reaches the pixel loops instead of waiting until the entire inspection finishes. */
    @Test public void importInspectionObservesJobCancellation() throws Exception {
        File input = suspiciousInput();
        AtomicInteger checks = new AtomicInteger();
        try {
            new FitsImportTask(input).run(context(() -> checks.incrementAndGet() >= 5));
            fail("Expected cancellation during frame inspection");
        } catch (CancellationException expected) {
            assertEquals(5, checks.get());
        }
    }

    /** Cancel and dismissal preserve the previous session by declining import; explicit override imports the files. */
    @Test public void importRequiresAnExplicitOverrideForSuspiciousFrames() throws Exception {
        File input = suspiciousInput();
        for (int answer : new int[]{0, -1, 1}) {
            AtomicBoolean asked = new AtomicBoolean();
            FitsImportInteraction interaction = new FitsImportInteraction() {
                /** No conversion is needed by this fixture. */
                @Override public int confirm(String message, String title) { throw new AssertionError(title); }
                /** Verifies the two decided choices and supplies the explicit test decision. */
                @Override public int choose(String message, String title, String[] options) {
                    assertEquals("Dataset Quality Warning", title);
                    assertTrue(options[0].startsWith("Cancel Import"));
                    assertEquals("Proceed Anyway", options[1]);
                    asked.set(true);
                    return answer;
                }
                /** A valid ready fixture must not take an error path. */
                @Override public void error(String message, String title) { throw new AssertionError(message); }
                /** No conversion progress window should be requested. */
                @Override public Progress progress(String title) { throw new AssertionError(title); }
            };
            JobOutcome<?> outcome = new FitsImportTask(input, null, interaction).run(context());
            assertTrue(asked.get());
            if (answer == 1) {
                assertTrue(outcome instanceof JobOutcome.Completed<?>);
            } else {
                assertTrue(outcome instanceof JobOutcome.Declined<?>);
            }
        }
    }

    /** Standard and iterative cancellation happen before Auto-Tune, frame detection or any report output. */
    @Test public void bothDetectionWorkflowsHonorThePreflightDecline() throws Exception {
        File input = suspiciousInput();
        FitsFileInformation[] files;
        try (FitsImportService importer = new FitsImportService(input, null)) { files = importer.readFiles(); }
        var config = SyntheticDatasetFactory.createSyntheticDetectionConfig();
        AtomicBoolean standardAsked = new AtomicBoolean();
        var standard = new DetectionTask(files, config, DetectionRunOptions.defaults(), null,
                summary -> { throw new AssertionError("Detection must not reach the report question"); }, inspection -> {
                    assertTrue(inspection.hasWarning());
                    standardAsked.set(true);
                    return false;
                });
        assertTrue(standard.run(context()) instanceof JobOutcome.Declined<?>);
        assertTrue(standardAsked.get());
        assertNull(standard.detectedOverlays());
        var request = new IterativeDetectionRequest(input, config, new AppConfig(), VisualizationSettings.defaults(),
                new IterativeDetectionPlan(5, 1, 5), null, 5, false, false, 5, null);
        AtomicBoolean iterativeAsked = new AtomicBoolean();
        var iterative = new IterativeDetectionTask(request,
                (summary, subset, settings) -> { throw new AssertionError("No iterative pass may run"); }, inspection -> {
                    assertTrue(inspection.hasWarning());
                    iterativeAsked.set(true);
                    return false;
                });
        assertTrue(iterative.run(context()) instanceof JobOutcome.Declined<?>);
        assertTrue(iterativeAsked.get());
        assertNull(iterative.getIndexFile());
        assertNotNull(input.listFiles());
        assertEquals("No detection output is created", 0,
                input.listFiles(file -> file.isDirectory() && file.getName().startsWith("detections_")).length);
    }

    /** Creates independent disposable data with enough sharp sensor defects for the calibrated warning threshold. */
    private File suspiciousInput() throws Exception {
        return SyntheticDatasetFactory.createUncalibratedMono16FitsSequence(folder.newFolder().toPath(),
                "spikes", 5, 50).toFile();
    }

    /** Supplies a noncancelled worker context without creating an application or writing preferences. */
    private static JobContext context() {
        return context(() -> false);
    }

    /** Exposes only the supplied cancellation flag; progress callbacks do not simulate cancellation. */
    private static JobContext context(BooleanSupplier cancelled) {
        return new JobContext() {
            /** These direct synchronous jobs do not need an admission handle. */
            @Override public JobHandle handle() { return null; }
            /** Reads the worker's cooperative cancellation request. */
            @Override public boolean isCancelled() { return cancelled.getAsBoolean(); }
            /** Progress is deliberately independent of a window. */
            @Override public void progress(int percent, String message) { }
        };
    }
}
