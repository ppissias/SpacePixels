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

import javax.swing.*;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.prefs.Preferences;

/**
 * The side panel of the frame viewers: their sections in a column that scrolls, on the right of the image. Its left
 * edge can be dragged to change the width, and a toolbar button hides it. The width and whether it is shown are
 * shared by the viewers and remembered.
 */
final class ViewerSidePanel {

    private static final String VISIBLE_KEY = "viewerSidePanel.visible";
    private static final String WIDTH_KEY = "viewerSidePanel.width";
    private static final int DEFAULT_WIDTH = 330;
    private static final int MIN_WIDTH = 250;
    private static final int MAX_WIDTH = 600;

    private final JPanel column = new ColumnPanel();
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JToggleButton toggle = new JToggleButton(new PanelIcon());
    private int width;

    ViewerSidePanel() {
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(column, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(UiStyle.space(16));
        scroll.setFocusable(false);
        JComponent handle = new JPanel();
        handle.setPreferredSize(new Dimension(UiStyle.space(5), 1));
        handle.setCursor(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR));
        Color line = UIManager.getColor("Separator.foreground");
        handle.setBorder(new MatteBorder(0, 1, 0, 0, line == null ? Color.GRAY : line));
        MouseAdapter resize = new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                Point onPanel = SwingUtilities.convertPoint(handle, e.getPoint(), panel);
                setWidth(width - onPanel.x);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                write(() -> preferences().putInt(WIDTH_KEY, width));
            }
        };
        handle.addMouseListener(resize);
        handle.addMouseMotionListener(resize);
        panel.add(handle, BorderLayout.WEST);
        panel.add(scroll, BorderLayout.CENTER);

        toggle.setToolTipText("Show or hide the side panel with the layers and the display stretch.");
        toggle.getAccessibleContext().setAccessibleName("Side panel");
        toggle.setRequestFocusEnabled(false);
        toggle.addActionListener(e -> {
            panel.setVisible(toggle.isSelected());
            write(() -> preferences().putBoolean(VISIBLE_KEY, toggle.isSelected()));
            panel.getParent().revalidate();
        });
        boolean visible = read(() -> preferences().getBoolean(VISIBLE_KEY, true), true);
        toggle.setSelected(visible);
        panel.setVisible(visible);
        setWidth(read(() -> preferences().getInt(WIDTH_KEY, DEFAULT_WIDTH), DEFAULT_WIDTH));
    }

    void add(JComponent section) {
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(section);
    }

    /** Puts a section above the others. */
    void addFirst(JComponent section) {
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(section, 0);
        column.revalidate();
    }

    /** The panel, for the east side of the viewer. */
    JComponent component() {
        return panel;
    }

    /** The toolbar button that shows or hides the panel. */
    JToggleButton toggleButton() {
        return toggle;
    }

    private void setWidth(int requested) {
        width = Math.max(UiStyle.space(MIN_WIDTH), Math.min(UiStyle.space(MAX_WIDTH), requested));
        panel.setPreferredSize(new Dimension(width, 10));
        // Beside the image the panel always gets this width, so the layouts around it must count it in full.
        panel.setMinimumSize(new Dimension(width, 10));
        panel.revalidate();
    }

    private static Preferences preferences() {
        return Preferences.userNodeForPackage(ViewerSidePanel.class);
    }

    private static <T> T read(java.util.function.Supplier<T> reader, T fallback) {
        try {
            return reader.get();
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }

    private static void write(Runnable writer) {
        try {
            writer.run();
        } catch (RuntimeException unavailable) {
            // The panel still works; it is just not remembered.
        }
    }

    /**
     * Fills the width of the scroll pane, so the sections wrap instead of scrolling sideways, and scrolls a control
     * that gets the keyboard focus, for example by Tab, into view.
     */
    private static final class ColumnPanel extends JPanel implements Scrollable {
        private final java.beans.PropertyChangeListener revealFocus = event -> {
            if (event.getNewValue() instanceof JComponent focused && SwingUtilities.isDescendingFrom(focused, this)) {
                focused.scrollRectToVisible(new Rectangle(focused.getSize()));
            }
        };

        @Override
        public void addNotify() {
            super.addNotify();
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner", revealFocus);
        }

        @Override
        public void removeNotify() {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("focusOwner", revealFocus);
            super.removeNotify();
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return UiStyle.space(16);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return visibleRect.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** A window with its right-hand column filled: the side panel. */
    private static final class PanelIcon implements Icon {
        @Override
        public int getIconWidth() {
            return UiStyle.space(18);
        }

        @Override
        public int getIconHeight() {
            return UiStyle.space(16);
        }

        @Override
        public void paintIcon(Component component, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fore = UIManager.getColor("Label.foreground");
                g2.setColor(fore == null ? Color.GRAY : fore);
                double s = getIconHeight() / 16.0;
                g2.translate(x, y);
                g2.scale(s, s);
                g2.setStroke(new BasicStroke(1.3f));
                g2.draw(new java.awt.geom.RoundRectangle2D.Double(1.5, 2.5, 15, 11, 3, 3));
                g2.fill(new java.awt.geom.Rectangle2D.Double(10.5, 3, 5.5, 10));
            } finally {
                g2.dispose();
            }
        }
    }
}
