package eu.startales.spacepixels.util;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class WcsCoordinateTransformerTest {

    @Test
    public void skyToPixelInvertsPixelToSky() {
        // Rotated and flipped, as plate solutions of real frames often are.
        WcsCoordinateTransformer transformer = WcsCoordinateTransformer.fromHeader(header(98.856, 7.321,
                -0.000427, 0.0000312, 0.0000309, 0.000427));
        assertNotNull(transformer);
        double[][] pixels = {{0, 0}, {4771.5, 3180}, {9543, 6360}, {120.25, 5999.75}, {-300, 7000}};
        for (double[] pixel : pixels) {
            WcsCoordinateTransformer.SkyCoordinate sky = transformer.pixelToSky(pixel[0], pixel[1]);
            double[] back = transformer.skyToPixel(sky.getRaDegrees(), sky.getDecDegrees());
            assertNotNull(back);
            assertEquals(pixel[0], back[0], 1e-6);
            assertEquals(pixel[1], back[1], 1e-6);
        }
    }

    @Test
    public void skyToPixelWorksAcrossRaZeroAndNearThePole() {
        WcsCoordinateTransformer nearZero = WcsCoordinateTransformer.fromHeader(header(0.05, 30, -0.0005, 0, 0, 0.0005));
        WcsCoordinateTransformer.SkyCoordinate west = nearZero.pixelToSky(2000, 1000);
        double[] back = nearZero.skyToPixel(west.getRaDegrees(), west.getDecDegrees());
        assertEquals(2000, back[0], 1e-6);
        assertEquals(1000, back[1], 1e-6);

        WcsCoordinateTransformer polar = WcsCoordinateTransformer.fromHeader(header(37.95, 89.26, -0.0005, 0, 0, 0.0005));
        WcsCoordinateTransformer.SkyCoordinate corner = polar.pixelToSky(3000, 2000);
        back = polar.skyToPixel(corner.getRaDegrees(), corner.getDecDegrees());
        assertEquals(3000, back[0], 1e-6);
        assertEquals(2000, back[1], 1e-6);
    }

    @Test
    public void skyToPixelIsNullOnTheFarSideOfTheSky() {
        WcsCoordinateTransformer transformer = WcsCoordinateTransformer.fromHeader(header(98.856, 7.321, -0.0004, 0, 0, 0.0004));
        assertNull(transformer.skyToPixel(98.856 + 180, -7.321));
    }

    private static Map<String, String> header(double ra, double dec, double cd11, double cd12, double cd21, double cd22) {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("CTYPE1", "RA---TAN");
        header.put("CTYPE2", "DEC--TAN");
        header.put("CRPIX1", "4772.5");
        header.put("CRPIX2", "3181.0");
        header.put("CRVAL1", Double.toString(ra));
        header.put("CRVAL2", Double.toString(dec));
        header.put("CD1_1", Double.toString(cd11));
        header.put("CD1_2", Double.toString(cd12));
        header.put("CD2_1", Double.toString(cd21));
        header.put("CD2_2", Double.toString(cd22));
        return header;
    }
}
