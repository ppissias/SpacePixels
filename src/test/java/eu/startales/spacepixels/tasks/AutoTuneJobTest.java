package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.jobs.JobCoordinator;
import eu.startales.spacepixels.jobs.JobHandle;
import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import io.github.ppissias.jtransient.engine.AutoTunerResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Auto-Tune runs as a job: its progress reaches the window and its result comes back as the outcome. */
public class AutoTuneJobTest {

    private static final int FRAME_COUNT = 6;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void autoTuneReportsProgressAndReturnsTheTunersResult() throws Exception {
        List<Integer> progress = new ArrayList<>();
        JobOutcome<AutoTunerResult> outcome = runAutoTune(null, progress);
        assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Completed<?>);
        assertNotNull(((JobOutcome.Completed<AutoTunerResult>) outcome).result());
        assertFalse(progress.isEmpty());
        assertEquals(100, (int) progress.get(progress.size() - 1));
    }

    /** A cancel requested at the tuning handover reaches JTransient before calibration starts. */
    @Test
    public void aCancelWhileTheTunerRunsEndsAsCancelled() throws Exception {
        List<Integer> progress = new ArrayList<>();
        JobOutcome<AutoTunerResult> outcome = runAutoTune("Starting mathematical tuning", progress);
        assertTrue(outcome + " " + messages, outcome instanceof JobOutcome.Cancelled<?>);
        assertFalse("never reports 100 %", progress.contains(100));
    }

    private final List<String> messages = new ArrayList<>();

    /** Runs Auto-Tune on synthetic frames; it is cancelled at the first progress message starting with {@code cancelAt}. */
    private JobOutcome<AutoTunerResult> runAutoTune(String cancelAt, List<Integer> progress) throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "mono16", FRAME_COUNT);
        try (ImageProcessing processing = ImageProcessing.openProcessor(sequence.toFile())) {
            FitsFileInformation[] files = processing.getFitsfileInformationHeadless();
            List<JobOutcome<AutoTunerResult>> outcomes = new ArrayList<>();
            JobCoordinator coordinator = new JobCoordinator(Runnable::run, Runnable::run, () -> false);
            coordinator.setListener(new JobCoordinator.Listener() {
                @Override
                public void jobStarted(JobHandle handle) {
                }

                @Override
                public void jobProgress(JobHandle handle, int percent, String message) {
                    progress.add(percent);
                    messages.add(percent + " " + message);
                    if (cancelAt != null && message.startsWith(cancelAt)) {
                        coordinator.cancel();
                    }
                }

                @Override
                public void jobEnded(JobHandle handle, JobOutcome<?> outcome) {
                }
            });
            coordinator.start(JobKind.AUTO_TUNE, null, new AutoTuneTask(files, SyntheticDatasetFactory.createSyntheticDetectionConfig(),
                    FRAME_COUNT, AutoTuneProfile.values()[0]),
                    (handle, outcome) -> outcomes.add(outcome));
            assertEquals(1, outcomes.size());
            return outcomes.get(0);
        }
    }
}
