/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;

/**
 * Reads and writes SpacePixels visualization/export preferences.
 */
public final class SpacePixelsVisualizationPreferencesIO {

    public static final String DEFAULT_FILENAME = "spacepixels_visualization.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SpacePixelsVisualizationPreferencesIO() {
    }

    public static SpacePixelsVisualizationPreferences load(Reader reader) throws IOException {
        SpacePixelsVisualizationPreferences preferences = GSON.fromJson(reader, SpacePixelsVisualizationPreferences.class);
        if (preferences == null) {
            throw new IOException("Configuration file did not contain visualization preferences.");
        }
        return preferences;
    }

    public static void write(Writer writer, SpacePixelsVisualizationPreferences preferences) {
        GSON.toJson(preferences, writer);
    }
}
