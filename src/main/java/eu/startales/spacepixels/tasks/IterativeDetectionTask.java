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
import eu.startales.spacepixels.util.IterativeDetectionRequest;
import eu.startales.spacepixels.util.IterativeDetectionPipelineService;
import eu.startales.spacepixels.util.RunPreviewScope;
import eu.startales.spacepixels.util.RunPreviewSink;

import java.io.File;

/** Runs the iterative passes and writes their reports; the result is the index page of the passes. */
public class IterativeDetectionTask implements Job<File> {
    private final IterativeDetectionRequest request;
    private final IterativeDetectionPipelineService.ReportPrompt reportPrompt;
    private final IterativeDetectionPipelineService pipeline = new IterativeDetectionPipelineService();
    /** Where the run's live-view pictures go; null makes none. */
    private RunPreviewSink previews;

    /** Creates noninteractive passes that decline individual reports above 50 classified detections. */
    public IterativeDetectionTask(IterativeDetectionRequest request) {
        this(request, (summary, files, settings) -> summary.totalDetections <= 50);
    }

    /** Creates independent passes with an explicit report decision; null exports without asking. */
    public IterativeDetectionTask(IterativeDetectionRequest request,
                                  IterativeDetectionPipelineService.ReportPrompt reportPrompt) {
        this.request = java.util.Objects.requireNonNull(request);
        this.reportPrompt = reportPrompt;
    }

    public File getIndexFile() { return pipeline.getIndexFile(); }

    /** Sends pictures of the work under way to {@code previews}, for the progress dialog's live view. */
    public IterativeDetectionTask showingPreviews(RunPreviewSink previews) {
        this.previews = previews;
        return this;
    }

    @Override
    public JobOutcome<File> run(JobContext context) throws Exception {
        try (CancellationScope cancellation = CancellationScope.open(context::isCancelled);
             RunPreviewScope preview = previews == null ? null : RunPreviewScope.open(previews)) {
            CancellationScope.check();
            return runCancellable(context);
        }
    }

    private JobOutcome<File> runCancellable(JobContext context) throws Exception {
        return JobOutcome.completed(pipeline.run(request, reportPrompt, context::progress));
    }
}
