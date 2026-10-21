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
import io.github.ppissias.jtransient.config.DetectionConfig;

import java.io.File;

/** Runs the iterative passes and writes their reports; the result is the index page of the passes. */
public class IterativeDetectionTask implements Job<File> {
    private final ImageProcessing preProcessing;
    private final DetectionConfig config;
    private final int maxFrames;

    /** @param maxFrames the most frames a pass uses, or 0 for all the frames of the session */
    public IterativeDetectionTask(ImageProcessing preProcessing, DetectionConfig config, int maxFrames) {
        this.preProcessing = preProcessing;
        this.config = config;
        this.maxFrames = maxFrames;
    }

    @Override
    public JobOutcome<File> run(JobContext context) throws Exception {
        try (CancellationScope cancellation = CancellationScope.open(context::isCancelled)) {
            CancellationScope.check();
            return runCancellable(context);
        }
    }

    private JobOutcome<File> runCancellable(JobContext context) throws Exception {
        // The limit is higher than a single pass because an iterative run aggregates detections.
        ImageProcessing.DetectionSafetyPrompt safetyPrompt = (summary) ->
                summary.confirmReport(preProcessing.hasPlateSolvedFrame(), 100, "Iterative pass", config);

        File masterDir = preProcessing.detectSlowObjectsIterative(config, safetyPrompt, context::progress, maxFrames);
        if (masterDir == null) {
            return JobOutcome.declined("Iterative run aborted by user.");
        }
        return JobOutcome.completed(masterDir);
    }
}
