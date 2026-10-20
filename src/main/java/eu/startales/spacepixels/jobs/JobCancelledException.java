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

/**
 * Thrown by {@link JobContext#progress} once the user has cancelled the job. The job's own clean-up runs as the
 * exception leaves it, and the coordinator turns it into {@link JobOutcome.Cancelled}, also when another exception wraps it.
 */
public final class JobCancelledException extends RuntimeException {
    public JobCancelledException() {
        super("The job was cancelled.");
    }
}
