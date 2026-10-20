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

import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.util.ImageProcessing;
import io.github.ppissias.jtransient.config.DetectionConfig;

import java.io.File;

/** Runs the detection on the whole session and writes the report; the result is the report file. */
public class DetectionTask implements Job<File> {
    private final ImageProcessing preProcessing;

    // Hold the configuration
    private final DetectionConfig config;

    public DetectionTask(ImageProcessing preProcessing, DetectionConfig config) {
        this.preProcessing = preProcessing;
        this.config = config;
    }

    @Override
    public JobOutcome<File> run(JobContext context) throws Exception {
        // Asks before writing a report for very many detections.
        ImageProcessing.DetectionSafetyPrompt safetyPrompt = (summary) ->
                summary.confirmReport(preProcessing.hasPlateSolvedFrame(), 50, null, config);

        File exportDir = preProcessing.detectObjects(config, safetyPrompt, context::progress);
        if (exportDir == null) {
            return JobOutcome.declined("Report generation aborted by user due to high track count. Try raising your thresholds.");
        }
        context.progress(100, "Batch detection complete!");
        return JobOutcome.completed(exportDir);
    }
}
