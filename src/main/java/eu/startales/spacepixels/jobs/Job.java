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
 * The work of one job, run on a worker thread. It returns how it ended; an exception or {@link Error} it throws
 * becomes a {@link JobOutcome.Failed}, and running out of memory leaves the thread for the application's handler.
 */
@FunctionalInterface
public interface Job<R> {
    JobOutcome<R> run(JobContext context) throws Exception;
}
