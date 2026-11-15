package eu.startales.spacepixels.mpc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Identifications made after a report was written, by Compare again or Identify all in Asteroids &amp; Comets, kept
 * beside the report as {@value #FILE_NAME} so they come back when the window opens it again. The report's own files
 * are not changed: the next detection writes a new report with its own identifications.
 */
public final class LaterIdentifications {
    /** The file in the report folder. */
    public static final String FILE_NAME = "later_identifications.json";

    /** One object's later identification: its status, names and note, what made it, and when (UTC, ISO 8601). */
    public static final class Entry {
        public String id;
        public String status;
        public List<String> names = new ArrayList<>();
        public String note;
        public String source;
        public String madeAtUtc;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private LaterIdentifications() { }

    /** The saved entries by object, the latest of each; empty when there is no file or it cannot be read. */
    public static Map<String, Entry> load(File reportFolder) {
        File file = new File(reportFolder, FILE_NAME);
        var entries = new LinkedHashMap<String, Entry>();
        if (!file.isFile()) return entries;
        try {
            Entry[] read = GSON.fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), Entry[].class);
            if (read != null) for (Entry entry : read) if (entry != null && entry.id != null && entry.status != null) entries.put(entry.id, entry);
        } catch (IOException | JsonParseException unreadable) {
            return new LinkedHashMap<>();
        }
        return entries;
    }

    /** Saves the entries, replacing the file in one step. */
    public static void save(File reportFolder, Map<String, Entry> entries) throws IOException {
        File file = new File(reportFolder, FILE_NAME);
        File partial = new File(reportFolder, FILE_NAME + ".part");
        Files.writeString(partial.toPath(), GSON.toJson(entries.values().toArray(new Entry[0])), StandardCharsets.UTF_8);
        Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * The report's objects with the saved identifications applied: an object with an entry takes its status, names
     * and note; the others are unchanged.
     */
    public static List<ReportedObjects.ReportedObject> apply(List<ReportedObjects.ReportedObject> objects, Map<String, Entry> entries) {
        var applied = new ArrayList<ReportedObjects.ReportedObject>();
        for (var object : objects) {
            Entry entry = entries.get(object.id());
            applied.add(entry == null ? object : new ReportedObjects.ReportedObject(object.id(), object.kind(), entry.status,
                    entry.names == null ? List.of() : List.copyOf(entry.names), entry.note, object.positions(),
                    object.speedArcsecPerHour(), object.positionAngleDegrees(), object.motion()));
        }
        return applied;
    }
}
