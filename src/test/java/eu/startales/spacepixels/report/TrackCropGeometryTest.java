package eu.startales.spacepixels.report;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.model.Track;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The cutout box of a finding: the shape of a streak, and never any part outside the frame. */
public class TrackCropGeometryTest {

    /** A nearly vertical streak gets a tall, narrow box from the pixels it covers, not a square. */
    @Test public void aStreakGetsABoxOfItsOwnShape() {
        DetectedObject streak = streak(400, 100, 440, 500);
        TrackCropGeometry.CropBounds bounds = new TrackCropGeometry.CropBounds(streak, 200, new int[]{0, 0});
        assertEquals(40 + 200, bounds.trackBoxWidth);
        assertEquals(400 + 200, bounds.trackBoxHeight);
        assertEquals(420 - 120, bounds.startX);
        assertEquals(300 - 300, bounds.startY);
    }

    /** Without its pixels, a streak's box follows its oriented extent. */
    @Test public void aStreakWithoutPixelsUsesItsOrientedExtent() {
        DetectedObject streak = new DetectedObject(500, 300, 1000, 400);
        streak.isStreak = true;
        streak.angle = Math.PI / 2;
        streak.majorExtent = 400;
        streak.minorExtent = 4;
        TrackCropGeometry.CropBounds bounds = new TrackCropGeometry.CropBounds(streak, 200, new int[]{0, 0});
        assertEquals(4 + 200, bounds.trackBoxWidth);
        assertEquals(400 + 200, bounds.trackBoxHeight);
    }

    /** A box larger than the frame shrinks to the frame, and one over an edge moves inside it. */
    @Test public void theBoxStaysInsideTheFrame() {
        Track track = new Track();
        track.points.add(streak(700, 0, 520, 580));
        TrackCropGeometry.CropBounds whole = new TrackCropGeometry.CropBounds(track, 200, new int[]{752, 581});
        assertEquals(180 + 200, whole.trackBoxWidth);
        assertEquals(581, whole.trackBoxHeight);
        assertEquals(752 - 380, whole.startX);
        assertEquals(0, whole.startY);
        assertEquals(whole.startX + whole.trackBoxWidth / 2, whole.fixedCenterX);

        DetectedObject corner = new DetectedObject(10, 570, 1000, 8);
        corner.pixelArea = 8;
        TrackCropGeometry.CropBounds moved = new TrackCropGeometry.CropBounds(corner, 200, new int[]{752, 581});
        assertEquals(0, moved.startX);
        assertEquals(581 - moved.trackBoxHeight, moved.startY);
        assertTrue(moved.trackBoxWidth > 200 && moved.trackBoxWidth < 220);
    }

    /** A point-like detection keeps the circle of its footprint, and an unknown frame size leaves the box as it is. */
    @Test public void aPointKeepsItsCircleAndAnUnknownFrameChangesNothing() {
        DetectedObject point = new DetectedObject(10, 10, 1000, 50);
        point.pixelArea = 50;
        TrackCropGeometry.CropBounds bounds = new TrackCropGeometry.CropBounds(point, 100, new int[]{0, 0});
        int diameter = (int) Math.round(2 * Math.sqrt(50 / Math.PI));
        assertEquals(diameter + 100, bounds.trackBoxWidth);
        assertEquals(diameter + 100, bounds.trackBoxHeight);
        assertEquals(10 - bounds.trackBoxWidth / 2, bounds.startX);
        assertEquals(10, TrackCropGeometry.insideFrame(10, 900, 800));
    }

    /** A one-pixel-wide streak from (x0, y0) to (x1, y1), with its centroid in the middle. */
    private static DetectedObject streak(int x0, int y0, int x1, int y1) {
        DetectedObject streak = new DetectedObject((x0 + x1) / 2.0, (y0 + y1) / 2.0, 1000, 0);
        streak.isStreak = true;
        streak.rawPixels = new ArrayList<>();
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int step = 0; step <= steps; step++) {
            streak.rawPixels.add(new Pixel(x0 + (x1 - x0) * step / steps, y0 + (y1 - y0) * step / steps, 100));
        }
        streak.pixelCount = streak.rawPixels.size();
        streak.pixelArea = streak.pixelCount;
        return streak;
    }
}
