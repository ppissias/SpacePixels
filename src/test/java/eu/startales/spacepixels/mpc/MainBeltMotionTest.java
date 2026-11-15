package eu.startales.spacepixels.mpc;

import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The main-belt motion rule: rates and direction at opposition, and fast, slow and sideways objects. */
public class MainBeltMotionTest {
    private static final long TIME = Instant.parse("2026-03-20T00:00:00Z").toEpochMilli();

    /** The opposition point (opposite the Sun on the ecliptic), as RA and Dec in degrees. */
    private static double[] opposition() {
        double[] e = MainBeltMotion.earthPosition(TIME);
        double obliquity = Math.toRadians(23.44);
        double n = Math.hypot(e[0], e[1]);
        double x = e[0] / n, y = e[1] / n;
        double ye = y * Math.cos(obliquity), ze = y * Math.sin(obliquity);
        return new double[]{(Math.toDegrees(Math.atan2(ye, x)) + 360) % 360, Math.toDegrees(Math.asin(ze))};
    }

    /** At opposition a main-belt asteroid moves westward at about 29 to 42″/h, nearest the fastest for the inner belt. */
    @Test
    public void mainBeltAtOpposition() {
        double[] p = opposition();
        var e = MainBeltMotion.expected(p[0], p[1], TIME);
        assertEquals(180, e.elongation(), 1);
        assertEquals(29, e.minRate(), 2);
        assertEquals(42, e.maxRate(), 2);
        assertEquals("westward, tilted by the ecliptic", 270, e.innerAngle(), 25);
        assertEquals(e.innerAngle(), e.outerAngle(), 1);
    }

    /** Usual, fast, slow and sideways motions. */
    @Test
    public void judgesMotions() {
        double[] p = opposition();
        var e = MainBeltMotion.expected(p[0], p[1], TIME);
        var usual = MainBeltMotion.assess(p[0], p[1], TIME, 35, e.innerAngle());
        assertFalse(usual.note(), usual.unusual());
        assertTrue(usual.note(), usual.note().startsWith("Motion usual for a main-belt asteroid") && usual.note().contains("Only an orbit"));
        var fast = MainBeltMotion.assess(p[0], p[1], TIME, 150, e.innerAngle());
        assertTrue(fast.unusual());
        assertTrue(fast.reason(), fast.reason().contains("near Earth"));
        var slow = MainBeltMotion.assess(p[0], p[1], TIME, 5, e.innerAngle());
        assertTrue(slow.reason(), slow.unusual() && slow.reason().contains("beyond the main belt"));
        var sideways = MainBeltMotion.assess(p[0], p[1], TIME, 35, (e.innerAngle() + 90) % 360);
        assertTrue(sideways.unusual());
        assertTrue(sideways.reason(), sideways.reason().contains("off the main-belt direction"));
    }
}
