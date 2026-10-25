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

/**
 * A viewer window whose lifetime is tied to the open session or application.
 * All lifecycle methods run on the Swing event dispatch thread.
 */
public interface SessionViewer {

    /**
     * Closes the viewer and releases all session and image resources, cancelling background workers and
     * detaching listeners.
     */
    void closeForSessionChange();

    /** Pauses settings edits during application jobs; viewers without trial inputs have nothing to disable. */
    default void setSettingsEditable(boolean editable) {
    }
}
