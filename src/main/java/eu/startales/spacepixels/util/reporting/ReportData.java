package eu.startales.spacepixels.util.reporting;

import com.google.gson.GsonBuilder;
import eu.startales.spacepixels.util.ReportInclusion;
import io.github.ppissias.jtransient.core.*;
import io.github.ppissias.jtransient.photometry.*;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import java.io.*;
import java.time.Instant;
import java.util.*;

/** Shared report identities and frame selection; never mutates the engine result. */
final class ReportData {
    record Finding(String id, String category, String anchor, double x, double y,
                   Double raDegrees, Double decDegrees, List<Integer> frames, Integer starId, String tier) { }

    final List<Finding> findings;
    final Set<Integer> rejectedFrames;
    final List<StarLightCurve> variableCandidates;
    final ReportInclusion inclusion;
    final String generatedAtUtc = Instant.now().toString();
    private final int totalFrames;
    private final Map<Integer, Finding> variablesByStar;

    private ReportData(DetectionReportContext context, PipelineTelemetry telemetry, VariableStarAnalysis variables,
                       List<ResidualTransientAnalysis.CommonMotionGroup> groups, ReportInclusion inclusion) {
        this.inclusion = inclusion;
        totalFrames = context.rawFrames.size();
        Set<Integer> rejected = new HashSet<>(UnclassifiedTransientSectionWriter.rejectedFrameIndices(telemetry));
        if (telemetry != null) for (var frame : telemetry.frameQualityStats) if (frame.rejected) rejected.add(frame.frameIndex);
        rejectedFrames = Set.copyOf(rejected);
        List<Finding> output = new ArrayList<>();
        tracks(output, context, "T", "Moving target", context.movingTargets);
        tracks(output, context, "ST", "Streak track", context.streakTracks);
        tracks(output, context, "SST", "Suspected streak", context.suspectedStreakTracks);
        tracks(output, context, "S", "Single-frame streak", context.singleStreaks);
        for (int i = 0; i < context.anomalies.size(); i++) {
            var a = context.anomalies.get(i);
            if (a != null && a.object != null) add(output, context, "A" + (i + 1), "Single-frame anomaly", a.object.x, a.object.y, List.of(a.object), null, a.lowConfidence ? "Low confidence" : null);
        }
        for (int i = 0; i < context.slowMoverCandidates.size(); i++) {
            var a = context.slowMoverCandidates.get(i);
            add(output, context, "DS" + (i + 1), "Slow-mover candidate", a.x, a.y, List.of(), null, "Candidate");
        }
        for (int i = 0; i < context.localRescueCandidates.size(); i++) {
            var a = context.localRescueCandidates.get(i);
            addPoints(output, context, "LR" + (i + 1), "Local rescue", a.points);
        }
        for (int i = 0; i < context.localActivityClusters.size(); i++) {
            var a = context.localActivityClusters.get(i);
            addPoints(output, context, "LC" + (i + 1), "Local activity", a.points);
        }
        for (int i = 0; i < groups.size(); i++) {
            addPoints(output, context, "CM" + (i + 1), "Common motion", groups.get(i).sources.stream().flatMap(List::stream).toList());
        }
        List<StarLightCurve> candidates = new ArrayList<>();
        if (inclusion.variableStars && variables != null && variables.readiness != null && variables.readiness.allowsScoring()) {
            for (StarLightCurve star : variables.candidates) {
                if (star.tier == VariabilityTier.HIGH_CONFIDENCE || star.tier == VariabilityTier.POSSIBLE) {
                    candidates.add(star);
                    List<Integer> frames = new ArrayList<>();
                    for (int j = 0; j < variables.frames.size() && j < star.flags.length; j++) {
                        if (star.flags[j] == 0 && Double.isFinite(star.deltaMag[j])) frames.add(variables.frames.get(j).frameIndex + 1);
                    }
                    add(output, context, "V" + candidates.size(), "Variable-star candidate", star.x, star.y, List.of(), star.id, star.tier.name());
                    Finding f = output.remove(output.size() - 1);
                    output.add(new Finding(f.id, f.category, candidates.size() <= 40 ? "variable-v" + candidates.size() : "variable-candidates",
                            f.x, f.y, f.raDegrees, f.decDegrees, List.copyOf(frames), star.id, f.tier));
                }
            }
        }
        variableCandidates = List.copyOf(candidates);
        variablesByStar = new HashMap<>();
        for (var f : output) if (f.starId != null) variablesByStar.put(f.starId, f);
        findings = List.copyOf(output);
    }

