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
import eu.startales.spacepixels.util.StretchAlgorithm;

/** Writes stretched copies of all frames of the session, colour or mono, into a new folder. */
public class BatchStretchTask implements Job<Void> {
    private final ImageProcessing preProcessing;
    private final int stretchFactor;
    private final int iterations;
    private final StretchAlgorithm algo;

    public BatchStretchTask(ImageProcessing preProcessing,
                            int stretchFactor, int iterations, StretchAlgorithm algo) {
        this.preProcessing = preProcessing;
        this.stretchFactor = stretchFactor;
        this.iterations = iterations;
        this.algo = algo;
    }

    @Override
    public JobOutcome<Void> run(JobContext context) throws Exception {
        preProcessing.batchStretch(stretchFactor, iterations, algo, context::progress);
        return JobOutcome.completed(null);
    }
}
