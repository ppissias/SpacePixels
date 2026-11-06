package eu.startales.spacepixels.detection;

import com.google.gson.*;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.report.FindingCategory;
import eu.startales.spacepixels.report.ReportExporter;
import eu.startales.spacepixels.util.DebugOutput;
import io.github.ppissias.jtransient.execution.CancellationScope;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Restores detached display geometry on the import worker; malformed or incompatible reports never reject images. */
public final class SavedReportOverlays {
    /** Portable frame provenance; names contain no parent path, and timing uses the imported start/exposure milliseconds. */
    public record FrameIdentity(String fileName, int width, int height, long timestampMillis, long exposureMillis) { }
    /** A restored display and its short provenance/availability explanation, or null display when none is usable. */
    public record Loaded(DetectionOverlays overlays, String notice) { }

    /** Prevents constructing this worker-side report reader. */
    private SavedReportOverlays() { }

    /** Captures frame order and metadata without reading image samples or publishing absolute paths. */
    public static List<FrameIdentity> identities(FitsFileInformation[] files) {
        return Arrays.stream(files).map(f -> f == null ? null : new FrameIdentity(new File(f.getFileName().replace('\\','/')).getName(), f.getSizeWidth(), f.getSizeHeight(),
                f.getObservationTimestamp(), f.getExposureDurationMillis())).toList();
    }

    /** Tries completed reports newest first, including individual iterative passes; call off the EDT. */
    public static Loaded latest(File folder, FitsFileInformation[] files) {
        var reports = new ArrayList<File>();
        File[] children = folder.listFiles(File::isDirectory);
        if (children == null) return new Loaded(null, null);
        for (var child : children) if (child.getName().startsWith("detections_") || child.getName().startsWith("iterative_detections_")) {
            try (var paths = Files.walk(child.toPath(), 4)) {
                paths.filter(p -> p.getFileName().toString().equals(ReportExporter.REPORT_FILE_NAME) && Files.isRegularFile(p))
                        .map(java.nio.file.Path::toFile).forEach(reports::add);
            } catch (IOException failure) { DebugOutput.println("Cannot inspect saved report: " + failure.getMessage()); }
        }
        reports.sort(Comparator.comparingLong(File::lastModified).reversed().thenComparing(File::getAbsolutePath,Comparator.reverseOrder()));
        for (var report : reports) {
            CancellationScope.check();
            try { return read(report, files); }
            catch (IOException | RuntimeException failure) {
                if (eu.startales.spacepixels.util.Cancellation.isCancellation(failure))
                    throw new java.util.concurrent.CancellationException(failure.getMessage());
                DebugOutput.println("Saved report findings unavailable: " + failure.getMessage());
            }
        }
        return new Loaded(null, reports.isEmpty() ? null : "Images imported; no compatible saved report findings were available.");
    }

