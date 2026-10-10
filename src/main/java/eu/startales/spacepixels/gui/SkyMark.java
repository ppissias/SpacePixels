/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.gui;

/** A catalogue object placed on a frame, in frame pixels, for {@link FrameView} to draw. */
final class SkyMark {

    enum Kind { STAR, NAMED_STAR, DEEP_SKY, VARIABLE }

    final Kind kind;
    /** Position in frame pixels (0-based, like the detections). */
    final double x;
    final double y;
    /** Magnitude used to choose which marks to show when there are too many; NaN when unknown. */
    final double magnitude;
    /** Half axes in frame pixels and the direction of the major axis in radians (frame x towards y); 0 when no size. */
    final double semiMajor;
    final double semiMinor;
    final double angle;
    /** Drawn next to the mark, for example "NGC 2264". */
    final String label;
    /** Shown in the cursor readout, for example "V0959 Mon · NB · 9.86–18.40 V". */
    final String description;

    SkyMark(Kind kind, double x, double y, double magnitude, double semiMajor, double semiMinor, double angle,
            String label, String description) {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.magnitude = magnitude;
        this.semiMajor = semiMajor;
        this.semiMinor = semiMinor;
        this.angle = angle;
        this.label = label;
        this.description = description;
    }
}
