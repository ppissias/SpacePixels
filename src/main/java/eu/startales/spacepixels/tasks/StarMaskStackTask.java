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
import eu.startales.spacepixels.util.DebugOutput;

import io.github.ppissias.jtransient.execution.CancellationScope;

import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.fits.FitsFileInformation;
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
    private final List<io.github.ppissias.jtransient.engine.ExcludedArea> excludedAreas;

    /** A stack without excluded areas. */
    public StarMaskStackTask(FitsFileInformation[] filesInfo, DetectionConfig config) {
        this(filesInfo, config, List.of());
    }

    /** A stack that leaves the session's excluded areas out, as detection does; they are captured at admission. */
    public StarMaskStackTask(FitsFileInformation[] filesInfo, DetectionConfig config, List<io.github.ppissias.jtransient.engine.ExcludedArea> excludedAreas) {
        this.filesInfo = filesInfo;
        this.config = config;
        this.excludedAreas = List.copyOf(excludedAreas);
    }

    @Override
    public JobOutcome<JTransientEngine.MasterStackResult> run(JobContext context) throws Exception {
        try (CancellationScope cancellation = CancellationScope.open(context::isCancelled)) {
            CancellationScope.check();
            return runCancellable(context);
        }
    }

    private JobOutcome<JTransientEngine.MasterStackResult> runCancellable(JobContext context) throws Exception {
        JTransientEngine engine = new JTransientEngine();
        try (var diagnostics = DebugOutput.openEngineDiagnostics()) {
            List<ImageFrame> frames = SessionFrameLoader.loadAll(filesInfo, context::progress, 30);
            JTransientEngine.MasterStackResult result = engine.generateMasterStackWithDetails(frames, config,
                    (percent, message) -> context.progress(30 + (int) (percent * 0.7), message), excludedAreas);
            context.progress(100, "Master stack ready");
            return JobOutcome.completed(result);
        } finally {
            engine.shutdown();
        }
    }
}
