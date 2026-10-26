package eu.startales.spacepixels.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;

/** Shared, font-aware limits for application windows, with scrolling when the complete controls cannot fit a display. */
final class WindowSizing {
    /** Stateless layout helpers; listeners belong to the individual window, never to a global registry. */
    private WindowSizing() { }

    /** Protects the current content minimum and a useful baseline, expressed in unscaled logical pixels. */
    static void install(Window window, int width, int height) {
        window.addComponentListener(new ComponentAdapter() {
            private boolean updating;

            /** Re-measures wrapped controls after each resize before allowing a smaller height. */
            private void update(boolean fit) {
                if (updating) return;
                updating = true;
                try {
                    window.validate();
                    Rectangle usable = usableBounds(window);
                    Insets insets = window.getInsets();
                    Container content = window instanceof RootPaneContainer root ? root.getContentPane() : window;
                    Dimension controls = content.getMinimumSize();
                    Dimension minimum = new Dimension(
                            Math.min(usable.width, Math.max(UiStyle.space(width), controls.width + insets.left + insets.right)),
                            Math.min(usable.height, Math.max(UiStyle.space(height), controls.height + insets.top + insets.bottom)));
                    if (!minimum.equals(window.getMinimumSize())) window.setMinimumSize(minimum);
                    if (fit) {
                        window.setSize(Math.min(window.getWidth(), usable.width), Math.min(window.getHeight(), usable.height));
                        window.setLocation(Math.max(usable.x, Math.min(window.getX(), usable.x + usable.width - window.getWidth())),
                                Math.max(usable.y, Math.min(window.getY(), usable.y + usable.height - window.getHeight())));
                    }
                } finally { updating = false; }
            }

            /** Sets initial native limits with the window's final decoration insets. */
            @Override public void componentShown(ComponentEvent event) { update(true); }

            /** Wrapping changes the required height as the available width changes. */
            @Override public void componentResized(ComponentEvent event) { update(false); }

            /** A move to another monitor must also release limits that no longer fit that monitor. */
            @Override public void componentMoved(ComponentEvent event) { update(false); }
        });
        Rectangle usable = usableBounds(window);
        window.setMinimumSize(new Dimension(Math.min(usable.width, UiStyle.space(width)),
                Math.min(usable.height, UiStyle.space(height))));
    }

    /** The current monitor's usable logical bounds, excluding task bars and desktop reservations. */
    static Rectangle usableBounds(Component component) {
        GraphicsConfiguration configuration = component.getGraphicsConfiguration();
        if (configuration == null) configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle bounds = new Rectangle(configuration.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width -= insets.left + insets.right;
        bounds.height -= insets.top + insets.bottom;
        return bounds;
    }

    /** Keeps complete viewer controls visible normally and scrollable when very large fonts exceed half the screen. */
    static JComponent viewerControls(JComponent controls) {
        WidthTrackingPanel content = new WidthTrackingPanel();
        content.add(controls, BorderLayout.CENTER);
        JScrollPane scroll = new JScrollPane(content) {
            /** Wrapped rows change the toolbar's allocation in the enclosing BorderLayout, not just its viewport. */
            @Override public boolean isValidateRoot() { return false; }

            /** Reserves the actual wrapped controls height up to a screen-bounded scrolling threshold. */
            @Override public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                size.height = Math.min(size.height, usableBounds(this).height / 2);
                return size;
            }

            /** Prevents BorderLayout shrinking the controls to the default tiny scroll-pane minimum. */
            @Override public Dimension getMinimumSize() {
                Dimension size = super.getMinimumSize();
                size.width = Math.max(size.width, controls.getMinimumSize().width);
                size.height = getPreferredSize().height;
                return size;
            }
        };
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setFocusable(false);
        return scroll;
    }

    /** A form's native minimum shows all rows if the display permits, retaining scroll access at larger sizes. */
    static JScrollPane form(JComponent controls) {
        return new JScrollPane(controls) {
            /** Leaves space for title bars and bottom actions instead of growing a dialog beyond the monitor. */
            @Override public Dimension getMinimumSize() {
                Dimension size = super.getMinimumSize();
                int available = Math.max(80, usableBounds(this).height - UiStyle.space(140));
                Insets insets = getInsets();
                size.width = Math.max(size.width, controls.getMinimumSize().width + insets.left + insets.right
                        + getVerticalScrollBar().getPreferredSize().width);
                size.height = Math.max(size.height, Math.min(controls.getPreferredSize().height, available));
                return size;
            }
        };
    }

    /** A standard chooser with the same protected dialog sizing as other application popups. */
    static JFileChooser fileChooser(File initial) {
        return new JFileChooser(initial) {
            /** Applies limits to the chooser's actual owning dialog after Swing has assembled its controls. */
            @Override protected JDialog createDialog(Component parent) {
                JDialog dialog = super.createDialog(parent);
                install(dialog, 600, 400);
                return dialog;
            }
        };
    }
}
