package eu.startales.spacepixels.preparation;

import nom.tam.fits.Fits;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Folder questions distinguish an accidental stack from one exposure group, without reading pixels. */
public class PreparationCohortTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** Raw exposures beside one FITS stack offer to keep the raw majority. */
    @Test public void rawMajorityOffersToExcludeAFitsStack() throws Exception {
        Path folder = temporary.newFolder("lights").toPath();
        for (int i = 0; i < 4; i++) Files.createFile(folder.resolve("light_" + i + ".cr2"));
        writeFits(folder.resolve("Autosave.fits"), 3, 3364.8);
        var question = PreparationCohort.question(PreparationInputs.Role.LIGHTS, FramePreparationService.inspect(Map.of(
                PreparationInputs.Role.LIGHTS, folder)).files().get(PreparationInputs.Role.LIGHTS));
        assertNotNull(question);
        assertTrue(question.excludeMinority());
        assertTrue(question.prompt().contains("camera raw"));
        assertEquals(List.of(folder.resolve("Autosave.fits").toAbsolutePath().normalize()), question.omittedByKeeping(0));
        assertTrue(question.details().contains("Autosave.fits"));
        assertTrue(question.details().contains("3364.8 s"));
    }

    /** Equal families are not resolved by picking the first one. */
    @Test public void equalFamiliesAskWhichGroupToKeep() throws Exception {
        Path folder = temporary.newFolder("flats").toPath();
        Files.createFile(folder.resolve("a.cr2"));
        Files.createFile(folder.resolve("b.cr2"));
        writeFits(folder.resolve("c.fit"), 2, 0.125);
        writeFits(folder.resolve("d.fit"), 2, 0.126);
        var inputs = FramePreparationService.inspect(Map.of(PreparationInputs.Role.LIGHTS, folder, PreparationInputs.Role.DARKS, darks()));
        var question = PreparationCohort.questions(inputs).get(0);
        assertFalse(question.excludeMinority());
        assertEquals(2, question.groups().size());
    }

    /** Tenths of a second stay together, and a stacked duration does not. */
    @Test public void exposureGroupsUseTheImportTolerance() throws Exception {
        Path similar = temporary.newFolder("similar").toPath();
        writeFits(similar.resolve("a.fit"), 2, 30.0);
        writeFits(similar.resolve("b.fit"), 2, 30.5);
        writeFits(similar.resolve("c.fit"), 2, 30.1);
        assertNull(PreparationCohort.question(PreparationInputs.Role.DARKS, List.of(
                similar.resolve("a.fit"), similar.resolve("b.fit"), similar.resolve("c.fit"))));
        Path mixed = temporary.newFolder("mixed").toPath();
        writeFits(mixed.resolve("sub.fit"), 2, 60.0);
        writeFits(mixed.resolve("another.fit"), 2, 60.1);
        writeFits(mixed.resolve("stack.fit"), 2, 3364.8);
        var question = PreparationCohort.question(PreparationInputs.Role.FLATS, List.copyOf(Files.list(mixed).toList()));
        assertNotNull(question);
        assertTrue(question.excludeMinority());
        assertEquals("stack.fit", question.omittedByKeeping(0).get(0).getFileName().toString());
    }

    /** One colour FITS among single-plane FITS is the minority, for every role the caller asks about. */
    @Test public void colourPlaneIsSeparateFromSinglePlaneFits() throws Exception {
        Path folder = temporary.newFolder("darks").toPath();
        writeFits(folder.resolve("dark1.fit"), 2, 30.0);
        writeFits(folder.resolve("dark2.fit"), 2, 30.1);
        writeFits(folder.resolve("colour.fit"), 3, 30.0);
        var question = PreparationCohort.question(PreparationInputs.Role.DARK_FLATS, List.copyOf(Files.list(folder).toList()));
        assertNotNull(question);
        assertTrue(question.excludeMinority());
        assertEquals("colour.fit", question.omittedByKeeping(0).get(0).getFileName().toString());
        assertTrue(question.details().contains("3 planes"));
    }

    /** Confirmed omissions are removed before preparation and still have to leave enough lights. */
    @Test public void confirmedOmissionIsRemovedFromTheInventory() throws Exception {
        Path folder = temporary.newFolder("keep").toPath();
        for (int i = 0; i < 3; i++) Files.createFile(folder.resolve("light_" + i + ".cr2"));
        Path stack = folder.resolve("Autosave.fits");
        writeFits(stack, 3, 3364.8);
        var full = FramePreparationService.inspect(Map.of(PreparationInputs.Role.LIGHTS, folder));
        var kept = full.excluding(List.of(stack));
        assertEquals(3, kept.files().get(PreparationInputs.Role.LIGHTS).size());
        var prepared = FramePreparationService.inspect(Map.of(PreparationInputs.Role.LIGHTS, folder), java.util.Set.of(stack));
        assertEquals(kept.files().get(PreparationInputs.Role.LIGHTS), prepared.files().get(PreparationInputs.Role.LIGHTS));
        assertThrows(java.io.IOException.class, () -> full.excluding(full.files().get(PreparationInputs.Role.LIGHTS)));
    }

    /** A tile-compressed colour image uses ZNAXIS, not the binary table's NAXIS of 2. */
    @Test public void compressedColourHeaderIsThreePlanes() throws Exception {
        Path folder = temporary.newFolder("compressed").toPath();
        writeFits(folder.resolve("mono.fit"), 2, 30.0);
        writeHeader(folder.resolve("colour.fit"),
                "SIMPLE  =                    T",
                "BITPIX  =                    8",
                "NAXIS   =                    2",
                "NAXIS1  =                    8",
                "NAXIS2  =                    1",
                "PCOUNT  =                    0",
                "GCOUNT  =                    1",
                "EXTEND  =                    T",
                "ZIMAGE  =                    T",
                "ZNAXIS  =                    3",
                "ZNAXIS1 =                    4",
                "ZNAXIS2 =                    4",
                "ZNAXIS3 =                    3",
                "EXPTIME =                 30.0");
        var question = PreparationCohort.question(PreparationInputs.Role.LIGHTS, List.copyOf(Files.list(folder).toList()));
        assertNotNull(question);
        assertTrue(question.groups().stream().anyMatch(group -> group.description().contains("3 planes")));
        assertTrue(PreparationCohort.fitsMissingRowOrder(List.of(folder.resolve("mono.fit"))).contains(folder.resolve("mono.fit").toAbsolutePath().normalize()));
    }

    /** A selected master file is not partitioned, even when its name looks like a product. */
    @Test public void masterFileIsNotQuestioned() throws Exception {
        Path lights = temporary.newFolder("only-lights").toPath();
        for (int i = 0; i < 3; i++) Files.createFile(lights.resolve(i + ".cr2"));
        Path master = temporary.newFile("master_dark.fit").toPath();
        writeFits(master, 2, 30.0);
        var inputs = FramePreparationService.inspect(Map.of(PreparationInputs.Role.LIGHTS, lights, PreparationInputs.Role.DARKS, master));
        assertTrue(PreparationCohort.questions(inputs).isEmpty());
    }

    private Path darks() throws Exception {
        Path folder = temporary.newFolder().toPath();
        Files.createFile(folder.resolve("d1.cr2"));
        Files.createFile(folder.resolve("d2.cr2"));
        return folder;
    }

    /** Writes header cards only, including a compressed-image header that has no pixel payload to load. */
    private static void writeHeader(Path path, String... cards) throws Exception {
        StringBuilder text = new StringBuilder();
        for (String card : cards) text.append(String.format("%-80s", card));
        text.append(String.format("%-80s", "END"));
        while (text.length() % 2880 != 0) text.append(' ');
        Files.writeString(path, text, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** Writes a tiny FITS image whose header carries the plane count and exposure the classifier reads. */
    private static void writeFits(Path path, int planes, double exposure) throws Exception {
        try (Fits fits = new Fits()) {
            var image = planes == 3 ? Fits.makeHDU(new short[][][]{{{1}}, {{2}}, {{3}}}) : Fits.makeHDU(new short[][]{{1, 2}});
            image.addValue("EXPTIME", exposure, "seconds");
            fits.addHDU(image);
            fits.write(path.toFile());
        }
    }
}
