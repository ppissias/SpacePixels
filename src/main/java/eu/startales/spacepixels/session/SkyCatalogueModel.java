/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.session;

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
public final class SkyCatalogueModel {


    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> correctionListeners = new CopyOnWriteArrayList<>();
    private SkyCatalogue catalogue;
    private PlateSolutionCorrection correction;
    private File sessionFolder;
    private SessionPersistence persistence;
    private PlateSolutionCorrection savedCorrection;
    private Throwable saveFailure;
    private long revision;
    private boolean closed;

    public void setPersistence(SessionPersistence persistence) { this.persistence = persistence; }
    public Throwable getSaveFailure() { return saveFailure; }
    public boolean isClosed() { return closed; }
    public void close() { closed = true; }


    /** The catalogue of the session, or null. */
    public SkyCatalogue get() {
        return catalogue;
    }

    public void set(SkyCatalogue catalogue) {
        this.catalogue = catalogue;
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    /** The folder of the session, where the correction is kept. */
    public void setSessionFolder(File folder) {
        this.sessionFolder = folder;
    }

    /** The plate-solution correction fitted for the session, whether in use or not; null when there is none. */
    public PlateSolutionCorrection getCorrection() {
        return correction;
    }

    public boolean isCorrectionEnabled() {
        return correction != null && correction.enabled;
    }

    public void setCorrection(PlateSolutionCorrection correction) {
        this.correction = correction;
        savedCorrection = correction == null ? null : correction.copy();
        saveFailure = null;
        revision++;
        fireCorrectionChanged();
    }

    /**
     * Switches the correction immediately in memory. Desktop sessions queue a detached snapshot for saving;
     * detection jobs wait for outstanding writes before resolving the saved plate solution.
     */
    public void setCorrectionEnabled(boolean enabled) throws IOException {
        if (closed) throw new IOException("This session is closed.");
        if (correction == null || !correction.isNeeded()) {
            return;
        }
        PlateSolutionCorrection updated = correction.copy();
        updated.enabled = enabled;
        updated.userEnabled = enabled;
        saveCorrection(updated);
    }

    public void saveCorrection(PlateSolutionCorrection updated) throws IOException {
        if (closed) throw new IOException("This session is closed.");
        PlateSolutionCorrection snapshot = updated.copy();
        if (persistence == null) {
            PlateSolutionCorrection.save(sessionFolder, snapshot);
            setCorrection(updated);
            return;
        }
        correction = updated;
        saveFailure = null;
        long version = ++revision;
        persistence.save(() -> persistence.storage().store().saveCorrection(sessionFolder, snapshot), failure -> {
            if (failure == null) savedCorrection = snapshot;
            if (revision != version) return;
            saveFailure = failure;
            if (failure != null) correction = savedCorrection == null ? null : savedCorrection.copy();
            fireCorrectionChanged();
        });
        fireCorrectionChanged();
    }

    private void fireCorrectionChanged() {
        for (Runnable listener : correctionListeners) {
            listener.run();
        }
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public void addCorrectionListener(Runnable listener) {
        correctionListeners.add(listener);
    }

    public void removeCorrectionListener(Runnable listener) {
        correctionListeners.remove(listener);
    }

    public int listenerCount() {
        return listeners.size() + correctionListeners.size();
    }
}
