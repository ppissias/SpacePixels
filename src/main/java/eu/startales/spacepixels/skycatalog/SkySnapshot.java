/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.skycatalog;

import java.io.File;
import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;

/**
 * The sky catalogue and plate-solution correction of a session, as a run takes them when it is admitted. The window
 * captures them from the session on the EDT, so a run never reads the files that the window may be rewriting, and a
 * value that could not be saved is still used. The correction is a copy; a catalogue is replaced, never changed, once
 * a session holds it.
 *
 * @param folder     the session's folder; a run on frames in another folder reads that folder's files instead
 * @param correction the correction, switched on or off; null when there is none
 * @param catalogue  the catalogue; null when there is none
 * @param solarSystem the saved or session-owned known-object predictions; null when unavailable
 * @param excludedAreas the rectangles the session leaves out of detection; null when there are none
 */
public record SkySnapshot(File folder, PlateSolutionCorrection correction, SkyCatalogue catalogue,
                          eu.startales.spacepixels.solarsystem.SolarSystemObjects solarSystem,
                          eu.startales.spacepixels.detection.ExcludedAreas excludedAreas) {

    /** A snapshot without excluded areas. */
    public SkySnapshot(File folder, PlateSolutionCorrection correction, SkyCatalogue catalogue,
                       eu.startales.spacepixels.solarsystem.SolarSystemObjects solarSystem) {
        this(folder, correction, catalogue, solarSystem, null);
    }

    /** This snapshot with the session's excluded areas, which the session owns rather than the sky catalogue. */
    public SkySnapshot withExcludedAreas(eu.startales.spacepixels.detection.ExcludedAreas areas) {
        return new SkySnapshot(folder, correction, catalogue, solarSystem, areas);
    }

    /** Whether this is the sky data of {@code directory}, the folder of a run's frames. */
    public boolean belongsTo(File directory) {
        return folder != null && directory != null
                && folder.toPath().toAbsolutePath().normalize().equals(directory.toPath().toAbsolutePath().normalize());
    }
}
