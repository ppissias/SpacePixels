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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Which overlay layers the frame viewers show and label. The application's {@link ViewerRegistry} holds one state for
 * all its viewers, so a layer switched off in Preview Frame is off in Blink too, and it is remembered for the next
 * start. Used on the Swing thread only.
 */
final class ViewerLayerState {

    /** An unchanging copy, for painting and for exports that must not follow later changes. */
    record Snapshot(Set<ViewerLayer> visible, Set<ViewerLayer> labelled) {
        Snapshot {
            visible = Set.copyOf(visible);
            labelled = Set.copyOf(labelled);
        }

        /** Every layer with its default choices. */
        static Snapshot defaults() {
            Set<ViewerLayer> visible = EnumSet.noneOf(ViewerLayer.class);
            Set<ViewerLayer> labelled = EnumSet.noneOf(ViewerLayer.class);
            for (ViewerLayer layer : ViewerLayer.values()) {
                if (layer.visibleByDefault) visible.add(layer);
                if (layer.labelsByDefault) labelled.add(layer);
            }
            return new Snapshot(visible, labelled);
        }

        /** Whether the layer is drawn: it is on, and so is every group above it. */
        boolean shows(ViewerLayer layer) {
            for (ViewerLayer current = layer; current != null; current = current.parent) {
                if (!visible.contains(current)) return false;
            }
            return true;
        }

        /** Whether the layer's labels are written, when it is drawn. */
        boolean labels(ViewerLayer layer) {
            return labelled.contains(layer);
        }
    }

    /** Where the choices are saved, or null to keep them in memory only. */
    private final Preferences storage;
    private final Set<ViewerLayer> visible = EnumSet.noneOf(ViewerLayer.class);
    private final Set<ViewerLayer> labelled = EnumSet.noneOf(ViewerLayer.class);
    /** Held weakly: each viewer keeps its own listener for as long as it lives. */
    private final List<WeakReference<Runnable>> listeners = new ArrayList<>();

    /** Reads the saved choices; missing or unreadable ones take the layer's default. */
    ViewerLayerState(Preferences storage) {
        this.storage = storage;
        for (ViewerLayer layer : ViewerLayer.values()) {
            if (read(layer.name() + ".visible", layer.visibleByDefault)) visible.add(layer);
            if (read(layer.name() + ".labels", layer.labelsByDefault)) labelled.add(layer);
        }
    }

    /** The choices saved under the viewers' preference node, or the defaults kept in memory when it is unavailable. */
    static ViewerLayerState fromPreferences() {
        Preferences storage;
        try {
            storage = Preferences.userNodeForPackage(ViewerLayerState.class).node("viewerLayers");
        } catch (RuntimeException unavailable) {
            storage = null;
        }
        return new ViewerLayerState(storage);
    }

    boolean isVisible(ViewerLayer layer) {
        return visible.contains(layer);
    }

    boolean isLabelled(ViewerLayer layer) {
        return labelled.contains(layer);
    }

    /** The outermost group above this layer that is off, the one to switch on first; null when none is off. */
    ViewerLayer offGroup(ViewerLayer layer) {
        ViewerLayer off = null;
        for (ViewerLayer group = layer.parent; group != null; group = group.parent) {
            if (!visible.contains(group)) off = group;
        }
        return off;
    }

    void setVisible(ViewerLayer layer, boolean on) {
        if (on ? visible.add(layer) : visible.remove(layer)) {
            write(layer.name() + ".visible", on);
            changed();
        }
    }

    void setLabelled(ViewerLayer layer, boolean on) {
        if (on ? labelled.add(layer) : labelled.remove(layer)) {
            write(layer.name() + ".labels", on);
            changed();
        }
    }

    void toggleVisible(ViewerLayer layer) {
        setVisible(layer, !isVisible(layer));
    }

    Snapshot snapshot() {
        return new Snapshot(visible, labelled);
    }

    /** Runs {@code listener} after each change. The state holds it weakly, so the caller must keep a reference. */
    void addListener(Runnable listener) {
        listeners.add(new WeakReference<>(listener));
    }

    void removeListener(Runnable listener) {
        listeners.removeIf(reference -> reference.get() == null || reference.get() == listener);
    }

    private void changed() {
        listeners.removeIf(reference -> reference.get() == null);
        for (WeakReference<Runnable> reference : List.copyOf(listeners)) {
            Runnable listener = reference.get();
            if (listener != null) listener.run();
        }
    }

    private boolean read(String key, boolean fallback) {
        try {
            return storage == null ? fallback : storage.getBoolean(key, fallback);
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }

    private void write(String key, boolean value) {
        try {
            if (storage != null) storage.putBoolean(key, value);
        } catch (RuntimeException unavailable) {
            // The choice still applies until SpacePixels closes.
        }
    }
}
