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
import eu.startales.spacepixels.util.StagedFile;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;

/**
 * Reads and writes the application-level SpacePixels configuration.
 */
public final class SpacePixelsAppConfigIO {

    public static final String DEFAULT_FILENAME = "spacepixels_app.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SpacePixelsAppConfigIO() {
    }

    public static AppConfig load(File file) throws IOException {
        try (FileReader reader = new FileReader(file)) {
            return load(reader);
        }
    }

    public static AppConfig load(Reader reader) throws IOException {
        AppConfig appConfig = GSON.fromJson(reader, AppConfig.class);
        if (appConfig == null) {
            throw new IOException("Configuration file did not contain an AppConfig object.");
        }
        return appConfig;
    }

    /** Replaces the file only once the new configuration is completely written. */
    public static void write(File file, AppConfig appConfig) throws IOException {
        try (StagedFile staged = new StagedFile(file)) {
            try (Writer writer = staged.newWriter()) {
                write(writer, appConfig);
            }
            staged.publish();
        }
    }

    public static void write(Writer writer, AppConfig appConfig) {
        GSON.toJson(appConfig, writer);
    }
}
