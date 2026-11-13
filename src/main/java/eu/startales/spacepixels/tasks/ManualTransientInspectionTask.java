package eu.startales.spacepixels.tasks;
import eu.startales.spacepixels.util.DebugOutput;

import io.github.ppissias.jtransient.execution.CancellationScope;

import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.fits.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.FrameTransients;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import io.github.ppissias.jtransient.engine.TransientInspection;
import io.github.ppissias.jtransient.engine.TransientReference;

import java.util.List;
import java.util.Objects;

/** Runs the engine on all frames of the session, for the inspection window that the window opens with the result. */
public class ManualTransientInspectionTask implements Job<ManualTransientInspectionTask.Result> {

    /** The frames, the transients left in each and the files they came from. */
    public record Result(List<ImageFrame> frames, List<FrameTransients> transients, FitsFileInformation[] files,
                         TransientReference reference) {
    }

    private final FitsFileInformation[] files;
    private final DetectionConfig config;
    private final List<io.github.ppissias.jtransient.engine.ExcludedArea> excludedAreas;

    /**
     * Copies the frame array and settings at admission; metadata elements stay shared for the existing WCS contract.
     * Later array/configuration edits do not affect this inspection. Missing frames fail during execution.
     */
    public ManualTransientInspectionTask(FitsFileInformation[] files, DetectionConfig config) {
        this(files, config, List.of());
    }

    /** An inspection that leaves the session's excluded areas out, as detection does; they are captured at admission. */
    public ManualTransientInspectionTask(FitsFileInformation[] files, DetectionConfig config,
                                         List<io.github.ppissias.jtransient.engine.ExcludedArea> excludedAreas) {
        this.files = files == null ? null : files.clone();
        this.config = config.clone();
        this.excludedAreas = List.copyOf(excludedAreas);
    }

    @Override
    public JobOutcome<Result> run(JobContext context) throws Exception {
        try (CancellationScope cancellation = CancellationScope.open(context::isCancelled)) {
            CancellationScope.check();
            return runCancellable(context);
        }
    }

    private JobOutcome<Result> runCancellable(JobContext context) throws Exception {
        JTransientEngine engine = new JTransientEngine();
        try (var diagnostics = DebugOutput.openEngineDiagnostics()) {
            FitsFileInformation[] filesInfo = Objects.requireNonNull(files, "frames");
            List<ImageFrame> framesForLibrary = SessionFrameLoader.loadAll(filesInfo, context::progress, 20);

            TransientEngineProgressListener progressListener =
                    (percentage, message) -> context.progress(20 + (int) (percentage * 0.8), message);

            TransientInspection inspection = engine.inspectTransients(framesForLibrary, config, progressListener,
                    excludedAreas);
            return JobOutcome.completed(new Result(framesForLibrary, inspection.frames(), filesInfo, inspection.reference()));
        } finally {
            engine.shutdown();
        }
    }
}
