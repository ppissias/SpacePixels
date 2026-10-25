package eu.startales.spacepixels.report;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import com.google.gson.GsonBuilder;
import io.github.ppissias.jtransient.core.*;
import io.github.ppissias.jtransient.photometry.*;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import java.io.*;
import java.time.Instant;
import java.util.*;

/** Shared report identities and frame selection; never mutates the engine result. */
final class ReportData {
    /** One export finding with stable compatibility IDs/text; category semantics resolve through its ID. */
    record Finding(String id, String category, String anchor, double x, double y,
                   Double raDegrees, Double decDegrees, List<Integer> frames, Integer starId, String tier) {
        /** Resolves category behavior without parsing a display label or creating another identity. */
        FindingCategory categoryDescriptor() { return FindingCategory.fromId(id); }
    }

    final List<Finding> findings;
    final Set<Integer> rejectedFrames;
    final List<StarLightCurve> variableCandidates;
    final ReportInclusion inclusion;
    final String generatedAtUtc = Instant.now().toString();
    private final int totalFrames;
    private final eu.startales.spacepixels.config.VariableStarReportOptions variableStarOptions;
    private final Map<Integer, Finding> variablesByStar;
    private final Map<String, Finding> findingsById;

    private ReportData(DetectionReportContext context, PipelineTelemetry telemetry, VariableStarAnalysis variables,
                       List<ResidualTransientAnalysis.CommonMotionGroup> groups, ReportInclusion inclusion) {
        this.inclusion = inclusion;
        variableStarOptions = context.settings.getVariableStarReportOptions();
        totalFrames = context.rawFrames.size();
        Set<Integer> rejected = new HashSet<>(UnclassifiedTransientSectionWriter.rejectedFrameIndices(telemetry));
        if (telemetry != null) for (var frame : telemetry.frameQualityStats) if (frame.rejected) rejected.add(frame.frameIndex);
        rejectedFrames = Set.copyOf(rejected);
        List<Finding> output = new ArrayList<>();
        tracks(output, context, context.movingTargets);
        tracks(output, context, context.streakTracks);
        tracks(output, context, context.singleStreaks);
        for (int i = 0; i < context.anomalies.size(); i++) {
            var a = context.anomalies.get(i);
            if (a != null && a.object != null) add(output, context, FindingCategory.ANOMALY.id(i + 1), a.object.x, a.object.y, List.of(a.object), null, null);
        }
        for (int i = 0; i < context.slowMoverCandidates.size(); i++) {
            var a = context.slowMoverCandidates.get(i);
            add(output, context, FindingCategory.SLOW_MOVER.id(i + 1), a.x, a.y, List.of(), null, "Candidate");
        }
        for (int i = 0; i < context.localRescueCandidates.size(); i++) {
            var a = context.localRescueCandidates.get(i);
            addPoints(output, context, FindingCategory.LOCAL_RESCUE.id(i + 1), a.points);
        }
        for (int i = 0; i < context.localActivityClusters.size(); i++) {
            var a = context.localActivityClusters.get(i);
            addPoints(output, context, FindingCategory.LOCAL_ACTIVITY.id(i + 1), a.points);
        }
        List<StarLightCurve> candidates = new ArrayList<>();
        if (FindingCategory.VARIABLE_STAR.included(inclusion) && variables != null && variables.readiness != null && variables.readiness.allowsScoring()) {
            var presentation = context.variablePresentation(variables);
            candidates.addAll(presentation.identities);
            for (StarLightCurve star : presentation.ordered) {
                if (star.tier == VariabilityTier.HIGH_CONFIDENCE || star.tier == VariabilityTier.POSSIBLE) {
                    int number = presentation.number(star);
                    List<Integer> frames = new ArrayList<>();
                    for (int j = 0; j < variables.frames.size() && j < star.flags.length; j++) {
                        if (star.flags[j] == 0 && Double.isFinite(star.deltaMag[j])) frames.add(variables.frames.get(j).frameIndex + 1);
                    }
                    add(output, context, FindingCategory.VARIABLE_STAR.id(number), star.x, star.y, List.of(), star.id, star.tier.name());
                    Finding f = output.remove(output.size() - 1);
                    output.add(new Finding(f.id, f.category, FindingCategory.VARIABLE_STAR.anchor(number, presentation.hasCard(star)),
                            f.x, f.y, f.raDegrees, f.decDegrees, List.copyOf(frames), star.id, f.tier));
                }
            }
        }
        // The likely artifacts come after the variable stars, in report order.
        for (int i = 0; i < context.cameraFixedGroups.size(); i++) {
            addPoints(output, context, FindingCategory.CAMERA_FIXED.id(i + 1), context.cameraFixedGroups.get(i).points);
        }
        for (int i = 0; i < groups.size(); i++) {
            addPoints(output, context, FindingCategory.COMMON_MOTION.id(i + 1), groups.get(i).sources.stream().flatMap(List::stream).toList());
        }
        variableCandidates = List.copyOf(candidates);
        variablesByStar = new HashMap<>();
        for (var f : output) if (f.starId != null) variablesByStar.put(f.starId, f);
        findings = List.copyOf(output);
        findingsById = new HashMap<>();
        for (Finding finding : findings) findingsById.put(finding.id(), finding);
    }

