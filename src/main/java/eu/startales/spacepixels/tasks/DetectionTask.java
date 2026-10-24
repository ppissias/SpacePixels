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

import eu.startales.spacepixels.config.DetectionRunOptions;
import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import io.github.ppissias.jtransient.config.DetectionConfigRules;
import io.github.ppissias.jtransient.execution.CancellationScope;

import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.RunPreviewScope;
import eu.startales.spacepixels.util.RunPreviewSink;
import eu.startales.spacepixels.util.SkySnapshot;
import io.github.ppissias.jtransient.config.DetectionConfig;

import java.io.File;

/** Runs the detection on the whole session and writes the report; the result is the report file. */
public class DetectionTask implements Job<File> {
    private final ImageProcessing preProcessing;

    // Hold the configuration
    private final DetectionConfig config;
    private final DetectionRunOptions options;
    private final SkySnapshot sky;
    private final ImageProcessing.DetectionSafetyPrompt safetyPrompt;
    /** Where the run's live-view pictures go; null makes none. */
    private RunPreviewSink previews;

    /**
     * Creates a noninteractive job with the default Auto-Tune frame limit that declines report export above 50
     * classified detections.
     */
    public DetectionTask(ImageProcessing preProcessing, DetectionConfig config) {
        this(preProcessing, config, VisualizationSettings.defaults(), SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES, null,
                summary -> summary.totalDetections <= 50);
    }

    /**
     * Captures a job's settings, application options, visualization settings, Auto-Tune frame limit, the session's
     * sky data and report decision when the job is created; a null {@code sky} reads the frames' folder. A missing
     * processor retains the existing execution-time failure.
     */
    public DetectionTask(ImageProcessing preProcessing, DetectionConfig config, VisualizationSettings visualization,
                         int autoTuneMaxCandidateFrames, SkySnapshot sky,
                         ImageProcessing.DetectionSafetyPrompt safetyPrompt) {
        this.preProcessing = preProcessing;
        this.config = DetectionConfigRules.snapshot(config,
                DetectionConfigRules.Operation.CONFIGURATION);
        this.options = preProcessing == null
                ? DetectionRunOptions.defaults().withAutoTuneMaxCandidateFrames(autoTuneMaxCandidateFrames)
                        .withVisualization(visualization)
                : preProcessing.captureRunOptions(visualization, autoTuneMaxCandidateFrames);
        this.sky = sky;
        this.safetyPrompt = safetyPrompt;
    }

    /** Sends pictures of the work under way to {@code previews}, for the progress dialog's live view. */
    public DetectionTask showingPreviews(RunPreviewSink previews) {
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
        File exportDir = preProcessing.detectObjects(config, safetyPrompt, context::progress, options, sky);
        if (exportDir == null) {
            return JobOutcome.declined("Report generation aborted by user due to high track count. Try raising your thresholds.");
        }
        context.progress(100, "Batch detection complete!");
        return JobOutcome.completed(exportDir);
    }
}
