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

import io.github.ppissias.jtransient.core.DataArea;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;

/**
 * Draws and describes the session data area: where every kept frame holds image data, a few pixels in from the
 * registration fill and the image edge.
 */
final class DataAreaPresentation {
    static final Color COLOR = new Color(60, 120, 255);
    /** A limiting frame is named when leaving it out would add at least this share of the frame. */
    private static final double NAMED_LIMIT_FRACTION = 0.005;

    private DataAreaPresentation() {
    }

    /** The outline as an SVG polygon in frame pixels, or null when there is no area. */
    static String svgPolygon(DataArea area) {
        if (area == null) {
            return null;
        }
        List<double[]> outline = area.outline();
        if (outline.size() < 3) {
            return null;
        }
        StringBuilder points = new StringBuilder();
        for (double[] p : outline) {
            points.append(points.length() == 0 ? "" : " ").append(String.format(Locale.US, "%.0f,%.0f", p[0], p[1]));
        }
        return "<polygon points='" + points + "' fill='none' stroke='" + hex(COLOR)
                + "' stroke-width='2' stroke-dasharray='8 5' vector-effect='non-scaling-stroke'/>";
    }

    /** Draws the outline on a full-size map of the frame. */
    static void paint(BufferedImage map, DataArea area) {
        if (area == null || map.getWidth() != area.getWidth() || map.getHeight() != area.getHeight()) {
            return;
        }
        List<double[]> outline = area.outline();
        if (outline.size() < 3) {
            return;
        }
        Path2D.Double path = new Path2D.Double();
        path.moveTo(outline.get(0)[0], outline.get(0)[1]);
        for (int i = 1; i < outline.size(); i++) {
            path.lineTo(outline.get(i)[0], outline.get(i)[1]);
        }
        path.closePath();
        Graphics2D g = map.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float width = Math.max(2f, map.getWidth() / 1500f);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                new float[]{6 * width, 4 * width}, 0f));
        g.setColor(COLOR);
        g.draw(path);
        g.dispose();
    }

    /**
     * One sentence for the reader: how much of the frame every kept frame covers, and the frame that limits it most
     * when that frame alone costs a noticeable share. Empty when the area was not measured.
     */
    static String describe(DataArea area, PipelineTelemetry telemetry) {
        if (area == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(String.format(Locale.US,
                "Every kept frame has image data on %.1f %% of the frame (the data area, %d px in from the registration fill and the image edge).",
                100 * area.getFraction(), DataArea.MARGIN));
        if (telemetry != null && !telemetry.dataAreaLimitingFrames.isEmpty()) {
            PipelineTelemetry.DataAreaFrameStat worst = telemetry.dataAreaLimitingFrames.get(0);
            if (worst.gainedFraction >= NAMED_LIMIT_FRACTION) {
                text.append(String.format(Locale.US, " Frame %d limits it most: without it the area would be %.1f %% of the frame larger.",
                        worst.frameIndex + 1, 100 * worst.gainedFraction));
            }
        }
        return text.toString();
    }

    static String hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }
}
