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

import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.fits.WcsSolutionResolver;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the frame viewers (Blink, Preview Frame, Manual Transient Inspection) share, so they look and work the same:
 * the zoom buttons, the status bar, the frame and cursor descriptions and the key bindings.
 */
final class ViewerSupport {

    private ViewerSupport() {
    }

    /**
     * "Zoom" and one group of Fit, 100 %, 200 % and 400 %, for a toolbar. The button of the zoom shown is selected;
     * none is after scrolling to another zoom.
     */
    static JComponent zoomControl(FrameView view) {
        JPanel control = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        control.setOpaque(false);
        JLabel label = new JLabel("Zoom");
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, UiStyle.space(6)));
        control.add(label);
        ButtonGroup group = new ButtonGroup();
        List<JToggleButton> buttons = new ArrayList<>();
        double[] scales = {0, 1, 2, 4};
        for (double scale : scales) {
            JToggleButton button = new JToggleButton(scale == 0 ? "Fit" : Math.round(scale * 100) + " %");
            button.putClientProperty("JButton.buttonType", "toolBarButton");
            button.setToolTipText(scale == 0 ? "Show the whole frame (F or double-click)."
                    : scale == 1 ? "One frame pixel per screen pixel (1)." : "Zoom to " + Math.round(scale * 100) + " %.");
            button.addActionListener(e -> {
                if (scale == 0) view.fit();
                else view.zoomToScale(scale);
            });
            group.add(button);
            buttons.add(button);
            control.add(button);
        }
        Runnable update = () -> {
            int selected = -1;
            if (view.isFitted()) {
                selected = 0;
            } else {
                for (int i = 1; i < scales.length; i++) {
                    if (Math.abs(view.zoomPercent() - scales[i] * 100) < 0.5) selected = i;
                }
            }
            if (selected < 0) group.clearSelection();
            else buttons.get(selected).setSelected(true);
        };
        view.setZoomListener(update);
        view.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                update.run();
            }
        });
        update.run();
        return control;
    }

    /** Brings a viewer that stays open back in front of the other windows, also from the task bar. */
    static void bringToFront(JFrame viewer) {
        int state = viewer.getExtendedState();
        if ((state & Frame.ICONIFIED) != 0) {
            viewer.setExtendedState(state & ~Frame.ICONIFIED);
        }
        viewer.toFront();
    }

    /** A thin vertical line between groups of toolbar controls. */
    static JComponent toolbarSeparator() {
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setPreferredSize(new Dimension(UiStyle.space(12), UiStyle.space(22)));
        return separator;
    }

    /** One key or mouse action of a viewer and what it does, for the Shortcuts window. */
    record Shortcut(String keys, String action) { }

    /**
     * The one-line status bar: the frame on the left; the cursor readout and the Shortcuts button on the right. The
     * button and the ? key open the viewer's list of keys, which its controls' tooltips also name.
     */
    static JComponent statusBar(JLabel frameLabel, JLabel cursorLabel, JRootPane rootPane, String viewer, List<Shortcut> shortcuts) {
        JButton help = new JButton("? Shortcuts");
        help.putClientProperty("JButton.buttonType", "borderless");
        help.setToolTipText("The keys and mouse actions of this window (?).");
        help.addActionListener(e -> showShortcuts(rootPane, viewer, shortcuts));
        bind(rootPane, "typed ?", "shortcuts", () -> showShortcuts(rootPane, viewer, shortcuts));
        cursorLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        JPanel right = new JPanel(new BorderLayout(UiStyle.space(12), 0));
        right.add(cursorLabel, BorderLayout.CENTER);
        right.add(help, BorderLayout.EAST);
        JPanel status = new JPanel(new BorderLayout(UiStyle.space(12), 0));
        status.add(frameLabel, BorderLayout.CENTER);
        status.add(right, BorderLayout.EAST);
        return status;
    }

    /** Shows a viewer's keys and mouse actions in a small window; Esc or Close closes it. */
    static void showShortcuts(Component owner, String viewer, List<Shortcut> shortcuts) {
        shortcutsDialog(owner, viewer, shortcuts).setVisible(true);
    }

    /** The Shortcuts window of a viewer, laid out but not shown. */
    static JDialog shortcutsDialog(Component owner, String viewer, List<Shortcut> shortcuts) {
        Window window = owner instanceof Window w ? w : SwingUtilities.getWindowAncestor(owner);
        JDialog dialog = new JDialog(window, viewer + " · Shortcuts", Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel list = new JPanel(new GridBagLayout());
        list.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(12), UiStyle.space(16), UiStyle.space(8), UiStyle.space(16)));
        int row = 0;
        for (Shortcut shortcut : shortcuts) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = row++;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(UiStyle.space(2), 0, UiStyle.space(2), UiStyle.space(16));
            JLabel keys = new JLabel(shortcut.keys());
            keys.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD));
            list.add(keys, c);
            c.gridx = 1;
            c.weightx = 1;
            c.insets = new Insets(UiStyle.space(2), 0, UiStyle.space(2), 0);
            list.add(new JLabel(shortcut.action()), c);
        }
        JLabel note = new JLabel("The keys work while the image has the keyboard focus; click the image to give it back.");
        note.setForeground(UiStyle.helpColor());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        JPanel bottom = new JPanel(new BorderLayout(UiStyle.space(12), 0));
        bottom.setBorder(BorderFactory.createEmptyBorder(0, UiStyle.space(16), UiStyle.space(12), UiStyle.space(16)));
        bottom.add(note, BorderLayout.CENTER);
        bottom.add(close, BorderLayout.EAST);
        JPanel content = new JPanel(new BorderLayout());
        content.add(list, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(close);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        AppIcon.apply(dialog);
        dialog.pack();
        dialog.setLocationRelativeTo(window);
        return dialog;
    }

    /** The keys and mouse actions every frame viewer shares, after its own. */
    static List<Shortcut> commonShortcuts() {
        return List.of(
                new Shortcut("T", "Show or hide the tracks"),
                new Shortcut("V", "Show or hide the variable-star candidates"),
                new Shortcut("A", "Show or hide the catalog annotations"),
                new Shortcut("C", "Align markers to the corrected plate solution"),
                new Shortcut("F", "Fit the whole frame"),
                new Shortcut("1", "100 % zoom"),
                new Shortcut("Scroll", "Zoom around the cursor"),
                new Shortcut("Drag", "Pan"),
                new Shortcut("Double-click", "Switch between fit and 100 %"),
                new Shortcut("Tab", "Reach the controls; Esc returns to the image"),
                new Shortcut("?", "This list"));
    }

    /** F fits, 1 shows 100 %; the stepping keys are bound by each viewer. */
    static void bindZoomKeys(JRootPane rootPane, FrameView view) {
        rootPane.putClientProperty("spacepixels.navigationView", view);
        bind(rootPane, "F", "fit", view::fit);
        bind(rootPane, "1", "actualSize", () -> view.zoomToScale(1.0));
    }

    /** Binds image-navigation keys only while the image owns focus; Escape from controls returns to the image. */
    static void bind(JRootPane rootPane, String key, String name, Runnable action) {
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
        rootPane.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                Object navigationView = rootPane.getClientProperty("spacepixels.navigationView");
                if ("ESCAPE".equals(key) && focus != navigationView && navigationView instanceof FrameView view) {
                    view.requestFocusInWindow();
                } else {
                    action.run();
                }
            }

            @Override
            public boolean isEnabled() {
                Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                return MenuSelectionManager.defaultManager().getSelectedPath().length == 0
                        && focus != null && (focus == rootPane.getClientProperty("spacepixels.navigationView")
                        || ("ESCAPE".equals(key) && SwingUtilities.isDescendingFrom(focus, rootPane)));
            }
        });
    }

    /**
     * Retains Tab access to controls. Ordinary button clicks leave image navigation focused; deliberate Tab
     * navigation lets buttons own Space and lets sliders, numeric editors and combo boxes own their arrows.
     */
    static void prepareControls(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof AbstractButton button) button.setRequestFocusEnabled(false);
            if (component instanceof Container child) {
                prepareControls(child);
            }
        }
    }

    /** The dataset folder name shown in viewer titles, or the file name when it has no parent. */
    static String sessionName(String filePath) {
        return sessionName(filePath == null ? null : new File(filePath));
    }

    /** The dataset folder name for a frame, or empty when the frame is absent. */
    static String sessionName(FitsFileInformation info) {
        return info == null ? "" : sessionName(info.getFilePath());
    }

    /** The dataset folder name, or the file name when it has no parent. */
    static String sessionName(File file) {
        if (file == null) return "";
        return file.getParentFile() != null ? file.getParentFile().getName() : file.getName();
    }

    /**
     * Installs the Display stretch section for {@code panel} in {@code holder}, replacing a section bound to another
     * panel. The same method serves Blink and the sequence viewers; {@code redraw} is the viewer's own refresh.
     */
    static StretchControls bindStretch(StretchControls current, StretchPanel panel, Runnable redraw, JComponent holder,
                                       java.util.function.Supplier<FitsFileInformation> currentFrame) {
        if (current != null && current.getPanel() == panel) return current;
        if (current != null) current.detach();
        StretchControls next = new StretchControls(panel, redraw, currentFrame);
        holder.removeAll();
        holder.add(next.getSection(), BorderLayout.CENTER);
        holder.revalidate();
        return next;
    }

    /** Keeps a label as wide as this text, so the controls next to it do not move when its value changes. */
    static void fixWidth(JLabel label, String widestText) {
        String text = label.getText();
        label.setText(widestText);
        Dimension size = label.getPreferredSize();
        label.setPreferredSize(new Dimension(size.width + 4, size.height));
        label.setText(text);
    }

    /** "Frame 7 of 20 · name · time · +12 min 30 s"; {@code first} is the first frame of the sequence, or null. */
    static String frameText(int index, int count, FitsFileInformation info, FitsFileInformation first) {
        StringBuilder text = new StringBuilder(String.format(Locale.US, "Frame %d of %d · %s", index + 1, count, info.getFileName()));
        long timestamp = info.getObservationTimestamp();
        if (timestamp > 0) {
            text.append(" · ").append(info.getObservationDate());
            long start = first == null ? 0 : first.getObservationTimestamp();
            if (start > 0 && index > 0) {
                long seconds = Math.round((timestamp - start) / 1000.0);
                text.append(seconds >= 0 ? " · +" : " · −").append(formatElapsed(Math.abs(seconds)));
            }
        }
        return text.toString();
    }

    static String formatElapsed(long seconds) {
        if (seconds < 60) {
            return seconds + " s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " min " + (seconds % 60) + " s";
        }
        return (minutes / 60) + " h " + (minutes % 60) + " min";
    }

    /** The cursor readout: pixel position, value and, on a plate-solved frame, RA and Dec. */
    static final class CursorReadout {
        /** Sky coordinates of each frame, resolved when the cursor first moves over it (null: not solved). */
        private final Map<FitsFileInformation, WcsCoordinateTransformer> skyCoordinates = new IdentityHashMap<>();
        private java.util.function.Function<FitsFileInformation, WcsSolutionResolver.ResolvedWcsSolution> resolver;

        /** Forgets the frames: the cursor shows pixel coordinates only until the next reset. */
        void clear() {
            reset(null, info -> null);
        }

        void reset(FitsFileInformation[] files,
                   java.util.function.Function<FitsFileInformation, WcsSolutionResolver.ResolvedWcsSolution> resolver) {
            this.resolver = resolver;
            skyCoordinates.clear();
        }

        /** Resolves the plate solutions again, after the correction was switched on or off. */
        void forgetSolutions() {
            skyCoordinates.clear();
        }

        /** "x 120, y 340 · RA …, Dec … · value 1204", or a blank when the cursor is not over the frame. */
        String describe(BlinkSequence.Frame frame, Point pixel) {
            if (frame == null || pixel == null || pixel.x >= frame.getWidth() || pixel.y >= frame.getHeight()) {
                return " ";
            }
            StringBuilder text = new StringBuilder(String.format(Locale.US, "x %d, y %d", pixel.x, pixel.y));
            WcsCoordinateTransformer transformer = skyCoordinatesOf(frame.getInfo());
            if (transformer != null) {
                WcsCoordinateTransformer.SkyCoordinate sky = transformer.pixelToSky(pixel.x, pixel.y);
                if (sky != null) {
                    text.append(" · RA ").append(WcsCoordinateTransformer.formatRa(sky.getRaDegrees()))
                            .append(", Dec ").append(WcsCoordinateTransformer.formatDec(sky.getDecDegrees()));
                }
            }
            return text.append(String.format(Locale.US, " · value %d", frame.valueAt(pixel.x, pixel.y))).toString();
        }

        private WcsCoordinateTransformer skyCoordinatesOf(FitsFileInformation info) {
            if (!skyCoordinates.containsKey(info)) {
                WcsCoordinateTransformer transformer = null;
                try {
                    WcsSolutionResolver.ResolvedWcsSolution solution = resolver.apply(info);
                    transformer = solution != null ? solution.getTransformer() : null;
                } catch (Exception ignored) {
                    // No usable plate solution: the cursor shows pixel coordinates only.
                }
                skyCoordinates.put(info, transformer);
            }
            return skyCoordinates.get(info);
        }
    }
}
