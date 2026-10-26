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
import eu.startales.spacepixels.detection.IterativeDetectionRequest;
import eu.startales.spacepixels.detection.IterativeDetectionPipelineService;
import eu.startales.spacepixels.detection.RunPreviewScope;
import eu.startales.spacepixels.detection.RunPreviewSink;
import eu.startales.spacepixels.detection.PreFlightSafetyPrompt;

import java.io.File;

/** Runs the iterative passes and writes their reports; the result is the index page of the passes. */
public class IterativeDetectionTask implements Job<File> {
    private final IterativeDetectionRequest request;
    private final IterativeDetectionPipelineService.ReportPrompt reportPrompt;
    private final PreFlightSafetyPrompt preFlight;
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
        this(request, reportPrompt, null);
    }

    /** Captures the desktop quality decision; a null callback retains noninteractive warning-only behavior. */
    public IterativeDetectionTask(IterativeDetectionRequest request,
                                  IterativeDetectionPipelineService.ReportPrompt reportPrompt,
                                  PreFlightSafetyPrompt preFlight) {
        this.request = java.util.Objects.requireNonNull(request);
        this.reportPrompt = reportPrompt;
        this.preFlight = preFlight;
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
        File report = pipeline.run(request, reportPrompt, context::progress, preFlight);
        return report == null ? JobOutcome.declined("Iterative detection declined after the dataset quality warning.")
                : JobOutcome.completed(report);
    }
}
