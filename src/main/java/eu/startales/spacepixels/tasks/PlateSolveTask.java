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
import io.github.ppissias.jplatesolve.PlateSolveResult;

import java.util.concurrent.ExecutionException;

/**
 * Solves one frame with ASTAP or Astrometry.net. The result is the solver's answer, which says itself whether the
 * frame was solved; an error before the solver answers fails the job with its reason.
 */
public class PlateSolveTask implements Job<PlateSolveResult> {
    private final ImageProcessing preProcessing;
    private final String filePath;
    private final boolean useAstap;
    private final boolean useAstrometryNet;

    public PlateSolveTask(ImageProcessing preProcessing, String filePath, boolean useAstap, boolean useAstrometryNet) {
        this.preProcessing = preProcessing;
        this.filePath = filePath;
        this.useAstap = useAstap;
        this.useAstrometryNet = useAstrometryNet;
    }

    @Override
    public JobOutcome<PlateSolveResult> run(JobContext context) throws Exception {
        try {
            // Waits on this worker thread for the solver.
            return JobOutcome.completed(preProcessing.solve(filePath, useAstap, useAstrometryNet).get());
        } catch (ExecutionException e) {
            // The solver's own error explains the failure better than its wrapper.
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw e;
        }
    }
}
