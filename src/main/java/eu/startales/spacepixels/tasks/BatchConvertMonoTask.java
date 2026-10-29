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
import eu.startales.spacepixels.fits.FitsBatchService;

import java.io.File;

/**
 * Converts the colour frames of the session to linear mono FITS copies. The result is the folder of
 * the new mono files, or null when every frame was already mono.
 */
public class BatchConvertMonoTask implements Job<File> {
    private final FitsBatchService batch;

    /** Converts with the session's batch service, captured when the job is created; null fails when it runs. */
    public BatchConvertMonoTask(FitsBatchService batch) {
        this.batch = batch;
    }

    @Override
    public JobOutcome<File> run(JobContext context) throws Exception {
        return JobOutcome.completed(batch.batchConvertToMono(context::progress));
    }
}
