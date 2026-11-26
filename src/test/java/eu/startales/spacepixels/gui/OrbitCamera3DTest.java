package eu.startales.spacepixels.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Headless unit tests for {@link OrbitCamera3D}: orientation without gimbal lock, logarithmic zoom limits,
 * near-plane segment clipping, smooth transitions and sky-dome star projection.
 */
public class OrbitCamera3DTest {

    /** Top-down north ecliptic pole view maps +X right and +Y up without gimbal lock. */
    @Test
    public void topDownViewMapsEclipticAxesWithoutGimbalLock() {
        var cam = new OrbitCamera3D();
        cam.setImmediate(Math.toRadians(-90.0), Math.PI * 0.5, 2.0);
        var origin = cam.project(0, 0, 0, 800, 600);
        var plusX = cam.project(1, 0, 0, 800, 600);
        var plusY = cam.project(0, 1, 0, 800, 600);
        assertNotNull(origin);
        assertNotNull(plusX);
        assertNotNull(plusY);
        assertEquals(400.0, origin.sx(), 1e-6);
        assertEquals(300.0, origin.sy(), 1e-6);
        assertTrue("+X must lie to the right of origin", plusX.sx() > origin.sx() + 100);
        assertEquals(origin.sy(), plusX.sy(), 1e-6);
        assertTrue("+Y must lie above origin on screen", plusY.sy() < origin.sy() - 100);
        assertEquals(origin.sx(), plusY.sx(), 1e-6);
    }

    /** Edge-on view maps +Z (north ecliptic inclination) straight upward on screen. */
    @Test
    public void edgeOnViewMapsInclinationVertically() {
        var cam = new OrbitCamera3D();
        cam.setImmediate(Math.toRadians(-90.0), 0.0, 2.0);
        var origin = cam.project(0, 0, 0, 800, 600);
        var north = cam.project(0, 0, 1.0, 800, 600);
        assertNotNull(origin);
        assertNotNull(north);
        assertEquals(origin.sx(), north.sx(), 1e-6);
        assertTrue("+Z must project above the ecliptic line", north.sy() < origin.sy() - 100);
    }

    /** Zoom respects both the close NEO flyby floor and outer Solar System ceiling. */
    @Test
    public void zoomClampsToSupportedRange() {
        var cam = new OrbitCamera3D();
        cam.setImmediate(0, 0.4, 2.0);
        for (int k = 0; k < 100; k++) cam.zoomBy(0.5);
        assertEquals(OrbitCamera3D.MIN_VIEW_RADIUS_AU, cam.viewRadiusAu(), 1e-12);
        for (int k = 0; k < 100; k++) cam.zoomBy(2.0);
        assertEquals(OrbitCamera3D.MAX_VIEW_RADIUS_AU, cam.viewRadiusAu(), 1e-12);
    }

    /** Near-plane clipping safely trims segments crossing the camera eye and culls segments behind it. */
    @Test
    public void nearPlaneClippingHandlesSegmentsCrossingCamera() {
        var cam = new OrbitCamera3D();
        cam.setImmediate(Math.toRadians(-90.0), 0.0, 1.0); // eye sits on -Y at y = -eyeDistanceAu()
        double eyeY = -cam.eyeDistanceAu();
        var behind = cam.projectSegment(new double[]{0, eyeY - 2.0, 0}, new double[]{0, eyeY - 0.5, 0}, 800, 600);
        assertNull("Segment wholly behind the eye must be culled", behind);

        var crossing = cam.projectSegment(new double[]{0, eyeY - 1.0, 0}, new double[]{0.5, 1.0, 0}, 800, 600);
        assertNotNull("Crossing segment must clip cleanly to the near plane", crossing);
        assertTrue(Double.isFinite(crossing.x1()) && Double.isFinite(crossing.y1()));
        assertTrue(Double.isFinite(crossing.x2()) && Double.isFinite(crossing.y2()));
    }

    /** Animated preset transition eases cleanly to the exact target and stops transitioning. */
    @Test
    public void smoothPresetTransitionReachesTargetAndStops() {
        var cam = new OrbitCamera3D();
        cam.setImmediate(0.0, 0.2, 3.0);
        long t0 = 1_000_000_000L;
        cam.animateTo(Math.toRadians(-90.0), Math.PI * 0.5, 1.5, t0);
        assertTrue(cam.isTransitioning());
        assertTrue(cam.stepTransition(t0 + 120_000_000L));
        assertTrue(cam.isTransitioning());
        assertTrue(cam.stepTransition(t0 + 500_000_000L));
        assertFalse(cam.isTransitioning());
        assertEquals(Math.toRadians(-90.0), cam.azimuth(), 1e-9);
        assertEquals(Math.PI * 0.5, cam.elevation(), 1e-9);
        assertEquals(1.5, cam.viewRadiusAu(), 1e-9);
    }

    /** Sky-dome star projection projects stars in front of the camera and culls stars behind it. */
    @Test
    public void skyStarProjectionRespectsCameraForwardDirection() {
        var cam = new OrbitCamera3D();
        cam.setImmediate(Math.toRadians(-90.0), 0.0, 2.0); // camera looks toward +Y
        double[] front = cam.projectSkyDirection(0, 1, 0, 800, 600);
        double[] back = cam.projectSkyDirection(0, -1, 0, 800, 600);
        assertNotNull(front);
        assertEquals(400.0, front[0], 1e-6);
        assertEquals(300.0, front[1], 1e-6);
        assertNull(back);
    }
}
