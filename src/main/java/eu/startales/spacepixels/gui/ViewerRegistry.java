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

import eu.startales.spacepixels.util.FitsFileInformation;

import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tracks viewer windows associated with the open session and closes them when a new session is imported and at
 * application exit. Windows that are closed and disposed by the user are automatically unregistered.
 * All registration and lifecycle methods run on the Swing event dispatch thread.
 */
public final class ViewerRegistry {

    private static final Logger logger = Logger.getLogger(ViewerRegistry.class.getName());

    private final List<SessionViewer> viewers = new ArrayList<>();
    private final Map<Window, WindowListener> windowListeners = new IdentityHashMap<>();

    /**
     * Registers {@code viewer}. If {@code viewer} is a {@link Window}, a window listener is attached to unregister
     * it when the window is closed and disposed. Returns {@code viewer} for chained assignment.
     */
    public <V extends SessionViewer> V register(V viewer) {
        if (viewer == null || viewers.contains(viewer)) {
            return viewer;
        }

        viewers.add(viewer);
        if (viewer instanceof Window window) {
            WindowListener listener = new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    unregister(viewer);
                }
            };
            window.addWindowListener(listener);
            windowListeners.put(window, listener);
        }
        return viewer;
    }

    /**
     * Unregisters {@code viewer} and removes its window listener, if any.
     */
    public void unregister(SessionViewer viewer) {
        if (viewer == null) {
            return;
        }
        viewers.remove(viewer);
        if (viewer instanceof Window window) {
            WindowListener listener = windowListeners.remove(window);
            if (listener != null) {
                window.removeWindowListener(listener);
            }
        }
    }

    /** The number of currently registered viewers. */
    public int count() {
        return viewers.size();
    }

    /** Whether {@code viewer} is currently registered. */
    public boolean isRegistered(SessionViewer viewer) {
        return viewers.contains(viewer);
    }

    /**
     * Closes every registered viewer when a new session is imported.
     */
    public void sessionChanged(FitsFileInformation[] files) {
        closeAll();
    }

    /**
     * Closes every registered viewer, releases their resources, and clears the registry.
     */
    public void closeAll() {
        List<SessionViewer> snapshot = new ArrayList<>(viewers);
        for (SessionViewer viewer : snapshot) {
            unregister(viewer);
            try {
                viewer.closeForSessionChange();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing session viewer: " + viewer, e);
            }
        }
    }
}
