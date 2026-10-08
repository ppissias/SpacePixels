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

import javax.swing.JFrame;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.prefs.Preferences;

/**
 * Size and position of the main window: restored from the last session when it still fits on a screen, otherwise
 * sized to the screen (85 % of the usable area, capped for large screens) and centred. Saved when the window closes.
 */
final class WindowPlacement {

    static final double SCREEN_FRACTION = 0.85;
    static final int MAX_WIDTH = 1500;
    static final int MAX_HEIGHT = 950;
    static final int MIN_WIDTH = 980;
    static final int MIN_HEIGHT = 560;

    private static final String KEY_X = "mainWindow.x";
    private static final String KEY_Y = "mainWindow.y";
    private static final String KEY_WIDTH = "mainWindow.width";
    private static final String KEY_HEIGHT = "mainWindow.height";
    private static final String KEY_MAXIMIZED = "mainWindow.maximized";

    private WindowPlacement() {
    }

    /** Applies the restored or default placement and saves the placement when the window closes. */
    static void install(JFrame frame) {
        Preferences preferences = preferences();
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setMinimumSize(new java.awt.Dimension(Math.min(MIN_WIDTH, usable.width), Math.min(MIN_HEIGHT, usable.height)));

        Rectangle saved = savedBounds(preferences);
        Rectangle bounds = saved != null && isMostlyVisible(saved) ? saved : defaultBounds(usable);
        frame.setBounds(bounds);
        if (saved != null && preferences != null && preferences.getBoolean(KEY_MAXIMIZED, false)) {
            frame.setExtendedState(frame.getExtendedState() | Frame.MAXIMIZED_BOTH);
        }

        // Remember the last normal (not maximized) bounds, so un-maximizing next time returns to them.
        Rectangle[] normalBounds = {bounds};
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                remember();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                remember();
            }

            private void remember() {
                if ((frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == 0 && frame.isShowing()) {
                    normalBounds[0] = frame.getBounds();
                }
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                save(preferences, normalBounds[0], (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0);
            }
        });
    }

    /** 85 % of the usable screen area, capped for large screens, never larger than the screen, centred. */
    static Rectangle defaultBounds(Rectangle usable) {
        int width = Math.min(usable.width, Math.max(Math.min(MIN_WIDTH, usable.width),
                Math.min(MAX_WIDTH, (int) Math.round(usable.width * SCREEN_FRACTION))));
        int height = Math.min(usable.height, Math.max(Math.min(MIN_HEIGHT, usable.height),
                Math.min(MAX_HEIGHT, (int) Math.round(usable.height * SCREEN_FRACTION))));
        return new Rectangle(usable.x + (usable.width - width) / 2, usable.y + (usable.height - height) / 2, width, height);
    }

    /** Whether most of the rectangle lies on one of the current screens (a monitor may have been removed). */
    private static boolean isMostlyVisible(Rectangle bounds) {
        long area = (long) bounds.width * bounds.height;
        if (area <= 0) {
            return false;
        }
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle visible = device.getDefaultConfiguration().getBounds().intersection(bounds);
            if (!visible.isEmpty() && (long) visible.width * visible.height >= area * 0.6) {
                return true;
            }
        }
        return false;
    }

    private static Rectangle savedBounds(Preferences preferences) {
        if (preferences == null) {
            return null;
        }
        int width = preferences.getInt(KEY_WIDTH, -1);
        int height = preferences.getInt(KEY_HEIGHT, -1);
        if (width < MIN_WIDTH / 2 || height < MIN_HEIGHT / 2) {
            return null;
        }
        return new Rectangle(preferences.getInt(KEY_X, 0), preferences.getInt(KEY_Y, 0), width, height);
    }

    private static void save(Preferences preferences, Rectangle bounds, boolean maximized) {
        if (preferences == null || bounds == null) {
            return;
        }
        try {
            preferences.putInt(KEY_X, bounds.x);
            preferences.putInt(KEY_Y, bounds.y);
            preferences.putInt(KEY_WIDTH, bounds.width);
            preferences.putInt(KEY_HEIGHT, bounds.height);
            preferences.putBoolean(KEY_MAXIMIZED, maximized);
            preferences.flush();
        } catch (Exception e) {
            ApplicationWindow.logger.fine("Could not save the window placement: " + e.getMessage());
        }
    }

    private static Preferences preferences() {
        try {
            return Preferences.userNodeForPackage(WindowPlacement.class);
        } catch (Exception e) {
            return null;
        }
    }
}
