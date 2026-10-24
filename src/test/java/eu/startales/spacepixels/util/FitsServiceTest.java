package eu.startales.spacepixels.util;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.session.ProcessingSession;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNet;
import nom.tam.fits.*;
import nom.tam.image.compression.hdu.CompressedImageHDU;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class FitsServiceTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** Both import modes return fresh metadata; only the session retains its own protected array. */
    @Test
    public void readyImportsReturnIndependentArraysAndTheSessionOwnsItsArray() throws Exception {
        File input = SyntheticDatasetFactory.createSolvedMono16FitsSequence(folder.getRoot().toPath(), "frames", 3).toFile();
        Interaction interaction = new Interaction();
        FitsBatchService batch = new FitsBatchService(input, new FitsVisualizationRenderer());
        try (FitsImportService importer = new FitsImportService(input, batch, interaction);
             FitsImportService strict = new FitsImportService(input, null)) {
            FitsFileInformation[] interactive = importer.readFiles();
            FitsFileInformation[] headless = strict.readFiles();
            assertEquals(3, interactive.length);
            FitsFileInformation[] reread = importer.readFiles();
            assertNotSame(interactive, reread);
            assertNotSame(interactive[0], reread[0]);
            for (int index = 0; index < interactive.length; index++) {
                assertEquals(headless[index].getFitsHeader(), interactive[index].getFitsHeader());
                assertEquals(headless[index].getFilePath(), interactive[index].getFilePath());
                assertEquals(headless[index].getObservationTimestamp(), interactive[index].getObservationTimestamp());
            }
            assertEquals(0, interaction.questions.size());
            ProcessingSession session = new ProcessingSession(new PlateSolveService(new AppConfig()), headless, null);
            FitsFileInformation first = headless[0];
            headless[0] = null;
            assertSame(first, session.getFiles()[0]);
            FitsFileInformation[] exposed = session.getFiles();
            exposed[0] = null;
            assertSame(first, session.getFiles()[0]);
            first.getFitsHeader().put("OBJECT", "shared");
            assertEquals("shared", session.getFiles()[0].getFitsHeader().get("OBJECT"));
            assertNotEquals("the importer retains no metadata", "shared", strict.readFiles()[0].getFitsHeader().get("OBJECT"));
            assertTrue(session.hasPlateSolvedFrame());
            session.close();
            assertEquals(0, session.getFiles().length);
            assertFalse(session.hasPlateSolvedFrame());
        }
    }

    /**
     * The session releases its own metadata even when its solver cannot confirm its shutdown. The solver's client
     * factory supplies the failing client, as a first online solve would make one.
     */
    @Test
    public void sessionForgetsFramesWhenSolverCloseFails() throws Exception {
        File input = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "close-fails", 3).toFile();
        FitsFileInformation[] files;
        try (FitsImportService importer = new FitsImportService(input, null)) {
            files = importer.readFiles();
        }
        PlateSolveService solver = new PlateSolveService(new AppConfig(), key -> new FailingCloseClient());
        solver.astrometryNetClient("");
        ProcessingSession session = new ProcessingSession(solver, files, null);
        assertThrows(IllegalStateException.class, session::close);
        assertTrue(session.isClosed());
        assertEquals(0, session.getFiles().length);
        session.close();
    }

    /** Closing a session closes its solver's online client once, however often the session is closed. */
    @Test
    public void closingASessionClosesItsSolverOnce() throws Exception {
        File input = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "close-once", 3).toFile();
        FitsFileInformation[] files;
        try (FitsImportService importer = new FitsImportService(input, null)) {
            files = importer.readFiles();
        }
        CountingCloseClient client = new CountingCloseClient();
        PlateSolveService solver = new PlateSolveService(new AppConfig(), key -> client);
        solver.astrometryNetClient("");
        ProcessingSession session = new ProcessingSession(solver, files, null);
        assertSame(solver, session.getPlateSolveService());
        assertNotNull("the session owns the batch operations on its folder", session.getBatchService());
        session.close();
        session.close();
        assertEquals(1, client.closes);
    }

    /** Counts its closes; it releases the real client's resources each time. No network. */
    private static final class CountingCloseClient extends AstrometryDotNet {
        private int closes;

        /** Creates an unused anonymous client solely to count closes. */
        private CountingCloseClient() { super(""); }

        /** Frees the client and counts the call. */
        @Override public void close() {
            super.close();
            closes++;
        }
    }

    /** Releases the real client's resources, then simulates a solver that cannot confirm shutdown; no network. */
    private static final class FailingCloseClient extends AstrometryDotNet {
        /** Creates an unused anonymous client solely for the cleanup-failure path. */
        private FailingCloseClient() { super(""); }

        /** Frees the client before propagating the shutdown failure to the session. */
        @Override public void close() {
            super.close();
            throw new IllegalStateException("Cannot confirm solver shutdown.");
        }
    }

    /** The import delegates conversions to the caller's interaction rather than constructing Swing adapters. */
    @Test
    public void importUsesExplicitInteractionForConversionAndRedirect() throws Exception {
        File input = folder.newFolder("facade-float");
        write(input, "float.fit", new float[][]{{1, 2}, {3, 4}}, false);
        Interaction interaction = new Interaction();
        File converted;
        try (FitsImportService importer = new FitsImportService(input, interaction)) {
            try {
                importer.readFiles();
                throw new AssertionError("Expected the accepted conversion to redirect import");
            } catch (RedirectImportException redirect) {
                converted = redirect.getNewDirectory();
            }
        }
        assertEquals(List.of("Format Conversion Required", "Success"), interaction.questions);
        assertEquals(0, interaction.openProgress);
        try (FitsImportService importer = new FitsImportService(converted, null)) {
            assertEquals(1, importer.readFiles().length);
        }
    }

    /** Default factories validate strictly and never ask how to convert an unsupported input. */
    @Test
    public void facadeWithoutInteractionRejectsFloatInputWithoutWritingDerivatives() throws Exception {
        File input = folder.newFolder("headless-float");
        write(input, "float.fit", new float[][]{{1, 2}, {3, 4}}, false);
        try (FitsImportService importer = new FitsImportService(input, null)) {
            Exception failure = assertThrows(nom.tam.fits.FitsException.class, importer::readFiles);
            assertTrue(failure.getMessage(), failure.getMessage().contains("BITPIX=-32"));
        }
        assertEquals(1, input.listFiles().length);
    }

    @Test
    public void negativeFloatConversionPreservesPixelsPaddingHeadersAndOutputNames() throws Exception {
        File input = folder.newFolder("negative");
        File original = write(input, "sample.fit", new float[][]{{0, -12, 100}, {45, 300, 800}}, false);
        Interaction interaction = new Interaction();
        FitsBatchService batch = new FitsBatchService(input, new FitsVisualizationRenderer());
        int offset = FitsPixelConverter.scanNegativeValues(new File[]{original}, ignored -> {}).offsetToKeepNegativeValues();
        try (FitsImportService importer = new FitsImportService(input, batch, interaction)) {
            RedirectImportException redirect = assertThrows(RedirectImportException.class,
                    importer::readFiles);
            assertEquals("_16bit_converted", redirect.getNewDirectory().getName());
        }
        assertTrue(interaction.questions.contains("Pixel Values Below Zero"));
        File converted = new File(input, "_16bit_converted/sample_16bit.fit");
        try (Fits source = new Fits(original); Fits result = new Fits(converted)) {
            BasicHDU<?> before = FitsImages.getImageHDU(source);
            BasicHDU<?> after = FitsImages.getImageHDU(result);
            assertRows(FitsPixelConverter.standardizeTo16BitMono(before.getKernel(), before.getHeader(), offset),
                    (short[][]) after.getKernel());
            assertEquals(offset, after.getHeader().getIntValue("SPOFFSET"));
            assertEquals("target", after.getHeader().getStringValue("OBJECT"));
            assertEquals(20, after.getHeader().getDoubleValue("CRVAL2"), 0);
            assertEquals(32768, after.getHeader().getDoubleValue("BZERO"), 0);
            assertEquals(1, after.getHeader().getDoubleValue("BSCALE"), 0);
            assertEquals(0, ((short[][]) after.getKernel())[0][0] + 32768);
        }
        assertEquals(0, interaction.openProgress);
    }

    @Test
    public void signed16ConversionAndNegativeClippingKeepTheExistingPolicy() throws Exception {
        File input = folder.newFolder("signed");
        File original = write(input, "sample.fit", new short[][]{{0, -5, 100}, {3, 9, 20}}, false);
        Interaction interaction = new Interaction();
        interaction.negativeChoice = 1;
        try (FitsImportService importer = importer(input, interaction)) {
            assertThrows(RedirectImportException.class, importer::readFiles);
        }
        try (Fits source = new Fits(original); Fits result = new Fits(new File(input, "_16bit_converted/sample_16bit.fit"))) {
            BasicHDU<?> before = FitsImages.getImageHDU(source);
            BasicHDU<?> after = FitsImages.getImageHDU(result);
            assertRows(FitsPixelConverter.standardizeTo16BitMono(before.getKernel(), before.getHeader(), 0),
                    (short[][]) after.getKernel());
            assertEquals(0, after.getHeader().getIntValue("SPOFFSET", 0));
        }
    }

    @Test
    public void decliningNegativeConversionDoesNotWriteDerivatives() throws Exception {
        File input = folder.newFolder("declined");
        write(input, "sample.fit", new float[][]{{0, -5, 100}, {3, 9, 20}}, false);
        Interaction interaction = new Interaction();
        interaction.negativeChoice = 2;
        try (FitsImportService importer = importer(input, interaction)) {
            assertEquals(0, importer.readFiles().length);
        }
        assertFalse(new File(input, "_16bit_converted").exists());
        assertEquals(0, interaction.openProgress);
    }

    @Test
    public void colorConversionKeepsBothColorAndLuminanceDerivatives() throws Exception {
        File input = SyntheticDatasetFactory.createColor32FloatFitsSequence(folder.getRoot().toPath(), "color", 2).toFile();
        Interaction interaction = new Interaction();
        try (FitsImportService importer = importer(input, interaction)) {
            var redirect = assertThrows(RedirectImportException.class, importer::readFiles);
            assertEquals("_16bit_mono", redirect.getNewDirectory().getName());
        }
        for (File original : FitsFiles.list(input)) {
            String stem = original.getName().replace(".fit", "");
            try (Fits source = new Fits(original);
                 Fits color = new Fits(new File(input, "_16bit_color/" + stem + "_16bit_color.fit"));
                 Fits mono = new Fits(new File(input, "_16bit_mono/" + stem + "_16bit_mono.fit"))) {
                BasicHDU<?> image = FitsImages.getImageHDU(source);
                short[][][] expected = FitsPixelConverter.standardizeTo16BitColor(image.getKernel(), image.getHeader(), 0);
                short[][][] actual = (short[][][]) FitsImages.getImageHDU(color).getKernel();
                for (int channel = 0; channel < expected.length; channel++) assertRows(expected[channel], actual[channel]);
                assertRows(FitsPixelConverter.extractLuminance(expected), (short[][]) FitsImages.getImageHDU(mono).getKernel());
            }
        }
    }

    @Test
    public void compressedUnsigned16InputsKeepTheirPixelsAndRedirectToUncompressedFolder() throws Exception {
        File input = folder.newFolder("compressed");
        short[][] pixels = {{-32768, -100, 200}, {100, 300, 1500}};
        ImageHDU image = (ImageHDU) Fits.makeHDU(pixels);
        image.getHeader().addValue("BZERO", 32768, null);
        image.getHeader().addValue("BSCALE", 1, null);
        CompressedImageHDU compressed = CompressedImageHDU.fromImageHDU(image);
        compressed.setCompressAlgorithm("RICE_1");
        compressed.compress();
        try (Fits output = new Fits()) {
            output.addHDU(compressed);
            output.write(new File(input, "sample.fit.fz"));
        }
        try (FitsImportService importer = importer(input, new Interaction())) {
            var redirect = assertThrows(RedirectImportException.class, importer::readFiles);
            assertEquals("_uncompressed", redirect.getNewDirectory().getName());
        }
        try (Fits converted = new Fits(new File(input, "_uncompressed/sample.fit"))) {
            assertRows(pixels, (short[][]) FitsImages.getImageHDU(converted).getKernel());
        }
        try (FitsImportService importer = new FitsImportService(new File(input, "_uncompressed"), null)) {
            assertEquals(1, importer.readFiles().length);
        }
    }

    @Test
    public void batchMonoAndStretchKeepPixelsOriginalsAndLegacyOutputNames() throws Exception {
        File input = SyntheticDatasetFactory.createColor16FitsSequence(folder.getRoot().toPath(), "batch", 2).toFile();
        File original = FitsFiles.list(input)[0];
        byte[] untouched = Files.readAllBytes(original.toPath());
        FitsBatchService batch = new FitsBatchService(input, new FitsVisualizationRenderer());
        File generated = batch.batchConvertToMono(true, 10, 2, StretchAlgorithm.ASINH, null);
        assertEquals("_mono", generated.getName());
        String stem = original.getName().replace(".fit", "");
        try (Fits source = new Fits(original);
             Fits mono = new Fits(new File(input, "_mono/" + stem + "_mono.fit"));
             Fits stretched = new Fits(new File(input, "_mono_stretched/" + stem + "_mono_stretch.fit"))) {
            BasicHDU<?> image = FitsImages.getImageHDU(source);
            short[][] expected = FitsPixelConverter.extractLuminance(
                    FitsPixelConverter.standardizeTo16BitColor(image.getKernel(), image.getHeader()));
            assertRows(expected, (short[][]) FitsImages.getImageHDU(mono).getKernel());
            try (Fits expectedFits = FitsPixelConverter.createFitsFromData(expected, image.getHeader())) {
                new FitsVisualizationRenderer().stretchFitsImage(expectedFits, 10, 2, StretchAlgorithm.ASINH);
                assertRows((short[][]) FitsImages.getImageHDU(expectedFits).getKernel(),
                        (short[][]) FitsImages.getImageHDU(stretched).getKernel());
            }
        }
        batch.batchStretch(10, 2, StretchAlgorithm.ASINH, null);
        assertTrue(new File(input, "_stretched/" + stem + "_stretch.fit").isFile());
        assertArrayEquals(untouched, Files.readAllBytes(original.toPath()));
    }

    private FitsImportService importer(File input, Interaction interaction) {
        return new FitsImportService(input,
                new FitsBatchService(input, new FitsVisualizationRenderer()), interaction);
    }

    private static File write(File directory, String name, Object pixels, boolean unsigned) throws Exception {
        File file = new File(directory, name);
        try (Fits fits = new Fits()) {
            BasicHDU<?> image = Fits.makeHDU(pixels);
            Header header = image.getHeader();
            if (unsigned) {
                header.addValue("BZERO", 32768, null);
                header.addValue("BSCALE", 1, null);
            }
            header.addValue("OBJECT", "target", null);
            header.addValue("CRVAL2", 20, null);
            fits.addHDU(image);
            fits.write(file);
        }
        return file;
    }

    private static void assertRows(short[][] expected, short[][] actual) {
        assertEquals(expected.length, actual.length);
        for (int row = 0; row < expected.length; row++) assertArrayEquals(expected[row], actual[row]);
    }

    private static class Interaction implements FitsImportInteraction {
        private final List<String> questions = new ArrayList<>();
        private int negativeChoice;
        private int openProgress;
        @Override public int confirm(String message, String title) { questions.add(title); return YES; }
        @Override public int choose(String message, String title, String[] options) { questions.add(title); return negativeChoice; }
        @Override public void error(String message, String title) { throw new AssertionError(title + ": " + message); }
        @Override public Progress progress(String title) {
            openProgress++;
            return new Progress() {
                @Override public void onProgressUpdate(int percent, String message) {}
                @Override public void close() { openProgress--; }
            };
        }
    }
}
