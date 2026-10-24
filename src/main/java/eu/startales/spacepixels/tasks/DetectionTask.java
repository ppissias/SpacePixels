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
import io.github.ppissias.jtransient.config.DetectionConfigRules;
import io.github.ppissias.jtransient.execution.CancellationScope;

import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.util.DetectionOverlays;
import eu.startales.spacepixels.util.DetectionSafetyPrompt;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.RunPreviewScope;
import eu.startales.spacepixels.util.RunPreviewSink;
import eu.startales.spacepixels.util.SkySnapshot;
import eu.startales.spacepixels.util.StandardDetectionPipelineService;
import io.github.ppissias.jtransient.config.DetectionConfig;

import java.io.File;
import java.util.Objects;

/**
 * Runs the detection on the whole session and writes the report; the result is the report file. The run's overlays
 * are kept apart from that result ({@link #detectedOverlays()}), so they outlast a declined report and a failed or
 * cancelled export.
 */
public class DetectionTask implements Job<File> {
    private final FitsFileInformation[] files;

    // Hold the configuration
    private final DetectionConfig config;
    private final DetectionRunOptions options;
    private final SkySnapshot sky;
    private final DetectionSafetyPrompt safetyPrompt;
    /** Where the run's live-view pictures go; null makes none. */
    private RunPreviewSink previews;
    /** Set on the worker as soon as the detection finishes; null until then. */
    private volatile DetectionOverlays detected;

    /**
     * Creates a noninteractive job with the default Auto-Tune frame limit that declines report export above 50
     * classified detections.
     */
    public DetectionTask(FitsFileInformation[] files, DetectionConfig config) {
        this(files, config, DetectionRunOptions.defaults(), null, summary -> summary.totalDetections <= 50);
    }

    /**
     * Copies the admitted frame array and settings, keeping shared metadata elements and immutable run options.
     * A null {@code sky} reads the frames' folder once at run start. Missing frames fail during execution.
     */
    public DetectionTask(FitsFileInformation[] files, DetectionConfig config, DetectionRunOptions options,
                         SkySnapshot sky, DetectionSafetyPrompt safetyPrompt) {
        this.files = files == null ? null : files.clone();
        this.config = DetectionConfigRules.snapshot(config,
                DetectionConfigRules.Operation.CONFIGURATION);
        this.options = Objects.requireNonNull(options);
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

    /**
     * The overlays of this job's detection, once it has finished; null when the job ended before that (cancelled or
     * failed during the detection). The window reads them when the job ends, whatever its outcome.
     */
    public DetectionOverlays detectedOverlays() {
        return detected;
    }

    private JobOutcome<File> runCancellable(JobContext context) throws Exception {
        File exportDir = new StandardDetectionPipelineService().run(config, Objects.requireNonNull(files, "frames"), safetyPrompt,
                context::progress, null, options, sky, overlays -> detected = overlays).report();
        if (exportDir == null) {
            return JobOutcome.declined("Report generation aborted by user due to high track count. Try raising your thresholds.");
        }
        context.progress(100, "Batch detection complete!");
        return JobOutcome.completed(exportDir);
    }
}
