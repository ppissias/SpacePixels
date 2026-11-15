package eu.startales.spacepixels.report;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The field depth falls back to the frames' 5-sigma signal limit when the Gaia patches cannot tell it. */
public class FieldDepthSignalTest {
    @Test
    public void usesTheSignalLimitWhenThePatchesCannotTell() {
        var depth = new FieldDepth();
        assertTrue(depth.isEmpty());
        depth.signalLimit = 23.8;
        assertFalse(depth.isEmpty());
        assertTrue(depth.fromSignal());
        assertEquals(23.8, depth.median(), 1e-9);
        assertEquals("G 23.8", depth.medianLabel());
        assertTrue(depth.method().contains("5 sigma"));
        var patches = new FieldDepth();
        patches.frames.add(new FieldDepth.Frame(0, "f1", 17.7, false));
        assertFalse(patches.fromSignal());
        assertEquals(17.7, patches.median(), 1e-9);
        assertTrue(patches.method().contains("share of stars found"));
    }
}
