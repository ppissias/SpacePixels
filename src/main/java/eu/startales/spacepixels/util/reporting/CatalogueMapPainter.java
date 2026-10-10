/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.util.skycatalog.SkyProjection;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * Paints the names people know onto a full-size map of the frame: Messier, NGC, IC and Sharpless objects with their
 * outlines and common names, bright stars with a proper name, and variable stars with a classic designation. Survey
 * numbers are left out.
 */
final class CatalogueMapPainter {

    static final Color DEEP_SKY_COLOR = new Color(255, 215, 70);
    static final Color NAMED_STAR_COLOR = new Color(235, 235, 255);
    static final Color VARIABLE_COLOR = new Color(255, 90, 210);
    /** Variable stars named on a map of the whole field, at most: the brightest with an old designation. */
    static final int MAX_VARIABLES = 15;

    private CatalogueMapPainter() {
    }

    /** Paints the catalogue onto the map; returns how many objects were drawn. */
    static int paint(BufferedImage map, SkyCatalogue catalogue, WcsCoordinateTransformer transformer) {
        if (catalogue == null || transformer == null) {
            return 0;
        }
        int width = map.getWidth();
        int height = map.getHeight();
        Graphics2D g = map.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        float stroke = Math.max(2f, width / 2400f);
        g.setStroke(new BasicStroke(stroke));
        g.setFont(new Font("Segoe UI", Font.BOLD, Math.max(18, width / 220)));
        double cross = width / 200.0;
        int count = 0;
        for (SkyCatalogue.DeepSkyObject object : catalogue.deepSky) {
            if (!SkyProjection.isWellKnownDeepSky(object)) {
                continue;
            }
            double[] shape = SkyProjection.deepSkyShape(transformer, object);
            if (shape == null || shape[0] < -shape[2] || shape[1] < -shape[2] || shape[0] > width + shape[2] || shape[1] > height + shape[2]) {
                continue;
            }
            double x = shape[0] + 0.5;
            double y = shape[1] + 0.5;
            g.setColor(DEEP_SKY_COLOR);
            double labelX;
            double labelY;
            if (shape[2] > cross) {
                AffineTransform saved = g.getTransform();
                g.rotate(shape[4], x, y);
                double minor = Math.max(cross / 3, shape[3]);
                g.draw(new Ellipse2D.Double(x - shape[2], y - minor, 2 * shape[2], 2 * minor));
                g.setTransform(saved);
                double reach = Math.min(shape[2], width / 6.0) * 0.72;
                labelX = x + reach;
                labelY = y - reach;
            } else {
                Path2D.Double path = new Path2D.Double();
                path.moveTo(x - cross, y);
                path.lineTo(x - cross / 3, y);
                path.moveTo(x + cross / 3, y);
                path.lineTo(x + cross, y);
                path.moveTo(x, y - cross);
                path.lineTo(x, y - cross / 3);
                path.moveTo(x, y + cross / 3);
                path.lineTo(x, y + cross);
                g.draw(path);
                labelX = x + cross * 1.3;
                labelY = y - cross * 1.3;
            }
            label(g, SkyProjection.deepSkyLabel(object), labelX, labelY, DEEP_SKY_COLOR);
            count++;
        }
        double ring = width / 180.0;
        for (SkyCatalogue.NamedStar star : catalogue.namedStars) {
            double[] p = transformer.skyToPixel(star.ra, star.dec);
            if (p == null || p[0] < 0 || p[1] < 0 || p[0] > width || p[1] > height) {
                continue;
            }
            g.setColor(NAMED_STAR_COLOR);
            g.draw(new Ellipse2D.Double(p[0] + 0.5 - ring, p[1] + 0.5 - ring, 2 * ring, 2 * ring));
            label(g, star.name, p[0] + 0.5 + ring * 1.3, p[1] + 0.5 - ring * 0.6, NAMED_STAR_COLOR);
            count++;
        }
        double diamond = width / 260.0;
        for (SkyCatalogue.VariableStar variable : SkyProjection.notableVariables(catalogue, MAX_VARIABLES)) {
            double[] p = transformer.skyToPixel(variable.ra, variable.dec);
            if (p == null || p[0] < 0 || p[1] < 0 || p[0] > width || p[1] > height) {
                continue;
            }
            double x = p[0] + 0.5;
            double y = p[1] + 0.5;
            Path2D.Double path = new Path2D.Double();
            path.moveTo(x, y - diamond);
            path.lineTo(x + diamond, y);
            path.lineTo(x, y + diamond);
            path.lineTo(x - diamond, y);
            path.closePath();
            g.setColor(VARIABLE_COLOR);
            g.draw(path);
            label(g, variable.name, x + diamond * 1.6, y - diamond, VARIABLE_COLOR);
            count++;
        }
        g.dispose();
        return count;
    }

    /** Text with a dark edge, readable on bright and dark sky. */
    private static void label(Graphics2D g, String text, double x, double y, Color color) {
        float offset = Math.max(1f, g.getFont().getSize() / 18f);
        g.setColor(new Color(0, 0, 0, 180));
        g.drawString(text, (float) x + offset, (float) y + offset);
        g.drawString(text, (float) x - offset, (float) y + offset);
        g.setColor(color);
        g.drawString(text, (float) x, (float) y);
    }
}
