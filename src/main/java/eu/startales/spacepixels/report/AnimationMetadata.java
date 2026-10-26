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

import java.util.List;
import java.util.Objects;

/**
 * Metadata describing an animation sequence and its frame sheet layout.
 *
 * @param fileName    the file name of the animated GIF
 * @param frameCount  total number of animation frames
 * @param columns     number of columns in the frame sheet grid per sheet page
 * @param rows        number of rows in the frame sheet grid per sheet page
 * @param frameWidth  native width of each individual frame in pixels
 * @param frameHeight native height of each individual frame in pixels
 * @param delayMs     base delay between frames in milliseconds (starting speed)
 * @param sheets      list of sheet image file names associated with this animation
 */
public record AnimationMetadata(
        String fileName,
        int frameCount,
        int columns,
        int rows,
        int frameWidth,
        int frameHeight,
        int delayMs,
        List<String> sheets
) {
    public AnimationMetadata {
        Objects.requireNonNull(fileName, "fileName");
        sheets = (sheets != null && !sheets.isEmpty())
                ? List.copyOf(sheets)
                : List.of(fileName + ".sheet.png");
    }

    /**
     * Convenience constructor creating metadata with a single sheet named {@code fileName + ".sheet.png"}.
     *
     * @param fileName    the file name of the animated GIF
     * @param frameCount  total number of animation frames
     * @param columns     number of columns in the frame sheet grid
     * @param rows        number of rows in the frame sheet grid
     * @param frameWidth  native width of each individual frame in pixels
     * @param frameHeight native height of each individual frame in pixels
     * @param delayMs     base delay between frames in milliseconds
     */
    public AnimationMetadata(
            String fileName,
            int frameCount,
            int columns,
            int rows,
            int frameWidth,
            int frameHeight,
            int delayMs) {
        this(fileName, frameCount, columns, rows, frameWidth, frameHeight, delayMs, List.of(fileName + ".sheet.png"));
    }
}
