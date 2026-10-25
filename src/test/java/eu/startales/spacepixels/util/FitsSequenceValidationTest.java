package eu.startales.spacepixels.util;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class FitsSequenceValidationTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void mixedTimestampsFailBothImportPathsAndPreparation() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "A.fit", new short[2][3], "2026-04-08T01:00:02");
        writeFits(directory, "B.fit", new short[2][3], null);
        writeFits(directory, "C.fit", new short[2][3], "2026-04-08T01:00:01");
        assertRejectedEverywhere(directory, "timed and untimed");
    }

    @Test
    public void unparseableTimestampCountsAsUntimed() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "A.fit", new short[2][3], "2026-04-08T01:00:02");
        writeFits(directory, "B.fit", new short[2][3], "not-a-date");
        assertRejectedEverywhere(directory, "timed and untimed");
    }

    @Test
    public void sameStemWithDifferentExtensionsFailsBeforePreparingAnyOutput() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "frame.fit", new int[2][3], null);
        writeFits(directory, "frame.fits", new int[2][3], null);
        assertRejectedEverywhere(directory, "same extension");
        assertEquals(2, directory.listFiles().length);
    }

    @Test
    public void mixedIntegerAndFloatingBitpixFailsBeforeNormalization() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "A.fit", new int[2][3], null);
        writeFits(directory, "B.fit", new float[2][3], null);
        assertRejectedGuiAndPreparation(directory, "same type");
    }

    @Test
    public void mixedMonoAndColorFailsBeforeNormalization() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "A.fit", new short[2][3], null);
        writeFits(directory, "B.fit", new short[3][2][3], null);
        assertRejectedGuiAndPreparation(directory, "same type");
    }

    @Test
    public void differentDimensionsFailBeforeNormalization() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "A.fit", new int[2][3], null);
        writeFits(directory, "B.fit", new int[3][3], null);
        assertRejectedGuiAndPreparation(directory, "same type");
    }

    /** Strict import keeps the API/CLI's readiness-first error when the sequence is also inconsistent. */
    @Test
    public void strictReadinessFailurePrecedesSequenceConsistencyFailure() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "A.fit", new int[2][3], null);
        writeFits(directory, "B.fit", new int[3][3], null);
        assertRejectedGuiAndPreparation(directory, "same type");
        try (FitsImportService importer = new FitsImportService(directory, null)) {
            nom.tam.fits.FitsException failure = assertThrows(nom.tam.fits.FitsException.class, importer::readFiles);
            assertTrue(failure.getMessage(), failure.getMessage().contains("A.fit has BITPIX=32"));
        }
        assertEquals("no conversion or derivative was written", 2, directory.listFiles().length);
    }

    @Test
    public void mixedSignedAndUnsigned16BitFailsEveryImportPath() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "A.fit", new short[2][3], null);
        writeFits(directory, "B.fit", new short[2][3], null);
        try (Fits signed = new Fits()) {
            signed.addHDU(Fits.makeHDU(new short[2][3]));
            signed.write(new File(directory, "C.fit"));
        }
        assertRejectedGuiAndPreparation(directory, "same type");
        try (FitsImportService importer = new FitsImportService(directory, null)) {
            IOException exception = assertThrows(IOException.class, importer::readFiles);
            assertTrue(exception.getMessage(), exception.getMessage().contains("C.fit has BZERO=0 and BSCALE=1"));
        }
    }

    @Test
    public void signed16BitIsRejectedWhenTheFolderMustAlreadyBeReady() throws Exception {
        File directory = temporaryFolder.newFolder();
        for (String name : new String[]{"A.fit", "B.fit"}) {
            try (Fits signed = new Fits()) {
                signed.addHDU(Fits.makeHDU(new short[2][3]));
                signed.write(new File(directory, name));
            }
        }
        try (FitsImportService importer = new FitsImportService(directory, null)) {
            IOException exception = assertThrows(IOException.class, importer::readFiles);
            assertTrue(exception.getMessage(), exception.getMessage().contains("Expected unsigned 16-bit FITS files"));
        }
    }

    @Test
    public void mixedExposureTimesFailEveryImportPathAndNameTheOddImages() throws Exception {
        File directory = temporaryFolder.newFolder();
        for (int i = 0; i < 4; i++) {
            writeFits(directory, "L" + i + ".fit", new short[2][3], null, 60.0);
        }
        writeFits(directory, "Long1.fit", new short[2][3], null, 120.0);
        writeFits(directory, "Long2.fit", new short[2][3], null, 120.0);
        assertRejectedEverywhere(directory, "4 images of 60 s, 2 images of 120 s (Long1.fit, Long2.fit)");
        try (FitsImportService importer = new FitsImportService(directory, null)) {
            MixedExposureException mixed = assertThrows(MixedExposureException.class, importer::readFiles);
            assertTrue(mixed.canImportLargestGroup());
            assertEquals(4, mixed.getLargestGroup().size());
            assertEquals("_exposure_60s", mixed.getLargestGroup().getFolderName());
            assertTrue(mixed.getMessage(), mixed.getMessage().contains("offers to copy the 4 images of 60 s into a subfolder"));
        }
    }

    @Test
    public void slightlyDifferentRecordedExposuresCountAsOne() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "A.fit", new short[2][3], null, 60.0);
        writeFits(directory, "B.fit", new short[2][3], null, 60.0999984741211);
        writeFits(directory, "C.fit", new short[2][3], null, 60.2000007629395);
        assertOrderForBothImports(directory, "A.fit", "B.fit", "C.fit");
        DetectionInputPreparation.prepareInputDirectory(directory, true, null);
    }

    @Test
    public void imagesWithoutAnExposureTimeImportWhenNoneHasOne() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "A.fit", new short[2][3], null);
        writeFits(directory, "B.fit", new short[2][3], null);
        assertOrderForBothImports(directory, "A.fit", "B.fit");
    }

    @Test
    public void imagesWithoutAnExposureTimeAreAGroupOfTheirOwn() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "A.fit", new short[2][3], null, 30.0);
        writeFits(directory, "B.fit", new short[2][3], null, 30.0);
        writeFits(directory, "C.fit", new short[2][3], null);
        assertRejectedEverywhere(directory, "2 images of 30 s, 1 image without an exposure time (C.fit)");
    }

    @Test
    public void exposureGroupsAreLargestFirstThenLongestAndToleranceIsTwoPercent() {
        File[] files = {new File("a"), new File("b"), new File("c"), new File("d"), new File("e"), new File("f"), new File("g")};
        long[] exposures = {120_000, 60_000, 121_000, 60_000, -1, 61_300, 1_000};
        List<ExposureGroup> groups = ExposureGroup.of(files, exposures);
        assertEquals(5, groups.size());
        assertEquals("2 images of 120–121 s", groups.get(0).describe());
        assertEquals("2 images of 60 s", groups.get(1).describe());
        assertEquals("1 image of 61.3 s", groups.get(2).describe());
        assertEquals("1 image of 1 s", groups.get(3).describe());
        assertEquals("1 image without an exposure time", groups.get(4).describe());
        assertEquals("_no_exposure_time", groups.get(4).getFolderName());

        // Below 5 s the tolerance is 0.1 s rather than 2 %.
        assertEquals(1, ExposureGroup.of(new File[]{new File("a"), new File("b")}, new long[]{1_000, 1_100}).size());
        assertEquals(2, ExposureGroup.of(new File[]{new File("a"), new File("b")}, new long[]{1_000, 1_101}).size());
        assertEquals("_exposure_0.5s", ExposureGroup.of(new File[]{new File("a")}, new long[]{500}).get(0).getFolderName());
    }

    @Test
    public void onlySingleImagesOfEachExposureCannotBeNarrowedDown() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "A.fit", new short[2][3], null, 30.0);
        writeFits(directory, "B.fit", new short[2][3], null, 60.0);
        try (FitsImportService importer = new FitsImportService(directory, null)) {
            MixedExposureException mixed = assertThrows(MixedExposureException.class, importer::readFiles);
            assertFalse(mixed.canImportLargestGroup());
            assertTrue(mixed.getMessage(), mixed.getMessage().endsWith("Import a sequence of images that share one exposure time."));
        }
    }

    @Test
    public void theLargestGroupIsCopiedIntoASubfolderThatImports() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "A.fit", new short[2][3], "2026-04-08T01:00:02", 60.0);
        writeFits(directory, "B.fit", new short[2][3], "2026-04-08T01:00:01", 60.0);
        writeFits(directory, "C.fit", new short[2][3], "2026-04-08T01:00:03", 120.0);
        MixedExposureException mixed;
        try (FitsImportService importer = new FitsImportService(directory, null)) {
            mixed = assertThrows(MixedExposureException.class, importer::readFiles);
        }
        File subfolder = mixed.getLargestGroup().copyIntoSubfolder(null);
        assertEquals(new File(directory, "_exposure_60s"), subfolder);
        String[] copied = subfolder.list();
        Arrays.sort(copied);
        assertArrayEquals(new String[]{"A.fit", "B.fit"}, copied);
        assertOrderForBothImports(subfolder, "B.fit", "A.fit");
        // The original folder still holds every image, and the subfolder does not count as one of them.
        assertRejectedEverywhere(directory, "same exposure time");

        // A second copy keeps the images already there (they may carry a plate solution) and adds missing ones.
        File a = new File(subfolder, "A.fit");
        assertTrue(a.setLastModified(1_000_000L));
        assertTrue(new File(subfolder, "B.fit").delete());
        mixed.getLargestGroup().copyIntoSubfolder(null);
        assertEquals(1_000_000L, a.lastModified());
        assertTrue(new File(subfolder, "B.fit").isFile());
    }

    @Test
    public void mixedExposureQuestionListsTheGroupsAndTheSubfolder() throws Exception {
        File directory = temporaryFolder.newFolder();
        for (int i = 0; i < 3; i++) {
            writeFits(directory, "S" + i + ".fit", new short[2][3], null, 60.0);
        }
        writeFits(directory, "L&1.fit", new short[2][3], null, 120.0);
        try (FitsImportService importer = new FitsImportService(directory, null)) {
            MixedExposureException mixed = assertThrows(MixedExposureException.class, importer::readFiles);
            String question = FitsImportService.mixedExposureQuestion(mixed);
            assertTrue(question, question.contains("These 4 images do not all have the same exposure time:"
                    + "<br>&bull; 3 images of 60 s<br>&bull; 1 image of 120 s (L&amp;1.fit)"));
            assertTrue(question, question.contains("import only the 3 images of 60 s? They are copied into the subfolder <b>_exposure_60s</b>"));
        }
    }

    @Test
    public void fullyTimedSequencesSortByTimeWithFilenameTieBreaker() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "A.fit", new short[2][3], "2026-04-08T01:00:02");
        writeFits(directory, "B.fit", new short[2][3], "2026-04-08T01:00:01");
        writeFits(directory, "C.fit", new short[2][3], "2026-04-08T01:00:01");
        assertOrderForBothImports(directory, "B.fit", "C.fit", "A.fit");
    }

    @Test
    public void untimedSequencesSortByFilenameAndExtensionsIgnoreCase() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "C.fit", new short[2][3], null);
        writeFits(directory, "A.FIT", new short[2][3], null);
        writeFits(directory, "B.fit", new short[2][3], null);
        assertOrderForBothImports(directory, "A.FIT", "B.fit", "C.fit");
    }

    @Test
    public void compressedFitsSuffixesAreDistinctToPreventStemCollisions() {
        File[] files = {new File("frame.fit.fz"), new File("frame.fits.fz")};
        assertThrows(IOException.class, () -> FitsInput.validateExtensions(files));
    }

    @Test
    public void closingTheImporterTerminatesItsBoundedPool() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "A.fit", new short[2][3], null);
        FitsImportService importer = new FitsImportService(directory, null);
        Field field = FitsImportService.class.getDeclaredField("executor");
        field.setAccessible(true);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) field.get(importer);
        try {
            importer.readFiles();
            assertTrue(executor.getMaximumPoolSize() <= 4);
        } finally {
            importer.close();
        }
        importer.close();
        assertTrue(executor.isShutdown());
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static void assertRejectedEverywhere(File directory, String message) throws Exception {
        assertRejectedGuiAndPreparation(directory, message);
        try (FitsImportService importer = new FitsImportService(directory, null)) {
            IOException exception = assertThrows(IOException.class, importer::readFiles);
            assertTrue(exception.getMessage(), exception.getMessage().contains(message));
        }
    }

    private static void assertRejectedGuiAndPreparation(File directory, String message) throws Exception {
        IOException preparationException = assertThrows(IOException.class,
                () -> DetectionInputPreparation.prepareInputDirectory(directory, true, null));
        assertTrue(preparationException.getMessage(), preparationException.getMessage().contains(message));
        try (FitsImportService importer = new FitsImportService(directory, new NoQuestions())) {
            IOException importException = assertThrows(IOException.class, importer::readFiles);
            assertTrue(importException.getMessage(), importException.getMessage().contains(message));
        }
    }

    private static void assertOrderForBothImports(File directory, String... expectedNames) throws Exception {
        try (FitsImportService importer = new FitsImportService(directory, new NoQuestions())) {
            assertOrder(importer.readFiles(), expectedNames);
        }
        try (FitsImportService importer = new FitsImportService(directory, null)) {
            assertOrder(importer.readFiles(), expectedNames);
        }
    }

    /** Sequence validation and ready-input reads must finish before any interactive preparation decision. */
    private static final class NoQuestions implements FitsImportInteraction {
        /** Fails if conversion is offered before an inconsistent sequence is rejected. */
        @Override public int confirm(String message, String title) { throw new AssertionError(title); }
        /** Fails if an exposure choice escapes the import service during metadata validation. */
        @Override public int choose(String message, String title, String[] options) { throw new AssertionError(title); }
        /** Fails if validation is replaced by a dialog rather than propagating its exception. */
        @Override public void error(String message, String title) { throw new AssertionError(title); }
        /** Fails if a read starts writing a prepared sequence. */
        @Override public Progress progress(String title) { throw new AssertionError(title); }
    }

    private static void assertOrder(FitsFileInformation[] files, String[] expectedNames) {
        assertEquals(expectedNames.length, files.length);
        for (int position = 0; position < files.length; position++) {
            assertEquals(expectedNames[position], files[position].getFileName());
        }
    }

    private static void writeFits(File directory, String name, Object kernel, String timestamp) throws Exception {
        writeFits(directory, name, kernel, timestamp, Double.NaN);
    }

    private static void writeFits(File directory, String name, Object kernel, String timestamp, double exposureSeconds) throws Exception {
        try (Fits fits = new Fits()) {
            BasicHDU<?> hdu = Fits.makeHDU(kernel);
            if (kernel instanceof short[][] || kernel instanceof short[][][]) {
                hdu.getHeader().addValue("BZERO", 32768.0, null);
            }
            if (timestamp != null) {
                hdu.getHeader().addValue("DATE-OBS", timestamp, null);
            }
            if (!Double.isNaN(exposureSeconds)) {
                hdu.getHeader().addValue("EXPTIME", exposureSeconds, null);
            }
            fits.addHDU(hdu);
            fits.write(new File(directory, name));
        }
    }
}
