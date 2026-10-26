package eu.startales.spacepixels.report;

import io.github.ppissias.jtransient.core.PixelEncoding;
import org.junit.Test;

import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Checks the display's physical sky/flux normalization independently of the photometry measurements. */
public class PhotometryCutoutTest {
    /** Constant stars must look alike through either lower or higher transmission, despite different sky levels. */
    @Test public void constantStarsMatchAcrossSkyAndTransparencyChangesWithoutMutatingPixels() {
        for (double transmission : new double[]{0.5, 2.0}) {
            short[][] bright = crop(1000, 1.0);
            short[][] faint = crop(2400, transmission);
            short[][] savedBright = copy(bright);
            short[][] savedFaint = copy(faint);
            double zeroPointDifference = -2.5 * Math.log10(transmission);

            var result = PhotometryReportSectionWriter.matchedCutouts(bright, faint, 0, zeroPointDifference);

            assertTrue(result.transparencyCorrected());
            assertArrayEquals(samples(result.brightest()), samples(result.faintest()));
            assertEquals(0, result.brightest().getRaster().getSample(0, 0, 0));
            assertEquals(0, result.faintest().getRaster().getSample(0, 0, 0));
            assertTrue(Arrays.deepEquals(savedBright, bright));
            assertTrue(Arrays.deepEquals(savedFaint, faint));
        }
    }

    /** Removing half-transmission must retain a real 40-percent flux decrease in the variable itself. */
    @Test public void intrinsicVariabilityRemainsVisibleAfterTransparencyCorrection() {
        var result = PhotometryReportSectionWriter.matchedCutouts(crop(1000, 1), crop(2400, 0.5 * 0.6),
                -0.3, -0.3 - 2.5 * Math.log10(0.5));

        assertEquals(255, result.brightest().getRaster().getSample(16, 16, 0));
        assertEquals(Math.round(255 * Math.sqrt(0.6)), result.faintest().getRaster().getSample(16, 16, 0));
        assertTrue(result.transparencyCorrected());
    }

    /** Missing ensemble evidence still permits sky-matched images, with no claim of a transparency correction. */
    @Test public void unknownZeroPointsLeaveTheMeasuredFluxRatioAlone() {
        var result = PhotometryReportSectionWriter.matchedCutouts(crop(1000, 1), crop(2400, 0.5),
                Double.NaN, Double.NaN);

        assertFalse(result.transparencyCorrected());
        assertEquals(0, result.faintest().getRaster().getSample(0, 0, 0));
        assertEquals(Math.round(255 * Math.sqrt(0.5)), result.faintest().getRaster().getSample(16, 16, 0));
    }

    /** Makes a constant sky plus a compact star; multiples of four avoid transmission-rounding noise. */
    private static short[][] crop(int sky, double fluxScale) {
        short[][] pixels = new short[32][32];
        for (int y = 0; y < pixels.length; y++) for (int x = 0; x < pixels[y].length; x++) {
            double radiusSquared = (x - 16) * (x - 16) + (y - 16) * (y - 16);
            int signal = 4 * (int) Math.round(600 * Math.exp(-radiusSquared / 3.0));
            pixels[y][x] = PixelEncoding.fromShiftedPositiveInt(sky + (int) Math.round(signal * fluxScale));
        }
        return pixels;
    }

    /** Copies borrowed test pixels to detect an accidental change to the scientific buffers. */
    private static short[][] copy(short[][] pixels) {
        return Arrays.stream(pixels).map(short[]::clone).toArray(short[][]::new);
    }

    /** Reads display grey levels, avoiding a second color-space conversion in the assertion. */
    private static int[] samples(BufferedImage image) {
        return image.getRaster().getSamples(0, 0, image.getWidth(), image.getHeight(), 0, (int[]) null);
    }
}
