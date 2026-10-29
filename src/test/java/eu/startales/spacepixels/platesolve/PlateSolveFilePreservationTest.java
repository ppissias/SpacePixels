package eu.startales.spacepixels.platesolve;

import eu.startales.spacepixels.config.AppConfig;
import nom.tam.fits.*;
import nom.tam.image.compression.hdu.CompressedImageHDU;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import static org.junit.Assert.*;

/** A WCS header edit must retain every HDU, every data byte and the original compression representation. */
public class PlateSolveFilePreservationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** Full-stream archives cannot be patched by physical offsets and must be refused without touching the source. */
    @Test public void wholeFileGzipIsRefusedWithoutReplacingTheArchive() throws Exception {
        Path plain = temporary.getRoot().toPath().resolve("plain.fit"), archive = plain.resolveSibling("plain.fit.gz");
        write(plain, false, true);
        try (var output = new java.util.zip.GZIPOutputStream(Files.newOutputStream(archive))) { Files.copy(plain, output); }
        byte[] original = Files.readAllBytes(archive);
        var failure = assertThrows(java.io.IOException.class,
                () -> new PlateSolveService(new AppConfig()).updateFitsHeaderWithWCS(archive.toString(), solution()));
        assertTrue(failure.getMessage().contains("decompress"));
        assertArrayEquals(original, Files.readAllBytes(archive));
    }

    /** Ordinary table extensions preceding an image are metadata, rather than the target image header. */
    @Test public void skipsAnOrdinaryTableBeforeTheImage() throws Exception {
        Path path = temporary.getRoot().toPath().resolve("table-first.fit");
        try (Fits fits = new Fits()) {
            fits.addHDU(Fits.makeHDU(new BinaryTable(new Object[]{new int[]{1, 2}})));
            fits.addHDU(Fits.makeHDU(new short[][]{{1, 2}, {3, 4}}));
            fits.write(path.toFile());
        }
        new PlateSolveService(new AppConfig()).updateFitsHeaderWithWCS(path.toString(), solution());
        try (Fits fits = new Fits(path.toFile())) {
            assertTrue(fits.getHDU(1) instanceof BinaryTableHDU);
            assertFalse(fits.getHDU(1).getHeader().containsKey("CRVAL1"));
            assertEquals(123.25, fits.getHDU(2).getHeader().getDoubleValue("CRVAL1"), 0);
        }
    }

    /** Stored checksums must still verify after the header changes; stale decompressed-header checksums are dropped. */
    @Test public void checksumStillVerifiesForOrdinaryAndCompressedImages() throws Exception {
        for (boolean compressed : new boolean[]{false, true}) {
            Path path = temporary.getRoot().toPath().resolve(compressed ? "checksum.fit.fz" : "checksum.fit");
            ImageHDU image = (ImageHDU) Fits.makeHDU(new short[][]{{1, 2, 3}, {4, 5, 6}});
            nom.tam.fits.utilities.FitsCheckSum.setChecksum(image);
            BasicHDU<?> stored = image;
            if (compressed) {
                var packed = CompressedImageHDU.fromImageHDU(image);
                packed.setCompressAlgorithm(nom.tam.fits.header.Compression.ZCMPTYPE_RICE_1); packed.compress();
                nom.tam.fits.utilities.FitsCheckSum.setChecksum(packed); stored = packed;
            }
            try (Fits fits = new Fits()) { fits.addHDU(stored); fits.write(path.toFile()); }
            new PlateSolveService(new AppConfig()).updateFitsHeaderWithWCS(path.toString(), solution());
            try (Fits fits = new Fits(path.toFile())) {
                BasicHDU<?> written = fits.getHDU(compressed ? 1 : 0);
                assertTrue("Stored HDU checksum is valid", written.verifyIntegrity());
                if (compressed) assertFalse(written.getHeader().containsKey("ZHECKSUM"));
            }
        }
    }

    /** Extensions after the selected image are retained, even when its new header needs additional FITS blocks. */
    @Test public void retainsEveryExtensionAndDataByteWhenTheImageHeaderGrows() throws Exception {
        for (boolean compressed : new boolean[]{false, true}) {
            Path path = temporary.getRoot().toPath().resolve(compressed ? "complete.fit.fz" : "complete.fit");
            write(path, compressed, true);
            byte[] original = Files.readAllBytes(path);
            BasicHDU<?>[] before;
            long oldDataStart;
            try (Fits fits = new Fits(path.toFile())) {
                before = fits.read();
                oldDataStart = before[1].getData().getFileOffset();
            }
            new PlateSolveService(new AppConfig()).updateFitsHeaderWithWCS(path.toString(), solution());
            byte[] updated = Files.readAllBytes(path);
            try (Fits fits = new Fits(path.toFile())) {
                BasicHDU<?>[] after = fits.read();
                assertEquals("No image or table extension is discarded", before.length, after.length);
                assertTrue("The selected header actually grew", after[1].getData().getFileOffset() > oldDataStart);
                for (int index = 0; index < before.length; index++) {
                    assertEquals(before[index].getClass(), after[index].getClass());
                    assertArrayEquals("Every stored data block is unchanged", dataBytes(original, before[index]), dataBytes(updated, after[index]));
                    if (index != 1) assertArrayEquals("Other HDU headers are byte-identical",
                            headerBytes(original, before[index]), headerBytes(updated, after[index]));
                }
                Header image = image(after[1]).getHeader();
                assertEquals(123.25, image.getDoubleValue("CRVAL1"), 0);
                assertEquals("test-target", image.getStringValue("OBJECT"));
            }
        }
    }

    /** Updating a detached decompressed header is insufficient: WCS must survive reopening the compressed container. */
    @Test public void compressedImageRetainsNewWcsAndUnchangedPixels() throws Exception {
        Path path = temporary.getRoot().toPath().resolve("compressed.fit.fz");
        write(path, true, false);
        Map<String, String> metadata = new PlateSolveService(new AppConfig()).updateFitsHeaderWithWCS(path.toString(), solution());
        assertEquals("123.25", metadata.get("CRVAL1"));
        try (Fits fits = new Fits(path.toFile())) {
            assertTrue(fits.getHDU(1) instanceof CompressedImageHDU);
            BasicHDU<?> image = image(fits.getHDU(1));
            assertEquals("WCS is persisted in the compressed image", 123.25, image.getHeader().getDoubleValue("CRVAL1"), 0);
            assertEquals("astap", image.getHeader().getStringValue("SOURCE"));
            short[][] data = (short[][]) image.getKernel();
            assertArrayEquals(new short[]{1, -2, 3}, data[0]);
            assertArrayEquals(new short[]{4, 5, -6}, data[1]);
        }
    }

    /** A truncated later extension cannot be silently removed and published as a successful update. */
    @Test public void truncatedExtensionLeavesTheOriginalUntouched() throws Exception {
        Path path = temporary.getRoot().toPath().resolve("truncated.fit");
        write(path, false, true);
        try (var file = new java.io.RandomAccessFile(path.toFile(), "rw")) { file.setLength(file.length() - 2880); }
        byte[] original = Files.readAllBytes(path);
        assertThrows(Exception.class, () -> new PlateSolveService(new AppConfig()).updateFitsHeaderWithWCS(path.toString(), solution()));
        assertArrayEquals(original, Files.readAllBytes(path));
        try (var files = Files.list(path.getParent())) { assertEquals(1, files.count()); }
    }

    /** Creates empty-primary, image, table and additional-image fixtures with distinctive non-WCS content. */
    private static void write(Path path, boolean compressed, boolean extensions) throws Exception {
        ImageHDU image = (ImageHDU) Fits.makeHDU(new short[][]{{1, -2, 3}, {4, 5, -6}});
        image.getHeader().addValue("OBJECT", "test-target", null);
        BasicHDU<?> stored = image;
        if (compressed) {
            var packed = CompressedImageHDU.fromImageHDU(image);
            packed.setCompressAlgorithm(nom.tam.fits.header.Compression.ZCMPTYPE_RICE_1); packed.compress(); stored = packed;
        }
        // Put END near a block boundary so adding a WCS must shift all following stored bytes.
        while (stored.getHeader().getNumberOfCards() < 34) stored.getHeader().insertHistory("Retain observing metadata");
        try (Fits fits = new Fits()) {
            fits.addHDU(BasicHDU.getDummyHDU()); fits.addHDU(stored);
            if (extensions) {
                BinaryTable table = new BinaryTable(new Object[]{new int[]{11, 22}, new String[]{"one", "two"}});
                var hdu = Fits.makeHDU(table); hdu.getHeader().addValue("EXTNAME", "CATALOGUE", null); fits.addHDU(hdu);
                var other = Fits.makeHDU(new float[][]{{0.25f, 2.5f}, {-0.5f, 8.0f}});
                other.getHeader().addValue("EXTNAME", "UNCERTAINTY", null); fits.addHDU(other);
            }
            fits.write(path.toFile());
        }
    }

    /** Gives the same logical image view that a later reader must recover from either storage representation. */
    private static BasicHDU<?> image(BasicHDU<?> hdu) throws Exception {
        return hdu instanceof CompressedImageHDU packed ? packed.asImageHDU() : hdu;
    }

    /** Extracts a stored, padded data segment without decoding or reserializing any pixels or table fields. */
    private static byte[] dataBytes(byte[] file, BasicHDU<?> hdu) {
        if (hdu.getData().getSize() == 0) return new byte[0];
        int start = Math.toIntExact(hdu.getData().getFileOffset());
        return Arrays.copyOfRange(file, start, Math.toIntExact(start + hdu.getData().getSize()));
    }

    /** Extracts the original padded header for extensions outside the selected image. */
    private static byte[] headerBytes(byte[] file, BasicHDU<?> hdu) {
        int start = Math.toIntExact(hdu.getHeader().getFileOffset());
        return Arrays.copyOfRange(file, start, Math.toIntExact(start + hdu.getHeader().getSize()));
    }

    /** Supplies a complete ordinary tangent-plane solution without network or external solver dependencies. */
    private static Map<String, String> solution() {
        return Map.of("source", "astap", "CTYPE1", "RA---TAN", "CTYPE2", "DEC--TAN", "CRVAL1", "123.25", "CRVAL2", "-12.5",
                "CRPIX1", "2", "CRPIX2", "1.5", "CD1_1", "-0.001", "CD2_2", "0.001");
    }
}
