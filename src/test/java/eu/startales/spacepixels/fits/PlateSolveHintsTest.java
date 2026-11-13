package eu.startales.spacepixels.fits;

import nom.tam.fits.Fits;
import org.junit.Test;
import java.util.LinkedHashMap;
import static org.junit.Assert.*;

/** Source-centre and scale preservation without advertising an obsolete post-registration WCS. */
public class PlateSolveHintsTest {
    /** A reference pixel away from the centre must not be mistaken for the image centre. */
    @Test public void capturesCentreAndScaleAndRemainsUnsolved() throws Exception {
        var header = Fits.makeHDU(new short[100][200]).getHeader();
        header.addValue("CTYPE1", "RA---TAN", null); header.addValue("CTYPE2", "DEC--TAN", null);
        header.addValue("CRPIX1", 10.0, null); header.addValue("CRPIX2", 20.0, null);
        header.addValue("CRVAL1", 359.99, null); header.addValue("CRVAL2", 40.0, null);
        header.addValue("CD1_1", -1.0 / 3600, null); header.addValue("CD1_2", 0.0, null);
        header.addValue("CD2_1", 0.0, null); header.addValue("CD2_2", 1.0 / 3600, null);
        var hints = PlateSolveHints.fromSource(header);
        assertNotNull(hints);
        assertEquals(1.0, hints.arcsecPerPixel(), 1e-5);
        assertTrue(Math.abs(hints.raDegrees() - 359.99) > 0.01);
        var output = Fits.makeHDU(new short[100][200]).getHeader();
        PlateSolveHints.write(output, hints);
        var restored = PlateSolveHints.read(output, 100);
        assertEquals(hints.raDegrees(), restored.raDegrees(), 1e-10);
        assertEquals(hints.decDegrees(), restored.decDegrees(), 1e-10);
        assertEquals(hints.arcsecPerPixel(), restored.arcsecPerPixel(), 1e-10);
        assertEquals(hints.radiusDegrees(), restored.radiusDegrees(), 1e-10);
        assertEquals(hints.imageHeightDegrees(), restored.imageHeightDegrees(), 1e-8);
        var cards = new LinkedHashMap<String, String>();
        output.iterator().forEachRemaining(card -> cards.put(card.getKey(), card.getValue()));
        assertNull(WcsCoordinateTransformer.fromHeader(cards));
        output.addValue("SPHSCALE", -1.0, null);
        assertNull(PlateSolveHints.read(output, 100));
    }

    /** Missing WCS never fabricates hints; inherited values are cleared if no source hint is available. */
    @Test public void missingSourceAndInvalidHintsAreIgnored() throws Exception {
        var header = Fits.makeHDU(new short[20][20]).getHeader();
        assertNull(PlateSolveHints.fromSource(header));
        header.addValue("SPHRA", 10.0, null);
        assertNull(PlateSolveHints.read(header, 20));
        PlateSolveHints.write(header, null);
        assertFalse(header.containsKey("SPHRA"));
    }
}
