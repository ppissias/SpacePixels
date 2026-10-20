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

    /**
     * Reports progress; it reaches the window only while this job is the current one. Once the application has run
     * out of memory, it throws {@link OutOfMemoryError} instead, so that the job stops while SpacePixels closes.
     */
    void progress(int percent, String message);
}
