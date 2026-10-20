/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.jobs;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Admits, starts and ends the long operations of the window, one at a time. A job is admitted on the UI thread before
 * its worker starts, so the window is locked from the click on. Its progress and its outcome come back on the UI
 * thread: the outcome exactly once, after the window is unlocked, and progress only while the job is still current.
 * <p>
 * {@link #start}, {@link #setListener} and {@link #current} are called on the UI thread; the {@link JobContext} of a
 * running job is used from its worker.
 */
public final class JobCoordinator {

    /** The window's side: locks and shows progress while a job runs. */
    public interface Listener {
        void jobStarted(JobHandle handle);

        void jobProgress(JobHandle handle, int percent, String message);

        void jobEnded(JobHandle handle);
    }

    /** Receives the outcome of one job, on the UI thread, after {@link Listener#jobEnded}. */
    @FunctionalInterface
    public interface OutcomeHandler<R> {
        void handle(JobHandle handle, JobOutcome<R> outcome);
    }

    /** The handle of an admitted job, or the reason a job was not admitted. */
    public record Admission(JobHandle handle, String refusal) {
        public boolean admitted() {
            return handle != null;
        }
    }

    private static final Logger LOGGER = Logger.getLogger(JobCoordinator.class.getName());

    private final Executor workers;
    private final Executor ui;
    private final BooleanSupplier otherWorkRunning;
    private volatile Listener listener;
    private volatile JobHandle current;
    private long nextId = 1;

    /**
     * @param workers          runs each job's work on a thread of its own
     * @param ui               runs code on the UI thread
     * @param otherWorkRunning whether work outside the coordinator holds the window, such as a job still on the event bus
     */
    public JobCoordinator(Executor workers, Executor ui, BooleanSupplier otherWorkRunning) {
        this.workers = Objects.requireNonNull(workers, "workers");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.otherWorkRunning = Objects.requireNonNull(otherWorkRunning, "otherWorkRunning");
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** The job that is running, or null. */
    public JobHandle current() {
        return current;
    }

    /**
     * Admits the job and starts it, or says why it cannot start now: another job is running, or other work holds the
     * window. An admitted job has locked the window when this returns; {@code handler} receives its outcome later.
     *
     * @param session the session the job is for, kept in its handle
     */
    public <R> Admission start(JobKind kind, Object session, Job<R> job, OutcomeHandler<R> handler) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(handler, "handler");
        JobHandle running = current;
        if (running != null) {
            return new Admission(null, running.kind().title() + " is still running.");
        }
        if (otherWorkRunning.getAsBoolean()) {
            return new Admission(null, "Wait until the current task has finished.");
        }
        JobHandle handle = new JobHandle(nextId++, kind, session);
        current = handle;
        if (listener != null) {
            listener.jobStarted(handle);
        }
        try {
            workers.execute(() -> work(handle, job, handler));
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, kind.title() + " could not start", e);
            end(handle, JobOutcome.failed("The job could not start: " + JobFailures.message(e), e), handler);
        }
        return new Admission(handle, null);
    }

    private <R> void work(JobHandle handle, Job<R> job, OutcomeHandler<R> handler) {
        JobOutcome<R> outcome;
        try {
            outcome = job.run(new Context(handle));
            if (outcome == null) {
                outcome = JobOutcome.failed("The job ended without a result.", null);
            }
        } catch (Throwable t) {
            // Out of memory leaves the thread for the application's handler, which closes SpacePixels with a message.
            String reason = JobFailures.rethrowOutOfMemory(t);
            LOGGER.log(Level.SEVERE, handle.kind().title() + " failed", t);
            outcome = JobOutcome.failed(reason, t);
        }
        JobOutcome<R> ended = outcome;
        ui.execute(() -> end(handle, ended, handler));
    }

    private <R> void end(JobHandle handle, JobOutcome<R> outcome, OutcomeHandler<R> handler) {
        if (current != handle) {
            return;
        }
        current = null;
        try {
            if (listener != null) {
                listener.jobEnded(handle);
            }
        } finally {
            handler.handle(handle, outcome);
        }
    }

    private final class Context implements JobContext {
        private final JobHandle handle;

        private Context(JobHandle handle) {
            this.handle = handle;
        }

        @Override
        public JobHandle handle() {
            return handle;
        }

        @Override
        public void progress(int percent, String message) {
            ui.execute(() -> {
                Listener target = listener;
                if (current == handle && target != null) {
                    target.jobProgress(handle, percent, message);
                }
            });
        }
    }
}
