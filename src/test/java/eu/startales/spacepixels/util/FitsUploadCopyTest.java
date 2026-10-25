package eu.startales.spacepixels.util;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The upload copy loses the site and the observer, and nothing else that a plate solver reads. */
public class FitsUploadCopyTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** A small 16-bit frame with a site, an observer, pointing hints and a checksum, as capture programs write them. */
    static File frameWithSite(File file) throws Exception {
        try (Fits fits = new Fits()) {
            BasicHDU<?> image = Fits.makeHDU(pixels(30, 20, 7));
            Header header = image.getHeader();
            header.addValue("SITELAT", "+37 51 54.000", "THE SITE LATITUDE");
            header.addValue("SITELONG", "+23 45 18.310", "THE SITE LONGITUDE");
            header.addValue("SITEELEV", 120.0, "THE SITE ELEVATION");
            header.addValue("OBSERVER", "A. Observer", "");
            header.addValue("OBJCTRA", "15 18 32", "THE RA OF THE IMAGE CENTER");
            header.addValue("OBJCTDEC", "+02 04 02", "THE DEC OF THE IMAGE CENTER");
            header.addValue("DATE-OBS", "2020-06-06T19:03:26.000", "GMT START OF EXPOSURE");
            fits.addHDU(image);
            fits.setChecksum();
            fits.write(file);
        }
        return file;
    }

    private static short[][] pixels(int width, int height, int seed) {
        short[][] pixels = new short[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y][x] = (short) ((x * 31 + y * 17 + seed) % 4000);
            }
        }
        return pixels;
    }

    private static Header header(File file, int hdu) throws Exception {
        try (Fits fits = new Fits(file)) {
            return fits.getHDU(hdu).getHeader();
        }
    }

    private static short[][] image(File file, int hdu) throws Exception {
        try (Fits fits = new Fits(file)) {
            return (short[][]) fits.getHDU(hdu).getKernel();
        }
    }

    @Test
    public void theCopyLosesTheSiteAndObserverAndKeepsPixelsAndPointing() throws Exception {
        File original = frameWithSite(folder.newFile("frame.fit"));
        byte[] before = Files.readAllBytes(original.toPath());

        File copy = FitsUploadCopy.create(original);
        try {
            assertTrue(copy.getName(), copy.getName().startsWith("spacepixels-solve-") && copy.getName().endsWith(".fits"));
            Header cleaned = header(copy, 0);
            for (String key : new String[]{"SITELAT", "SITELONG", "SITEELEV", "OBSERVER", "CHECKSUM"}) {
                assertFalse(key + " is gone", cleaned.containsKey(key));
            }
            assertEquals("15 18 32", cleaned.getStringValue("OBJCTRA"));
            assertEquals("+02 04 02", cleaned.getStringValue("OBJCTDEC"));
            assertEquals("2020-06-06T19:03:26.000", cleaned.getStringValue("DATE-OBS"));
            assertTrue("DATASUM still describes the unchanged data", cleaned.containsKey("DATASUM"));
            assertTrue(Arrays.deepEquals(image(original, 0), image(copy, 0)));
            assertEquals("the copy keeps every block", before.length, copy.length());
            assertArrayEquals("the original is not touched", before, Files.readAllBytes(original.toPath()));
            assertFalse(new String(Files.readAllBytes(copy.toPath()), java.nio.charset.StandardCharsets.US_ASCII).contains("51 54"));
        } finally {
            Files.deleteIfExists(copy.toPath());
        }
    }

    @Test
    public void everyHeaderOfAFileWithExtensionsIsCleaned() throws Exception {
        File original = folder.newFile("two-hdus.fits");
        try (Fits fits = new Fits()) {
            BasicHDU<?> primary = Fits.makeHDU(pixels(8, 6, 1));
            primary.getHeader().addValue("OBSGEO-B", 37.865, "latitude");
            BasicHDU<?> extension = Fits.makeHDU(pixels(12, 9, 2));
            extension.getHeader().addValue("OBSGEO-L", 23.755, "longitude");
            extension.getHeader().addValue("OBSERVAT", "Home observatory", "");
            extension.getHeader().addValue("OBJECT", "M5", "");
            fits.addHDU(primary);
            fits.addHDU(extension);
            fits.write(original);
        }

        File copy = FitsUploadCopy.create(original);
        try {
            assertFalse(header(copy, 0).containsKey("OBSGEO-B"));
            Header second = header(copy, 1);
            assertFalse(second.containsKey("OBSGEO-L"));
            assertFalse(second.containsKey("OBSERVAT"));
            assertEquals("M5", second.getStringValue("OBJECT"));
            assertTrue(Arrays.deepEquals(image(original, 1), image(copy, 1)));
            assertEquals(original.length(), copy.length());
        } finally {
            Files.deleteIfExists(copy.toPath());
        }
    }

    @Test
    public void aFileThatIsNotFitsIsCopiedAsItIs() throws Exception {
        File original = folder.newFile("notes.txt");
        Files.writeString(original.toPath(), "not a FITS file, SITELAT = 37");
        File copy = FitsUploadCopy.create(original);
        try {
            assertArrayEquals(Files.readAllBytes(original.toPath()), Files.readAllBytes(copy.toPath()));
        } finally {
            Files.deleteIfExists(copy.toPath());
        }
    }

    @Test
    public void aLastDataBlockWrittenWithoutPaddingIsCopiedAsFarAsItGoes() throws Exception {
        File padded = frameWithSite(folder.newFile("padded.fit"));
        byte[] bytes = Files.readAllBytes(padded.toPath());
        // 30 x 20 16-bit pixels fill one data block, written here without its padding.
        int dataStart = bytes.length - 2880;
        int unpaddedLength = dataStart + 30 * 20 * 2;
        File original = folder.newFile("unpadded.fit");
        Files.write(original.toPath(), Arrays.copyOf(bytes, unpaddedLength));

        File copy = FitsUploadCopy.create(original);
        try {
            assertEquals(unpaddedLength, copy.length());
            byte[] copied = Files.readAllBytes(copy.toPath());
            assertArrayEquals("the data is the same", Arrays.copyOfRange(bytes, dataStart, unpaddedLength),
                    Arrays.copyOfRange(copied, dataStart, unpaddedLength));
            assertNotEquals("the original header has the site", null, findCard(bytes, "SITELAT"));
            assertNull("the copy's does not", findCard(copied, "SITELAT"));
        } finally {
            Files.deleteIfExists(copy.toPath());
        }
    }

    private static String findCard(byte[] fits, String key) {
        for (int offset = 0; offset + 80 <= 2880; offset += 80) {
            String card = new String(fits, offset, 80, java.nio.charset.StandardCharsets.US_ASCII);
            if (card.startsWith(String.format("%-8s", key))) {
                return card;
            }
        }
        return null;
    }
}
