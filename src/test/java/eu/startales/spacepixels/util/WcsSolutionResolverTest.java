package eu.startales.spacepixels.util;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WcsSolutionResolverTest {

    @Test
    public void resolvesStandardWcsFromFitsHeader() {
        FitsFileInformation fileInfo = new FitsFileInformation("sample.fit", "sample.fit", true, 1320, 989);
        fileInfo.getFitsHeader().putAll(createStandardWcsHeader(140.89806, -7.0669513));

        WcsSolutionResolver.ResolvedWcsSolution resolved = WcsSolutionResolver.resolve(fileInfo, new FitsFileInformation[]{fileInfo});

        assertNotNull(resolved);
        assertEquals("FITS header", resolved.getSourceType());
        assertEquals("sample.fit", resolved.getSourceFileName());
        assertFalse(resolved.isSharedAcrossAlignedSet());

        WcsCoordinateTransformer.SkyCoordinate center = resolved.getTransformer().pixelToSky(659.5, 494.0);
        assertEquals(140.89806, center.getRaDegrees(), 1.0e-9);
        assertEquals(-7.0669513, center.getDecDegrees(), 1.0e-9);
    }

    @Test
    public void fallsBackToCompatibleAlignedFileHeader() {
        FitsFileInformation preferred = new FitsFileInformation("preferred.fit", "preferred.fit", true, 1320, 989);
        FitsFileInformation aligned = new FitsFileInformation("aligned.fit", "aligned.fit", true, 1320, 989);
        aligned.getFitsHeader().putAll(createStandardWcsHeader(10.0, 20.0));

        WcsSolutionResolver.ResolvedWcsSolution resolved = WcsSolutionResolver.resolve(preferred, new FitsFileInformation[]{preferred, aligned});

        assertNotNull(resolved);
        assertTrue(resolved.isSharedAcrossAlignedSet());
        assertEquals("aligned.fit", resolved.getSourceFileName());

        WcsCoordinateTransformer.SkyCoordinate center = resolved.getTransformer().pixelToSky(659.5, 494.0);
        assertEquals(10.0, center.getRaDegrees(), 1.0e-9);
        assertEquals(20.0, center.getDecDegrees(), 1.0e-9);
    }

    @Test
    public void ignoresAlignedFilesWithIncompatibleDimensions() {
        FitsFileInformation preferred = new FitsFileInformation("preferred.fit", "preferred.fit", true, 1320, 989);
        FitsFileInformation incompatible = new FitsFileInformation("aligned.fit", "aligned.fit", true, 1400, 1000);
        incompatible.getFitsHeader().putAll(createStandardWcsHeader(10.0, 20.0));

        WcsSolutionResolver.ResolvedWcsSolution resolved = WcsSolutionResolver.resolve(preferred, new FitsFileInformation[]{preferred, incompatible});

        assertNull(resolved);
    }

    private static Map<String, String> createStandardWcsHeader(double raDegrees, double decDegrees) {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("CTYPE1", "RA---TAN");
        header.put("CTYPE2", "DEC--TAN");
        header.put("CUNIT1", "deg");
        header.put("CUNIT2", "deg");
        header.put("CRPIX1", "660.5");
        header.put("CRPIX2", "495.0");
        header.put("CRVAL1", Double.toString(raDegrees));
        header.put("CRVAL2", Double.toString(decDegrees));
        header.put("CD1_1", "0.000467862222");
        header.put("CD1_2", "0.0");
        header.put("CD2_1", "0.0");
        header.put("CD2_2", "-0.000467862222");
        return header;
    }
}
