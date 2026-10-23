/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util;

import java.awt.image.BufferedImage;

/**
 * A small picture of the work under way, for the progress dialog's live view, with its caption. The image is a
 * detached thumbnail, never a pipeline buffer. Without an image, the view keeps its picture ({@code keepImage}) or
 * clears it, and shows the new caption.
 */
public record RunPreview(BufferedImage image, String caption, boolean keepImage) {

    public static RunPreview of(BufferedImage image, String caption) {
        return new RunPreview(image, caption, false);
    }

    /** The picture stays, now with this caption, for example when it belongs to an earlier stage. */
    public static RunPreview captionOnly(String caption) {
        return new RunPreview(null, caption, true);
    }

    /** The picture is removed, for example when an iterative pass starts. */
    public static RunPreview cleared(String caption) {
        return new RunPreview(null, caption, false);
    }
}
