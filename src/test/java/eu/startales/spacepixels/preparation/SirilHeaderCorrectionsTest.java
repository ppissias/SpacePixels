package eu.startales.spacepixels.preparation;

import eu.startales.spacepixels.fits.FitsPixelConverter;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Protects the maintainer's metadata fixes and their fixed-width, pixel-preserving private-copy contract. */
public class SirilHeaderCorrectionsTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** Dotted times and quoted site coordinates are corrected without changing one pixel or HDU boundary. */
    @Test public void fixesPrivateHeaderAndInvalidatesChecksumsWithoutChangingPixels() throws Exception {
        Path original = temporary.newFile("original.fit").toPath();
        Header header = new Header();
        header.addValue("SITELAT", "-00 30 00", "Synthetic site");
        header.addValue("SITELONG", "12.5", "Synthetic site");
        header.addValue("DATE-OBS", "2020-02-29T23.59.58.125", "Legacy separators");
        header.addValue("EXPTIME", 30.5, "seconds");
        header.addValue("CHECKSUM", "0000000000000000", null);
        header.addValue("DATASUM", "0", null);
        try (Fits fits = FitsPixelConverter.createFitsFromData(new short[][]{{-32768, -123, 32767}}, header)) { fits.write(original.toFile()); }
        byte[] sourceBytes = Files.readAllBytes(original);
        Path copy = temporary.getRoot().toPath().resolve("private.fit");
        Files.copy(original, copy);
        var fixes = SirilHeaderCorrections.normalizePrivateCopy(copy);
        assertEquals(3, fixes.size());
        Header corrected = SirilHeaderCorrections.imageHeader(copy);
        assertEquals(-0.5, corrected.getDoubleValue("SITELAT"), 1e-12);
        assertEquals(12.5, corrected.getDoubleValue("SITELONG"), 1e-12);
        assertEquals("2020-02-29T23:59:58.125", corrected.getStringValue("DATE-OBS"));
        assertFalse(corrected.containsKey("CHECKSUM"));
        assertFalse(corrected.containsKey("DATASUM"));
        assertArrayEquals(sourceBytes, Files.readAllBytes(original));
        assertArrayEquals(Arrays.copyOfRange(sourceBytes, 2880, sourceBytes.length),
                Arrays.copyOfRange(Files.readAllBytes(copy), 2880, sourceBytes.length));
        assertTrue(SirilHeaderCorrections.normalizePrivateCopy(copy).isEmpty());
    }

    /** Invalid dates or coordinate ranges stop before Siril can silently discard their metadata. */
    @Test public void invalidLegacyMetadataFailsExplicitly() throws Exception {
        assertEquals("2020-01-01T12:34:56Z", SirilHeaderCorrections.normalizeDate("2020-01-01T12:34:56Z"));
        assertThrows(java.io.IOException.class, () -> SirilHeaderCorrections.normalizeDate("2020-02-30T23.59.00"));
        assertThrows(java.io.IOException.class, () -> SirilHeaderCorrections.siteNumber("12 60 00", "SITELAT"));
        assertThrows(java.io.IOException.class, () -> SirilHeaderCorrections.siteNumber("181", "SITELONG"));
        assertThrows(java.io.IOException.class, () -> SirilHeaderCorrections.siteNumber("NaN", "SITELAT"));
    }

    /** Nanosecond timestamps fit their fixed-width replacement card without losing precision or moving pixel data. */
    @Test public void preservesLongFractionalTimestampsAndPixelBytes() throws Exception {
        for (String fraction : new String[]{"12345678", "123456789"}) {
            Path copy = temporary.newFile("private-" + fraction.length() + ".fit").toPath();
            Header header = new Header();
            header.addValue("DATE-OBS", "2020-02-29T23.59.58." + fraction, null);
            try (Fits fits = FitsPixelConverter.createFitsFromData(new short[][]{{-32768, -123, 32767}}, header)) {
                fits.write(copy.toFile());
            }
            byte[] before = Files.readAllBytes(copy);
            assertEquals(1, SirilHeaderCorrections.normalizePrivateCopy(copy).size());
            byte[] after = Files.readAllBytes(copy);
            assertEquals(before.length, after.length);
            assertEquals("2020-02-29T23:59:58." + fraction, SirilHeaderCorrections.imageHeader(copy).getStringValue("DATE-OBS"));
            assertArrayEquals(Arrays.copyOfRange(before, 2880, before.length), Arrays.copyOfRange(after, 2880, after.length));
        }
    }

    /** Tile-compressed images keep their metadata in the image extension, behind an empty primary HDU. */
    @Test public void repairsCompressedExtensionWithoutChangingItsDataBlock() throws Exception {
        Path file = temporary.newFile("image.fit.fz").toPath();
        var image = (nom.tam.fits.ImageHDU) Fits.makeHDU(new short[][]{{1, 2, 3}, {4, 5, 6}});
        var compressed = nom.tam.image.compression.hdu.CompressedImageHDU.fromImageHDU(image);
        compressed.setCompressAlgorithm(nom.tam.fits.header.Compression.ZCMPTYPE_RICE_1);
        compressed.compress();
        compressed.getHeader().addValue("SITELAT", "+12:30:00", null);
        compressed.getHeader().addValue("DATE-OBS", "2021-02-03T04.05.06.75", null);
        try (Fits fits = new Fits()) { fits.addHDU(compressed); fits.write(file.toFile()); }
        long dataStart;
        try (Fits fits = new Fits(file.toFile())) {
            var extension = fits.getHDU(1).getHeader();
            dataStart = extension.getFileOffset() + extension.getSize();
        }
        byte[] before = Files.readAllBytes(file);
        assertEquals(2, SirilHeaderCorrections.normalizePrivateCopy(file).size());
        Header repaired = SirilHeaderCorrections.imageHeader(file);
        assertEquals(12.5, repaired.getDoubleValue("SITELAT"), 1e-12);
        assertEquals("2021-02-03T04:05:06.75", repaired.getStringValue("DATE-OBS"));
        assertArrayEquals(Arrays.copyOfRange(before, Math.toIntExact(dataStart), before.length),
                Arrays.copyOfRange(Files.readAllBytes(file), Math.toIntExact(dataStart), before.length));
    }
}
