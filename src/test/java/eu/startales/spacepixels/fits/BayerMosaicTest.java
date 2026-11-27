package eu.startales.spacepixels.fits;

import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** The one reader of the Bayer cards: what is a raw colour mosaic, and the facts preparation and import show. */
public class BayerMosaicTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** A single plane naming a pattern is a mosaic; the cards are normalised, and absent ones stay absent. */
    @Test public void readsTheCardsOfASinglePlane() {
        BayerMosaic mosaic = BayerMosaic.of(Map.of("BAYERPAT", "grbg ", "CREATOR", "ZWO Seestar S30 Pro", "INSTRUME", "imx585"), 1);
        assertEquals("GRBG", mosaic.pattern());
        assertTrue(mosaic.ordinary());
        assertNull(mosaic.rowOrder());
        assertFalse(mosaic.hasRowOrder());
        assertNull(mosaic.offsetX());
        assertFalse(mosaic.hasOffsets());
        assertEquals(0, mosaic.effectiveOffsetX());
        assertEquals("ZWO Seestar S30 Pro", mosaic.creator());
        assertEquals("imx585", mosaic.instrument());
        BayerMosaic full = BayerMosaic.of(Map.of("BAYERPAT", "RGGB", "ROWORDER", "bottom-up", "XBAYROFF", "1", "YBAYROFF", "0."), 1);
        assertEquals("BOTTOM-UP", full.rowOrder());
        assertTrue(full.hasRowOrder());
        assertEquals(Integer.valueOf(1), full.offsetX());
        assertEquals(Integer.valueOf(0), full.offsetY());
        assertEquals("bottom-up", BayerMosaic.rowOrderText(full.rowOrder()));
        assertFalse(BayerMosaic.of(Map.of("BAYERPAT", "X-TRANS"), 1).ordinary());
    }

    /** Three planes are already colour, whatever their cards say; a blank or missing pattern is not a mosaic. */
    @Test public void onlyASinglePlaneWithAPatternIsAMosaic() {
        assertNull(BayerMosaic.of(Map.of("BAYERPAT", "RGGB"), 3));
        assertNull(BayerMosaic.of(Map.of("BAYERPAT", "  "), 1));
        assertNull(BayerMosaic.of(Map.of("ROWORDER", "TOP-DOWN"), 1));
        assertNull(BayerMosaic.of((Map<String, String>) null, 1));
    }

    /** Some capture programs write BAYERPAT = 'NONE' for a monochrome camera: that is no mosaic, in any case or spacing. */
    @Test public void aPatternOfNoneIsNoMosaic() throws Exception {
        for (String none : List.of("NONE", "none", " None ", "N O N E")) {
            assertNull(none, BayerMosaic.of(Map.of("BAYERPAT", none), 1));
            assertFalse(none, BayerMosaic.namesMosaic(none));
        }
        assertTrue(BayerMosaic.namesMosaic("rggb"));
        assertFalse(BayerMosaic.namesMosaic(null));
        assertFalse(BayerMosaic.namesMosaic(" "));
        Header image = new Header();
        image.addValue("NAXIS", 2, null);
        image.addValue("BAYERPAT", "NONE", null);
        Header primary = new Header();
        primary.addValue("BAYERPAT", "RGGB", null);
        assertNull("A NONE on the image is not replaced by the primary's card", BayerMosaic.of(image, primary));

        Path folder = temporary.newFolder("mono-none").toPath();
        for (int i = 1; i <= 2; i++) {
            try (Fits fits = new Fits()) {
                var hdu = Fits.makeHDU(new short[][]{{1, 2}, {3, 4}});
                hdu.getHeader().addValue("BAYERPAT", "NONE", null);
                fits.addHDU(hdu);
                fits.write(folder.resolve("frame_0" + i + ".fit").toFile());
            }
        }
        assertNull(BayerMosaic.read(folder.resolve("frame_01.fit").toFile()));
        assertNull(BayerMosaic.firstIn(folder.toFile()));
        var frame = new FitsFileInformation("a.fit", "a.fit", true, 2, 2, Map.of("BAYERPAT", "NONE"));
        assertNull(BayerMosaic.firstOf(new FitsFileInformation[]{frame}));
    }

    /** Files are interpreted alike when pattern, rows and offsets agree; absent offsets count as 0, camera names not at all. */
    @Test public void commonInterpretationIgnoresCameraNamesButNotRows() {
        BayerMosaic a = BayerMosaic.of(Map.of("BAYERPAT", "GRBG", "CREATOR", "one"), 1);
        BayerMosaic b = BayerMosaic.of(Map.of("BAYERPAT", "GRBG", "XBAYROFF", "0", "INSTRUME", "two"), 1);
        BayerMosaic rows = BayerMosaic.of(Map.of("BAYERPAT", "GRBG", "ROWORDER", "TOP-DOWN"), 1);
        assertSame(a, BayerMosaic.common(List.of(a, b)));
        assertNull(BayerMosaic.common(List.of(a, rows)));
        assertNull(BayerMosaic.common(List.of()));
    }

    /** Headers are read without pixels; a tile-compressed image counts its planes from ZNAXIS and its cards fall back to the primary. */
    @Test public void readsHeadersFromFilesAndFolders() throws Exception {
        Map<String, Object> cards = new HashMap<>();
        Path raw = SyntheticDatasetFactory.createRawColourFitsSequence(temporary.getRoot().toPath(), "raw", 3, cards);
        Path first = raw.resolve("frame_01.fit");
        BayerMosaic mosaic = BayerMosaic.read(first.toFile());
        assertEquals("GRBG", mosaic.pattern());
        assertEquals("ZWO Seestar S30 Pro", mosaic.creator());
        assertEquals("GRBG", BayerMosaic.firstIn(raw.toFile()).pattern());
        Path mono = SyntheticDatasetFactory.createMono16FitsSequence(temporary.getRoot().toPath(), "mono", 3);
        assertNull(BayerMosaic.firstIn(mono.toFile()));
        Files.writeString(mono.resolve("broken.fit"), "not a FITS file");
        assertNull("An unreadable file is left to the ordinary checks", BayerMosaic.firstIn(mono.toFile()));
        assertNull(BayerMosaic.firstIn(temporary.getRoot().toPath().resolve("missing").toFile()));

        Header compressed = new Header();
        compressed.addValue("ZIMAGE", true, null);
        compressed.addValue("NAXIS", 2, null);
        compressed.addValue("ZNAXIS", 2, null);
        Header primary = new Header();
        primary.addValue("NAXIS", 0, null);
        primary.addValue("BAYERPAT", "RGGB", null);
        primary.addValue("ROWORDER", "TOP-DOWN", null);
        assertEquals(1, BayerMosaic.planes(compressed));
        BayerMosaic fromPrimary = BayerMosaic.of(compressed, primary);
        assertEquals("RGGB", fromPrimary.pattern());
        assertEquals("TOP-DOWN", fromPrimary.rowOrder());
        compressed.addValue("ZNAXIS", 3, null);
        compressed.addValue("ZNAXIS3", 3, null);
        assertNull(BayerMosaic.of(compressed, primary));
    }

    /** Imported frames carry their header cards; the first mosaic among them is found without reading files. */
    @Test public void findsTheFirstMosaicAmongImportedFrames() throws Exception {
        Path raw = SyntheticDatasetFactory.createRawColourFitsSequence(temporary.getRoot().toPath(), "frames", 3, Map.of("ROWORDER", "TOP-DOWN"));
        try (Fits fits = new Fits(raw.resolve("frame_01.fit").toFile())) {
            var header = fits.getHDU(0).getHeader();
            Map<String, String> cards = new HashMap<>();
            var iterator = header.iterator();
            while (iterator.hasNext()) { var card = iterator.next(); cards.put(card.getKey(), card.getValue()); }
            var frame = new FitsFileInformation("a.fit", "a.fit", true, 96, 96, cards);
            var colour = new FitsFileInformation("b.fit", "b.fit", false, 96, 96, cards);
            assertNull(BayerMosaic.firstOf(new FitsFileInformation[]{colour}));
            BayerMosaic mosaic = BayerMosaic.firstOf(new FitsFileInformation[]{colour, frame});
            assertEquals("GRBG", mosaic.pattern());
            assertEquals("TOP-DOWN", mosaic.rowOrder());
            assertTrue(mosaic.rawColourExplanation().contains("Bayer GRBG mosaic in one image plane"));
        }
    }
}
