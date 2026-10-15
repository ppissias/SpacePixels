/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */

package eu.startales.spacepixels.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The auto-tune results of one session, kept in its folder as {@value #FILE_NAME}, the same way
 * {@link eu.startales.spacepixels.util.skycatalog.SkyCatalogueStore} keeps the sky catalogue.
 * A later import of the same frames shows the saved runs. Running Auto-Tune again replaces
 * the result of the tuner that was run. The calibrated sweep itself is not stored: only the chosen
 * settings, the measurement report, and the per-sigma summary used by the auto-tune chart.
 */
public final class AutoTuneSessionStore {

    public static final String FILE_NAME = "auto_tune.json";
    static final int CURRENT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting()
            .serializeSpecialFloatingPointValues().create();
    private static final DateTimeFormatter RAN_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US);

    private AutoTuneSessionStore() {
    }

    /** One saved file: both tuners, and the frames it was measured on. */
    public static final class Document {
        public int version = CURRENT_VERSION;
        public int frameCount;
        public int width;
        public int height;
        /** File names only, sorted, so the folder can be moved. */
        public List<String> fileNames = new ArrayList<>();
        public Run calibrated;
        public Run legacy;
    }

    /** One tuner's result. {@code ranAt} is an ISO-8601 instant. {@code profile} is the profile the run was asked for. */
    public static final class Run {
        public String ranAt;
        public long elapsedSeconds;
        public String profile;
        public JTransientAutoTuner.AutoTunerResult result;
    }

    public static File fileIn(File sessionFolder) {
        return new File(sessionFolder, FILE_NAME);
    }

    /** Sorted copy of the names, skipping blanks. */
    public static List<String> sortedNames(List<String> fileNames) {
        List<String> names = new ArrayList<>();
        if (fileNames != null) {
            for (String name : fileNames) {
                if (name != null && !name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    /** Whether this document was measured on these frames. */
    public static boolean matches(Document document, int frameCount, int width, int height, List<String> fileNames) {
        return document != null
                && document.frameCount == frameCount
                && document.width == width
                && document.height == height
                && sortedNames(document.fileNames).equals(sortedNames(fileNames));
    }

    /** Writes the document, replacing the previous file only once the new one is complete. */
    public static void save(File sessionFolder, Document document) throws IOException {
        if (sessionFolder == null) {
            throw new IOException("No session folder to save the auto-tune results in.");
        }
        document.version = CURRENT_VERSION;
        document.fileNames = sortedNames(document.fileNames);
        slim(document);
        File target = fileIn(sessionFolder);
        File partial = new File(sessionFolder, FILE_NAME + ".part");
        try (Writer writer = Files.newBufferedWriter(partial.toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(document, writer);
        }
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * The saved document, or null when there is none, it cannot be read, or it is from a newer version.
     * The caller still checks {@link #matches}.
     */
    public static Document load(File sessionFolder) {
        if (sessionFolder == null) {
            return null;
        }
        File file = fileIn(sessionFolder);
        if (!file.isFile()) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Document document = GSON.fromJson(reader, Document.class);
            if (document == null || document.version > CURRENT_VERSION) {
                return null;
            }
            if (document.fileNames == null) {
                document.fileNames = new ArrayList<>();
            }
            return document;
        } catch (IOException | JsonParseException e) {
            return null;
        }
    }

    /**
     * Drops the calibrated tuner's evaluated combinations after keeping the per-sigma summary.
     *
     * @return true when combinations were removed, so the caller can rewrite an older large file
     */
    public static boolean slim(Document document) {
        if (document == null || document.calibrated == null || document.calibrated.result == null
                || document.calibrated.result.calibration == null) {
            return false;
        }
        CalibratedAutoTuner.Calibration calibration = document.calibrated.result.calibration;
        if (calibration.candidates == null || calibration.candidates.isEmpty()) {
            return false;
        }
        CalibratedAutoTuner.discardSweep(calibration);
        return true;
    }

    /** The saved document when it belongs to these frames, otherwise null. */
    public static Document loadMatching(File sessionFolder, int frameCount, int width, int height, List<String> fileNames) {
        Document document = load(sessionFolder);
        return matches(document, frameCount, width, height, fileNames) ? document : null;
    }

    /**
     * One line for the main window and the Overview, or null when neither tuner has a readable time.
     * Example: {@code Auto-tune saved: calibrated, run at 2026-10-15 18:42 (2 min 7 s); legacy, run at 2026-10-14 09:10 (45 s)}.
     */
    public static String describe(Document document) {
        if (document == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        addRun(parts, "calibrated", document.calibrated);
        addRun(parts, "legacy", document.legacy);
        return parts.isEmpty() ? null : "Auto-tune saved: " + String.join("; ", parts);
    }

    /** Local date and time of an ISO-8601 instant, or null when it cannot be read. */
    public static String formatRanAt(String ranAt) {
        if (ranAt == null || ranAt.isEmpty()) {
            return null;
        }
        try {
            return RAN_AT.withZone(ZoneId.systemDefault()).format(Instant.parse(ranAt));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static void addRun(List<String> parts, String tuner, Run run) {
        String when = run == null ? null : formatRanAt(run.ranAt);
        if (when == null) {
            return;
        }
        String duration = run.elapsedSeconds > 0 ? " (" + formatDuration(run.elapsedSeconds) + ")" : "";
        parts.add(tuner + ", run at " + when + duration);
    }

    private static String formatDuration(long seconds) {
        return seconds < 60 ? seconds + " s" : (seconds / 60) + " min " + (seconds % 60) + " s";
    }
}
