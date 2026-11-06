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

import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.UIManager;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * The drawn switches of the viewers' side panel. Each is a check box with its own icons, so it keeps a check box's
 * keyboard and screen-reader behaviour: an eye to show a layer, "Aa" for its labels, and an on/off switch for a
 * section. Icons dim themselves while their check box is disabled.
 */
final class SidePanelIcons {

    private enum Kind { EYE, LABELS, SWITCH }

    private SidePanelIcons() {
    }

    /** A layer's show/hide eye, named for screen readers and tests, for example "Show tracks". */
    static JCheckBox eye(String accessibleName) {
        return box(Kind.EYE, accessibleName);
    }

    /** A layer's label switch, drawn as "Aa". */
    static JCheckBox labels(String accessibleName) {
        return box(Kind.LABELS, accessibleName);
    }

    /** A section's on/off switch. */
    static JCheckBox onOff(String accessibleName) {
        return box(Kind.SWITCH, accessibleName);
    }

    private static JCheckBox box(Kind kind, String accessibleName) {
        JCheckBox box = new JCheckBox();
        Icon off = new ToggleIcon(kind, false);
        Icon on = new ToggleIcon(kind, true);
        box.setIcon(off);
        box.setSelectedIcon(on);
        box.setDisabledIcon(off);
        box.setDisabledSelectedIcon(on);
        box.setRolloverIcon(off);
        box.setRolloverSelectedIcon(on);
        box.setPressedIcon(on);
        box.setOpaque(false);
        box.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 2, 2, 2));
        box.getAccessibleContext().setAccessibleName(accessibleName);
        return box;
    }

    /** The accent of the current theme, used for switched-on switches. */
    static Color accent() {
        Color color = UIManager.getColor("Component.accentColor");
        if (color == null) color = UIManager.getColor("CheckBox.icon.selectedBackground");
        return color == null ? new Color(0x5B9CFF) : color;
    }

    private static Color foreground() {
        Color color = UIManager.getColor("Label.foreground");
        return color == null ? Color.GRAY : color;
    }

    private static final class ToggleIcon implements Icon {
        private final Kind kind;
        private final boolean on;

        ToggleIcon(Kind kind, boolean on) {
            this.kind = kind;
            this.on = on;
        }

        @Override
        public int getIconWidth() {
            return UiStyle.space(kind == Kind.SWITCH ? 34 : 22);
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
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                boolean enabled = component == null || component.isEnabled();
                float alpha = !enabled ? 0.3f : kind == Kind.LABELS && !on ? 0.35f : kind == Kind.EYE && !on ? 0.55f : 1f;
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g2.translate(left, top);
                double scale = getIconHeight() / 18.0;
                g2.scale(scale, scale);
                switch (kind) {
                    case EYE -> paintEye(g2);
                    case LABELS -> paintLabels(g2);
                    case SWITCH -> paintSwitch(g2);
                }
            } finally {
                g2.dispose();
            }
        }

        private void paintEye(Graphics2D g2) {
            g2.setColor(foreground());
            g2.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Double outline = new Path2D.Double();
            outline.moveTo(3, 9);
            outline.quadTo(11, 1.5, 19, 9);
            outline.quadTo(11, 16.5, 3, 9);
            outline.closePath();
            g2.draw(outline);
            g2.draw(new Ellipse2D.Double(8.5, 6.5, 5, 5));
            if (!on) {
                g2.draw(new Line2D.Double(4, 15.5, 18, 2.5));
            }
        }

        private void paintLabels(Graphics2D g2) {
            g2.setColor(foreground());
            g2.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD, 11f));
            FontMetrics metrics = g2.getFontMetrics();
            String text = "Aa";
            g2.drawString(text, (22 - metrics.stringWidth(text)) / 2f, 9 + (metrics.getAscent() - metrics.getDescent()) / 2f);
        }

        private void paintSwitch(Graphics2D g2) {
            RoundRectangle2D.Double track = new RoundRectangle2D.Double(1, 2, 32, 14, 14, 14);
            if (on) {
                g2.setColor(accent());
                g2.fill(track);
                g2.setColor(Color.WHITE);
                g2.fill(new Ellipse2D.Double(20, 4, 10, 10));
            } else {
                Color fore = foreground();
                g2.setColor(new Color(fore.getRed(), fore.getGreen(), fore.getBlue(), 60));
                g2.fill(track);
                g2.setColor(new Color(fore.getRed(), fore.getGreen(), fore.getBlue(), 120));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(track);
                g2.setColor(Color.WHITE);
                g2.fill(new Ellipse2D.Double(4, 4, 10, 10));
            }
        }
    }
}
