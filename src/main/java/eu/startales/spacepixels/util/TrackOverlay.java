/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util;

import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.engine.PipelineResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The tracks of the last detection run, reduced to what a viewer draws: a label and the position in each frame.
 * Tracks are named and ordered as in the report: moving objects T1, T2, … and streak tracks ST1, ST2, ….
 */
public final class TrackOverlay {

    /** One position of a track, in the pixel coordinates of the frame with this file name. */
    public static final class Point {
        public final String fileName;
        public final double x;
        public final double y;

        Point(String fileName, double x, double y) {
            this.fileName = fileName;
            this.x = x;
            this.y = y;
        }
    }

    public static final class Track {
        public final String label;
        public final boolean streak;
        public final List<Point> points;

        Track(String label, boolean streak, List<Point> points) {
            this.label = label;
            this.streak = streak;
            this.points = Collections.unmodifiableList(points);
        }

        /** The positions in the frame with this file name (a streak can have more than one). */
        public List<Point> pointsIn(String fileName) {
            List<Point> inFrame = new ArrayList<>();
            for (Point point : points) {
                if (point.fileName.equals(fileName)) {
                    inFrame.add(point);
                }
            }
            return inFrame;
        }
    }

    private final List<Track> tracks;

    private TrackOverlay(List<Track> tracks) {
        this.tracks = Collections.unmodifiableList(tracks);
    }

    public List<Track> getTracks() {
        return tracks;
    }

    /** Whether any track has a position in one of these frames. */
    public boolean coversAnyOf(Set<String> fileNames) {
        for (Track track : tracks) {
            for (Point point : track.points) {
                if (fileNames.contains(point.fileName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The multi-frame tracks of a run, split as the report splits them. Suspected streaks and single-frame streaks
     * are left out: they are not followed across frames.
     */
    public static TrackOverlay from(PipelineResult result) {
        return fromTracks(result == null ? null : result.tracks);
    }

    static TrackOverlay fromTracks(List<TrackLinker.Track> linkedTracks) {
        List<Track> streakTracks = new ArrayList<>();
        List<Track> movingTracks = new ArrayList<>();
        if (linkedTracks != null) {
            for (TrackLinker.Track track : linkedTracks) {
                if (track == null || track.points == null || track.points.isEmpty() || track.isSuspectedStreakTrack) {
                    continue;
                }
                Set<Integer> frames = new HashSet<>();
                List<Point> points = new ArrayList<>();
                for (SourceExtractor.DetectedObject point : track.points) {
                    frames.add(point.sourceFrameIndex);
                    if (point.sourceFilename != null) {
                        points.add(new Point(point.sourceFilename, point.x, point.y));
                    }
                }
                if (frames.size() < 2) {
                    continue;
                }
                if (track.isStreakTrack) {
                    streakTracks.add(new Track("ST" + (streakTracks.size() + 1), true, points));
                } else {
                    movingTracks.add(new Track("T" + (movingTracks.size() + 1), false, points));
                }
            }
        }
        List<Track> all = new ArrayList<>(movingTracks);
        all.addAll(streakTracks);
        return new TrackOverlay(all);
    }
}
