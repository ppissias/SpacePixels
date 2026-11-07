package eu.startales.spacepixels.gui;

import com.formdev.flatlaf.ui.FlatLineBorder;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.List;

/**
 * One numbered step of the main window's workflow strip: its heading, its status directly under the heading, then its
 * controls. The steps of a strip share one height and one status height, with room for two status lines, so their first
 * controls line up and nothing moves when a status changes.
 */
final class WorkflowStep extends JPanel {
    private static final int RESERVED_STATUS_LINES = 2;

    private final List<WorkflowStep> strip;
    private final JComponent status;
    private final JPanel statusArea;

    /**
     * @param strip    the steps that line up with this one; this step adds itself
     * @param optional whether the heading says the step can be skipped
     * @param status   the step's status, which sets its own colours
     */
    WorkflowStep(List<WorkflowStep> strip, int number, String title, boolean optional, JComponent status, JComponent... rows) {
        this.strip = strip;
        this.status = status;
        strip.add(this);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        getAccessibleContext().setAccessibleName("Step " + number + ": " + title);
        add(heading(number, title, optional));
        statusArea = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                Dimension own = super.getPreferredSize();
                return new Dimension(own.width, statusHeight());
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, statusHeight());
            }
        };
        statusArea.setOpaque(false);
        statusArea.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(4), UiStyle.space(2), UiStyle.space(4), 0));
        statusArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusArea.add(status, BorderLayout.NORTH);
        add(statusArea);
        for (JComponent row : rows) {
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(row);
        }
        add(Box.createVerticalGlue());
    }

    /** Lets every step of the strip measure its status again; call it after a status changed. */
    static void statusChanged(List<WorkflowStep> strip) {
        for (WorkflowStep step : strip) {
            step.statusArea.invalidate();
            step.invalidate();
        }
    }

    /** The themes come with their own border colour, and the border follows a change of theme. */
    @Override
    public void updateUI() {
        super.updateUI();
        Color line = UIManager.getColor("Component.borderColor");
        setBorder(new FlatLineBorder(new Insets(6, 8, 6, 8), line != null ? line : Color.GRAY, 1f, 8));
    }

    /** As tall as the tallest step of the strip, so their tops and bottoms line up. */
    @Override
    public Dimension getPreferredSize() {
        Dimension own = super.getPreferredSize();
        int height = own.height;
        for (WorkflowStep other : strip) {
            if (other != this) {
                height = Math.max(height, other.getLayout().preferredLayoutSize(other).height);
            }
        }
        return new Dimension(own.width, height);
    }

    /** The tallest status of the strip, and at least the reserved lines. */
    private int statusHeight() {
        Insets insets = statusArea.getInsets();
        int height = RESERVED_STATUS_LINES * getFontMetrics(UiStyle.bodyFont()).getHeight();
        for (WorkflowStep step : strip) {
            height = Math.max(height, step.status.getPreferredSize().height);
        }
        return height + insets.top + insets.bottom;
    }

    private static JPanel heading(int number, String title, boolean optional) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(new Badge(number));
        row.add(Box.createHorizontalStrut(UiStyle.space(6)));
        JLabel name = new JLabel(title);
        name.putClientProperty("FlatLaf.styleClass", "h4");
        row.add(name);
        if (optional) {
            row.add(Box.createHorizontalGlue());
            row.add(Box.createHorizontalStrut(UiStyle.space(12)));
            JLabel caption = new JLabel("optional");
            caption.setForeground(UiStyle.helpColor());
            caption.putClientProperty("FlatLaf.styleClass", "small");
            row.add(caption);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /** The step's number in a small circle. */
    private static final class Badge extends JLabel {
        Badge(int number) {
            super(Integer.toString(number), SwingConstants.CENTER);
            putClientProperty("FlatLaf.styleClass", "semibold");
        }

        @Override
        public Dimension getPreferredSize() {
            int side = Math.max(UiStyle.space(20), getFontMetrics(getFont()).getHeight() + UiStyle.space(2));
            return new Dimension(side, side);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D circle = (Graphics2D) g.create();
            circle.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = UIManager.getColor("Button.background");
            Color line = UIManager.getColor("Component.borderColor");
            int side = Math.min(getWidth(), getHeight()) - 1;
            circle.setColor(fill != null ? fill : getBackground());
            circle.fillOval(0, 0, side, side);
            circle.setColor(line != null ? line : getForeground());
            circle.drawOval(0, 0, side, side);
            circle.dispose();
            super.paintComponent(g);
        }
    }
}
