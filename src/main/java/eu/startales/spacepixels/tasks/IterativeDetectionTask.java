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

import io.github.ppissias.jtransient.execution.CancellationScope;

import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.IterativeDetectionRequest;
import eu.startales.spacepixels.util.IterativeDetectionPipelineService;
import io.github.ppissias.jtransient.config.DetectionConfig;

import java.io.File;

/** Runs the iterative passes and writes their reports; the result is the index page of the passes. */
public class IterativeDetectionTask implements Job<File> {
    private final ImageProcessing preProcessing;
    private final DetectionConfig config;
    private final int maxFrames;
    private final IterativeDetectionRequest request;
    private final IterativeDetectionPipelineService pipeline = new IterativeDetectionPipelineService();

    public IterativeDetectionTask(IterativeDetectionRequest request) {
        this.request = java.util.Objects.requireNonNull(request);
        this.preProcessing = null;
        this.config = request.config();
        this.maxFrames = request.plan().maximumFrames();
    }

    public File getIndexFile() { return pipeline.getIndexFile(); }

    /** @param maxFrames the most frames a pass uses, or 0 for all the frames of the session */
    public IterativeDetectionTask(ImageProcessing preProcessing, DetectionConfig config, int maxFrames) {
        this.preProcessing = preProcessing;
        this.config = config;
        this.maxFrames = maxFrames;
        this.request = null;
    }

    @Override
    public JobOutcome<File> run(JobContext context) throws Exception {
        try (CancellationScope cancellation = CancellationScope.open(context::isCancelled)) {
            CancellationScope.check();
            return runCancellable(context);
        }
    }

    private JobOutcome<File> runCancellable(JobContext context) throws Exception {
        if (request != null) {
            File index = pipeline.run(request, (summary, files, settings) -> summary.confirmReport(
                    java.util.Arrays.stream(files).anyMatch(FitsFileInformation::isWcsSolved), 50,
                    "Iterative pass: " + files.length + " frames", settings), context::progress);
            return JobOutcome.completed(index);
        }
        ImageProcessing.DetectionSafetyPrompt safetyPrompt = (summary) ->
                summary.confirmReport(preProcessing.hasPlateSolvedFrame(), 50, "Iterative pass", config);

        File masterDir = preProcessing.detectSlowObjectsIterative(config, safetyPrompt, context::progress, maxFrames);
        if (masterDir == null) {
            return JobOutcome.declined("Iterative run aborted by user.");
        }
        return JobOutcome.completed(masterDir);
    }
}
