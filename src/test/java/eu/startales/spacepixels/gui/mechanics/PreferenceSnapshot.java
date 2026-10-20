package eu.startales.spacepixels.gui.mechanics;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.prefs.Preferences;

/**
 * A copy of the SpacePixels preference node, taken before a window test and put back afterwards.
 * On Windows these values live in the registry, so a scratch {@code user.home} does not cover them.
 */
final class PreferenceSnapshot {

    private static final String NODE = "eu/startales/spacepixels";

    private final boolean existed;
    private final byte[] exported;

    private PreferenceSnapshot(boolean existed, byte[] exported) {
        this.existed = existed;
        this.exported = exported;
    }

    static PreferenceSnapshot capture() throws Exception {
        Preferences root = Preferences.userRoot();
        boolean existed = root.nodeExists(NODE);
        if (!existed) {
            return new PreferenceSnapshot(false, null);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        root.node(NODE).exportSubtree(out);
        return new PreferenceSnapshot(true, out.toByteArray());
    }

    void restore() throws Exception {
        Preferences root = Preferences.userRoot();
        if (root.nodeExists(NODE)) {
            root.node(NODE).removeNode();
            root.flush();
        }
        if (existed) {
            Preferences.importPreferences(new ByteArrayInputStream(exported));
            root.flush();
        }
    }
}
