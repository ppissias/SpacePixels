/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.api;

/**
 * Signals a failure while preparing inputs, auto-tuning, running, or exporting a SpacePixels
 * pipeline execution.
 */
public class SpacePixelsPipelineException extends Exception {
    /**
     * Creates a pipeline exception with a message.
     *
     * @param message failure description
     */
    public SpacePixelsPipelineException(String message) {
        super(message);
    }

    /**
     * Creates a pipeline exception with a message and root cause.
     *
     * @param message failure description
     * @param cause underlying cause
     */
    public SpacePixelsPipelineException(String message, Throwable cause) {
        super(message, cause);
    }
}
