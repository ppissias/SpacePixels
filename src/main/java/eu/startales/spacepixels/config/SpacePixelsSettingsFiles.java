/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.config;
import eu.startales.spacepixels.util.StagedFile;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;

/**
 * Saves the detection profile and the visualization preferences, the two files behind Save on the detection
 * settings tab.
 */
public final class SpacePixelsSettingsFiles {

    private SpacePixelsSettingsFiles() {
    }

    /**
     * Writes both files completely before either saved file is replaced. The two files are independent, so when the
     * second replacement fails the new profile stays saved; the message then says which file was not saved.
     */
    public static void save(File profileFile, SpacePixelsDetectionProfile profile,
                            File visualizationFile, SpacePixelsVisualizationPreferences visualization) throws IOException {
        StringWriter profileJson = new StringWriter();
        SpacePixelsDetectionProfileIO.write(profileJson, profile);
        StringWriter visualizationJson = new StringWriter();
        SpacePixelsVisualizationPreferencesIO.write(visualizationJson, visualization);

        boolean profileSaved = false;
        try (StagedFile stagedProfile = new StagedFile(profileFile);
             StagedFile stagedVisualization = new StagedFile(visualizationFile)) {
            stagedProfile.writeString(profileJson.toString());
            stagedVisualization.writeString(visualizationJson.toString());
            stagedProfile.publish();
            profileSaved = true;
            stagedVisualization.publish();
        } catch (IOException e) {
            throw new IOException((profileSaved
                    ? "The detection profile was saved, but the visualization preferences were not. "
                    : "Nothing was saved; the saved settings files are unchanged. ") + e.getMessage(), e);
        }
    }
}
