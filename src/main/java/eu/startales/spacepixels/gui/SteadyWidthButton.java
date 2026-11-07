package eu.startales.spacepixels.gui;

import javax.swing.JButton;
import java.awt.Dimension;
import java.awt.FontMetrics;

/** A button as wide as its widest caption, so the panels around it stay put while its caption follows the selection. */
final class SteadyWidthButton extends JButton {
    private String widestCaption = "";

    /** Keeps room for this caption from now on. */
    void reserveFor(String caption) {
        if (!caption.equals(widestCaption)) {
            widestCaption = caption;
            revalidate();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        FontMetrics metrics = getFontMetrics(getFont());
        String shown = getText() == null ? "" : getText();
        size.width += Math.max(0, metrics.stringWidth(widestCaption) - metrics.stringWidth(shown));
        return size;
    }
}
