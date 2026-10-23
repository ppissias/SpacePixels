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
import eu.startales.spacepixels.util.IterativeDetectionRequest;
import eu.startales.spacepixels.util.IterativeDetectionPipelineService;
import eu.startales.spacepixels.util.RunPreviewScope;
import eu.startales.spacepixels.util.RunPreviewSink;
import io.github.ppissias.jtransient.config.DetectionConfig;

import java.io.File;

/** Runs the iterative passes and writes their reports; the result is the index page of the passes. */
public class IterativeDetectionTask implements Job<File> {
    private final ImageProcessing preProcessing;
    private final DetectionConfig config;
    private final int maxFrames;
    private final IterativeDetectionRequest request;
    private final IterativeDetectionPipelineService.ReportPrompt reportPrompt;
    private final ImageProcessing.DetectionSafetyPrompt safetyPrompt;
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
        this.preProcessing = null;
        this.config = request.config();
        this.maxFrames = request.plan().maximumFrames();
        this.reportPrompt = reportPrompt;
        this.safetyPrompt = null;
    }

    public File getIndexFile() { return pipeline.getIndexFile(); }

    /** Creates legacy noninteractive passes, declining reports above 50 detections; zero uses all frames. */
    public IterativeDetectionTask(ImageProcessing preProcessing, DetectionConfig config, int maxFrames) {
        this(preProcessing, config, maxFrames, summary -> summary.totalDetections <= 50);
    }

    /** Creates legacy session passes with an explicit report decision; zero uses all frames. */
    public IterativeDetectionTask(ImageProcessing preProcessing, DetectionConfig config, int maxFrames,
                                  ImageProcessing.DetectionSafetyPrompt safetyPrompt) {
        this.preProcessing = preProcessing;
        this.config = config;
        this.maxFrames = maxFrames;
        this.request = null;
        this.reportPrompt = null;
        this.safetyPrompt = safetyPrompt;
    }

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
        if (request != null) {
            File index = pipeline.run(request, reportPrompt, context::progress);
            return JobOutcome.completed(index);
        }
        File masterDir = preProcessing.detectSlowObjectsIterative(config, safetyPrompt, context::progress, maxFrames);
        if (masterDir == null) {
            return JobOutcome.declined("Iterative run aborted by user.");
        }
        return JobOutcome.completed(masterDir);
    }
}
