package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobCoordinator;
import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.AutoTunerRunner;
import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.StretchAlgorithm;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
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
        try (ImageProcessing processing = ImageProcessing.getInstance(folder.newFolder("session"))) {
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
                JTransientAutoTuner.AutoTuneProfile.values()[0], AutoTunerRunner.Algorithm.CALIBRATED)));
        assertFailed(outcomeOf(new DetectionTask(null, new DetectionConfig())));
        assertFailed(outcomeOf(new IterativeDetectionTask(null, new DetectionConfig(), 0)));
        assertFailed(outcomeOf(new ManualTransientInspectionTask(null, new DetectionConfig())));
        assertFailed(outcomeOf(new StarMaskStackTask(new FitsFileInformation[]{missingFrame()}, new DetectionConfig())));
        assertFailed(outcomeOf(new BlinkImagesTask(new FitsFileInformation[]{missingFrame()}, new AtomicBoolean(), (read, total) -> { })));
    }

    @Test
    public void blinkLoadsEveryFrameAndStopsWhenAsked() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "mono16", 3);
        try (ImageProcessing processing = ImageProcessing.getInstance(sequence.toFile())) {
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

    @Test
    public void aDetectionCancelledWhileItRunsStopsBeforeWritingAReport() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "mono16", 5);
        try (ImageProcessing processing = ImageProcessing.getInstance(sequence.toFile())) {
            processing.getFitsfileInformationHeadless();
            List<JobOutcome<File>> outcomes = new ArrayList<>();
            JobCoordinator coordinator = new JobCoordinator(Runnable::run, Runnable::run, () -> false);
            // The user cancels as soon as the first progress appears.
            coordinator.setListener(new JobCoordinator.Listener() {
                @Override
                public void jobStarted(eu.startales.spacepixels.jobs.JobHandle handle) {
                }

                @Override
                public void jobProgress(eu.startales.spacepixels.jobs.JobHandle handle, int percent, String message) {
                    coordinator.cancel();
                }

                @Override
                public void jobEnded(eu.startales.spacepixels.jobs.JobHandle handle, JobOutcome<?> outcome) {
                }
            });
            coordinator.start(JobKind.DETECTION, processing, new DetectionTask(processing, SyntheticDatasetFactory.createSyntheticDetectionConfig()),
                    (handle, outcome) -> outcomes.add(outcome));
            assertEquals(1, outcomes.size());
            assertTrue(String.valueOf(outcomes.get(0)), outcomes.get(0) instanceof JobOutcome.Cancelled<?>);
            File[] reports = sequence.toFile().listFiles(file -> file.getName().startsWith("detections_"));
            assertEquals(0, reports == null ? 0 : reports.length);
        }
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
