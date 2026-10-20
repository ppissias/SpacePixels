package eu.startales.spacepixels.tasks;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import eu.startales.spacepixels.events.AutoTuneFinishedEvent;
import eu.startales.spacepixels.events.BlinkFinishedEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobCoordinator;
import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.util.AutoTunerRunner;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.StretchAlgorithm;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * A failed task ends with its finished event, which closes the progress dialog and unlocks the window; running out of
 * memory leaves the task for the application's handler instead of being swallowed.
 */
public class TaskFailureEventsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void failingTasksPostTheirFinishedEventWithTheReason() throws Exception {
        FitsFileInformation missing = missingFrame();

        AutoTuneFinishedEvent tune = last(run(new AutoTuneTask(bus(), null, new DetectionConfig(), 10,
                JTransientAutoTuner.AutoTuneProfile.values()[0], AutoTunerRunner.Algorithm.CALIBRATED)), AutoTuneFinishedEvent.class);
        assertFalse(tune.isSuccess());
        assertNotNull(tune.getMessage());

        BlinkFinishedEvent blink = last(run(new BlinkImagesTask(bus(), new FitsFileInformation[]{missing}, new AtomicBoolean(true))),
                BlinkFinishedEvent.class);
        assertFalse(blink.isSuccess());
        assertNotNull(blink.getErrorMessage());
    }

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
        assertFailed(outcomeOf(new DetectionTask(null, new DetectionConfig())));
        assertFailed(outcomeOf(new IterativeDetectionTask(null, new DetectionConfig(), 0)));
        assertFailed(outcomeOf(new ManualTransientInspectionTask(null, new DetectionConfig())));
        assertFailed(outcomeOf(new StarMaskStackTask(new FitsFileInformation[]{missingFrame()}, new DetectionConfig())));
    }

    @Test
    public void outOfMemoryLeavesTheJobInsteadOfBeingSwallowed() {
        // The flag is how the application stops the other jobs once one has run out of memory.
        ApplicationWindow.OOM_FLAG = true;
        try {
            List<JobOutcome<?>> outcomes = new ArrayList<>();
            JobCoordinator coordinator = new JobCoordinator(Runnable::run, Runnable::run, () -> false);
            assertThrows(OutOfMemoryError.class, () -> coordinator.start(JobKind.STAR_MASK_STACK, null,
                    new StarMaskStackTask(new FitsFileInformation[]{missingFrame()}, new DetectionConfig()), (handle, outcome) -> outcomes.add(outcome)));
            assertTrue(outcomes.isEmpty());
        } finally {
            ApplicationWindow.OOM_FLAG = false;
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

    private Recorder recorder;

    private EventBus bus() {
        recorder = new Recorder();
        EventBus bus = new EventBus();
        bus.register(recorder);
        return bus;
    }

    private List<Object> run(Runnable task) {
        task.run();
        return recorder.events;
    }

    private static <T> T last(List<Object> events, Class<T> type) {
        assertFalse("No events were posted", events.isEmpty());
        Object last = events.get(events.size() - 1);
        assertTrue("Last event was " + last.getClass().getSimpleName(), type.isInstance(last));
        return type.cast(last);
    }

    /** Records every event posted on its bus. */
    public static final class Recorder {
        final List<Object> events = new ArrayList<>();

        @Subscribe
        public void on(Object event) {
            events.add(event);
        }
    }
}
