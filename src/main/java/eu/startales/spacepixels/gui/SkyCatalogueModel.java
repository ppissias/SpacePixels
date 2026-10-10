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

import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The sky catalogue of the imported session, shared by the main window (which fetches or loads it) and the frame
 * viewers (which draw it). A viewer that is open when the catalogue arrives is told, and offers it without changing
 * what it shows. Used on the Swing thread only.
 */
final class SkyCatalogueModel {

    private static final SkyCatalogueModel SHARED = new SkyCatalogueModel();

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private SkyCatalogue catalogue;

    static SkyCatalogueModel shared() {
        return SHARED;
    }

    /** The catalogue of the session, or null. */
    SkyCatalogue get() {
        return catalogue;
    }

    void set(SkyCatalogue catalogue) {
        this.catalogue = catalogue;
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    void addListener(Runnable listener) {
        listeners.add(listener);
    }

    void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    int listenerCount() {
        return listeners.size();
    }
}
