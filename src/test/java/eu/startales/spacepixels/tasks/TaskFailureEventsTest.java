package eu.startales.spacepixels.tasks;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import eu.startales.spacepixels.events.AutoTuneFinishedEvent;
import eu.startales.spacepixels.events.BatchConvertFinishedEvent;
import eu.startales.spacepixels.events.BatchStretchFinishedEvent;
import eu.startales.spacepixels.events.BlinkFinishedEvent;
import eu.startales.spacepixels.events.DetectionFinishedEvent;
import eu.startales.spacepixels.events.DetectionStartedEvent;
import eu.startales.spacepixels.events.SolveFinishedEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.util.AutoTunerRunner;
import eu.startales.spacepixels.util.FitsFileInformation;
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
import static org.junit.Assert.assertNull;
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
        StretchAlgorithm algorithm = StretchAlgorithm.values()[0];
        FitsFileInformation missing = missingFrame();

        BatchStretchFinishedEvent stretch = last(run(new BatchStretchTask(bus(), null, 1, 1, algorithm)), BatchStretchFinishedEvent.class);
        assertFalse(stretch.isSuccess());
        assertNotNull(stretch.getErrorMessage());

        BatchConvertFinishedEvent convert = last(run(new BatchConvertMonoTask(bus(), null, false, 1, 1, algorithm)), BatchConvertFinishedEvent.class);
        assertFalse(convert.isSuccess());
        assertNotNull(convert.getErrorMessage());

        assertNull(last(run(new PlateSolveTask(bus(), null, missing.getFilePath(), 0, true, false)), SolveFinishedEvent.class).getResult());

        AutoTuneFinishedEvent tune = last(run(new AutoTuneTask(bus(), null, new DetectionConfig(), 10,
                JTransientAutoTuner.AutoTuneProfile.values()[0], AutoTunerRunner.Algorithm.CALIBRATED)), AutoTuneFinishedEvent.class);
        assertFalse(tune.isSuccess());
        assertNotNull(tune.getMessage());

        assertFailed(last(run(new DetectionTask(bus(), null, new DetectionConfig())), DetectionFinishedEvent.class));
        assertFailed(last(run(new ManualTransientInspectionTask(bus(), null, new DetectionConfig(), null)), DetectionFinishedEvent.class));
        assertFailed(last(run(new StarMaskStackTask(bus(), new FitsFileInformation[]{missing}, new DetectionConfig(), result -> { })),
                DetectionFinishedEvent.class));

        BlinkFinishedEvent blink = last(run(new BlinkImagesTask(bus(), new FitsFileInformation[]{missing}, new AtomicBoolean(true))),
                BlinkFinishedEvent.class);
        assertFalse(blink.isSuccess());
        assertNotNull(blink.getErrorMessage());
    }

    @Test
    public void outOfMemoryLeavesTheTaskInsteadOfBeingSwallowed() throws Exception {
        // The flag is how the application stops the other tasks once one has run out of memory.
        ApplicationWindow.OOM_FLAG = true;
        try {
            Recorder recorder = new Recorder();
            EventBus bus = new EventBus();
            bus.register(recorder);
            StarMaskStackTask task = new StarMaskStackTask(bus, new FitsFileInformation[]{missingFrame()}, new DetectionConfig(), result -> { });
            assertThrows(OutOfMemoryError.class, task::run);
            assertEquals(1, recorder.events.size());
            assertTrue(recorder.events.get(0) instanceof DetectionStartedEvent);
        } finally {
            ApplicationWindow.OOM_FLAG = false;
        }
    }

    private FitsFileInformation missingFrame() {
        File file = new File(folder.getRoot(), "missing.fit");
        return new FitsFileInformation(file.getAbsolutePath(), file.getName(), true, 6, 4);
    }

    private static void assertFailed(DetectionFinishedEvent event) {
        assertFalse(event.isSuccess());
        assertNotNull(event.getErrorMessage());
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
