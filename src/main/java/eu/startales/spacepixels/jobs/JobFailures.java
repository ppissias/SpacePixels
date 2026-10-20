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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * How a job ends after an error, so that no failure leaves the window locked: running out of memory goes to the
 * application's uncaught-exception handler, which closes SpacePixels with a message; any other error, an
 * {@link Error} included, becomes the job's failure.
 */
public final class JobFailures {

    /** Deep enough for any real cause chain; stops a chain that loops. */
    private static final int MAX_CAUSE_DEPTH = 32;

    /** Set once the application has run out of memory, which closes it; running jobs then stop at their next report. */
    private static final AtomicBoolean APPLICATION_OUT_OF_MEMORY = new AtomicBoolean();

    private JobFailures() {
    }

    /**
     * Records that the application has run out of memory. Returns true only the first time, for the one handler that
     * tells the user and closes SpacePixels.
     */
    public static boolean markApplicationOutOfMemory() {
        return APPLICATION_OUT_OF_MEMORY.compareAndSet(false, true);
    }

    /** Whether the application has run out of memory and is closing. */
    public static boolean isApplicationOutOfMemory() {
        return APPLICATION_OUT_OF_MEMORY.get();
    }

    /** For tests only: forgets that the application ran out of memory. */
    static void clearApplicationOutOfMemory() {
        APPLICATION_OUT_OF_MEMORY.set(false);
    }

    /** Whether {@code failure} or one of its causes is an {@link OutOfMemoryError}. */
    public static boolean isOutOfMemory(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof OutOfMemoryError) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Throws {@code failure} again when it is an out-of-memory error, wrapped or not, so that it leaves the job's
     * thread and reaches the uncaught-exception handler; otherwise returns the message for the failure.
     */
    public static String rethrowOutOfMemory(Throwable failure) {
        if (isOutOfMemory(failure)) {
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            throw new IllegalStateException(failure);
        }
        return message(failure);
    }

    /** The failure's message, or its type when it has none. */
    public static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
