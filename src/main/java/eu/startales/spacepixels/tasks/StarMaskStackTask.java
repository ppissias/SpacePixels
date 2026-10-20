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
import eu.startales.spacepixels.util.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;

import java.util.List;

/**
 * Builds the session's master stack exactly as a detection run does (same drift check, quality settings and frame
 * selection, then the median), for the Star Mask Explorer. Runs under the job coordinator, with the same progress
 * display and window locking as Manual Transient Inspection.
 */
public class StarMaskStackTask implements Job<JTransientEngine.MasterStackResult> {

    private final FitsFileInformation[] filesInfo;
    private final DetectionConfig config;

    public StarMaskStackTask(FitsFileInformation[] filesInfo, DetectionConfig config) {
        this.filesInfo = filesInfo;
        this.config = config;
    }

    @Override
    public JobOutcome<JTransientEngine.MasterStackResult> run(JobContext context) throws Exception {
        JTransientEngine engine = new JTransientEngine();
        try {
            List<ImageFrame> frames = SessionFrameLoader.loadAll(filesInfo, context::progress, 30);
            JTransientEngine.MasterStackResult result = engine.generateMasterStackWithDetails(frames, config,
                    (percent, message) -> context.progress(30 + (int) (percent * 0.7), message));
            context.progress(100, "Master stack ready");
            return JobOutcome.completed(result);
        } finally {
            engine.shutdown();
        }
    }
}
