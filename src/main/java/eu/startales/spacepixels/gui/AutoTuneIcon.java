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

import com.formdev.flatlaf.util.UIScale;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;

/**
 * The Auto-Tune icon: three slider controls with a four-point star above them, the settings that set themselves.
 * Drawn as vector shapes, so it stays sharp at any scale, in the button's text colour (the star in the accent colour).
 */
final class AutoTuneIcon implements Icon {

    private static final Color STAR_COLOR = new Color(77, 166, 255);

    private final int size;

    /** @param size the icon's width and height in unscaled pixels (the drawing is laid out on a 20-unit grid) */
    AutoTuneIcon(int size) {
        this.size = size;
    }

    @Override
    public int getIconWidth() {
        return UIScale.scale(size);
    }

    @Override
    public int getIconHeight() {
        return UIScale.scale(size);
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(x, y);
            double scale = getIconWidth() / 20.0;
            g2.scale(scale, scale);

            boolean enabled = c == null || c.isEnabled();
            Color line = enabled ? c != null ? c.getForeground() : UIManager.getColor("Button.foreground")
                    : UIManager.getColor("Button.disabledText");
            if (line == null) {
                line = Color.LIGHT_GRAY;
            }
            Color star = enabled ? STAR_COLOR : line;

            // Three sliders: a track with a round knob, each knob at a different position.
            double[] rows = {8, 12.75, 17.5};
            double[] knobs = {5.5, 12.5, 8};
            for (int i = 0; i < rows.length; i++) {
                g2.setColor(withAlpha(line, 160));
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new Line2D.Double(1.5, rows[i], i == 0 ? 9.5 : 18.5, rows[i]));
                g2.setColor(line);
                g2.fill(new Ellipse2D.Double(knobs[i] - 2.6, rows[i] - 2.6, 5.2, 5.2));
            }

            // The star at the top right, where the first slider's track stops short for it.
            g2.setColor(star);
            g2.fill(fourPointStar(15, 5, 5, 1.3));
        } finally {
            g2.dispose();
        }
    }

    private static Path2D fourPointStar(double cx, double cy, double outer, double inner) {
        Path2D path = new Path2D.Double();
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI / 4 * i - Math.PI / 2;
            double r = i % 2 == 0 ? outer : inner;
            double px = cx + r * Math.cos(angle);
            double py = cy + r * Math.sin(angle);
            if (i == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        path.closePath();
        return path;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
