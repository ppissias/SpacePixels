/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.solarsystem;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import eu.startales.spacepixels.skycatalog.SkyField;
import eu.startales.spacepixels.util.StagedFile;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

/** Keeps the predicted asteroids and comets of a session in its folder, as {@value SolarSystemObjects#FILE_NAME}. */
public final class SolarSystemStore {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    /** Frame times may differ this much from the saved span (rounding, a frame added or left out at an end). */
    static final long SPAN_TOLERANCE_MILLIS = 60_000;

    private SolarSystemStore() {
    }

    public static File fileIn(File sessionFolder) {
        return new File(sessionFolder, SolarSystemObjects.FILE_NAME);
    }

    /** Writes the predictions, replacing the previous ones only once the new file is complete. */
    public static void save(File sessionFolder, SolarSystemObjects objects) throws IOException {
        try (StagedFile staged = new StagedFile(fileIn(sessionFolder))) {
            try (Writer writer = staged.newWriter()) {
                GSON.toJson(objects, writer);
            }
            staged.publish();
        }
    }

    /** The saved predictions of the session, or null when there are none or they cannot be read. */
    public static SolarSystemObjects load(File sessionFolder) {
        File file = fileIn(sessionFolder);
        if (!file.isFile()) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            SolarSystemObjects objects = GSON.fromJson(reader, SolarSystemObjects.class);
            if (objects == null || objects.field == null || objects.version > SolarSystemObjects.CURRENT_VERSION) {
                return null;
            }
            if (objects.bodies == null) {
                objects.bodies = new ArrayList<>();
            }
            objects.bodies.removeIf(body -> body == null || body.samples == null || body.samples.isEmpty());
            if (objects.problems == null) {
                objects.problems = new ArrayList<>();
            }
            return objects;
        } catch (IOException | JsonParseException e) {
            return null;
        }
    }

    /**
     * Whether saved predictions belong to these frames: the same field, and a span that covers the frames' times
     * ({@code startMillis} to {@code endMillis}, null when the frames have no times).
     */
    public static boolean matches(SolarSystemObjects objects, SkyField field, long[] span) {
        return objects != null && field != null && span != null && field.isSameFieldAs(objects.field)
                && objects.startMillis <= span[0] + SPAN_TOLERANCE_MILLIS
                && objects.endMillis >= span[1] - SPAN_TOLERANCE_MILLIS;
    }
}
