package eu.startales.spacepixels.mpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The moving objects of one report, read from its {@code report-data.json}: every moving-object track with its measured
 * detections, and every faint mover with its fitted positions, each with the status the report gave it against the
 * asteroid predictions. This is what the Asteroids &amp; Comets window works on, after a run and for a saved report
 * alike. Nothing is kept but copied values.
 */
public final class ReportedObjects {
    /** Track and faint-mover kinds. */
    public static final String TRACK = "track", FAINT_MOVER = "faint mover";
    /** The report's statuses, and the one used when the report had no predictions. */
    public static final String KNOWN = "known", CHECK = "check", NO_KNOWN_MATCH = "no-known-match", NOT_COMPARED = "not-compared";
    /**
     * Not a report status: set by Identify all in Asteroids &amp; Comets when a known object lies within 1′ with agreeing
     * motion, a looser match than Known (see {@link NearbyKnown}).
     */
    public static final String POSSIBLE = "possible";

    /**
     * One position: a measured centroid of a track (with the detection's FWHM in pixels and signal-to-noise, null when
     * the report did not keep them), or a fitted faint-mover position (FWHM and SNR null). Frame numbers are one-based.
     */
    public record Position(int frameNumber, double x, double y, Double fwhm, Double snr) { }

    /** A faint mover's fitted straight motion: its place at a reference time (UTC ms) and its rate in pixels per minute. */
    public record Motion(double x, double y, long referenceMillis, double vxPerMinute, double vyPerMinute) { }

    /** One track or faint mover with its status, positions and, for a faint mover, its fitted motion (else null). */
    public record ReportedObject(String id, String kind, String status, List<String> names, String note,
                                 List<Position> positions, Double speedArcsecPerHour, Double positionAngleDegrees, Motion motion) {
        /** Whether the positions were measured frame by frame, rather than fitted along a path. */
        public boolean measured() { return TRACK.equals(kind); }
    }

    /** One frame of the report, as the report recorded it. */
    public record Frame(String fileName, int width, int height, long timestampMillis, long exposureMillis) { }

    /** Predicted body metadata captured in {@code report-data.json} for external links and predicted brightness. */
    public record KnownBody(String name, boolean comet, Double minimumMagnitude, Double maximumMagnitude, String informationUrl) { }

    /** Relative report asset paths for a faint mover's shift stack and interactive review cube. */
    public record FaintMoverAssetPaths(String cutout, String reviewData) { }

