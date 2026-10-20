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

/**
 * How a task ends after an error, so that no failure leaves the window locked: running out of memory goes to the
 * application's uncaught-exception handler, which closes SpacePixels with a message; any other error, an
 * {@link Error} included, becomes the task's failure event.
 */
final class TaskFailures {

    /** Deep enough for any real cause chain; stops a chain that loops. */
    private static final int MAX_CAUSE_DEPTH = 32;

    private TaskFailures() {
    }

    /** Whether {@code failure} or one of its causes is an {@link OutOfMemoryError}. */
    static boolean isOutOfMemory(Throwable failure) {
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
     * Throws {@code failure} again when it is an out-of-memory error, wrapped or not, so that it leaves the task's
     * thread and reaches the uncaught-exception handler; otherwise returns the message for the failure event.
     */
    static String rethrowOutOfMemory(Throwable failure) {
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
    static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
