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
import eu.startales.spacepixels.util.PlateSolveService;
import io.github.ppissias.jplatesolve.PlateSolveResult;

import java.util.Map;

/**
 * Writes a plate solution into the frame's FITS header, which rewrites the whole file, and then removes the solver's
 * temporary files. The result is the frame's new header. The original file is replaced only once the new one is
 * complete, so a failure leaves it unchanged.
 */
public class WriteWcsTask implements Job<Map<String, String>> {
    private final PlateSolveService solver;
    private final String filePath;
    private final PlateSolveResult result;

    /** Writes with the session's solver, captured when the job is created; null fails when it runs. */
    public WriteWcsTask(PlateSolveService solver, String filePath, PlateSolveResult result) {
        this.solver = solver;
        this.filePath = filePath;
        this.result = result;
    }

    @Override
    public JobOutcome<Map<String, String>> run(JobContext context) throws Exception {
        try {
            return JobOutcome.completed(solver.updateFitsHeaderWithWCS(filePath, result.getSolveInformation()));
        } finally {
            solver.cleanupSolveArtifacts(filePath, result);
        }
    }
}
