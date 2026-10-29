package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.preparation.FramePreparationService;
import eu.startales.spacepixels.preparation.PreparationRequest;

/** Adapts an immutable preparation request to the application's single cancellable job slot, without Swing. */
public final class FramePreparationTask implements Job<FramePreparationService.Result> {
    private final PreparationRequest request;
    private final eu.startales.spacepixels.fits.ImageScaling.Decision scalingDecision;
    private final java.util.concurrent.atomic.AtomicBoolean stopping = new java.util.concurrent.atomic.AtomicBoolean();

    /** Captures the complete selected recipe before the worker starts. */
    public FramePreparationTask(PreparationRequest request) { this(request, null); }

    /** Captures the worker's outlier decision boundary without accessing GUI controls or live settings. */
    public FramePreparationTask(PreparationRequest request, eu.startales.spacepixels.fits.ImageScaling.Decision scalingDecision) {
        this.request = request; this.scalingDecision = scalingDecision;
    }

    /** Runs external preparation and validates output before delivering one completed product. */
    @Override public JobOutcome<FramePreparationService.Result> run(JobContext context) throws Exception {
        try {
            return JobOutcome.completed(new FramePreparationService().prepare(request,
                    () -> stopping.get() || context.isCancelled(), context::progress, context::details, scalingDecision));
        } catch (java.util.concurrent.CancellationException cancelled) {
            return JobOutcome.cancelled();
        }
    }

    /** Requests stop independently of a queued Swing outcome, including while the application is shutting down. */
    public void cancel() { stopping.set(true); }
}
