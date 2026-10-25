package eu.startales.spacepixels.gui;

import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.FlowLayout;

/** Control rows that reserve the height of every wrapped line, including inside a vertical BoxLayout. */
final class WrappingControlsPanel extends JPanel {
    /** Creates a left-aligned row; gaps are pixels in the current look and feel. */
    WrappingControlsPanel(int horizontalGap, int verticalGap) {
        super(new WrapLayout(FlowLayout.LEFT, horizontalGap, verticalGap));
    }

    /** Re-measures enclosing BoxLayouts after a resize changes the number of rows. */
    @Override public void setBounds(int x, int y, int width, int height) {
        boolean changed = width != getWidth();
        super.setBounds(x, y, width, height);
        if (changed) javax.swing.SwingUtilities.invokeLater(this::revalidate);
    }

    /** Keeps the measured row height instead of allowing BoxLayout to shrink wrapped controls. */
    @Override public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** A row can wrap down to the width of its widest individual control. */
    @Override public Dimension getMinimumSize() {
        int width = 0;
        for (java.awt.Component child : getComponents()) {
            if (child.isVisible()) width = Math.max(width, child.getPreferredSize().width);
        }
        java.awt.Insets insets = getInsets();
        WrapLayout layout = (WrapLayout) getLayout();
        return new Dimension(width + insets.left + insets.right + 2 * layout.getHgap(), getPreferredSize().height);
    }
}
