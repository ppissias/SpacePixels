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

import java.util.Map;

/**
 * Writes a plate solution into the frame's FITS header, which rewrites the whole file, and then removes the solver's
 * temporary files. The result is the frame's new header. The original file is replaced only once the new one is
 * complete, so a failure leaves it unchanged.
 */
public class WriteWcsTask implements Job<Map<String, String>> {
    private final ImageProcessing preProcessing;
    private final String filePath;
    private final PlateSolveResult result;

    public WriteWcsTask(ImageProcessing preProcessing, String filePath, PlateSolveResult result) {
        this.preProcessing = preProcessing;
        this.filePath = filePath;
        this.result = result;
    }

    @Override
    public JobOutcome<Map<String, String>> run(JobContext context) throws Exception {
        try {
            return JobOutcome.completed(preProcessing.updateFitsHeaderWithWCS(filePath, result.getSolveInformation()));
        } finally {
            preProcessing.cleanupSolveArtifacts(filePath, result);
        }
    }
}
