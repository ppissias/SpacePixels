package eu.startales.spacepixels.util;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
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
        assertThrows(IOException.class, () -> FitsSequenceValidator.validateExtensions(files));
    }

    @Test
    public void imageProcessorCloseTerminatesItsBoundedImportPool() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeFits(directory, "A.fit", new short[2][3], null);
        ImageProcessing processor = ImageProcessing.getInstance(directory);
        Field field = ImageProcessing.class.getDeclaredField("executor");
        field.setAccessible(true);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) field.get(processor);
        try {
            processor.getFitsfileInformationHeadless();
            assertTrue(executor.getMaximumPoolSize() <= 4);
        } finally {
            processor.close();
        }
        processor.close();
        assertTrue(executor.isShutdown());
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static void assertRejectedEverywhere(File directory, String message) throws Exception {
        assertRejectedGuiAndPreparation(directory, message);
        try (ImageProcessing processor = ImageProcessing.getInstance(directory)) {
            IOException exception = assertThrows(IOException.class, processor::getFitsfileInformationHeadless);
            assertTrue(exception.getMessage(), exception.getMessage().contains(message));
        }
    }

    private static void assertRejectedGuiAndPreparation(File directory, String message) throws Exception {
        IOException preparationException = assertThrows(IOException.class,
                () -> DetectionInputPreparation.prepareInputDirectory(directory, true, null));
        assertTrue(preparationException.getMessage(), preparationException.getMessage().contains(message));
        try (ImageProcessing processor = ImageProcessing.getInstance(directory)) {
            IOException importException = assertThrows(IOException.class, processor::getFitsfileInformation);
            assertTrue(importException.getMessage(), importException.getMessage().contains(message));
        }
    }

    private static void assertOrderForBothImports(File directory, String... expectedNames) throws Exception {
        try (ImageProcessing processor = ImageProcessing.getInstance(directory)) {
            assertOrder(processor.getFitsfileInformation(), expectedNames);
            assertOrder(processor.getFitsfileInformationHeadless(), expectedNames);
        }
    }

    private static void assertOrder(FitsFileInformation[] files, String[] expectedNames) {
        assertEquals(expectedNames.length, files.length);
        for (int position = 0; position < files.length; position++) {
            assertEquals(expectedNames[position], files[position].getFileName());
        }
    }

    private static void writeFits(File directory, String name, Object kernel, String timestamp) throws Exception {
        try (Fits fits = new Fits()) {
            BasicHDU<?> hdu = Fits.makeHDU(kernel);
            if (kernel instanceof short[][] || kernel instanceof short[][][]) {
                hdu.getHeader().addValue("BZERO", 32768.0, null);
            }
            if (timestamp != null) {
                hdu.getHeader().addValue("DATE-OBS", timestamp, null);
            }
            fits.addHDU(hdu);
            fits.write(new File(directory, name));
        }
    }
}
