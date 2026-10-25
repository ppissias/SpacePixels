package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.FitsFileInformation;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;

import static org.junit.Assert.*;

/** Verifies persisted display choices, independent magnitude bands and hover targets after filtering. */
public class ViewerAnnotationPreferencesTest {
    /** Missing and damaged values preserve the old display; valid choices survive viewer reconstruction. */
    @Test public void preferencesRoundTripAndRecoverFromInvalidLimits() throws Exception {
        Preferences storage = Preferences.userRoot().node("spacepixels-test/" + UUID.randomUUID());
        try {
            assertEquals(ViewerAnnotationPreferences.DEFAULT, ViewerAnnotationPreferences.load(storage));
            ViewerAnnotationPreferences chosen = new ViewerAnnotationPreferences(14.5, 12, false);
            chosen.save(storage);
            assertEquals(chosen, ViewerAnnotationPreferences.load(storage));
            storage.putDouble("faintestStar", Double.NaN);
            storage.putDouble("faintestVariable", 100);
            assertEquals(new ViewerAnnotationPreferences(99, 99, false), ViewerAnnotationPreferences.load(storage));
        } finally {
            storage.removeNode();
        }
    }

    /** Limits affect the intended layers, include boundary/unknown values and keep deep-sky objects visible. */
    @Test public void magnitudeLimitsRespectCategoriesAndUnknownValues() {
        ViewerAnnotationPreferences filters = new ViewerAnnotationPreferences(14, 12, false);
        assertTrue(filters.includes(mark(SkyMark.Kind.STAR, 14, 10)));
        assertFalse(filters.includes(mark(SkyMark.Kind.NAMED_STAR, 14.1, 10)));
        assertFalse(filters.includes(mark(SkyMark.Kind.VARIABLE, 13, 10)));
        assertTrue(filters.includes(mark(SkyMark.Kind.VARIABLE, Double.NaN, 10)));
        assertTrue(filters.includes(mark(SkyMark.Kind.DEEP_SKY, 20, 10)));
        assertTrue(ViewerAnnotationPreferences.DEFAULT.includes(mark(SkyMark.Kind.VARIABLE, 100, 10)));
    }

    /** Hiding names retains hover identification; filtering a mark removes its stale hover target. */
    @Test public void filteredMarkersAndHoverAgreeWithoutNames() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FrameView view = new FrameView();
            view.setSize(400, 320);
            FitsFileInformation info = new FitsFileInformation("test.fit", "test.fit", true, 200, 160);
            view.showFrame(BlinkSequence.fromMono(info, new short[160][200]), new byte[1][65536], false);
            view.setSkyMarks(List.of(mark(SkyMark.Kind.STAR, 15, 100)), 18);
            view.setSkyPreferences(new ViewerAnnotationPreferences(16, 99, false));
            paint(view);
            assertNotNull(view.skyMarkAt(new Point(100, 80)));
            view.setSkyPreferences(new ViewerAnnotationPreferences(14, 99, false));
            assertNull(view.skyMarkAt(new Point(100, 80)));
            paint(view);
            assertNull(view.skyMarkAt(new Point(100, 80)));
        });
    }

    /** Creates a catalogue marker at a fixed frame position without involving catalogue downloads. */
    private static SkyMark mark(SkyMark.Kind kind, double magnitude, double horizontalPixel) {
        return new SkyMark(kind, horizontalPixel, 80, magnitude, 0, 0, 0, "Test star", "Test star description");
    }

    /** Paints the real marker renderer so hover assertions use the marks actually displayed. */
    private static void paint(FrameView view) {
        Graphics2D graphics = new BufferedImage(400, 320, BufferedImage.TYPE_INT_RGB).createGraphics();
        try {
            view.paint(graphics);
        } finally {
            graphics.dispose();
        }
    }
}
