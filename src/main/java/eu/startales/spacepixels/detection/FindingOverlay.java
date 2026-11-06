package eu.startales.spacepixels.detection;

import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.report.FindingCategory;
import eu.startales.spacepixels.report.TrackFindingIndex;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.model.DetectedObject;
import java.io.File;
import java.util.*;

/** Detached measured markers and explicitly labelled stack/reference positions for the report's other categories. */
public final class FindingOverlay {
    /** One measured centroid in registered image pixels; frame numbers refer to the run's one-based input order. */
    public record Position(int frameNumber, double x, double y) { }
    /** A finding's report identity and display geometry; no positions means a reference, never a per-frame detection. */
    public record Marker(String id, double x, double y, List<Position> positions, String description) {
        /** Owns the small position container without retaining mutable engine objects. */
        public Marker { positions = List.copyOf(positions); }
    }
    private final List<Marker> markers;
    private final Map<String, Integer> frameNumbers;
    private final int width, height;

    /** Captures marker and frame identities after a run or a validated report import; no image buffers are retained. */
    FindingOverlay(List<Marker> markers, FitsFileInformation[] files) {
        this.markers = List.copyOf(markers);
        var numbers = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < files.length; i++) if (files[i] != null && files[i].getFilePath() != null)
            numbers.put(new File(files[i].getFilePath()).getAbsolutePath(), i + 1);
        frameNumbers = Map.copyOf(numbers);
        var first = Arrays.stream(files).filter(Objects::nonNull).findFirst().orElse(null);
        width = first == null ? 0 : first.getSizeWidth();
        height = first == null ? 0 : first.getSizeHeight();
    }

    /** Returns detached marker geometry in report order. */
    public List<Marker> markers() { return markers; }

    /** Returns the run's one-based frame identity, refusing different folders or dimensions. */
    public Integer frameNumber(FitsFileInformation file) {
        return file == null || file.getFilePath() == null || file.getSizeWidth() != width || file.getSizeHeight() != height
                ? null : frameNumbers.get(new File(file.getFilePath()).getAbsolutePath());
    }

    /** Whether any requested frame belongs to this nonempty finding layer. */
    public boolean coversAnyOf(FitsFileInformation[] files) {
        if (files != null && !markers.isEmpty()) for (var file : files) if (frameNumber(file) != null) return true;
        return false;
    }

    /** Copies measured centroids and original frame numbers, without treating a representative coordinate as a measurement. */
    public static List<Position> positions(List<DetectedObject> points) {
        return points == null ? List.of() : points.stream().filter(Objects::nonNull)
                .filter(p -> p.sourceFrameIndex >= 0 && Double.isFinite(p.x) && Double.isFinite(p.y))
                .map(p -> new Position(p.sourceFrameIndex + 1, p.x, p.y)).toList();
    }

    /** Captures categories not already drawn by the track, variable-star and fitted-faint-mover layers. */
    public static FindingOverlay from(PipelineResult result, FitsFileInformation[] files) {
        var markers = new ArrayList<Marker>();
        if (result != null) {
            var tracks = TrackFindingIndex.of(result.tracks);
            for (var track : tracks.singleStreaks()) add(markers, tracks.id(track), track.points);
            for (int i = 0; i < result.anomalies.size(); i++) if (result.anomalies.get(i).object != null)
                add(markers, FindingCategory.ANOMALY.id(i + 1), List.of(result.anomalies.get(i).object));
            for (int i = 0; i < result.slowMoverCandidates.size(); i++) {
                var p = result.slowMoverCandidates.get(i);
                markers.add(new Marker(FindingCategory.SLOW_MOVER.id(i + 1), p.x, p.y, List.of(), "Stack candidate reference; not a per-frame detection"));
            }
            var residuals = result.residualTransientAnalysis;
            if (residuals != null) {
                for (int i = 0; i < residuals.localRescueCandidates.size(); i++)
                    add(markers, FindingCategory.LOCAL_RESCUE.id(i + 1), residuals.localRescueCandidates.get(i).points);
                for (int i = 0; i < residuals.localActivityClusters.size(); i++)
                    add(markers, FindingCategory.LOCAL_ACTIVITY.id(i + 1), residuals.localActivityClusters.get(i).points);
                for (int i = 0; i < residuals.commonMotionGroups.size(); i++)
                    add(markers, FindingCategory.COMMON_MOTION.id(i + 1), residuals.commonMotionGroups.get(i).sources.stream().flatMap(List::stream).toList());
            }
            for (int i = 0; i < result.cameraFixedGroups.size(); i++)
                add(markers, FindingCategory.CAMERA_FIXED.id(i + 1), result.cameraFixedGroups.get(i).points);
        }
        return new FindingOverlay(markers, files == null ? new FitsFileInformation[0] : files);
    }

    /** Keeps report-category representative semantics separate from the copied measured centroids. */
    private static void add(List<Marker> markers, String id, List<DetectedObject> points) {
        if (points == null || points.isEmpty()) return;
        var category = FindingCategory.fromId(id);
        double x = category.usesCentroid() ? points.stream().mapToDouble(p -> p.x).average().orElse(0) : points.getFirst().x;
        double y = category.usesCentroid() ? points.stream().mapToDouble(p -> p.y).average().orElse(0) : points.getFirst().y;
        markers.add(new Marker(id, x, y, positions(points), category.label()));
    }
}
