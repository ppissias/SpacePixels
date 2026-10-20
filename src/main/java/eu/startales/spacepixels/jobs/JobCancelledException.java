/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.jobs;

import java.util.concurrent.CancellationException;

/**
 * Thrown by {@link JobContext#progress} once the user has cancelled the job. The job's own clean-up runs as the
 * exception leaves it, and the coordinator turns it into {@link JobOutcome.Cancelled}, also when another exception wraps it.
 * It is a {@link CancellationException}, so services that know nothing of jobs can tell a cancel from a failure.
 */
public final class JobCancelledException extends CancellationException {
    public JobCancelledException() {
        super("The job was cancelled.");
    }
}
