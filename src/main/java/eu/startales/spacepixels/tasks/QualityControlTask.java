package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.detection.QualityControlService;
import eu.startales.spacepixels.detection.QualityControlSnapshot;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobOutcome;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.execution.CancellationScope;

/** Cancellable, all-frame quality assessment; captures its cohort and configuration at admission. */
public final class QualityControlTask implements Job<QualityControlSnapshot> {
    private final FitsFileInformation[] files;
    private final DetectionConfig config;

    /** Owns a copy of the file container and a detached settings snapshot; metadata elements are immutable. */
    public QualityControlTask(FitsFileInformation[] files, DetectionConfig config) {
        this.files = files.clone();
        this.config = config.clone();
    }

    /** Runs on the job worker; no partial result is delivered after cancellation or failure. */
    @Override public JobOutcome<QualityControlSnapshot> run(JobContext context) throws Exception {
        try (var cancellation = CancellationScope.open(context::isCancelled)) {
            CancellationScope.check();
            var result = new QualityControlService().evaluate(files, config, context::progress);
            CancellationScope.check();
            return JobOutcome.completed(result);
        }
    }
}
