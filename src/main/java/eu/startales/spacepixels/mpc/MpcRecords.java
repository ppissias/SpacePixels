/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.mpc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the user recorded about a report's objects and the Minor Planet Center, kept beside the report as
 * {@value #FILE_NAME}: the designation the MPC gave an object (a provisional designation, a number, or the temporary
 * designation of its NEO Confirmation Page entry), and when the user submitted this night's observations of it.
 * SpacePixels never submits; the date is the user's note, so a night is not sent twice.
 */
public final class MpcRecords {
    /** The file in the report folder. */
    public static final String FILE_NAME = "mpc_records.json";
    private static final Pattern NUMBER = Pattern.compile("^\\(?(\\d+)\\)?$");
    private static final Pattern PROVISIONAL = Pattern.compile("^(\\d{4} [A-Z]{2}\\d*)$");
    private static final Pattern TEMPORARY = Pattern.compile("^[A-Za-z0-9]{1,7}$");

    /** One object's record: its designation from the MPC (else null) and the UTC date (yyyy-MM-dd) it was marked submitted (else null). */
    public static final class Entry {
        public String id;
        public String designation;
        public String submittedUtc;

        boolean isEmpty() {
            return (designation == null || designation.isBlank()) && submittedUtc == null;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private MpcRecords() { }

    /** The saved entries by object; empty when there is no file or it cannot be read. */
    public static Map<String, Entry> load(File reportFolder) {
        File file = new File(reportFolder, FILE_NAME);
        var entries = new LinkedHashMap<String, Entry>();
        if (!file.isFile()) return entries;
        try {
            Entry[] read = GSON.fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), Entry[].class);
            if (read != null) for (Entry entry : read) if (entry != null && entry.id != null && !entry.isEmpty()) entries.put(entry.id, entry);
        } catch (IOException | JsonParseException unreadable) {
            return new LinkedHashMap<>();
        }
        return entries;
    }

    /** Saves the entries that hold something, replacing the file in one step; removes the file when none does. */
    public static void save(File reportFolder, Map<String, Entry> entries) throws IOException {
        File file = new File(reportFolder, FILE_NAME);
        var kept = entries.values().stream().filter(e -> e != null && !e.isEmpty()).toArray(Entry[]::new);
        if (kept.length == 0) {
            Files.deleteIfExists(file.toPath());
            return;
        }
        File partial = new File(reportFolder, FILE_NAME + ".part");
        Files.writeString(partial.toPath(), GSON.toJson(kept), StandardCharsets.UTF_8);
        Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The designation as typed, tidied: spaces trimmed and runs of spaces made one; null when empty. */
    public static String tidy(String designation) {
        if (designation == null) return null;
        String tidy = designation.trim().replaceAll("\\s+", " ");
        return tidy.isEmpty() ? null : tidy;
    }

    /** Why a typed designation cannot name an object in an ADES file; null when it can (or is empty). */
    public static String problem(String designation) {
        String tidy = tidy(designation);
        if (tidy == null || NUMBER.matcher(tidy).matches() || PROVISIONAL.matcher(tidy).matches() || TEMPORARY.matcher(tidy).matches()) {
            return null;
        }
        return "Enter the MPC's designation as it gave it: a number such as 2320, a provisional designation such as "
                + "2026 AB12, or a temporary designation of 1 to 7 letters or digits, as on the NEO Confirmation Page.";
    }

    /**
     * The tracklet that names these observations by a designation from the MPC: a number as the permanent designation,
     * a provisional designation as such, anything else (the NEO Confirmation Page's) as the tracklet ID. Null when
     * {@link #problem} objects.
     */
    public static AdesPsv.Tracklet tracklet(String designation, List<MpcObservation> observations) {
        String tidy = tidy(designation);
        if (tidy == null || problem(tidy) != null) return null;
        var number = NUMBER.matcher(tidy);
        if (number.matches()) return new AdesPsv.Tracklet(number.group(1), null, null, observations);
        if (PROVISIONAL.matcher(tidy).matches()) return new AdesPsv.Tracklet(null, tidy, null, observations);
        return new AdesPsv.Tracklet(null, null, tidy, observations);
    }
}
