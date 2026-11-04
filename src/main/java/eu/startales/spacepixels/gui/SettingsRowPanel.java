package eu.startales.spacepixels.gui;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

/** Wrapping settings text beside a shared input column and a permanently reserved Reset slot. */
final class SettingsRowPanel extends JPanel {
    private final WrappingHelpLabel description;
    private final JComponent controls;
    private final JPanel controlHolder = new JPanel(new BorderLayout());
    private final JPanel controlGroup = new JPanel(new BorderLayout(0, UiStyle.space(6)));
    private final JPanel resetSlot = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final ControlColumn column;
    private JButton reset;

    /** Measures the widest detailed-page controls on the EDT, including hidden Reset buttons. */
    static final class ControlColumn {
        private final List<SettingsRowPanel> rows = new ArrayList<>();

        /** Creates one column owner for a settings navigator, without shared application state. */
        ControlColumn() { }

        /** Fits the widest input and its Reset slot, without letting auxiliary sliders widen every page. */
        int width() {
            int width = 0;
            for (var row : rows) {
                int resetWidth = row.reset == null ? 0 : row.reset.getPreferredSize().width;
                width = Math.max(width, row.controls.getPreferredSize().width + UiStyle.space(8) + resetWidth);
            }
            return width;
        }
    }

    /** Owns the supplied title and control components; descriptions keep inline HTML and never fix a height. */
    SettingsRowPanel(JLabel title, String help, JComponent controls, ControlColumn column) {
        super(new BorderLayout(UiStyle.space(20), 0));
        this.controls = controls;
        this.column = column;
        column.rows.add(this);
        description = new WrappingHelpLabel(help, 480);
        title.setFont(UiStyle.bodyFont().deriveFont(java.awt.Font.BOLD));
        JPanel text = new JPanel(new BorderLayout(0, UiStyle.space(3)));
        text.add(title, BorderLayout.NORTH);
        text.add(description, BorderLayout.CENTER);
        add(text, BorderLayout.CENTER);
        JPanel top = new JPanel(new BorderLayout(UiStyle.space(8), 0));
        top.add(controls, BorderLayout.WEST);
        top.add(resetSlot, BorderLayout.CENTER);
        controlGroup.add(top, BorderLayout.NORTH);
        controlHolder.add(controlGroup, BorderLayout.NORTH);
        add(controlHolder, BorderLayout.EAST);
        setBorder(BorderFactory.createEmptyBorder(UiStyle.space(5), 0, UiStyle.space(15), 0));
        setAlignmentX(LEFT_ALIGNMENT);
    }

    /** Reserves the button's natural size even when the saved setting hides it. */
    void setResetButton(JButton button) {
        reset = button;
        resetSlot.add(button);
    }

    /** Places an auxiliary slider beneath the input line, using the existing compact column width. */
    void setAccessory(JComponent accessory) {
        controlGroup.add(accessory, BorderLayout.SOUTH);
    }

    /** Re-measures text for the parent width before BoxLayout chooses this row's height. */
    @Override public Dimension getPreferredSize() {
        if (description != null) {
            if (reset != null) resetSlot.setPreferredSize(reset.getPreferredSize());
            controlHolder.setPreferredSize(new Dimension(column.width(), controlGroup.getPreferredSize().height));
            Insets insets = getInsets();
            int available = getParent() == null ? 0 : getParent().getWidth();
            if (getParent() != null) {
                Insets parentInsets = getParent().getInsets();
                available -= parentInsets.left + parentInsets.right;
            }
            int width = available > 0
                    ? available - column.width() - UiStyle.space(20) - insets.left - insets.right
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
