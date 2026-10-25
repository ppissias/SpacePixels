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
import eu.startales.spacepixels.fits.StretchAlgorithm;

/** Writes stretched copies of all frames of the session, colour or mono, into a new folder. */
public class BatchStretchTask implements Job<Void> {
    private final FitsBatchService batch;
    private final int stretchFactor;
    private final int iterations;
    private final StretchAlgorithm algo;

    /** Stretches with the session's batch service, captured when the job is created; null fails when it runs. */
    public BatchStretchTask(FitsBatchService batch,
                            int stretchFactor, int iterations, StretchAlgorithm algo) {
        this.batch = batch;
        this.stretchFactor = stretchFactor;
        this.iterations = iterations;
        this.algo = algo;
    }

    @Override
    public JobOutcome<Void> run(JobContext context) throws Exception {
        batch.batchStretch(stretchFactor, iterations, algo, context::progress);
        return JobOutcome.completed(null);
    }
}
