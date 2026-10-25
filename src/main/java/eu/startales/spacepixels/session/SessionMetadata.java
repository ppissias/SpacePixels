package eu.startales.spacepixels.session;

import eu.startales.spacepixels.detection.AutoTuneSessionStore;
import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;

/** Saved session data prepared on a worker before the session is handed to the UI. */
public record SessionMetadata(SkyCatalogue catalogue, PlateSolutionCorrection correction,
                              AutoTuneSessionStore.Document autoTune) {
    public static SessionMetadata empty() { return new SessionMetadata(null, null, null); }
}
