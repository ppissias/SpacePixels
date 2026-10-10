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

import eu.startales.spacepixels.util.PlateSolutionCorrection;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The sky catalogue of the imported session and its plate-solution correction, shared by the main window (which
 * fetches, fits or loads them) and the frame viewers (which draw the catalogue and switch the correction on or off).
 * A viewer that is open when either arrives is told, and offers it without changing what it shows. Used on the Swing
 * thread only.
 */
final class SkyCatalogueModel {

    private static final SkyCatalogueModel SHARED = new SkyCatalogueModel();

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> correctionListeners = new CopyOnWriteArrayList<>();
    private SkyCatalogue catalogue;
    private PlateSolutionCorrection correction;
    private File sessionFolder;

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

    /** The folder of the session, where the correction is kept. */
    void setSessionFolder(File folder) {
        this.sessionFolder = folder;
    }

    /** The plate-solution correction fitted for the session, whether in use or not; null when there is none. */
    PlateSolutionCorrection getCorrection() {
        return correction;
    }

    boolean isCorrectionEnabled() {
        return correction != null && correction.enabled;
    }

    void setCorrection(PlateSolutionCorrection correction) {
        this.correction = correction;
        PlateSolutionCorrection.forget(sessionFolder);
        fireCorrectionChanged();
    }

    /**
     * Switches the correction on or off for the whole session. It is saved at once, so everything that resolves the
     * plate solution, including a detection run, uses the new setting.
     */
    void setCorrectionEnabled(boolean enabled) throws IOException {
        if (correction == null || correction.enabled == enabled) {
            return;
        }
        correction.enabled = enabled;
        try {
            PlateSolutionCorrection.save(sessionFolder, correction);
        } catch (IOException e) {
            correction.enabled = !enabled;
            throw e;
        } finally {
            PlateSolutionCorrection.forget(sessionFolder);
        }
        fireCorrectionChanged();
    }

    private void fireCorrectionChanged() {
        for (Runnable listener : correctionListeners) {
            listener.run();
        }
    }

    void addListener(Runnable listener) {
        listeners.add(listener);
    }

    void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    void addCorrectionListener(Runnable listener) {
        correctionListeners.add(listener);
    }

    void removeCorrectionListener(Runnable listener) {
        correctionListeners.remove(listener);
    }

    int listenerCount() {
        return listeners.size() + correctionListeners.size();
    }
}
