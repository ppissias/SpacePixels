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
 */
public record SkySnapshot(File folder, PlateSolutionCorrection correction, SkyCatalogue catalogue) {

    /** Whether this is the sky data of {@code directory}, the folder of a run's frames. */
    public boolean belongsTo(File directory) {
        return folder != null && directory != null
                && folder.toPath().toAbsolutePath().normalize().equals(directory.toPath().toAbsolutePath().normalize());
    }
}
