/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.detection;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * A small picture of the work under way, for the progress dialog's live view, with its caption. The image is a
 * detached thumbnail, never a pipeline buffer. Without an image, the view keeps its picture ({@code keepImage}) or
 * clears it, and shows the new caption. A preview with {@code slides} shows them in turn, starting with the first.
 */
public record RunPreview(BufferedImage image, String caption, boolean keepImage, List<RunPreview> slides) {

    public RunPreview {
        slides = slides == null ? List.of() : List.copyOf(slides);
    }

    public static RunPreview of(BufferedImage image, String caption) {
        return new RunPreview(image, caption, false, List.of());
    }

    /** The picture stays, now with this caption, for example when it belongs to an earlier stage. */
    public static RunPreview captionOnly(String caption) {
        return new RunPreview(null, caption, true, List.of());
    }

    /** The picture is removed, for example when an iterative pass starts. */
    public static RunPreview cleared(String caption) {
        return new RunPreview(null, caption, false, List.of());
    }

    /** Several pictures shown in turn, for example the findings of one detection stage; {@code slides} is not empty. */
    public static RunPreview slides(List<RunPreview> slides) {
        RunPreview first = slides.get(0);
        return new RunPreview(first.image(), first.caption(), false, slides);
    }

    /** The same preview with {@code prefix} before every caption, for example the pass of an iterative run. */
    public RunPreview withCaptionPrefix(String prefix) {
        List<RunPreview> prefixed = new ArrayList<>(slides.size());
        for (RunPreview slide : slides) {
            prefixed.add(slide.withCaptionPrefix(prefix));
        }
        return new RunPreview(image, prefix + caption, keepImage, prefixed);
    }
}
