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

/** What a running job may use. Its methods can be called from the job's worker thread. */
public interface JobContext {

    JobHandle handle();

    /** Whether the user has cancelled this job; a job that reports no progress for a while can stop here itself. */
    boolean isCancelled();

    /**
     * Reports progress; it reaches the window only while this job is the current one. Once the user has cancelled the
     * job, it throws {@link JobCancelledException} instead, unless {@code percent} is 100, which means the work is
     * done. Once the application has run out of memory, it throws {@link OutOfMemoryError}, so that the job stops
     * while SpacePixels closes.
     */
    void progress(int percent, String message);
}
