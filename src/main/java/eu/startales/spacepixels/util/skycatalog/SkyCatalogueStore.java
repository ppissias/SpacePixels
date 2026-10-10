/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util.skycatalog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

/** Keeps the sky catalogue of a session in its folder, as {@value SkyCatalogue#FILE_NAME}. */
public final class SkyCatalogueStore {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private SkyCatalogueStore() {
    }

    public static File fileIn(File sessionFolder) {
        return new File(sessionFolder, SkyCatalogue.FILE_NAME);
    }

    /** Writes the catalogue, replacing the previous one only once the new file is complete. */
    public static void save(File sessionFolder, SkyCatalogue catalogue) throws IOException {
        File target = fileIn(sessionFolder);
        File partial = new File(sessionFolder, SkyCatalogue.FILE_NAME + ".part");
        try (Writer writer = Files.newBufferedWriter(partial.toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(catalogue, writer);
        }
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** The catalogue of the session, or null when there is none or it cannot be read. */
    public static SkyCatalogue load(File sessionFolder) {
        File file = fileIn(sessionFolder);
        if (!file.isFile()) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            SkyCatalogue catalogue = GSON.fromJson(reader, SkyCatalogue.class);
            if (catalogue == null || catalogue.field == null || catalogue.version > SkyCatalogue.CURRENT_VERSION) {
                return null;
            }
            if (catalogue.stars == null) {
                catalogue.stars = new ArrayList<>();
            }
            if (catalogue.deepSky == null) {
                catalogue.deepSky = new ArrayList<>();
            }
            if (catalogue.variables == null) {
                catalogue.variables = new ArrayList<>();
            }
            if (catalogue.depthSamples == null) {
                catalogue.depthSamples = new ArrayList<>();
            }
            if (catalogue.namedStars == null) {
                catalogue.namedStars = new ArrayList<>();
            }
            if (catalogue.problems == null) {
                catalogue.problems = new ArrayList<>();
            }
            return catalogue;
        } catch (IOException | JsonParseException e) {
            return null;
        }
    }
}
