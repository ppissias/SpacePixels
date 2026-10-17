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
     * Adds the same whole number of ADU to every pixel of every frame so the lowest becomes 0 (recommended). All values
     * and the sky noise are kept; the zero fill that registration leaves at the frame edges stays at 0, and the
     * converted files record the offset in the {@code SPOFFSET} keyword.
     */
    ADD_OFFSET,
    /**
     * Sets values below zero to 0. The original levels are kept, but where the sky is near zero this cuts off the low
     * side of the noise, so the background is measured too high and the noise too low.
     */
    CLIP_TO_ZERO
}
