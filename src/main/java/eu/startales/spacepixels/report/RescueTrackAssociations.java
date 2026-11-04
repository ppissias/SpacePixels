package eu.startales.spacepixels.report;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Possible report-only continuations of ordinary tracks, preserving all rescue points and competing associations. */
final class RescueTrackAssociations {
    /** Agreement with the interpolated measured track at rescue exposure midpoints; no identity or confidence claim. */
    record Match(String rescueId, String trackId, int points, double rmsPixels, double maximumPixels,
                 double displacementDifferencePixels, double tolerancePixels) { }

    /** No mutable run state is owned. */
    private RescueTrackAssociations() { }

    /** Compares every visible rescue group with every ordinary track without changing engine assignments or report IDs. */
    static List<Match> capture(DetectionReportContext context) {
        var output = new ArrayList<Match>();
        double tolerance = context.config == null ? 1.0 : Math.max(1.0, context.config.maxStarJitter);
        for (int i = 0; i < context.localRescueCandidates.size(); i++) {
            var rescue = ReportTrackMotion.points(context.localRescueCandidates.get(i).points, context.fitsFiles);
            for (var track : context.movingTargets) {
                var match = compare(FindingCategory.LOCAL_RESCUE.id(i + 1), context.trackFindings.id(track), rescue,
                        ReportTrackMotion.points(track.points, context.fitsFiles), tolerance);
                if (match != null) output.add(match);
            }
        }
        return List.copyOf(output);
    }

    /** Requires every rescue point within a measured track span and the same displacement within a labelled review tolerance. */
    static Match compare(String rescueId, String trackId, List<ReportTrackMotion.Point> rescue,
                         List<ReportTrackMotion.Point> track, double tolerance) {
        if (rescue.size() < 2 || track.size() < 3 || !Double.isFinite(tolerance) || tolerance <= 0) return null;
        var frames = track.stream().map(ReportTrackMotion.Point::frameIndex).collect(java.util.stream.Collectors.toSet());
        double squares = 0, maximum = 0;
        for (var p : rescue) {
            if (frames.contains(p.frameIndex())) return null;
            var predicted = ReportTrackMotion.interpolate(track, p.midpointMillis());
            if (predicted == null) return null;
            double distance = Math.hypot(p.x() - predicted[0], p.y() - predicted[1]);
            if (distance > tolerance) return null;
            squares += distance * distance; maximum = Math.max(maximum, distance);
        }
        var first = rescue.getFirst(); var last = rescue.getLast();
        var a = ReportTrackMotion.interpolate(track, first.midpointMillis());
        var b = ReportTrackMotion.interpolate(track, last.midpointMillis());
        double difference = Math.hypot((last.x() - first.x()) - (b[0] - a[0]),
                (last.y() - first.y()) - (b[1] - a[1]));
        if (difference > tolerance) return null;
        return new Match(rescueId, trackId, rescue.size(), Math.sqrt(squares / rescue.size()), maximum, difference, tolerance);
    }

    /** Writes reciprocal links with measured evidence; multiple plausible tracks remain visible rather than picking one. */
    static void write(PrintWriter report, DetectionReportContext context, String findingId) {
        var matches = context.rescueAssociations.stream()
                .filter(m -> m.rescueId().equals(findingId) || m.trackId().equals(findingId)).toList();
        if (matches.isEmpty()) return;
        report.print("<div class='track-link-note rescue-track-association'><strong>Possible track continuation (review):</strong> ");
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) report.print("; ");
            var m = matches.get(i);
            String other = m.rescueId().equals(findingId) ? m.trackId() : m.rescueId();
            var finding = context.data.findings.stream().filter(f -> f.id().equals(other)).findFirst().orElseThrow();
            report.printf(Locale.US, "<a href='#%s'>%s</a>: %d separate frames, RMS %.2f px, maximum %.2f px; displacement difference %.2f px (review tolerance %.2f px)",
                    finding.anchor(), other, m.points(), m.rmsPixels(), m.maximumPixels(), m.displacementDifferencePixels(), m.tolerancePixels());
        }
        report.print(". Compared with the measured track interpolated at exposure midpoints, without extrapolation. "
                + "These links do not confirm identity or merge findings; other nearby tracks may also be compatible.</div>");
    }
}
