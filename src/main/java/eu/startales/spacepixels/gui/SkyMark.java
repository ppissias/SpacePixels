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

    /** {@code SOLAR_SYSTEM} is a known asteroid or comet where JPL predicts it at the time of the frame. */
    enum Kind { STAR, NAMED_STAR, DEEP_SKY, VARIABLE, SOLAR_SYSTEM }

    final Kind kind;
    /** Position in frame pixels (0-based, like the detections); NaN for an asteroid or comet not predicted at this time. */
    final double x;
    final double y;
    /** Magnitude used to choose which marks to show when there are too many; NaN when unknown. */
    final double magnitude;
    /**
     * Half axes in frame pixels and the direction of the major axis in radians (frame x towards y); 0 when no size.
     * For an asteroid or comet, the 3-sigma uncertainty of its predicted position.
     */
    final double semiMajor;
    final double semiMinor;
    final double angle;
    /** Drawn next to the mark, for example "NGC 2264". */
    final String label;
    /** Shown in the cursor readout, for example "V0959 Mon · NB · 9.86–18.40 V". */
    final String description;
    /** An asteroid's or comet's predicted path over the whole session, as x0, y0, x1, y1, … in frame pixels; else null. */
    final double[] path;
    /** Its motion during this frame's exposure, {x start, y start, x end, y end}; null when not known or for other kinds. */
    final double[] trail;

    SkyMark(Kind kind, double x, double y, double magnitude, double semiMajor, double semiMinor, double angle,
            String label, String description) {
        this(kind, x, y, magnitude, semiMajor, semiMinor, angle, label, description, null, null);
    }

    SkyMark(Kind kind, double x, double y, double magnitude, double semiMajor, double semiMinor, double angle,
            String label, String description, double[] path, double[] trail) {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.magnitude = magnitude;
        this.semiMajor = semiMajor;
        this.semiMinor = semiMinor;
        this.angle = angle;
        this.label = label;
        this.description = description;
        this.path = path;
        this.trail = trail;
    }

    /** Whether the mark has a position on this frame (an asteroid or comet outside its predicted times has none). */
    boolean isPlaced() {
        return Double.isFinite(x) && Double.isFinite(y);
    }
}
