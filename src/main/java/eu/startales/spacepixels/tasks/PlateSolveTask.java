/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */

package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.util.ImageProcessing;
import io.github.ppissias.jplatesolve.PlateSolveResult;

import io.github.ppissias.jplatesolve.PlateSolveOperation;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Solves one frame with ASTAP or Astrometry.net. The result is the solver's answer, which says itself whether the
 * frame was solved; an error before the solver answers fails the job with its reason.
 */
public class PlateSolveTask implements Job<PlateSolveResult> {
    private final ImageProcessing preProcessing;
    private final String filePath;
    private final boolean useAstap;
    private final boolean useAstrometryNet;

    public PlateSolveTask(ImageProcessing preProcessing, String filePath, boolean useAstap, boolean useAstrometryNet) {
        this.preProcessing = preProcessing;
        this.filePath = filePath;
        this.useAstap = useAstap;
        this.useAstrometryNet = useAstrometryNet;
    }

    @Override
    public JobOutcome<PlateSolveResult> run(JobContext context) throws Exception {
        if (context.isCancelled()) return JobOutcome.cancelled();
        AtomicReference<PlateSolveOperation.Progress> progress = new AtomicReference<>();
        PlateSolveOperation operation = preProcessing.startSolve(filePath, useAstap, useAstrometryNet, progress::set);
        if (useAstap || !useAstrometryNet) {
            return awaitSolve(operation, context, progress);
        }
        try {
            return awaitSolve(operation, context, progress);
        } catch (Exception failure) {
            // A lost connection often has no message, and a refused login depends on the account: say which.
            throw new IOException(preProcessing.astrometryNetFailure(failure), failure);
        }
    }

    static JobOutcome<PlateSolveResult> awaitSolve(PlateSolveOperation operation, JobContext context,
                                                  AtomicReference<PlateSolveOperation.Progress> progress) throws Exception {
        var completion = operation.completion().toCompletableFuture();
        boolean interrupted = false;
        Throwable waitingFailure = null;
        long started = System.nanoTime();
        long lastProgress = 0;
        try {
            while (!completion.isDone() && waitingFailure == null && !interrupted) {
                try {
                    if (context.isCancelled()) operation.cancel();
                    long elapsed = System.nanoTime() - started;
                    if (elapsed - lastProgress >= TimeUnit.MILLISECONDS.toNanos(500)) {
                        PlateSolveOperation.Progress latest = progress.get();
                        String phase = latest == null ? "Preparing plate solve" : latest.message();
                        context.progress(0, phase + " (" + TimeUnit.NANOSECONDS.toSeconds(elapsed) + " s)");
                        lastProgress = elapsed;
                    }
                    completion.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException polling) {
                } catch (InterruptedException interruption) {
                    interrupted = true;
                    operation.cancel();
                } catch (Throwable failure) {
                    waitingFailure = failure;
                    operation.cancel();
                }
            }
            PlateSolveOperation.Outcome outcome;
            while (true) {
                try {
                    outcome = completion.get();
                    break;
                } catch (InterruptedException interruption) {
                    interrupted = true;
                    operation.cancel();
                } catch (ExecutionException failure) {
                    throw new IllegalStateException("Solve completion failed without an outcome.", failure.getCause());
                }
            }
            if (waitingFailure instanceof Error error) throw error;
            if (outcome.status() == PlateSolveOperation.Status.FAILED
                    || outcome.status() == PlateSolveOperation.Status.TIMED_OUT) {
                Throwable cause = outcome.cause();
                if (cause instanceof Error error) throw error;
                if (cause instanceof Exception exception) throw exception;
                throw new IllegalStateException("Plate solve failed.", cause);
            }
            if (waitingFailure != null && !(waitingFailure instanceof CancellationException)) {
                if (waitingFailure instanceof Exception exception) throw exception;
                throw new IllegalStateException(waitingFailure);
            }
            return switch (outcome.status()) {
                case SUCCEEDED, REJECTED -> JobOutcome.completed(outcome.result());
                case CANCELLED -> JobOutcome.cancelled();
                default -> throw new IllegalStateException("Unexpected solve outcome: " + outcome.status());
            };
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
