package eu.startales.spacepixels.util;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FitsPixelConverterTest {

    @Test
    public void createFitsFromDataPreservesCustomHeaderAndRebuildsMonoStructure() throws Exception {
        BasicHDU<?> colorHdu = Fits.makeHDU(new short[3][2][2]);
        Header originalHeader = colorHdu.getHeader();
        originalHeader.addValue("DATE-OBS", "2026-04-08T00:00:00", null);
        originalHeader.addValue("EXPTIME", 30.0d, null);

        Fits convertedFits = FitsPixelConverter.createFitsFromData(new short[2][2], originalHeader);
        Header convertedHeader = convertedFits.getHDU(0).getHeader();

        assertEquals(2, convertedHeader.getIntValue("NAXIS"));
        assertFalse(convertedHeader.containsKey("NAXIS3"));
        assertEquals("2026-04-08T00:00:00", convertedHeader.getStringValue("DATE-OBS"));
        assertEquals(30.0d, convertedHeader.getDoubleValue("EXPTIME"), 1.0e-9);
        assertEquals(32768.0d, convertedHeader.getDoubleValue("BZERO"), 1.0e-9);
        assertEquals(1.0d, convertedHeader.getDoubleValue("BSCALE"), 1.0e-9);
    }

    @Test
    public void standardizeTo16BitMonoScalesUnitFloatDataIntoUnsignedShortStorage() throws Exception {
        short[][] converted = FitsPixelConverter.standardizeTo16BitMono(new float[][]{
                {0.0f, 0.5f, 1.0f, 2.0f}
        });

        assertEquals(Short.MIN_VALUE, converted[0][0]);
        assertEquals(0, converted[0][1]);
        assertEquals(Short.MAX_VALUE, converted[0][2]);
        assertEquals(Short.MAX_VALUE, converted[0][3]);
    }

    @Test
    public void standardizeTo16BitColorClampsIntDataAndExtractsLuminance() throws Exception {
        short[][][] converted = FitsPixelConverter.standardizeTo16BitColor(new int[][][]{
                {{0, 70000}},
                {{32768, -10}},
                {{65535, 16384}}
        });

        assertEquals(Short.MIN_VALUE, converted[0][0][0]);
        assertEquals(Short.MAX_VALUE, converted[0][0][1]);
        assertEquals(0, converted[1][0][0]);
        assertEquals(Short.MIN_VALUE, converted[1][0][1]);
        assertEquals(Short.MAX_VALUE, converted[2][0][0]);

        short[][] luminance = FitsPixelConverter.extractLuminance(converted);
        assertEquals(0, luminance[0][0]);
        assertTrue(luminance[0][1] > Short.MIN_VALUE);
    }

    @Test
    public void convertColorKernelToMonoAveragesChannels() throws Exception {
        short[][] mono = FitsPixelConverter.convertColorKernelToMono(new short[][][]{
                {{-32768, 32767}},
                {{0, 32767}},
                {{32767, 32767}}
        });

        assertEquals(0, mono[0][0]);
        assertEquals(32767, mono[0][1]);
    }
}
