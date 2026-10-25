package eu.startales.spacepixels.gui;

import javax.swing.JLabel;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;
import java.awt.Dimension;
import java.awt.Insets;

/** HTML guidance whose preferred height follows measured content at the available form-column width. */
final class WrappingHelpLabel extends JLabel {
    private final int logicalWidth;
    private int wrapWidth;

    /** Preserves inline HTML while using current body typography and readable theme colors. */
    WrappingHelpLabel(String html, int logicalWidth) {
        super("<html>" + html + "</html>");
        this.logicalWidth = logicalWidth;
        setFont(UiStyle.bodyFont());
        setForeground(UiStyle.helpColor());
        setAlignmentX(LEFT_ALIGNMENT);
    }

    /** Sets the next measured column width in scaled pixels; no fixed text height is retained. */
    void wrapAt(int width) {
        wrapWidth = Math.max(1, width);
    }

    /** Measures HTML layout at the form's width, including font scaling and all wrapped lines. */
    @Override public Dimension getPreferredSize() {
        int width = wrapWidth > 0 ? wrapWidth : UiStyle.space(logicalWidth);
        if (wrapWidth == 0 && getParent() != null && getParent().getWidth() > 0) {
            Insets parentInsets = getParent().getInsets();
            width = Math.min(width, getParent().getWidth() - parentInsets.left - parentInsets.right);
        }
        View html = (View) getClientProperty(BasicHTML.propertyKey);
        if (html == null) return super.getPreferredSize();
        Insets insets = getInsets();
        html.setSize(Math.max(1, width - insets.left - insets.right), 0);
        return new Dimension(width, (int) Math.ceil(html.getPreferredSpan(View.Y_AXIS)) + insets.top + insets.bottom);
    }

    /** Allows narrow windows to wrap the content rather than enforcing its preferred width. */
    @Override public Dimension getMinimumSize() {
        return new Dimension(UiStyle.space(180), getPreferredSize().height);
    }

    /** Lets BoxLayout allocate available width and measured height. */
    @Override public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
