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

/**
 * Told by the window, on the event thread, when its session changes: a folder was imported, or the session was
 * cleared, which gives no files.
 */
@FunctionalInterface
interface SessionListener {
    void sessionChanged(FitsFileInformation[] files);
}
