/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.api;

/**
 * How {@link InputPreparationMode#AUTO_PREPARE_TO_16BIT_MONO} stores pixel values below zero, which unsigned 16-bit
 * FITS cannot hold. They come from signed 16-bit or floating-point frames that were bias- or dark-subtracted without a
 * pedestal.
 */
public enum NegativeValueHandling {
    /**
     * Adds one common offset before the sequence's common linear multiplier (recommended). Ordinary negative values
     * and sky noise are kept; extreme samples follow the separate outlier policy. Registration fill stays at 0 and
     * converted files record the pedestal in output ADU in {@code SPOFFSET}.
     */
    ADD_OFFSET,
    /**
     * Sets values below zero to 0. The original levels are kept, but where the sky is near zero this cuts off the low
     * side of the noise, so the background is measured too high and the noise too low.
     */
    CLIP_TO_ZERO
}
