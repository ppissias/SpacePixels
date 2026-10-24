package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.config.VariableStarReportOptions;
import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobCoordinator;
import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.StretchAlgorithm;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Every task ends with one outcome: a failure carries its reason, which the window shows after unlocking. (Running out
 * of memory is the coordinator's part; see {@code JobCoordinatorTest}. Blink recovers from it itself.)
 */
public class TaskOutcomesTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aFailedPlateSolveCarriesItsReasonWithoutOpeningADialog() throws Exception {
        try (ImageProcessing processing = ImageProcessing.openProcessor(folder.newFolder("session"))) {
            // In memory only: the saved application config is not touched.
            processing.getAppConfig().astapExecutablePath = new File(folder.getRoot(), "missing-astap.exe").getAbsolutePath();
            JobOutcome<?> solved = outcomeOf(new PlateSolveTask(processing, missingFrame().getFilePath(), true, false));
            assertTrue(String.valueOf(solved), solved instanceof JobOutcome.Failed<?> failed
                    && failed.reason().startsWith("ASTAP executable path is not correct"));
        }
    }

    @Test
    public void failingJobsEndAsFailedWithTheReason() {
        StretchAlgorithm algorithm = StretchAlgorithm.values()[0];
        assertFailed(outcomeOf(new BatchStretchTask(null, 1, 1, algorithm)));
        assertFailed(outcomeOf(new BatchConvertMonoTask(null, false, 1, 1, algorithm)));
        assertFailed(outcomeOf(new PlateSolveTask(null, missingFrame().getFilePath(), true, false)));
        assertFailed(outcomeOf(new AutoTuneTask(null, new DetectionConfig(), 10,
                AutoTuneProfile.values()[0])));
        assertFailed(outcomeOf(new DetectionTask(null, new DetectionConfig())));
        assertFailed(outcomeOf(iterativeTask(new File(folder.getRoot(), "missing"))));
        assertFailed(outcomeOf(new ManualTransientInspectionTask(null, new DetectionConfig())));
        assertFailed(outcomeOf(new StarMaskStackTask(new FitsFileInformation[]{missingFrame()}, new DetectionConfig())));
        assertFailed(outcomeOf(new BlinkImagesTask(new FitsFileInformation[]{missingFrame()}, new AtomicBoolean(), (read, total) -> { })));
    }

    @Test
    public void blinkLoadsEveryFrameAndStopsWhenAsked() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "mono16", 3);
        try (ImageProcessing processing = ImageProcessing.openProcessor(sequence.toFile())) {
            FitsFileInformation[] files = processing.getFitsfileInformationHeadless();
            List<String> reads = new ArrayList<>();
            JobOutcome<BlinkSequence> loaded = outcomeOf(new BlinkImagesTask(files, new AtomicBoolean(),
                    (read, total) -> reads.add(read + "/" + total)));
            assertTrue(String.valueOf(loaded), loaded instanceof JobOutcome.Completed<BlinkSequence> completed
                    && completed.result().getFrames().size() == 3);
            assertEquals(List.of("0/3", "1/3", "2/3"), reads);

            JobOutcome<BlinkSequence> stopped = outcomeOf(new BlinkImagesTask(files, new AtomicBoolean(true), (read, total) -> { }));
            assertTrue(String.valueOf(stopped), stopped instanceof JobOutcome.Declined<BlinkSequence> declined
                    && declined.reason().equals("Blinking stopped."));
        }
    }

    /** Detection forwards the measured summary to an injected decision and respects report refusal. */
    @Test
    public void detectionUsesTheSuppliedReportDecision() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "prompt", 5);
        AtomicBoolean asked = new AtomicBoolean();
        try (ImageProcessing processing = ImageProcessing.openProcessor(sequence.toFile())) {
            DetectionTask task = new DetectionTask(processing, SyntheticDatasetFactory.createSyntheticDetectionConfig(),
                    VisualizationSettings.defaults(), SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES, null, summary -> {
                asked.set(true);
                assertTrue(summary.totalDetections >= 0);
                return false;
            });
            assertTrue(outcomeOf(task) instanceof JobOutcome.Declined<?>);
        }
        assertTrue(asked.get());
        assertEquals(0, foldersStartingWith(sequence, "detections_").length);
    }

    /** A detection keeps the Auto-Tune frame limit it was created with; a later change reaches only the next job. */
    @Test
    public void detectionKeepsTheAutoTuneLimitItWasCreatedWith() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "limit", 5);
        try (ImageProcessing processing = ImageProcessing.openProcessor(sequence.toFile())) {
            var config = SyntheticDatasetFactory.createSyntheticDetectionConfig();
            DetectionTask first = new DetectionTask(processing, config, VisualizationSettings.defaults(), 19, null, summary -> true);
            DetectionTask next = new DetectionTask(processing, config, VisualizationSettings.defaults(), 23, null, summary -> true);
            assertEquals(19, recordedAutoTuneLimit(outcomeOf(first)));
            assertEquals(23, recordedAutoTuneLimit(outcomeOf(next)));
        }
    }

    /** A detection keeps the visualization settings it was created with: its report shows them, not the next job's. */
    @Test
    public void detectionKeepsTheVisualizationSettingsItWasCreatedWith() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "visualization", 5);
        try (ImageProcessing processing = ImageProcessing.openProcessor(sequence.toFile())) {
            var config = SyntheticDatasetFactory.createSyntheticDetectionConfig();
            var edited = VisualizationSettings.defaults().withAutoStretchWhiteSigma(4.2).withVariableStarReportOptions(
                    new VariableStarReportOptions(13, VariableStarReportOptions.SortOrder.SIGNIFICANCE));
            DetectionTask first = new DetectionTask(processing, config, edited, 19, null, summary -> true);
            DetectionTask next = new DetectionTask(processing, config, VisualizationSettings.defaults(), 19, null, summary -> true);
            File firstReport = report(outcomeOf(first));
            File nextReport = report(outcomeOf(next));
            assertEquals(13, recordedProfile(firstReport).get("variableStarMaxCandidateCards").getAsInt());
            assertEquals(VariableStarReportOptions.DEFAULT_MAX_CANDIDATE_CARDS,
                    recordedProfile(nextReport).get("variableStarMaxCandidateCards").getAsInt());
            assertFalse("the stretch of each run's master stack picture differs", java.util.Arrays.equals(
                    java.nio.file.Files.readAllBytes(new File(firstReport.getParentFile(), "master_stack.png").toPath()),
                    java.nio.file.Files.readAllBytes(new File(nextReport.getParentFile(), "master_stack.png").toPath())));
        }
    }

    /** The Auto-Tune frame limit that a completed detection wrote to its report's detection_config.json. */
    private static int recordedAutoTuneLimit(JobOutcome<File> outcome) throws Exception {
        return recordedProfile(report(outcome)).get("autoTuneMaxCandidateFrames").getAsInt();
    }

    /** The report file of a completed detection. */
    private static File report(JobOutcome<File> outcome) {
        assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Completed<?>);
        return ((JobOutcome.Completed<File>) outcome).result();
    }

    /** The detection_config.json written beside {@code report}. */
    private static com.google.gson.JsonObject recordedProfile(File report) throws Exception {
        String profile = java.nio.file.Files.readString(new File(report.getParentFile(), "detection_config.json").toPath());
        return com.google.gson.JsonParser.parseString(profile).getAsJsonObject();
    }

    /** Every independent iterative pass supplies its own subset and effective settings to the injected decision. */
    @Test
    public void iterativeRequestUsesTheSuppliedPerPassReportDecision() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "pass-prompts", 6);
        var config = SyntheticDatasetFactory.createSyntheticDetectionConfig();
        var application = new eu.startales.spacepixels.config.AppConfig();
        var request = new eu.startales.spacepixels.util.IterativeDetectionRequest(sequence.toFile(), config, application,
                VisualizationSettings.defaults(),
                new eu.startales.spacepixels.util.IterativeDetectionPlan(5, 1, 6), null, 5, false, true, 6, null);
        List<Integer> subsets = new ArrayList<>();
        IterativeDetectionTask task = new IterativeDetectionTask(request, (summary, files, settings) -> {
            subsets.add(files.length);
            assertEquals(config.detectionSigmaMultiplier, settings.detectionSigmaMultiplier, 0);
            return false;
        });
        assertTrue(outcomeOf(task) instanceof JobOutcome.Completed<?>);
        assertEquals(List.of(5, 6), subsets);
        String run = java.nio.file.Files.readString(new File(task.getIndexFile().getParentFile(), "run-data.json").toPath());
        assertTrue(run.contains("REPORT_DECLINED"));
        assertFalse(run.contains("\"outcome\": \"FAILED\""));
    }

    /**
     * A detection that finishes keeps its overlays whatever becomes of its report: written, declined, not writable or
     * cancelled while it is written. The window gives them to the session when the job ends.
     */
    @Test
    public void aFinishedDetectionKeepsItsOverlaysWhateverBecomesOfTheReport() throws Exception {
        DetectionConfig config = SyntheticDatasetFactory.createSyntheticDetectionConfig();
        Path written = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "written", 5);
        try (ImageProcessing processing = ImageProcessing.openProcessor(written.toFile())) {
            DetectionTask task = new DetectionTask(processing, config, VisualizationSettings.defaults(), 19, null, summary -> true);
            assertTrue(outcomeOf(task) instanceof JobOutcome.Completed<?>);
            assertNotNull(task.detectedOverlays());
            assertNotNull(task.detectedOverlays().tracks());
            assertNotNull(task.detectedOverlays().variables());
        }
        Path declined = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "declined", 5);
        try (ImageProcessing processing = ImageProcessing.openProcessor(declined.toFile())) {
            DetectionTask task = new DetectionTask(processing, config, VisualizationSettings.defaults(), 19, null, summary -> false);
            assertTrue(outcomeOf(task) instanceof JobOutcome.Declined<?>);
            assertNotNull("a declined report leaves the run's overlays", task.detectedOverlays());
        }
        Path unwritable = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "unwritable", 5);
        try (ImageProcessing processing = ImageProcessing.openProcessor(unwritable.toFile())) {
            // After the detection, before the report: the session folder becomes a file, so no report folder fits in it.
            DetectionTask task = new DetectionTask(processing, config, VisualizationSettings.defaults(), 19, null, summary -> {
                replaceFolderWithFile(unwritable.toFile());
                return true;
            });
            JobOutcome<File> outcome = outcomeOf(task);
            assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Failed<?> failed
                    && failed.reason().contains("the report could not be written"));
            assertNotNull("a report that cannot be written leaves the run's overlays", task.detectedOverlays());
        }
        Path cancelled = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "cancelled", 5);
        AtomicReference<DetectionTask> task = new AtomicReference<>();
        JobOutcome<File> outcome = runCancelledAt(cancelled, "Writing the report: unclassified transients", processing -> {
            task.set(new DetectionTask(processing, config, VisualizationSettings.defaults(), 19, null, summary -> true));
            return task.get();
        }, new ArrayList<>());
        assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Cancelled<?>);
        assertNotNull("a report cancelled while it is written leaves the run's overlays", task.get().detectedOverlays());
    }

    /** A detection that stops before it finishes leaves no overlays, so the session keeps those of its last run. */
    @Test
    public void aDetectionThatStopsBeforeItFinishesLeavesNoOverlays() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "stopped", 5);
        AtomicReference<DetectionTask> cancelled = new AtomicReference<>();
        JobOutcome<File> outcome = runCancelledAt(sequence, "", processing -> {
            cancelled.set(new DetectionTask(processing, SyntheticDatasetFactory.createSyntheticDetectionConfig()));
            return cancelled.get();
        }, new ArrayList<>());
        assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Cancelled<?>);
        assertNull(cancelled.get().detectedOverlays());

        DetectionTask failed = new DetectionTask(null, new DetectionConfig());
        assertFailed(outcomeOf(failed));
        assertNull(failed.detectedOverlays());
    }

    @Test
    public void aDetectionCancelledWhileItRunsStopsBeforeWritingAReport() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "mono16", 5);
        // The user cancels as soon as the first progress appears.
        JobOutcome<File> outcome = runCancelledAt(sequence, "", processing ->
                new DetectionTask(processing, SyntheticDatasetFactory.createSyntheticDetectionConfig()), new ArrayList<>());
        assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Cancelled<?>);
        assertEquals(0, foldersStartingWith(sequence, "detections_").length);
    }

    @Test
    public void aDetectionCancelledWhileItWritesItsReportRemovesTheUnfinishedFolder() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "mono16", 5);
        List<String> messages = new ArrayList<>();
        JobOutcome<File> outcome = runCancelledAt(sequence, "Writing the report: unclassified transients", processing ->
                new DetectionTask(processing, SyntheticDatasetFactory.createSyntheticDetectionConfig()), messages);
        assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Cancelled<?>);
        assertTrue("the report reports its steps: " + messages, messages.contains("Writing the report: maps..."));
        assertEquals(0, foldersStartingWith(sequence, "detections_").length);
    }

    @Test
    public void anIterativeRunCancelledDuringItsSecondPassKeepsTheFirstWithAnIndex() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "mono16", 10);
        JobOutcome<File> outcome = runCancelledAt(sequence, "Pass 10 frames: Writing the report", processing ->
                iterativeTask(sequence.toFile()), new ArrayList<>());
        assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Cancelled<?>);
        File[] runs = foldersStartingWith(sequence, "iterative_detections_");
        assertEquals(1, runs.length);
        assertTrue(new File(runs[0], "5_frames/detection_report.html").isFile());
        assertFalse("the unfinished pass is removed", new File(runs[0], "10_frames").exists());
        String index = java.nio.file.Files.readString(new File(runs[0], "index.html").toPath());
        assertTrue(index, index.contains("Cancelled") && index.contains("Completed"));
    }

    @Test
    public void anIterativeRunCancelledBeforeAnyPassFinishedLeavesNoFolder() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "mono16", 10);
        JobOutcome<File> outcome = runCancelledAt(sequence, "Pass 5 frames: Loading", processing ->
                iterativeTask(sequence.toFile()), new ArrayList<>());
        assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Cancelled<?>);
        assertEquals(0, foldersStartingWith(sequence, "iterative_detections_").length);
    }

    /** Iterative passes of 5, 10, ... frames over {@code directory}, as the application requests them, without Auto-Tune. */
    private static IterativeDetectionTask iterativeTask(File directory) {
        return new IterativeDetectionTask(new eu.startales.spacepixels.util.IterativeDetectionRequest(directory,
                SyntheticDatasetFactory.createSyntheticDetectionConfig(), new eu.startales.spacepixels.config.AppConfig(),
                VisualizationSettings.defaults(),
                new eu.startales.spacepixels.util.IterativeDetectionPlan(5, 5, 0), null, 5, false, false, 0, null));
    }

    /** Runs the job on the sequence and cancels it at the first progress message starting with {@code cancelAt}. */
    private static JobOutcome<File> runCancelledAt(Path sequence, String cancelAt,
                                                   java.util.function.Function<ImageProcessing, Job<File>> job,
                                                   List<String> messages) throws Exception {
        try (ImageProcessing processing = ImageProcessing.openProcessor(sequence.toFile())) {
            processing.getFitsfileInformationHeadless();
            List<JobOutcome<File>> outcomes = new ArrayList<>();
            JobCoordinator coordinator = new JobCoordinator(Runnable::run, Runnable::run, () -> false);
            coordinator.setListener(new JobCoordinator.Listener() {
                @Override
                public void jobStarted(eu.startales.spacepixels.jobs.JobHandle handle) {
                }

                @Override
                public void jobProgress(eu.startales.spacepixels.jobs.JobHandle handle, int percent, String message) {
                    messages.add(message);
                    if (message.startsWith(cancelAt)) {
                        coordinator.cancel();
                    }
                }

                @Override
                public void jobEnded(eu.startales.spacepixels.jobs.JobHandle handle, JobOutcome<?> outcome) {
                }
            });
            coordinator.start(JobKind.DETECTION, null, job.apply(processing), (handle, outcome) -> outcomes.add(outcome));
            assertEquals(1, outcomes.size());
            return outcomes.get(0);
        }
    }

    private static File[] foldersStartingWith(Path sequence, String prefix) {
        File[] folders = sequence.toFile().listFiles(file -> file.isDirectory() && file.getName().startsWith(prefix));
        return folders == null ? new File[0] : folders;
    }

    /** Runs the job under a coordinator that works on the calling thread, and returns its one outcome. */
    private static <R> JobOutcome<R> outcomeOf(Job<R> job) {
        List<JobOutcome<R>> outcomes = new ArrayList<>();
        new JobCoordinator(Runnable::run, Runnable::run, () -> false).start(JobKind.DETECTION, null, job, (handle, outcome) -> outcomes.add(outcome));
        assertEquals(1, outcomes.size());
        return outcomes.get(0);
    }

    /** Replaces the session folder with a file of the same name, so no report folder can be created in it. */
    private static void replaceFolderWithFile(File directory) {
        try {
            for (File file : directory.listFiles()) {
                Files.delete(file.toPath());
            }
            Files.delete(directory.toPath());
            Files.writeString(directory.toPath(), "not a folder");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertFailed(JobOutcome<?> outcome) {
        assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Failed<?> failed && failed.reason() != null);
    }

    private FitsFileInformation missingFrame() {
        File file = new File(folder.getRoot(), "missing.fit");
        return new FitsFileInformation(file.getAbsolutePath(), file.getName(), true, 6, 4);
    }
}
