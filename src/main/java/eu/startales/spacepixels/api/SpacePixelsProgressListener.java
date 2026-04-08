/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.api;

/**
 * Receives coarse pipeline execution progress updates.
 */
@FunctionalInterface
public interface SpacePixelsProgressListener {
    /**
     * Called with the latest progress update.
     *
     * @param percentage progress percentage in the range {@code 0..100}
     * @param message human-readable status message for the current stage
     */
    void onProgress(int percentage, String message);
}
