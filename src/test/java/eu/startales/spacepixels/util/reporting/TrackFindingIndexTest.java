package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ReportInclusion;
import eu.startales.spacepixels.util.TrackOverlay;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import org.junit.Test;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Protects the common track identities used by report data, cards, maps and viewer overlays. */
public class TrackFindingIndexTest {
    /** Keeps engine order for linked tracks and the established frame/file/position ordering for single events. */
    @Test public void mixedGroupsKeepStableIdsWithoutMutatingEngineTracks() {
        Track moving = track(false, point(0, "a.fit", 1, 2), point(1, "b.fit", 3, 4));
        Track streak = track(true, point(0, "a.fit", 5, 6), point(2, "c.fit", 7, 8));
        Track laterMoving = track(false, point(1, "b.fit", 9, 10), point(2, "c.fit", 11, 12));
        Track laterSingle = track(true, point(3, "d.fit", 13, 14));
        Track fragments = track(true, point(1, "b.fit", 15, 16), point(1, "b.fit", 17, 18));
        Track earlierFile = track(false, point(1, "a.fit", 20, 21));
        Track earlierPosition = track(false, point(1, "b.fit", 14, 99));
        List<Track> input = Arrays.asList(laterSingle, streak, fragments, moving, earlierPosition,
                null, new Track(), earlierFile, laterMoving);

        TrackFindingIndex index = TrackFindingIndex.of(input);
        assertEquals(List.of(moving, laterMoving), index.movingTargets());
        assertEquals(List.of(streak), index.streakTracks());
        assertEquals(List.of(earlierFile, earlierPosition, fragments, laterSingle), index.singleStreaks());
        assertEquals("T1", index.id(moving));
        assertEquals("T2", index.id(laterMoving));
        assertEquals("ST1", index.id(streak));
        assertEquals("S3", index.id(fragments));
        assertSame(fragments, index.singleStreaks().get(2));
        assertEquals(2, fragments.points.size());
        assertEquals(15, fragments.points.get(0).x, 0);
        assertEquals(laterSingle, input.get(0));
        assertThrows(UnsupportedOperationException.class, () -> index.movingTargets().clear());
        assertThrows(IllegalArgumentException.class, () -> index.id(new Track()));

        var overlay = TrackOverlay.from(PipelineResult.builder().tracks(input).build());
        assertEquals(List.of("T1", "T2", "ST1"), overlay.getTracks().stream().map(track -> track.label).toList());
        var context = new DetectionReportContext(VisualizationSettings.defaults(), new File("."), List.of(),
                new FitsFileInformation[0], new DetectionConfig(), AstrometryContextFactory.buildContext(new FitsFileInformation[0], null, null),
                null, null, null, null, null, List.of(), List.of(), null, List.of(), List.of(), List.of(),
                index.singleStreaks(), index.streakTracks(), index.movingTargets());
        context.data = ReportData.create(context, null, null, List.of(), ReportInclusion.all());
        assertEquals(List.of("T1", "T2", "ST1", "S1", "S2", "S3", "S4"),
                context.data.findings.stream().map(ReportData.Finding::id).toList());
        assertEquals("finding-st1", context.findingAnchor(FindingCategory.STREAK_TRACK, 1));
        assertEquals("S3", context.findingId(FindingCategory.SINGLE_STREAK, 3));
    }

    /** Supports empty engine output and preserves explicit component groups without reclassifying their members. */
    @Test public void emptyAndPreparedGroupsHaveExplicitOwnershipAndOrder() {
        assertTrue(TrackFindingIndex.of(null).singleStreaks().isEmpty());
        Track single = track(false, point(4, "e.fit", 1, 2));
        var index = TrackFindingIndex.fromGroups(List.of(single), List.of(), List.of());
        assertEquals("T1", index.id(single));
        assertEquals(List.of(single), index.movingTargets());
    }

    /** Creates one measured position with a zero-based frame index and a stable source-file identity. */
    private static DetectedObject point(int frame, String filename, double horizontal, double vertical) {
        var point = new DetectedObject(horizontal, vertical, 100, 5);
        point.sourceFrameIndex = frame;
        point.sourceFilename = filename;
        return point;
    }

    /** Builds a mutable engine track whose original object and point order must survive report indexing. */
    private static Track track(boolean streak, DetectedObject... points) {
        var track = new Track();
        track.isStreakTrack = streak;
        for (DetectedObject point : points) track.addPoint(point);
        return track;
    }
}
