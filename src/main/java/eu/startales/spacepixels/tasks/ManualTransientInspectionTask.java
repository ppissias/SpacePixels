package eu.startales.spacepixels.tasks;

import io.github.ppissias.jtransient.execution.CancellationScope;

import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.FrameTransients;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

import java.util.List;

/** Runs the engine on all frames of the session, for the inspection window that the window opens with the result. */
public class ManualTransientInspectionTask implements Job<ManualTransientInspectionTask.Result> {

    /** The frames, the transients left in each and the files they came from. */
    public record Result(List<ImageFrame> frames, List<FrameTransients> transients, FitsFileInformation[] files) {
    }

    private final ImageProcessing preProcessing;
    private final DetectionConfig config;

    /**
     * The engine gets a copy of the settings taken at the click, so nothing a run changes can reach the settings panel
     * or the saved profile, and later edits don't affect the running inspection.
     */
    public ManualTransientInspectionTask(ImageProcessing preProcessing, DetectionConfig config) {
        this.preProcessing = preProcessing;
        this.config = config.clone();
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
        try {
            FitsFileInformation[] filesInfo = preProcessing.getImportedFiles();
            List<ImageFrame> framesForLibrary = SessionFrameLoader.loadAll(filesInfo, context::progress, 20);

            TransientEngineProgressListener progressListener =
                    (percentage, message) -> context.progress(20 + (int) (percentage * 0.8), message);

            List<FrameTransients> cleanTransients = engine.detectTransients(framesForLibrary, config, progressListener);
            return JobOutcome.completed(new Result(framesForLibrary, cleanTransients, filesInfo));
        } finally {
            engine.shutdown();
        }
    }
}
