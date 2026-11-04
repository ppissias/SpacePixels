package eu.startales.spacepixels.report;

import io.github.ppissias.jtransient.model.DetectedObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Controls report-only continuations, including reversed/crossing motion, missing metadata and ambiguous frame IDs. */
public class RescueTrackAssociationsTest {
    /** Separate original frames within the actual measured track span can supply a possible continuation. */
    @Test public void comparesExposureMidpointsWithoutClaimingOrMergingDetections() {
        var track = points(0, 2, 4, 8);
        var rescue = points(1, 3, 7);
        var match = RescueTrackAssociations.compare("LR1", "T1", rescue, track, 1);
        assertNotNull(match);
        assertEquals(3, match.points());
        assertEquals(0, match.rmsPixels(), 1e-12);
        assertEquals(0, match.displacementDifferencePixels(), 1e-12);
        assertEquals(4, track.size());
        assertEquals(3, rescue.size());
    }

    /** A crossing, reversed motion, a shared original frame and extrapolation do not supply these links. */
    @Test public void rejectsSpatialCoincidenceWithoutTimedAgreement() {
        var track = points(0, 2, 4, 8);
        var reversed = List.of(new ReportTrackMotion.Point(1, 90_000, 17, 20),
                new ReportTrackMotion.Point(7, 450_000, 11, 20));
        assertNull(RescueTrackAssociations.compare("LR1", "T1", reversed, track, 1));
        var crossing = List.of(new ReportTrackMotion.Point(1, 90_000, 11, 17),
                new ReportTrackMotion.Point(7, 450_000, 17, 23));
        assertNull(RescueTrackAssociations.compare("LR1", "T1", crossing, track, 1));
        assertNull(RescueTrackAssociations.compare("LR1", "T1", points(1, 9), track, 1));
        assertNull(RescueTrackAssociations.compare("LR1", "T1", points(2, 3), track, 1));
    }

    /** A two-frame local repeat retains a cautious review comparison when both positions actually agree. */
    @Test public void supportsTwoFrameGroupsButRequiresAllTheirEvidence() {
        assertNotNull(RescueTrackAssociations.compare("LR2", "T1", points(5, 7), points(0, 2, 8), 1));
        assertNull(RescueTrackAssociations.compare("LR2", "T1", points(5), points(0, 2, 8), 1));
        assertNull(RescueTrackAssociations.compare("LR2", "T1", points(5, 7), points(0, 8), 1));
    }

    /** Timestamp zero is valid; real exposure lengths determine the midpoint and copied scalar positions. */
    @Test public void copiesCentroidsAndRejectsAmbiguousClocksOrFrames() {
        var a = detection(0, 0, 60_000, 10);
        var b = detection(3, 180_000, 120_000, 13);
        var copied = ReportTrackMotion.points(List.of(a, b), null);
        assertEquals(30_000, copied.getFirst().midpointMillis());
        assertEquals(240_000, copied.getLast().midpointMillis());
        a.x = 100;
        assertEquals(10, copied.getFirst().x(), 0);
        assertThrows(UnsupportedOperationException.class, () -> copied.clear());
        b.sourceFrameIndex = 0;
        assertTrue(ReportTrackMotion.points(List.of(a, b), null).isEmpty());
        b.sourceFrameIndex = 3; b.timestamp = -1;
        assertTrue(ReportTrackMotion.points(List.of(a, b), null).isEmpty());
        b.timestamp = 0; b.exposureDuration = 60_000;
        assertTrue(ReportTrackMotion.points(List.of(a, b), null).isEmpty());
    }

    /** The engine's missing-time sentinel is exactly -1; pre-Unix dates and timestamp zero remain real times. */
    @Test public void preservesRealTimesBeforeTheUnixEpoch() {
        var positions = ReportTrackMotion.points(List.of(detection(0, -180_000, 60_000, 10),
                detection(1, -120_000, 60_000, 11), detection(2, -60_000, 60_000, 12),
                detection(3, 0, 60_000, 13)), null);
        assertEquals(4, positions.size());
        assertEquals(-150_000, positions.getFirst().midpointMillis());
        assertEquals(30_000, positions.getLast().midpointMillis());
        assertEquals(1, ReportTrackMotion.fit(positions).vx(), 1e-12);
    }

    /** Unweighted descriptive fitting keeps actual uneven timing and pixel/minute units. */
    @Test public void fitsUnevenCadenceWithoutUsingOrdinalFrameRates() {
        var fit = ReportTrackMotion.fit(points(0, 1, 3, 8));
        assertNotNull(fit);
        assertEquals(1, fit.vx(), 1e-12);
        assertEquals(0, fit.vy(), 0);
        assertEquals(0, fit.rms(), 1e-12);
        assertEquals(14, fit.at(270_000)[0], 1e-12);
    }

    /** Builds exact unit drift with sparse original frame identities and known midpoint times. */
    private static List<ReportTrackMotion.Point> points(int... indices) {
        var output = new ArrayList<ReportTrackMotion.Point>();
        for (int i : indices) output.add(new ReportTrackMotion.Point(i, 30_000 + i * 60_000L, 10 + i, 20));
        return output;
    }

    /** Creates a metadata-only detection with capture-start milliseconds and a positive exposure duration. */
    private static DetectedObject detection(int index, long start, long exposure, double x) {
        var p = new DetectedObject(x, 20, 100, 5);
        p.sourceFrameIndex = index; p.timestamp = start; p.exposureDuration = exposure;
        return p;
    }
}
