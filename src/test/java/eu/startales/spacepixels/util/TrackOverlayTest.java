package eu.startales.spacepixels.util;

import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TrackOverlayTest {

    private static SourceExtractor.DetectedObject point(int frame, double x, double y) {
        SourceExtractor.DetectedObject object = new SourceExtractor.DetectedObject(x, y, 100, 5);
        object.sourceFrameIndex = frame;
        object.sourceFilename = "f" + frame + ".fit";
        return object;
    }

    private static TrackLinker.Track track(boolean streak, boolean suspected, SourceExtractor.DetectedObject... points) {
        TrackLinker.Track track = new TrackLinker.Track();
        track.isStreakTrack = streak;
        track.isSuspectedStreakTrack = suspected;
        for (SourceExtractor.DetectedObject point : points) {
            track.addPoint(point);
        }
        return track;
    }

    @Test
    public void tracksAreNumberedAsInTheReport() {
        List<TrackLinker.Track> tracks = Arrays.asList(
                track(false, false, point(0, 10, 10), point(1, 12, 11)),
                track(true, false, point(0, 50, 50), point(2, 90, 60)),
                track(false, false, point(3, 5, 5)),                       // one frame only: not followed
                track(true, true, point(1, 1, 1), point(2, 2, 2)),         // suspected streak: not drawn
                track(false, false, point(1, 30, 30), point(2, 31, 32)));

        TrackOverlay overlay = TrackOverlay.fromTracks(tracks);

        assertEquals(3, overlay.getTracks().size());
        assertEquals("T1", overlay.getTracks().get(0).label);
        assertEquals("T2", overlay.getTracks().get(1).label);
        assertEquals("ST1", overlay.getTracks().get(2).label);
        assertTrue(overlay.getTracks().get(2).streak);
        assertEquals(1, overlay.getTracks().get(1).pointsIn("f2.fit").size());
        assertEquals(31.0, overlay.getTracks().get(1).pointsIn("f2.fit").get(0).x, 0.0);
    }

    @Test
    public void coversOnlyFramesWithATrackPosition() {
        TrackOverlay overlay = TrackOverlay.fromTracks(Arrays.asList(track(false, false, point(0, 1, 1), point(1, 2, 2))));
        assertTrue(overlay.coversAnyOf(new HashSet<>(Arrays.asList("f1.fit", "f9.fit"))));
        assertFalse(overlay.coversAnyOf(new HashSet<>(Arrays.asList("f7.fit", "f9.fit"))));
        assertTrue(TrackOverlay.fromTracks(null).getTracks().isEmpty());
    }
}
