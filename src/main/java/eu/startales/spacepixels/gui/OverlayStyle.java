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

import eu.startales.spacepixels.report.FindingCategory;

import javax.swing.Icon;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * How each overlay layer is drawn: colours, strokes and shapes, used by the frame view and by the swatches of the
 * side panel, so a swatch looks like its marks. Findings take the colours of their report categories, as in the
 * HTML report; everything already in a catalogue is drawn in one off-white. A finding is vivid where it was measured in
 * the frame shown and faint where it was measured in another frame of the run.
 */
final class OverlayStyle {

    /** Colour of every catalogue mark: white means already known, colour means found by SpacePixels. */
    static final Color CATALOGUE = new Color(0xF4F1E8);
    static final Color DETECTION_POINT = new Color(50, 255, 50);
    static final Color DETECTION_STREAK = new Color(255, 50, 50);
    /** The image well behind the swatches, so light marks show in the light theme too. */
    private static final Color SWATCH_BACKGROUND = new Color(0x14121f);
    private static final float[] FAINT_MOVER_DASH = {5, 4};
    private static final float[] REFERENCE_DASH = {3, 3};
    private static final float[] DEEP_SKY_DASH = {4, 3};
    private static final float[] PATH_DASH = {6, 4};
    /** Opacity of a finding's marks on the frames it was not measured in. */
    private static final int FAINT_ALPHA = 110;

    private OverlayStyle() {
    }

    static Color trackColour(boolean streak) {
        return (streak ? FindingCategory.STREAK_TRACK : FindingCategory.MOVING_TARGET).color();
    }

