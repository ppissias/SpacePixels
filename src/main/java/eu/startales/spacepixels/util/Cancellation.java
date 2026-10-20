/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util;

import java.util.concurrent.CancellationException;

/** Tells a cancelled run from a failed one, in services that know nothing of the window's jobs. */
public final class Cancellation {

    private Cancellation() {
    }

    /**
     * Whether {@code failure} is a cancel: a {@link CancellationException}, also when another exception wraps it, as
     * when a progress report from one of the engine's worker threads stopped the run.
     */
    public static boolean isCancellation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CancellationException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
