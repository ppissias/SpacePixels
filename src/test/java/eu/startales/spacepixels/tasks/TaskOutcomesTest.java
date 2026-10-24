package eu.startales.spacepixels.tasks;

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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
                    SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES, summary -> {
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
            DetectionTask first = new DetectionTask(processing, config, 19, summary -> true);
            DetectionTask next = new DetectionTask(processing, config, 23, summary -> true);
            assertEquals(19, recordedAutoTuneLimit(outcomeOf(first)));
            assertEquals(23, recordedAutoTuneLimit(outcomeOf(next)));
        }
    }

    /** The Auto-Tune frame limit that a completed detection wrote to its report's detection_config.json. */
    private static int recordedAutoTuneLimit(JobOutcome<File> outcome) throws Exception {
        assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Completed<?>);
        File report = ((JobOutcome.Completed<File>) outcome).result();
        String profile = java.nio.file.Files.readString(new File(report.getParentFile(), "detection_config.json").toPath());
        return com.google.gson.JsonParser.parseString(profile).getAsJsonObject().get("autoTuneMaxCandidateFrames").getAsInt();
    }

    /** Every independent iterative pass supplies its own subset and effective settings to the injected decision. */
    @Test
    public void iterativeRequestUsesTheSuppliedPerPassReportDecision() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "pass-prompts", 6);
        var config = SyntheticDatasetFactory.createSyntheticDetectionConfig();
        var application = new eu.startales.spacepixels.config.AppConfig();
        var request = new eu.startales.spacepixels.util.IterativeDetectionRequest(sequence.toFile(), config, application,
                eu.startales.spacepixels.util.reporting.DetectionReportGenerator.captureExportSettings(application.variableStarReportOptions),
                new eu.startales.spacepixels.util.IterativeDetectionPlan(5, 1, 6), null, 5, false, true, 6);
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
                eu.startales.spacepixels.util.reporting.ExportVisualizationSettings.defaults(),
                new eu.startales.spacepixels.util.IterativeDetectionPlan(5, 5, 0), null, 5, false, false, 0));
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

    private static void assertFailed(JobOutcome<?> outcome) {
        assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Failed<?> failed && failed.reason() != null);
    }

    private FitsFileInformation missingFrame() {
        File file = new File(folder.getRoot(), "missing.fit");
        return new FitsFileInformation(file.getAbsolutePath(), file.getName(), true, 6, 4);
    }
}