    /** The colour of a finding's marks on the frames it was not measured in. */
    static Color faint(Color color) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), FAINT_ALPHA);
    }

    /** A finding's marks on the frames it was not measured in. */
    static Stroke faintStroke() {
        return new BasicStroke(1f);
    }

    /** A finding's marks on the frame it was measured in. */
    static Stroke vividStroke() {
        return new BasicStroke(1.6f);
    }

    static Stroke variableStroke(boolean highConfidence) {
        return highConfidence ? new BasicStroke(1.5f) : dashed(1.5f, REFERENCE_DASH);
    }

    static Stroke faintMoverStroke() {
        return new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, FAINT_MOVER_DASH, 0);
    }

    /** Report references, which are not per-frame measurements, are dashed squares. */
    static Stroke referenceStroke() {
        return dashed(1.2f, REFERENCE_DASH);
    }

    static Stroke deepSkyStroke() {
        return dashed(1.2f, DEEP_SKY_DASH);
    }

    /** The predicted path of an asteroid or comet over the session. */
    static Stroke pathStroke() {
        return dashed(1.4f, PATH_DASH);
    }

    private static Stroke dashed(float width, float[] dash) {
        return new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10, dash, 0);
    }

    /** A cross with a gap in the middle, so the star under it stays visible. */
    static void drawCrosshair(Graphics2D g2, double x, double y, double arm) {
        double gap = Math.max(1.5, arm / 3);
        g2.draw(new Line2D.Double(x - arm, y, x - gap, y));
        g2.draw(new Line2D.Double(x + gap, y, x + arm, y));
        g2.draw(new Line2D.Double(x, y - arm, x, y - gap));
        g2.draw(new Line2D.Double(x, y + gap, x, y + arm));
    }

    static Shape diamond(double x, double y, double size) {
        Path2D.Double diamond = new Path2D.Double();
        diamond.moveTo(x, y - size);
        diamond.lineTo(x + size, y);
        diamond.lineTo(x, y + size);
        diamond.lineTo(x - size, y);
        diamond.closePath();
        return diamond;
    }

    static Shape ring(double x, double y, double radius) {
        return new Ellipse2D.Double(x - radius, y - radius, 2 * radius, 2 * radius);
    }

    static Shape square(double x, double y, double radius) {
        return new Rectangle2D.Double(x - radius, y - radius, 2 * radius, 2 * radius);
    }

    /** The swatch of a layer, or null for the groups, which have none. */
    static Swatch swatch(ViewerLayer layer) {
        return layer == ViewerLayer.REPORT_FINDINGS || layer == ViewerLayer.OTHER_FINDINGS || layer == ViewerLayer.CATALOGUE
                ? null : new Swatch(layer);
    }

    /** A small dark tile with the layer's mark on it; dimmed while the layer is off or unavailable. */
    static final class Swatch implements Icon {
        private final ViewerLayer layer;
        private boolean dimmed;

        private Swatch(ViewerLayer layer) {
            this.layer = layer;
        }

        void setDimmed(boolean dimmed) {
            this.dimmed = dimmed;
        }

        @Override
        public int getIconWidth() {
            return UiStyle.space(18);
        }

        @Override
        public int getIconHeight() {
            return UiStyle.space(18);
        }

        @Override
        public void paintIcon(Component component, Graphics g, int left, int top) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (dimmed || (component != null && !component.isEnabled())) {
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
                }
                double size = getIconWidth();
                g2.setColor(SWATCH_BACKGROUND);
                g2.fill(new RoundRectangle2D.Double(left, top, size, size, size / 4, size / 4));
                // The marks are drawn on an 18-unit tile, scaled with the interface.
                g2.translate(left, top);
                g2.scale(size / 18.0, size / 18.0);
                paintMark(g2);
            } finally {
                g2.dispose();
            }
        }

        private void paintMark(Graphics2D g2) {
            double c = 9;
            switch (layer) {
                case RAW_DETECTIONS -> {
                    g2.setColor(DETECTION_POINT);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.draw(square(c, c, 4.5));
                }
                case VARIABLE_STARS -> {
                    g2.setColor(FindingCategory.VARIABLE_STAR.color());
                    g2.setStroke(variableStroke(true));
                    g2.draw(ring(c, c, 5.5));
                }
                case FAINT_MOVERS -> {
                    g2.setColor(FindingCategory.FAINT_MOVER.color());
                    g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{2.5f, 2.5f}, 0));
                    g2.draw(ring(c, c, 5.5));
                }
                case MOVING_TRACKS -> track(g2, trackColour(false));
                case STREAK_TRACKS -> track(g2, trackColour(true));
                case SINGLE_FRAME -> halves(g2, FindingCategory.SINGLE_STREAK.color(), FindingCategory.ANOMALY.color());
                case SLOW_MOVERS -> {
                    g2.setColor(FindingCategory.SLOW_MOVER.color());
                    g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10, new float[]{2, 2}, 0));
                    g2.draw(square(c, c, 5));
                }
                case LOCAL_RESIDUALS -> halves(g2, FindingCategory.LOCAL_RESCUE.color(), FindingCategory.LOCAL_ACTIVITY.color());
                case GROUPS -> halves(g2, FindingCategory.CAMERA_FIXED.color(), FindingCategory.COMMON_MOTION.color());
                case KNOWN_STARS -> {
                    g2.setColor(CATALOGUE);
                    g2.setStroke(new BasicStroke(1.2f));
                    drawCrosshair(g2, c, c, 6);
                }
                case KNOWN_VARIABLES -> {
                    g2.setColor(CATALOGUE);
                    g2.setStroke(new BasicStroke(1.2f));
                    g2.draw(diamond(c, c, 5.5));
                }
                case DEEP_SKY -> {
                    g2.setColor(CATALOGUE);
                    g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10, new float[]{2.5f, 2}, 0));
                    g2.draw(new Ellipse2D.Double(2.5, 5.5, 13, 7));
                }
                case SOLAR_SYSTEM -> {
                    g2.setColor(CATALOGUE);
                    g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10, new float[]{2, 2}, 0));
                    g2.draw(new Line2D.Double(1.5, 16.5, 7.5, 10.5));
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.draw(ring(10.5, 7.5, 4.5));
                }
                default -> {
                }
            }
        }

        /** A track's line leading to the ring at its position in the frame shown. */
        private static void track(Graphics2D g2, Color color) {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new Line2D.Double(2, 16, 7, 11));
            g2.setStroke(new BasicStroke(1.6f));
            g2.draw(ring(10.5, 7.5, 4.5));
        }

        /** A measured-position ring for a layer of two report categories: one half in each category's colour. */
        private static void halves(Graphics2D g2, Color first, Color second) {
            g2.setStroke(new BasicStroke(1.2f));
            g2.setColor(first);
            g2.draw(new Arc2D.Double(3.5, 3.5, 11, 11, 90, 180, Arc2D.OPEN));
            g2.setColor(second);
            g2.draw(new Arc2D.Double(3.5, 3.5, 11, 11, 270, 180, Arc2D.OPEN));
        }
    }
}
