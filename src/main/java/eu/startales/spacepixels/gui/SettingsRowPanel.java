package eu.startales.spacepixels.gui;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;

/** Shared content-sized settings row with a wrapping text column and controls at their natural size. */
final class SettingsRowPanel extends JPanel {
    private final WrappingHelpLabel description;
    private final JComponent controls;

    /** Owns the supplied title and control components; descriptions keep inline HTML and never fix a height. */
    SettingsRowPanel(JLabel title, String help, JComponent controls) {
        super(new BorderLayout(UiStyle.space(20), 0));
        this.controls = controls;
        description = new WrappingHelpLabel(help, 480);
        title.setFont(UiStyle.bodyFont().deriveFont(java.awt.Font.BOLD));
        JPanel text = new JPanel(new BorderLayout(0, UiStyle.space(3)));
        text.add(title, BorderLayout.NORTH);
        text.add(description, BorderLayout.CENTER);
        add(text, BorderLayout.CENTER);
        JPanel controlHolder = new JPanel(new BorderLayout());
        controlHolder.add(controls, BorderLayout.NORTH);
        add(controlHolder, BorderLayout.EAST);
        setBorder(BorderFactory.createEmptyBorder(UiStyle.space(5), 0, UiStyle.space(15), 0));
        setAlignmentX(LEFT_ALIGNMENT);
    }

    /** Re-measures text for the parent width before BoxLayout chooses this row's height. */
    @Override public Dimension getPreferredSize() {
        if (description != null) {
            Insets insets = getInsets();
            int available = getParent() == null ? 0 : getParent().getWidth();
            if (getParent() != null) {
                Insets parentInsets = getParent().getInsets();
                available -= parentInsets.left + parentInsets.right;
            }
            int width = available > 0
                    ? available - controls.getPreferredSize().width - UiStyle.space(20) - insets.left - insets.right
                    : UiStyle.space(480);
            description.wrapAt(Math.max(UiStyle.space(180), width));
        }
        return super.getPreferredSize();
    }

    /** Rows span the form width while keeping their content-driven preferred height. */
    @Override public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
