/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.api;

/**
 * Controls whether the API should reject non-ready inputs or normalize them into a detection-ready
 * 16-bit monochrome FITS directory first.
 */
public enum InputPreparationMode {
    /**
     * Rejects directories that are not already detection-ready uncompressed 16-bit monochrome FITS.
     */
    FAIL_IF_NOT_READY,
    /**
     * Converts supported FITS or XISF inputs into a new detection-ready 16-bit monochrome FITS
     * directory before running the pipeline.
     */
    AUTO_PREPARE_TO_16BIT_MONO
}
