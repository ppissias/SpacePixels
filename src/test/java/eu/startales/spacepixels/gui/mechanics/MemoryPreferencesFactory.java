package eu.startales.spacepixels.gui.mechanics;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/**
 * Java preferences held in memory for the GUI mechanics tests, selected by the {@code guiMechanicsTest} task. The
 * tests then start from the defaults, whatever the user has chosen, and can never change the user's own preferences,
 * also when a run is killed.
 */
public final class MemoryPreferencesFactory implements PreferencesFactory {

    private static final Preferences ROOT = new MemoryNode(null, "");

    @Override
    public Preferences systemRoot() {
        return ROOT;
    }

    @Override
    public Preferences userRoot() {
        return ROOT;
    }

    /** One preference node: its values and child nodes in plain maps. */
    private static final class MemoryNode extends AbstractPreferences {
        private final MemoryNode parent;
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, MemoryNode> children = new HashMap<>();

        MemoryNode(MemoryNode parent, String name) {
            super(parent, name);
            this.parent = parent;
        }

        @Override
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }

        @Override
        protected String getSpi(String key) {
            return values.get(key);
        }

        @Override
        protected void removeSpi(String key) {
            values.remove(key);
        }

        @Override
        protected void removeNodeSpi() {
            parent.children.remove(name());
        }

        @Override
        protected String[] keysSpi() {
            return values.keySet().toArray(new String[0]);
        }

        @Override
        protected String[] childrenNamesSpi() {
            return children.keySet().toArray(new String[0]);
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            return children.computeIfAbsent(name, child -> new MemoryNode(this, child));
        }

        @Override
        protected void syncSpi() {
        }

        @Override
        protected void flushSpi() {
        }
    }
}
