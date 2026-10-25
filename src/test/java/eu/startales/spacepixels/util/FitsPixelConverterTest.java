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
    public void addedOffsetLiftsEveryPixelExceptTheZeroFillAtTheRowEnds() throws Exception {
        Header signed = Fits.makeHDU(new short[1][1]).getHeader();
        short[][] converted = FitsPixelConverter.standardizeTo16BitMono(new short[][]{
                {0, 0, -5, 0, 10, 0},
                {-7, 3, 0, 0, 0, 2}
        }, signed, 7);

        int[][] expected = {{0, 0, 2, 7, 17, 0}, {0, 10, 7, 7, 7, 9}};
        for (int row = 0; row < expected.length; row++) {
            for (int column = 0; column < expected[row].length; column++) {
                assertEquals("row " + row + " column " + column, expected[row][column], converted[row][column] + 32768);
            }
        }
    }

    @Test
    public void withoutAnOffsetValuesBelowZeroStillBecomeZero() throws Exception {
        short[][] converted = FitsPixelConverter.standardizeTo16BitMono(new short[][]{{-5, 0, 10}},
                Fits.makeHDU(new short[1][1]).getHeader());
        assertEquals(0, converted[0][0] + 32768);
        assertEquals(0, converted[0][1] + 32768);
        assertEquals(10, converted[0][2] + 32768);
    }

    @Test
    public void onlyStorageThatCanGoBelowZeroIsScanned() throws Exception {
        assertTrue(FitsPixelConverter.canHoldNegativeValues(header(16, 0.0, 1.0)));
        assertFalse(FitsPixelConverter.canHoldNegativeValues(header(16, 32768.0, 1.0)));
        assertTrue(FitsPixelConverter.canHoldNegativeValues(header(16, 32768.0, 2.0)));
        assertFalse(FitsPixelConverter.canHoldNegativeValues(header(32, 2147483648.0, 1.0)));
        assertTrue(FitsPixelConverter.canHoldNegativeValues(header(-32, 0.0, 1.0)));
        assertFalse(FitsPixelConverter.canHoldNegativeValues(header(8, 0.0, 1.0)));
        assertTrue(FitsInput.isUnsigned16Bit(header(16, 32768.0, 1.0)));
        assertFalse(FitsInput.isUnsigned16Bit(header(16, 0.0, 1.0)));
    }

    @Test
    public void scanFindsTheLowestValueOverTheWholeSequence() throws Exception {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("spacepixels-negative-scan");
        java.io.File first = write(directory.resolve("a.fit").toFile(), new short[][]{{5, -3, 0}}, null);
        java.io.File second = write(directory.resolve("b.fit").toFile(), new short[][]{{-12, 4, 100}}, null);

        FitsPixelConverter.NegativeValueScan scan = FitsPixelConverter.scanNegativeValues(new java.io.File[]{first, second}, null);

        assertTrue(scan.hasNegativeValues());
        assertEquals(-12.0, scan.lowest(), 0.0);
        assertEquals(2, scan.negativePixels());
        assertEquals(6, scan.totalPixels());
        assertEquals(12, scan.offsetToKeepNegativeValues());
    }

    @Test
    public void scanSkipsUnsignedFramesAndIgnoresValuesThatRoundToZero() throws Exception {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("spacepixels-negative-scan");
        java.io.File unsigned = write(directory.resolve("u.fit").toFile(), new short[][]{{-32768, 0}}, 32768.0);
        java.io.File tiny = write(directory.resolve("f.fit").toFile(), new float[][]{{-0.4f, 20.0f}}, null);

        FitsPixelConverter.NegativeValueScan scan = FitsPixelConverter.scanNegativeValues(new java.io.File[]{unsigned, tiny}, null);

        assertFalse(scan.hasNegativeValues());
        assertEquals(2, scan.totalPixels());
        assertEquals(0, scan.offsetToKeepNegativeValues());
    }

    @Test
    public void createFitsFromDataAddsTheOffsetToOneAlreadyRecorded() throws Exception {
        Header original = Fits.makeHDU(new short[2][2]).getHeader();
        original.addValue(FitsPixelConverter.ADDED_OFFSET_KEY, 30, null);

        Header kept = FitsPixelConverter.createFitsFromData(new short[2][2], original).getHDU(0).getHeader();
        Header added = FitsPixelConverter.createFitsFromData(new short[2][2], original, 12).getHDU(0).getHeader();

        assertEquals(30, kept.getIntValue(FitsPixelConverter.ADDED_OFFSET_KEY));
        assertEquals(42, added.getIntValue(FitsPixelConverter.ADDED_OFFSET_KEY));
        assertEquals(1, countCards(added, FitsPixelConverter.ADDED_OFFSET_KEY));
    }

    private static Header header(int bitpix, double bzero, double bscale) {
        Header header = new Header();
        header.addValue("BITPIX", bitpix, null);
        header.addValue("BZERO", bzero, null);
        header.addValue("BSCALE", bscale, null);
        return header;
    }

    private static java.io.File write(java.io.File file, Object kernel, Double bzero) throws Exception {
        try (Fits fits = new Fits()) {
            BasicHDU<?> hdu = Fits.makeHDU(kernel);
            if (bzero != null) {
                hdu.getHeader().addValue("BZERO", bzero, null);
            }
            fits.addHDU(hdu);
            fits.write(file);
        }
        return file;
    }

    private static int countCards(Header header, String key) {
        int count = 0;
        nom.tam.util.Cursor<String, nom.tam.fits.HeaderCard> cursor = header.iterator();
        while (cursor.hasNext()) {
            if (key.equals(cursor.next().getKey())) {
                count++;
            }
        }
        return count;
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
