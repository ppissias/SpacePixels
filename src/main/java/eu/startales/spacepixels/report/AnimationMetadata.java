/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.report;

/**
 * Metadata describing an animation sequence and its frame sheet layout.
 *
 * @param fileName    the file name of the animated GIF
 * @param frameCount  total number of animation frames
 * @param columns     number of columns in the frame sheet grid
 * @param rows        number of rows in the frame sheet grid
 * @param frameWidth  width of each individual frame in pixels
 * @param frameHeight height of each individual frame in pixels
 * @param delayMs     base delay between frames in milliseconds (starting speed)
 */
public record AnimationMetadata(
        String fileName,
        int frameCount,
        int columns,
        int rows,
        int frameWidth,
        int frameHeight,
        int delayMs
) {
}
