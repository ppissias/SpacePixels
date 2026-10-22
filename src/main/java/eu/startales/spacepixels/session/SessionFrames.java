package eu.startales.spacepixels.session;

import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.TrackOverlay;
import eu.startales.spacepixels.util.VariableStarOverlay;

/** Shared session data. Metadata objects and large overlay buffers are retained by reference, never deep-copied. */
public final class SessionFrames {
    private FitsFileInformation[] files;
    private volatile TrackOverlay tracks;
    private volatile VariableStarOverlay variables;

    public FitsFileInformation[] getFiles() { return files; }
    public void setFiles(FitsFileInformation[] files) { this.files = files; }
    public TrackOverlay getTracks() { return tracks; }
    public VariableStarOverlay getVariables() { return variables; }
    public void setOverlays(TrackOverlay tracks, VariableStarOverlay variables) {
        this.tracks = tracks;
        this.variables = variables;
    }
    public void clear() {
        files = null;
        tracks = null;
        variables = null;
    }
}