    /** Reads one completed export. New provenance must match exactly; older exports retain explicitly labelled reference markers. */
    public static Loaded read(File report, FitsFileInformation[] files) throws IOException {
        if (files == null || files.length == 0 || !report.isFile()) throw new IOException("No input frames or completed report");
        JsonObject data;
        try (var reader = Files.newBufferedReader(report.toPath().resolveSibling("report-data.json"), StandardCharsets.UTF_8)) {
            data = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (JsonParseException failure) { throw new IOException("Invalid report JSON", failure); }
        if (data.get("schemaVersion").getAsInt() != 1 || data.get("frameCount").getAsInt() != files.length)
            throw new IOException("Report schema or frame count differs");
        boolean legacy = !data.has("inputFrames");
        if (!legacy) {
            FrameIdentity[] recorded = new Gson().fromJson(data.get("inputFrames"), FrameIdentity[].class);
            if (!identities(files).equals(Arrays.asList(recorded))) throw new IOException("Report frame identities differ");
        }
        var tracks = new ArrayList<TrackOverlay.Track>();
        var variables = new ArrayList<VariableStarOverlay.Candidate>();
        var markers = new ArrayList<FindingOverlay.Marker>();
        var faint = new ArrayList<FaintMoverOverlay.Candidate>();
        var times = new LinkedHashMap<String, Long>();
        var faintIds = new HashSet<String>();
        var variableText = new HashMap<String,JsonObject>();
        if (data.has("variableMarkers")) for (var value : data.getAsJsonArray("variableMarkers")) {
            var entry = value.getAsJsonObject();variableText.put(entry.get("label").getAsString(),entry);
        }
        var findings = data.getAsJsonArray("findings");
        var included = new HashSet<String>();
        for (var value : findings) included.add(value.getAsJsonObject().get("id").getAsString());
        if (data.has("faintMovers")) for (var value : data.getAsJsonArray("faintMovers")) {
            var entry = value.getAsJsonObject();String id = entry.get("id").getAsString();
            if (!included.contains(id) || !entry.has("fittedPositions")) continue;
            var candidateTimes = new LinkedHashMap<String, Long>();boolean valid = true;
            for (var p : entry.getAsJsonArray("fittedPositions")) {
                var position = p.getAsJsonObject();int index = position.get("frameNumber").getAsInt() - 1;
                if (index < 0 || index >= files.length) { valid = false; break; }
                long mid = position.get("midpointMillis").getAsLong();var file = files[index];
                if (file.getObservationTimestamp() == -1 || mid != file.getObservationTimestamp() + Math.max(0, file.getExposureDurationMillis()) / 2) { valid = false; break; }
                candidateTimes.put(new File(file.getFilePath()).getAbsolutePath(), mid);
            }
            if (!valid || candidateTimes.size() < 2) continue;
            if (!times.isEmpty() && !times.equals(candidateTimes)) throw new IOException("Fitted-path frame scopes differ");
            faint.add(new FaintMoverOverlay.Candidate(id, number(entry,"x"), number(entry,"y"),
                    number(entry,"vxPixelsPerMinute"), number(entry,"vyPixelsPerMinute"), entry.get("referenceTimeMillis").getAsLong()));
            times.putAll(candidateTimes);faintIds.add(id);
        }
        int referenceTracks = 0;
        for (var value : findings) {
            CancellationScope.check();
            var entry = value.getAsJsonObject();String id = entry.get("id").getAsString();
            var category = FindingCategory.fromId(id);
            for (var valueFrame : entry.getAsJsonArray("frames")) {
                int frame = valueFrame.getAsInt();
                if (frame < 1 || frame > files.length) throw new IOException("Invalid finding frame association");
            }
            double x = number(entry,"x"), y = number(entry,"y");
            if (x < 0 || y < 0 || x >= files[0].getSizeWidth() || y >= files[0].getSizeHeight()) throw new IOException("Finding outside input dimensions");
            List<FindingOverlay.Position> positions = new ArrayList<>();
            if (entry.has("positions")) for (var p : entry.getAsJsonArray("positions")) {
                var position = p.getAsJsonObject();int frame = position.get("frameNumber").getAsInt();
                if (frame < 1 || frame > files.length) throw new IOException("Invalid finding frame");
                double px = number(position,"x"), py = number(position,"y");
                if (px < 0 || py < 0 || px >= files[frame - 1].getSizeWidth() || py >= files[frame - 1].getSizeHeight())
                    throw new IOException("Measured finding position outside input dimensions");
                positions.add(new FindingOverlay.Position(frame,px,py));
            }
            if (category == FindingCategory.VARIABLE_STAR) {
                boolean high = entry.has("tier") && entry.get("tier").getAsString().equals("HIGH_CONFIDENCE");
                var text = variableText.get(id);
                if (text != null && number(text,"x") == x && number(text,"y") == y)
                    variables.add(new VariableStarOverlay.Candidate(id,text.has("name") ? text.get("name").getAsString() : null,
                            text.get("description").getAsString(),x,y,high));
                else variables.add(new VariableStarOverlay.Candidate(id,x,y,high));
            } else if (faintIds.contains(id)) { /* Draw the explicitly fitted layer, not another reference marker. */ }
            else if ((category == FindingCategory.MOVING_TARGET || category == FindingCategory.STREAK_TRACK) && !positions.isEmpty()) {
                var points = positions.stream().map(p -> new TrackOverlay.Point(files[p.frameNumber() - 1].getFileName(),p.x(),p.y())).toList();
                tracks.add(new TrackOverlay.Track(id, category == FindingCategory.STREAK_TRACK, points));
            } else {
                String description = category.label();
                if (positions.isEmpty()) {
                    if (category == FindingCategory.ANOMALY && entry.getAsJsonArray("frames").size() == 1) {
                        positions.add(new FindingOverlay.Position(entry.getAsJsonArray("frames").get(0).getAsInt(),x,y));
                        description += " · saved single-frame centroid";
                    } else {
                        description += " · report reference position; per-frame measurements unavailable";
                        if (category == FindingCategory.MOVING_TARGET || category == FindingCategory.STREAK_TRACK) referenceTracks++;
                    }
                }
                markers.add(new FindingOverlay.Marker(id,x,y,positions,description));
            }
        }
        var paths = new HashSet<String>();for (var file : files) paths.add(new File(file.getFilePath()).getAbsolutePath());
        var overlays = new DetectionOverlays(TrackOverlay.restored(tracks), new VariableStarOverlay(variables,paths,files[0].getSizeWidth(),files[0].getSizeHeight()),
                new FaintMoverOverlay(faint,times,files[0].getSizeWidth(),files[0].getSizeHeight()),new FindingOverlay(markers,files));
        return new Loaded(overlays,"Restored " + findings.size() + " findings from " + report.getParentFile().getName() + "."
                + (legacy ? " Older report: frame identities are unavailable; " + referenceTracks + " tracks use reference markers." : ""));
    }

    /** Refuses nonfinite display coordinates or rates rather than creating a misleading visible marker. */
    private static double number(JsonObject entry, String key) throws IOException {
        double value = entry.get(key).getAsDouble();
        if (!Double.isFinite(value)) throw new IOException("Nonfinite report geometry");
        return value;
    }
}
