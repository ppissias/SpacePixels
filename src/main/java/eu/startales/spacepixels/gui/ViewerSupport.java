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

import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.WcsSolutionResolver;

import javax.swing.*;
import java.awt.*;
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

    /** "Zoom:", Fit, 100 %, 200 % and 400 %, for a row of controls. */
    static List<JComponent> zoomControls(FrameView view) {
        List<JComponent> controls = new ArrayList<>();
        controls.add(new JLabel("Zoom:"));
        JButton fitButton = new JButton("Fit");
        fitButton.setToolTipText("Show the whole frame (F or double-click).");
        fitButton.addActionListener(e -> view.fit());
        controls.add(fitButton);
        for (int percent : new int[]{100, 200, 400}) {
            JButton zoomButton = new JButton(percent + " %");
            zoomButton.setToolTipText(percent == 100 ? "One frame pixel per screen pixel (1)." : "Zoom to " + percent + " %.");
            zoomButton.addActionListener(e -> view.zoomToScale(percent / 100.0));
            controls.add(zoomButton);
        }
        return controls;
    }

    /** The frame description in the middle, the cursor on the right and the keys underneath. */
    static JComponent statusBar(JLabel frameLabel, JLabel cursorLabel, String keyHint) {
        JLabel hint = new JLabel(keyHint);
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        JPanel status = new JPanel(new BorderLayout(12, 2));
        status.add(frameLabel, BorderLayout.CENTER);
        status.add(cursorLabel, BorderLayout.EAST);
        status.add(hint, BorderLayout.SOUTH);
        return status;
    }

    /** F fits, 1 shows 100 %; the stepping keys are bound by each viewer. */
    static void bindZoomKeys(JRootPane rootPane, FrameView view) {
        bind(rootPane, "F", "fit", view::fit);
        bind(rootPane, "1", "actualSize", () -> view.zoomToScale(1.0));
    }

    static void bind(JRootPane rootPane, String key, String name, Runnable action) {
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
        rootPane.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    /**
     * Makes these controls unable to take the keyboard focus. The viewers keep the focus on the frame view: a
     * focused button would take Space and a focused list the arrow keys.
     */
    static void removeFocus(Container container) {
        for (Component component : container.getComponents()) {
            component.setFocusable(false);
            if (component instanceof Container) {
                removeFocus((Container) component);
            }
        }
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
        private FitsFileInformation[] files;

        /** The frames of the sequence, which can share a plate solution. */
        void reset(FitsFileInformation[] files) {
            this.files = files;
            skyCoordinates.clear();
        }

        /** Resolves the plate solutions again, after the correction was switched on or off. */
        void forgetSolutions() {
            skyCoordinates.clear();
        }

        /** "x 120  y 340  value 1204  RA …  Dec …", or a blank when the cursor is not over the frame. */
        String describe(BlinkSequence.Frame frame, Point pixel) {
            if (frame == null || pixel == null || pixel.x >= frame.getWidth() || pixel.y >= frame.getHeight()) {
                return " ";
            }
            StringBuilder text = new StringBuilder(String.format(Locale.US, "x %d  y %d  value %d",
                    pixel.x, pixel.y, frame.valueAt(pixel.x, pixel.y)));
            WcsCoordinateTransformer transformer = skyCoordinatesOf(frame.getInfo());
            if (transformer != null) {
                WcsCoordinateTransformer.SkyCoordinate sky = transformer.pixelToSky(pixel.x, pixel.y);
                if (sky != null) {
                    text.append("  RA ").append(WcsCoordinateTransformer.formatRa(sky.getRaDegrees()))
                            .append("  Dec ").append(WcsCoordinateTransformer.formatDec(sky.getDecDegrees()));
                }
            }
            return text.toString();
        }

        private WcsCoordinateTransformer skyCoordinatesOf(FitsFileInformation info) {
            if (!skyCoordinates.containsKey(info)) {
                WcsCoordinateTransformer transformer = null;
                try {
                    WcsSolutionResolver.ResolvedWcsSolution solution = WcsSolutionResolver.resolve(info, files);
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
