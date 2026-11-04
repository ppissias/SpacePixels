package eu.startales.spacepixels.detection;

import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.report.FindingCategory;
import io.github.ppissias.jtransient.engine.PipelineResult;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detached fitted paths for viewers, restricted to the searched frames; retains no pixels or cutouts. */
public final class FaintMoverOverlay {
    /** One unverified path, numbered in engine/report order, with motion in pixels per minute. */
    public record Candidate(String id, double x, double y, double vx, double vy, long referenceTimeMillis) {
        /** Evaluates the fitted path at an exposure midpoint in UTC milliseconds. */
        public double[] positionAt(long midpoint) {
            double minutes = (midpoint - referenceTimeMillis) / 60000.0;
            return new double[]{x + vx * minutes, y + vy * minutes};
        }
    }

    private final List<Candidate> candidates;
    private final Map<String, Long> midpoints;
    private final int width;
    private final int height;

    /** Owns small immutable copies of fitted paths and searched-frame identities. */
    private FaintMoverOverlay(List<Candidate> candidates, Map<String, Long> midpoints, int width, int height) {
        this.candidates = List.copyOf(candidates);
        this.midpoints = Map.copyOf(midpoints);
        this.width = width;
        this.height = height;
    }

    /** Returns the candidate display values, without the engine's mutable cutout buffers. */
    public List<Candidate> candidates() { return candidates; }

    /** Returns a searched frame's exposure midpoint, or null for a different session or unsearched frame. */
    public Long midpoint(FitsFileInformation file) {
        return file == null || file.getFilePath() == null || file.getSizeWidth() != width || file.getSizeHeight() != height
                ? null : midpoints.get(new File(file.getFilePath()).getAbsolutePath());
    }

    /** Whether these viewer files include a searched frame and the run found candidates. */
    public boolean coversAnyOf(FitsFileInformation[] files) {
        if (candidates.isEmpty() || files == null) return false;
        for (var file : files) if (midpoint(file) != null) return true;
        return false;
    }

    /** Returns searched exposure midpoints in UTC milliseconds, for drawing the fitted path. */
    public List<Long> times() { return midpoints.values().stream().sorted().toList(); }

    /** Copies a completed run's path values and full file identities; sequence indices refer to the run's input. */
    public static FaintMoverOverlay from(PipelineResult result, FitsFileInformation[] files) {
        var candidates = new ArrayList<Candidate>();
        var times = new LinkedHashMap<String, Long>();
        if (files == null) files = new FitsFileInformation[0];
        if (result != null && result.telemetry != null && result.telemetry.faintMoverSearchTelemetry != null) {
            var telemetry = result.telemetry.faintMoverSearchTelemetry;
            if (telemetry.skippedReason == null) for (int i : telemetry.searchedFrameIndices) {
                if (i < 0 || i >= files.length || files[i] == null || files[i].getFilePath() == null) continue;
                var file = files[i];
                if (file.getObservationTimestamp() == -1L) continue;
                times.put(new File(file.getFilePath()).getAbsolutePath(),
                        file.getObservationTimestamp() + Math.max(0, file.getExposureDurationMillis()) / 2);
            }
            for (int i = 0; i < result.faintMoverCandidates.size(); i++) {
                var c = result.faintMoverCandidates.get(i);
                candidates.add(new Candidate(FindingCategory.FAINT_MOVER.id(i + 1), c.x, c.y, c.vx, c.vy, c.referenceTimeMillis));
            }
        }
        var first = java.util.Arrays.stream(files).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return new FaintMoverOverlay(candidates, times, first == null ? 0 : first.getSizeWidth(), first == null ? 0 : first.getSizeHeight());
    }
}
