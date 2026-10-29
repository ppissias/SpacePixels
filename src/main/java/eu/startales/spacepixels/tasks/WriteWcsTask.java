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
import eu.startales.spacepixels.platesolve.PlateSolveService;
import io.github.ppissias.jplatesolve.PlateSolveResult;

import java.util.Map;

/**
 * Writes a plate solution into a staged copy's image header, preserving all stored image/table bytes, then removes
 * the solver's temporary files. The result is the saved image metadata. Publication follows complete-file and
 * WCS verification; failure leaves the original unchanged.
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
