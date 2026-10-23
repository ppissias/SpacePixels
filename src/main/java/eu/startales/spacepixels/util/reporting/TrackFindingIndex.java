package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Shared report/overlay track grouping and numbering; borrows engine tracks without changing points or identity. */
public final class TrackFindingIndex {
    private final List<Track> movingTargets;
    private final List<Track> streakTracks;
    private final List<Track> singleStreaks;
    private final Map<Track, String> ids = new IdentityHashMap<>();

    /** Copies group membership and assigns existing one-based category IDs while preserving shared track objects. */
    private TrackFindingIndex(List<Track> movingTargets, List<Track> streakTracks, List<Track> singleStreaks) {
        this.movingTargets = List.copyOf(movingTargets);
        this.streakTracks = List.copyOf(streakTracks);
        this.singleStreaks = List.copyOf(singleStreaks);
        register(FindingCategory.MOVING_TARGET, this.movingTargets);
        register(FindingCategory.STREAK_TRACK, this.streakTracks);
        register(FindingCategory.SINGLE_STREAK, this.singleStreaks);
    }

    /** Groups engine tracks by distinct observed frames, retaining multi-frame order and existing single-frame sorting. */
    public static TrackFindingIndex of(List<Track> tracks) {
        List<Track> moving = new ArrayList<>();
        List<Track> streaks = new ArrayList<>();
        List<Track> single = new ArrayList<>();
        if (tracks != null) for (Track track : tracks) {
            if (track == null || track.points == null || track.points.isEmpty()) continue;
            if (track.points.stream().map(point -> point.sourceFrameIndex).distinct().count() == 1) single.add(track);
            else if (track.isStreakTrack) streaks.add(track);
            else moving.add(track);
        }
        single.sort(Comparator.comparingInt(TrackFindingIndex::displayFrame)
                .thenComparing(TrackFindingIndex::displayFile)
                .thenComparingDouble(track -> track.points.get(0) == null ? Double.POSITIVE_INFINITY : track.points.get(0).x)
                .thenComparingDouble(track -> track.points.get(0) == null ? Double.POSITIVE_INFINITY : track.points.get(0).y));
        return new TrackFindingIndex(moving, streaks, single);
    }

    /** Uses explicit component-level report groups without reclassifying or reordering a caller's prepared context. */
    static TrackFindingIndex fromGroups(List<Track> moving, List<Track> streaks, List<Track> single) {
        return new TrackFindingIndex(moving, streaks, single);
    }

    /** Returns moving-object tracks in their original engine order. */
    public List<Track> movingTargets() { return movingTargets; }

    /** Returns confirmed streak tracks in their original engine order. */
    public List<Track> streakTracks() { return streakTracks; }

    /** Returns single-frame streak events in the established frame/file/position order. */
    public List<Track> singleStreaks() { return singleStreaks; }

    /** Resolves the report-local ID of the same engine object rather than rebuilding a separate counter. */
    public String id(Track track) {
        String id = ids.get(track);
        if (id == null) throw new IllegalArgumentException("Track is not part of this finding index.");
        return id;
    }

    /** Assigns category IDs from explicit group order without changing engine objects. */
    private void register(FindingCategory category, List<Track> tracks) {
        for (int index = 0; index < tracks.size(); index++) ids.put(tracks.get(index), category.id(index + 1));
    }

    /** Returns the earliest zero-based frame index, preserving the old single-streak sort convention. */
    private static int displayFrame(Track track) {
        return track.points.stream().filter(java.util.Objects::nonNull).mapToInt(point -> point.sourceFrameIndex)
                .min().orElse(Integer.MAX_VALUE);
    }

    /** Returns the first measured point's filename, with the existing empty-name fallback. */
    private static String displayFile(Track track) {
        DetectedObject first = track.points.get(0);
        return first != null && first.sourceFilename != null ? first.sourceFilename : "";
    }
}
