package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobCoordinator;
import eu.startales.spacepixels.jobs.JobHandle;
import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import io.github.ppissias.jplatesolve.PlateSolveOperation;
import io.github.ppissias.jplatesolve.PlateSolveResult;
import org.junit.Test;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class PlateSolveTaskTest {
    private static final PlateSolveResult SOLVED = new PlateSolveResult(true, null, null, Map.of());

    @Test
    public void cancellingPlateSolveKeepsAdmissionLockedUntilCleanupFinishes() throws Exception {
        var solvers = Executors.newSingleThreadExecutor();
        var workers = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<JobOutcome<PlateSolveResult>> result = new AtomicReference<>();
        JobCoordinator coordinator = new JobCoordinator(workers, Runnable::run, () -> false);
        try {
            PlateSolveOperation operation = PlateSolveOperation.start(Duration.ofSeconds(10), null, solvers, context -> {
                context.onCleanup(outcome -> { cleaning.countDown(); release.await(); });
                started.countDown();
                Thread.sleep(20000);
                return SOLVED;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(coordinator.start(JobKind.PLATE_SOLVE, null,
                    context -> PlateSolveTask.awaitSolve(operation, context, new AtomicReference<>()),
                    (handle, outcome) -> { result.set(outcome); finished.countDown(); }).admitted());
            assertTrue(coordinator.cancel());
            assertTrue(cleaning.await(2, TimeUnit.SECONDS));
            assertNotNull(coordinator.current());
            assertFalse(operation.isDone());
            assertFalse(coordinator.start(JobKind.DETECTION, null, context -> JobOutcome.completed("other"),
                    (handle, outcome) -> { }).admitted());
            release.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertTrue(String.valueOf(result.get()), result.get() instanceof JobOutcome.Cancelled<?>);
            assertNull(coordinator.current());
        } finally {
            release.countDown();
            workers.shutdownNow();
            solvers.shutdownNow();
        }
    }

    @Test
    public void interruptingTheWaitingTaskAlsoJoinsCleanupAndRestoresInterrupt() throws Exception {
        var solvers = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cleaned = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<JobOutcome<PlateSolveResult>> outcome = new AtomicReference<>();
        try {
            PlateSolveOperation operation = PlateSolveOperation.start(Duration.ofSeconds(10), null, solvers, context -> {
                context.onCleanup(result -> cleaned.countDown());
                started.countDown();
                Thread.sleep(20000);
                return SOLVED;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Thread waiting = new Thread(() -> {
                try {
                    outcome.set(PlateSolveTask.awaitSolve(operation, context(), new AtomicReference<>()));
                    interrupted.set(Thread.currentThread().isInterrupted());
                } catch (Throwable problem) { failure.set(problem); }
            });
            waiting.start();
            waiting.interrupt();
            waiting.join(3000);
            assertFalse(waiting.isAlive());
            assertNull(failure.get());
            assertEquals(0, cleaned.getCount());
            assertTrue(interrupted.get());
            assertTrue(outcome.get() instanceof JobOutcome.Cancelled<?>);
        } finally {
            solvers.shutdownNow();
        }
    }

    @Test
    public void aSolverAnswerStillCarriesSuccessOrRejection() throws Exception {
        for (boolean success : new boolean[]{true, false}) {
            PlateSolveResult answer = new PlateSolveResult(success, success ? null : "No solution", null, Map.of());
            PlateSolveOperation operation = PlateSolveOperation.start(Duration.ofSeconds(5), null, Runnable::run, context -> answer);
            var outcome = PlateSolveTask.awaitSolve(operation, context(), new AtomicReference<>());
            assertTrue(outcome instanceof JobOutcome.Completed<PlateSolveResult> completed && completed.result() == answer);
        }
    }

    @Test
    public void cleanupFailureFailsTheJobInsteadOfClaimingItStoppedCleanly() {
        PlateSolveOperation operation = PlateSolveOperation.start(Duration.ofSeconds(5), null, Runnable::run, context -> {
            context.onCleanup(outcome -> { throw new IOException("ASTAP still alive"); });
            return SOLVED;
        });
        assertThrows(PlateSolveOperation.CleanupException.class,
                () -> PlateSolveTask.awaitSolve(operation, context(), new AtomicReference<>()));
    }

    private static JobContext context() {
        return new JobContext() {
            @Override public JobHandle handle() { return null; }
            @Override public boolean isCancelled() { return false; }
            @Override public void progress(int percent, String message) { }
        };
    }
}