    static ReportData create(DetectionReportContext context, PipelineTelemetry telemetry, VariableStarAnalysis variables,
                             List<ResidualTransientAnalysis.CommonMotionGroup> groups, ReportInclusion inclusion) {
        return new ReportData(context, telemetry, variables, groups, inclusion);
    }

    private static void tracks(List<Finding> out, DetectionReportContext c, String prefix, String category, List<TrackLinker.Track> tracks) {
        for (int i = 0; i < tracks.size(); i++) addPoints(out, c, prefix + (i + 1), category, tracks.get(i).points);
    }

    private static void addPoints(List<Finding> out, DetectionReportContext c, String id, String category, List<SourceExtractor.DetectedObject> points) {
        if (points == null || points.isEmpty()) return;
        double x = 0, y = 0;
        for (var p : points) { x += p.x; y += p.y; }
        boolean centroid = category.equals("Local activity") || category.equals("Common motion");
        add(out, c, id, category, centroid ? x / points.size() : points.get(0).x, centroid ? y / points.size() : points.get(0).y, points, null, null);
    }

    private static void add(List<Finding> out, DetectionReportContext c, String id, String category, double x, double y,
                            List<SourceExtractor.DetectedObject> points, Integer starId, String tier) {
        Double ra = null, dec = null;
        if (c.astrometryContext != null && c.astrometryContext.getTransformer() != null) {
            var sky = c.astrometryContext.getTransformer().pixelToSky(x, y);
            if (sky != null && Double.isFinite(sky.getRaDegrees()) && Double.isFinite(sky.getDecDegrees())) {
                ra = sky.getRaDegrees(); dec = sky.getDecDegrees();
            }
        }
        List<Integer> frames = points.stream().map(p -> p.sourceFrameIndex + 1).distinct().sorted().toList();
        out.add(new Finding(id, category, "finding-" + id.toLowerCase(Locale.ROOT), x, y, ra, dec, frames, starId, tier));
    }

    Finding variable(int starId) { return variablesByStar.get(starId); }

    List<Integer> sample(Set<Integer> mandatory, int limit) {
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < totalFrames; i++) if (!rejectedFrames.contains(i)) kept.add(i);
        Set<Integer> required = new HashSet<>();
        for (int i = 0; i < kept.size(); i++) if (mandatory.contains(kept.get(i))) required.add(i);
        return DetectionReportGenerator.getRepresentativeSequence(kept.size(), required, limit).stream().map(kept::get).toList();
    }

    List<Integer> neighbours(int index) {
        List<Integer> selected = new ArrayList<>();
        for (int i = index - 1; i >= 0; i--) if (!rejectedFrames.contains(i)) { selected.add(i); break; }
        if (index >= 0 && index < totalFrames && !rejectedFrames.contains(index)) selected.add(index);
        for (int i = index + 1; i < totalFrames; i++) if (!rejectedFrames.contains(i)) { selected.add(i); break; }
        return selected;
    }

    void writeJson(File directory) throws IOException {
        var data = new LinkedHashMap<String, Object>();
        data.put("schemaVersion", 1); data.put("generatedAtUtc", generatedAtUtc);
        data.put("spacePixelsVersion", ReportPresentation.version("spacepixels"));
        data.put("jTransientVersion", ReportPresentation.version("jtransient"));
        data.put("frameCount", totalFrames);
        data.put("frameNumbering", "one-based; empty frames on stack candidates means per-frame evidence is unavailable");
        data.put("rejectedFrames", rejectedFrames.stream().sorted().map(i -> i + 1).toList());
        data.put("includedGroups", inclusion); data.put("findings", findings);
        try (var file = new AtomicReportFile(new File(directory, "report-data.json"))) {
            new GsonBuilder().setPrettyPrinting().create().toJson(data, file.writer()); file.commit();
        }
    }
}
