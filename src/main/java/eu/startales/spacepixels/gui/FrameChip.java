package eu.startales.spacepixels.gui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** A small rounded count in the frame toolbar, tinted from the theme's own background so every theme keeps its contrast. */
final class FrameChip extends JLabel {
    private final Color tint;

    FrameChip(Color tint, String tooltip) {
        this.tint = tint;
        setBorder(BorderFactory.createEmptyBorder(UiStyle.space(2), UiStyle.space(8), UiStyle.space(2), UiStyle.space(8)));
        setToolTipText(tooltip);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D rounded = (Graphics2D) g.create();
        rounded.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color base = getParent() == null ? getBackground() : getParent().getBackground();
        int arc = getHeight();
        rounded.setColor(QualityControlTable.blend(base, tint, 22));
        rounded.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        rounded.setColor(QualityControlTable.blend(base, tint, 60));
        rounded.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        rounded.dispose();
        super.paintComponent(g);
    }
}
