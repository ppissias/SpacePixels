package eu.startales.spacepixels.jobs;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The coordinator admits a job before its worker runs, refuses a second one, and delivers each outcome exactly once on
 * the UI executor after unlocking the window. The worker executor here holds the work until the test runs it.
 */
public class JobCoordinatorTest {

    private final List<Runnable> workers = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private boolean otherWorkRunning;
    private final JobCoordinator coordinator = new JobCoordinator(workers::add, Runnable::run, () -> otherWorkRunning);

    {
        coordinator.setListener(new JobCoordinator.Listener() {
            @Override
            public void jobStarted(JobHandle handle) {
                calls.add("started " + handle.id());
            }

            @Override
            public void jobProgress(JobHandle handle, int percent, String message) {
                calls.add("progress " + handle.id() + " " + percent + " " + message);
            }

            @Override
            public void jobEnded(JobHandle handle) {
                calls.add("ended " + handle.id());
            }
        });
    }

    @Test
    public void aJobLocksTheWindowBeforeItsWorkerRunsAndASecondJobIsRefused() {
        Object session = new Object();
        JobCoordinator.Admission first = coordinator.start(JobKind.DETECTION, session, context -> JobOutcome.completed("report"), record());
        assertTrue(first.admitted());
        assertSame(session, first.handle().session());
        assertEquals(List.of("started 1"), calls);
        assertEquals(1, workers.size());

        JobCoordinator.Admission second = coordinator.start(JobKind.STAR_MASK_STACK, session, context -> JobOutcome.completed("stack"), record());
        assertFalse(second.admitted());
        assertEquals("Detect Moving Targets is still running.", second.refusal());
        assertEquals(1, workers.size());

        workers.remove(0).run();
        assertEquals(List.of("started 1", "ended 1", "outcome 1 Completed[result=report]"), calls);
        assertNull(coordinator.current());
        assertTrue(coordinator.start(JobKind.STAR_MASK_STACK, session, context -> JobOutcome.completed("stack"), record()).admitted());
    }

    @Test
    public void noJobStartsWhileOtherWorkHoldsTheWindow() {
        otherWorkRunning = true;
        JobCoordinator.Admission admission = coordinator.start(JobKind.DETECTION, null, context -> JobOutcome.completed("report"), record());
        assertFalse(admission.admitted());
        assertEquals("Wait until the current task has finished.", admission.refusal());
        assertTrue(calls.isEmpty());
        assertTrue(workers.isEmpty());
    }

    @Test
    public void anExceptionOrAnErrorEndsTheJobAsFailedAndUnlocksTheWindow() {
        IOException diskFull = new IOException("disk full");
        coordinator.<String>start(JobKind.DETECTION, null, context -> {
            throw diskFull;
        }, (handle, outcome) -> {
            JobOutcome.Failed<String> failed = (JobOutcome.Failed<String>) outcome;
            calls.add("failed " + failed.reason());
            assertSame(diskFull, failed.cause());
        });
        workers.remove(0).run();
        coordinator.start(JobKind.DETECTION, null, context -> {
            throw new StackOverflowError();
        }, record());
        workers.remove(0).run();
        assertEquals(List.of("started 1", "ended 1", "failed disk full",
                "started 2", "ended 2", "outcome 2 Failed[reason=StackOverflowError, cause=java.lang.StackOverflowError]"), calls);
        assertNull(coordinator.current());
    }

    @Test
    public void runningOutOfMemoryLeavesTheWorkerThread() {
        coordinator.start(JobKind.DETECTION, null, context -> {
            throw new OutOfMemoryError("Java heap space");
        }, record());
        assertThrows(OutOfMemoryError.class, () -> workers.remove(0).run());
        assertEquals(List.of("started 1"), calls);
    }

    @Test
    public void progressReachesTheWindowOnlyWhileTheJobRuns() {
        AtomicReference<JobContext> kept = new AtomicReference<>();
        coordinator.start(JobKind.TRANSIENT_INSPECTION, null, context -> {
            kept.set(context);
            context.progress(40, "Loading frame 2 of 5...");
            return JobOutcome.declined("No.");
        }, record());
        workers.remove(0).run();
        kept.get().progress(90, "Late");
        assertEquals(List.of("started 1", "progress 1 40 Loading frame 2 of 5...", "ended 1", "outcome 1 Declined[reason=No.]"), calls);
        assertEquals(1, kept.get().handle().id());
    }

    @Test
    public void aJobWithoutAResultOrWithoutAWorkerFailsAndUnlocksTheWindow() {
        coordinator.start(JobKind.DETECTION, null, context -> null, record());
        workers.remove(0).run();
        JobCoordinator rejecting = new JobCoordinator(command -> {
            throw new RejectedExecutionException("no threads left");
        }, Runnable::run, () -> false);
        List<String> rejected = new ArrayList<>();
        assertTrue(rejecting.start(JobKind.DETECTION, null, context -> JobOutcome.completed("report"),
                (handle, outcome) -> rejected.add(((JobOutcome.Failed<String>) outcome).reason())).admitted());
        assertEquals(List.of("started 1", "ended 1", "outcome 1 Failed[reason=The job ended without a result., cause=null]"), calls);
        assertEquals(List.of("The job could not start: no threads left"), rejected);
        assertNull(rejecting.current());
    }

    private JobCoordinator.OutcomeHandler<String> record() {
        return (handle, outcome) -> calls.add("outcome " + handle.id() + " " + outcome);
    }
}
