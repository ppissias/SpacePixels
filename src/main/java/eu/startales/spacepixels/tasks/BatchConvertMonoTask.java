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

import java.io.File;

/**
 * Converts the colour frames of the session to mono FITS files, stretched too when asked. The result is the folder of
 * the new mono files, or null when every frame was already mono.
 */
public class BatchConvertMonoTask implements Job<File> {
    private final ImageProcessing preProcessing;
    private final boolean stretchEnabled;
    private final int stretchFactor;
    private final int iterations;
    private final StretchAlgorithm algo;

    public BatchConvertMonoTask(ImageProcessing preProcessing,
                                boolean stretchEnabled, int stretchFactor,
                                int iterations, StretchAlgorithm algo) {
        this.preProcessing = preProcessing;
        this.stretchEnabled = stretchEnabled;
        this.stretchFactor = stretchFactor;
        this.iterations = iterations;
        this.algo = algo;
    }

    @Override
    public JobOutcome<File> run(JobContext context) throws Exception {
        return JobOutcome.completed(preProcessing.batchConvertToMono(stretchEnabled, stretchFactor, iterations, algo, context::progress));
    }
}
