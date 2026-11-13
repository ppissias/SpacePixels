package eu.startales.spacepixels.session;

import eu.startales.spacepixels.detection.AutoTuneSessionStore;
import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.solarsystem.SolarSystemObjects;

/** Saved session data prepared on a worker before the session is handed to the UI. */
public record SessionMetadata(SkyCatalogue catalogue, PlateSolutionCorrection correction,
                              AutoTuneSessionStore.Document autoTune, SolarSystemObjects solarSystem,
                              eu.startales.spacepixels.detection.ExcludedAreas excludedAreas) {
    /** Metadata without saved excluded areas. */
    public SessionMetadata(SkyCatalogue catalogue, PlateSolutionCorrection correction,
                           AutoTuneSessionStore.Document autoTune, SolarSystemObjects solarSystem) {
        this(catalogue, correction, autoTune, solarSystem, null);
    }

    public static SessionMetadata empty() { return new SessionMetadata(null, null, null, null, null); }
}