    static ReportData create(DetectionReportContext context, PipelineTelemetry telemetry, VariableStarAnalysis variables,
                             List<ResidualTransientAnalysis.CommonMotionGroup> groups, ReportInclusion inclusion) {
        return new ReportData(context, telemetry, variables, groups, inclusion);
    }

    /** Numbers track findings through the shared category contract without changing engine order. */
    private static void tracks(List<Finding> out, DetectionReportContext context, List<Track> tracks) {
        for (Track track : tracks) addPoints(out, context, context.trackFindings.id(track), track.points);
    }

    /** Chooses category-owned representative coordinates while keeping frame associations unchanged. */
    private static void addPoints(List<Finding> out, DetectionReportContext c, String id, List<DetectedObject> points) {
        if (points == null || points.isEmpty()) return;
        double x = 0, y = 0;
        for (var p : points) { x += p.x; y += p.y; }
        boolean centroid = FindingCategory.fromId(id).usesCentroid();
        add(out, c, id, centroid ? x / points.size() : points.get(0).x, centroid ? y / points.size() : points.get(0).y, points, null, null);
    }

    /** Adds one finding with category-owned labels/anchors and the export's resolved sky coordinates. */
    private static void add(List<Finding> out, DetectionReportContext c, String id, double x, double y,
                            List<DetectedObject> points, Integer starId, String tier) {
        Double ra = null, dec = null;
        if (c.astrometryContext != null && c.astrometryContext.getTransformer() != null) {
            var sky = c.astrometryContext.getTransformer().pixelToSky(x, y);
            if (sky != null && Double.isFinite(sky.getRaDegrees()) && Double.isFinite(sky.getDecDegrees())) {
                ra = sky.getRaDegrees(); dec = sky.getDecDegrees();
            }
        }
        List<Integer> frames = points.stream().map(p -> p.sourceFrameIndex + 1).distinct().sorted().toList();
        FindingCategory category = FindingCategory.fromId(id);
        out.add(new Finding(id, category.label(), category.anchor(category.number(id)),
                x, y, ra, dec, frames, starId, tier));
    }

    Finding variable(int starId) { return variablesByStar.get(starId); }

    /** Returns a registered finding, detecting a writer/model numbering mismatch instead of emitting a broken link. */
    Finding finding(FindingCategory category, int number) {
        return Objects.requireNonNull(findingsById.get(category.id(number)), "Unregistered finding " + category.id(number));
    }

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
        data.put("variableStarPresentation", variableStarOptions);
        try (var file = new AtomicReportFile(new File(directory, "report-data.json"))) {
            new GsonBuilder().setPrettyPrinting().create().toJson(data, file.writer()); file.commit();
        }
    }
}