    /**
     * The report's frames, in its one-based order, its objects (tracks first, then faint movers), the stars' FWHM
     * in pixels the faint-mover search used (null without that search), plus optional known-body metadata and
     * faint-mover review asset paths.
     */
    public record Report(File folder, List<Frame> frames, List<ReportedObject> objects, Double faintMoverFwhm,
                         Map<String, KnownBody> knownBodies, Map<String, FaintMoverAssetPaths> faintMoverAssets) {
        /** Convenience constructor for reports without extra known-body metadata or faint-mover asset paths. */
        public Report(File folder, List<Frame> frames, List<ReportedObject> objects, Double faintMoverFwhm) {
            this(folder, frames, objects, faintMoverFwhm, Map.of(), Map.of());
        }

        /** The same report with other objects, for identifications made after it was written. */
        public Report withObjects(List<ReportedObject> replaced) {
            return new Report(folder, frames, List.copyOf(replaced), faintMoverFwhm, knownBodies, faintMoverAssets);
        }

        /**
         * The run's star jitter in pixels from its saved detection settings, which the report's identification allows
         * (at least one pixel); one pixel when the settings are not there.
         */
        public double reviewFloorPixels() {
            try {
                var config = JsonParser.parseString(Files.readString(new File(folder, "detection_config.json").toPath(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                return Math.max(1, number(config, "maxStarJitter", 1));
            } catch (IOException | RuntimeException unavailable) {
                return 1;
            }
        }

        /** The start of the first exposure, UTC epoch milliseconds, or 0 when no frame has a time. */
        public long sessionStartMillis() {
            return frames.stream().mapToLong(Frame::timestampMillis).filter(t -> t > 0).min().orElse(0);
        }
    }

    private ReportedObjects() { }

    /** Reads a report folder. Throws when it holds no readable report data. */
    public static Report load(File folder) throws IOException {
        File file = new File(folder, "report-data.json");
        JsonObject data;
        try {
            data = JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException unreadable) {
            throw new IOException("The report data cannot be read: " + file.getName() + " in " + folder + ".");
        }
        var frames = new ArrayList<Frame>();
        for (JsonElement element : array(data, "inputFrames")) {
            JsonObject f = element.getAsJsonObject();
            frames.add(new Frame(text(f, "fileName"), integer(f, "width"), integer(f, "height"),
                    (long) number(f, "timestampMillis", 0), (long) number(f, "exposureMillis", 0)));
        }
        Map<String, JsonObject> identifications = new HashMap<>();
        Map<String, KnownBody> knownBodies = new HashMap<>();
        JsonObject known = data.has("knownSolarSystem") && data.get("knownSolarSystem").isJsonObject()
                ? data.getAsJsonObject("knownSolarSystem") : null;
        for (JsonElement element : known == null ? new JsonArray() : array(known, "identifications")) {
            identifications.put(text(element.getAsJsonObject(), "id"), element.getAsJsonObject());
        }
        for (JsonElement element : known == null ? new JsonArray() : array(known, "bodies")) {
            if (!element.isJsonObject()) continue;
            JsonObject b = element.getAsJsonObject();
            String name = text(b, "name");
            if (name != null && !name.isBlank()) {
                boolean comet = b.has("comet") && b.get("comet").isJsonPrimitive() && b.get("comet").getAsBoolean();
                knownBodies.put(name, new KnownBody(name, comet, optional(b, "minimumMagnitude"),
                        optional(b, "maximumMagnitude"), text(b, "informationUrl")));
            }
        }
        var objects = new ArrayList<ReportedObject>();
        var faintMoverAssets = new HashMap<String, FaintMoverAssetPaths>();
        for (JsonElement element : array(data, "findings")) {
            JsonObject finding = element.getAsJsonObject();
            String id = text(finding, "id");
            if (id == null || !id.matches("T[0-9]+")) continue;
            var positions = new ArrayList<Position>();
            for (JsonElement p : array(finding, "positions")) {
                JsonObject position = p.getAsJsonObject();
                positions.add(new Position(integer(position, "frameNumber"), number(position, "x", Double.NaN), number(position, "y", Double.NaN),
                        optional(position, "fwhm"), optional(position, "snr")));
            }
            objects.add(object(id, TRACK, identifications.get(id), positions, null));
        }
        for (JsonElement element : array(data, "faintMovers")) {
            JsonObject entry = element.getAsJsonObject();
            String id = text(entry, "id");
            var positions = new ArrayList<Position>();
            for (JsonElement p : array(entry, "fittedPositions")) {
                JsonObject position = p.getAsJsonObject();
                positions.add(new Position(integer(position, "frameNumber"), number(position, "x", Double.NaN),
                        number(position, "y", Double.NaN), null, null));
            }
            var motion = new Motion(number(entry, "x", Double.NaN), number(entry, "y", Double.NaN),
                    (long) number(entry, "referenceTimeMillis", 0), number(entry, "vxPixelsPerMinute", Double.NaN),
                    number(entry, "vyPixelsPerMinute", Double.NaN));
            objects.add(object(id, FAINT_MOVER, identifications.get(id), positions, motion));
            if (id != null) {
                String cutout = text(entry, "cutout");
                JsonObject review = entry.has("review") && entry.get("review").isJsonObject()
                        ? entry.getAsJsonObject("review") : null;
                String reviewData = review == null ? null : text(review, "data");
                if (cutout != null || reviewData != null) {
                    faintMoverAssets.put(id, new FaintMoverAssetPaths(cutout, reviewData));
                }
            }
        }
        JsonObject search = data.has("faintMoverSearch") && data.get("faintMoverSearch").isJsonObject()
                ? data.getAsJsonObject("faintMoverSearch") : null;
        return new Report(folder, List.copyOf(frames), List.copyOf(objects), search == null ? null : optional(search, "fwhmPixels"),
                Map.copyOf(knownBodies), Map.copyOf(faintMoverAssets));
    }

    /** One object with the report's identification, or "not compared" when the report had no predictions. */
    private static ReportedObject object(String id, String kind, JsonObject identification, List<Position> positions, Motion motion) {
        if (identification == null) {
            return new ReportedObject(id, kind, NOT_COMPARED, List.of(), "This report has no asteroid predictions to compare with.",
                    List.copyOf(positions), null, null, motion);
        }
        var names = new ArrayList<String>();
        for (JsonElement name : array(identification, "names")) names.add(name.getAsString());
        String note = text(identification, "note");
        String unusual = text(identification, "unusualMotion");
        // An unmatched object's note already says it; a known one's motion is worth saying too.
        if (unusual != null && note != null && !note.contains(unusual)) note = note + " Unusual motion: " + unusual + ".";
        return new ReportedObject(id, kind, text(identification, "status"), List.copyOf(names), note,
                List.copyOf(positions), optional(identification, "speedArcsecPerHour"), optional(identification, "positionAngleDegrees"), motion);
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }

    private static int integer(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsInt() : 0;
    }

    private static double number(JsonObject object, String key, double fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsDouble() : fallback;
    }

    private static Double optional(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsDouble() : null;
    }
}
