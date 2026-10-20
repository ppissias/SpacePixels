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

/** How a job ended: with its result, declined or cancelled by the user, or failed with a reason. */
public sealed interface JobOutcome<R>
        permits JobOutcome.Completed, JobOutcome.Declined, JobOutcome.Cancelled, JobOutcome.Failed {

    /** The job finished and produced its result. */
    record Completed<R>(R result) implements JobOutcome<R> {
    }

    /** The user said no during the job, for example to writing a large report. */
    record Declined<R>(String reason) implements JobOutcome<R> {
    }

    /** The user cancelled the job, which stopped at its next progress report. */
    record Cancelled<R>() implements JobOutcome<R> {
    }

    /** The job stopped with an error; {@code reason} is what the user is told. */
    record Failed<R>(String reason, Throwable cause) implements JobOutcome<R> {
    }

    static <R> JobOutcome<R> completed(R result) {
        return new Completed<>(result);
    }

    static <R> JobOutcome<R> declined(String reason) {
        return new Declined<>(reason);
    }

    static <R> JobOutcome<R> cancelled() {
        return new Cancelled<>();
    }

    static <R> JobOutcome<R> failed(String reason, Throwable cause) {
        return new Failed<>(reason, cause);
    }
}
