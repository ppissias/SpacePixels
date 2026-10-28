package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.preparation.FramePreparationService;
import eu.startales.spacepixels.preparation.PreparationRequest;

/** Adapts an immutable preparation request to the application's single cancellable job slot, without Swing. */
public final class FramePreparationTask implements Job<FramePreparationService.Result> {
    private final PreparationRequest request;
    private final java.util.concurrent.atomic.AtomicBoolean stopping = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.CompletableFuture<Void> finished = new java.util.concurrent.CompletableFuture<>();

    /** Captures the complete selected recipe before the worker starts. */
    public FramePreparationTask(PreparationRequest request) { this.request = request; }

    /** Runs external preparation and validates output before delivering one completed product. */
    @Override public JobOutcome<FramePreparationService.Result> run(JobContext context) throws Exception {
        try {
            return JobOutcome.completed(new FramePreparationService().prepare(request,
                    () -> stopping.get() || context.isCancelled(), context::progress));
        } finally { finished.complete(null); }
    }

    /** Requests stop independently of a queued Swing outcome, including while the application is shutting down. */
    public void cancel() { stopping.set(true); }

    /** Called only by a shutdown worker; waits for subprocess cleanup before the application can exit normally. */
    public void awaitCleanup() throws Exception { finished.get(30, java.util.concurrent.TimeUnit.SECONDS); }
}
