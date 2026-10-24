package eu.startales.spacepixels.session;

import eu.startales.spacepixels.util.FitsFileInformation;

/**
 * The frame list of an imported folder, shared by its import service and its session. The metadata objects are kept
 * by reference, never deep-copied. A detection run's overlays belong to the session ({@link ProcessingSession}).
 */
public final class SessionFrames {
    private FitsFileInformation[] files;

    /** The imported frames, or null before the folder's metadata is read. */
    public FitsFileInformation[] getFiles() { return files; }

    /** Replaces the frame list after an import reads or converts the folder. */
    public void setFiles(FitsFileInformation[] files) { this.files = files; }

    /** Forgets the frame list when the session closes. */
    public void clear() {
        files = null;
    }
}
