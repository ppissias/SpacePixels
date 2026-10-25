package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.jobs.JobCoordinator;
import eu.startales.spacepixels.session.ProcessingSession;

/**
 * The open desktop session, for EDT commands. A job copies the session it is admitted with and does not read this again.
 */
interface SessionAccess {
    /** The open session, or null before an import. */
    ProcessingSession current();

    /** Whether {@code session} is still the open one. */
    boolean isCurrent(ProcessingSession session);

    /** Applies {@code handler} only when its job's session is still current. */
    <R> JobCoordinator.OutcomeHandler<R> forCurrent(JobCoordinator.OutcomeHandler<R> handler);
}
