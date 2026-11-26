package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.mpc.FindOrb;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link OrbitCanvas3D} rendering (single-night NEO, revised multi-night comparison, hyperbolic comet, animation
 * and zoom) and {@link OrbitViewerDialog} lifecycle without moving the mouse or stealing window focus.
 */
public class OrbitViewerDialogTest {

    /**
     * Rendering an Apollo NEO and a linked multi-night orbit produces distinct frames when animated in time and when
     * the camera tilts or zooms, including export snapshots.
     */
    @Test
    public void renders3DOrbitAndRespondsToTimeAndCameraChanges() {
        var revised = new FindOrb.Orbit("2026-03-10T00:00:00Z", 2461109.5, 1.0876642, 0.12, 0.6733024, 0.05,
                29.518276, 3.1, 298.65056, 4.0, 44.770727, 8.0, 97.203032, 10.0, 0.3553373, 0.09,
                19.4, 0.42, 12, 97.9, 0.0698, 6.2,
                List.of(new FindOrb.Residual("2026-03-10T02:47:28Z", 0.1, -0.1, true),
                        new FindOrb.Residual("2026-03-12T03:15:00Z", -0.2, 0.1, true)),
                "2025-11-17T00:00:00Z", 2460997.628877);
        var singleNight = new FindOrb.Orbit("2026-03-10T00:00:00Z", 2461109.5, 1.32, 0.55, 0.54, 0.21,
                22.0, 15.0, 304.0, 30.0, 52.0, 80.0, 84.0, 70.0, 0.607, 0.65,
                19.8, 0.79, 6, 85.0, 0.12, 12.4,
                List.of(new FindOrb.Residual("2026-03-10T02:47:28Z", 0.4, -0.4, true)),
                "2025-11-17T00:00:00Z", 2460980.0);

        OrbitCanvas3D canvas = new OrbitCanvas3D();
        canvas.setOrbit("T1 (222169)", revised, singleNight, 2, true);
        BufferedImage atEpoch = canvas.renderSnapshot(640, 480, 1.0, false);

        // Animate forward 90 days -> scene pixels must visibly change.
        canvas.setSimulationJd(revised.epochJd() + 90.0);
        BufferedImage later = canvas.renderSnapshot(640, 480, 1.0, false);
        assertTrue("Time animation must change rendered frame", diffPixels(atEpoch, later) > 500);

        // Rotate and zoom into Earth -> scene pixels must visibly change again.
        canvas.setFocusMode(OrbitCanvas3D.FocusMode.EARTH);
        canvas.camera().zoomBy(0.05);
        canvas.camera().orbitBy(0.6, -0.3);
        BufferedImage closeEarth = canvas.renderSnapshot(640, 480, 1.0, true);
        assertTrue("Zoom and Earth focus must change rendered frame", diffPixels(later, closeEarth) > 1000);

        // Unbound hyperbolic comet without crash.
        var hyper = new FindOrb.Orbit("2026-06-08T12:00:00Z", 2461200.0, -2.5, null, 1.25, null,
                40.0, null, 75.0, null, 120.0, null, Double.NaN, null, 0.9, null,
                null, 0.3, 8, 0.0, 0.15, null, List.of(), null, 2461220.0);
        canvas.setOrbit("C1", hyper, null, 1, true);
        BufferedImage hyperImg = canvas.renderSnapshot(640, 480, 1.0, true);
        assertNotEquals(hyperImg.getRGB(0, 0), hyperImg.getRGB(320, 240));
    }

    /**
     * Dialog starts playback timer only while animating, updates cleanly between objects, and stops its timer on
     * dispose (runs off-screen without taking focus; skipped in headless environments).
     */
    @Test
    public void dialogPlaybackTimerStartsAndStopsCleanly() throws Exception {
        if (GraphicsEnvironment.isHeadless()) return;
        var orbit = new FindOrb.Orbit("2026-03-10T00:00:00Z", 2461109.5, 2.35, 0.2, 0.18, 0.05,
                11.4, 2.0, 140.0, 5.0, 88.0, 10.0, 45.0, 12.0, 1.927, 0.1,
                16.5, 0.15, 6, 0.0, 0.95, 5.0, List.of(), null, 2460950.0);

        AtomicReference<OrbitViewerDialog> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var dialog = new OrbitViewerDialog(null, new File("."), (a, d) -> d.accept(null), msg -> { });
            dialog.setFocusableWindowState(false);
            dialog.setLocation(-4000, -4000);
            dialog.setOrbit("T1", "T1", orbit, null, 1, "<b>Preliminary</b>");
            ref.set(dialog);
        });
        OrbitViewerDialog dialog = ref.get();
        try {
            SwingUtilities.invokeAndWait(() -> {
                assertFalse("Idle dialog must not burn CPU with a running timer", dialog.isTimerRunning());
                dialog.setPlaying(true);
                assertTrue(dialog.isPlaying());
                assertTrue(dialog.isTimerRunning());
                dialog.setPlaying(false);
                assertFalse(dialog.isPlaying());
                assertFalse(dialog.isTimerRunning());
                dialog.setPlaying(true);
                assertTrue(dialog.isTimerRunning());
            });
        } finally {
            SwingUtilities.invokeAndWait(dialog::dispose);
        }
        SwingUtilities.invokeAndWait(() -> assertFalse("Dispose must stop the animation timer", dialog.isTimerRunning()));
    }

    private static int diffPixels(BufferedImage a, BufferedImage b) {
        int diff = 0;
        for (int y = 0; y < a.getHeight(); y += 2) {
            for (int x = 0; x < a.getWidth(); x += 2) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) diff++;
            }
        }
        return diff;
    }
}
