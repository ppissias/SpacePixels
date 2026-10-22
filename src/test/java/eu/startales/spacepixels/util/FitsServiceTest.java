package eu.startales.spacepixels.util;

import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.session.ProcessingSession;
import eu.startales.spacepixels.session.SessionFrames;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
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

    @Test
    public void readyInteractiveAndHeadlessMetadataMatchAndShareTheSessionCache() throws Exception {
        File input = SyntheticDatasetFactory.createSolvedMono16FitsSequence(folder.getRoot().toPath(), "frames", 3).toFile();
        SessionFrames state = new SessionFrames();
        Interaction interaction = new Interaction();
        FitsBatchService batch = new FitsBatchService(input, new FitsVisualizationRenderer());
        try (FitsImportService importer = new FitsImportService(input, state, batch, interaction);
             ImageProcessing processing = ImageProcessing.getInstance(input, settings())) {
            FitsFileInformation[] interactive = importer.getFitsfileInformation();
            FitsFileInformation[] headless = processing.getFitsfileInformationHeadless();
            assertEquals(3, interactive.length);
            assertSame(interactive, state.getFiles());
            for (int index = 0; index < interactive.length; index++) {
                assertEquals(headless[index].getFitsHeader(), interactive[index].getFitsHeader());
                assertEquals(headless[index].getFilePath(), interactive[index].getFilePath());
                assertEquals(headless[index].getObservationTimestamp(), interactive[index].getObservationTimestamp());
            }
            assertEquals(0, interaction.questions.size());
            ProcessingSession session = new ProcessingSession(processing, headless, null);
            assertSame(headless[0], session.getFiles()[0]);
            assertSame(headless[0], processing.getSessionFrames().getFiles()[0]);
            session.getFiles()[0].getFitsHeader().put("OBJECT", "shared");
            assertEquals("shared", processing.getImportedFiles()[0].getFitsHeader().get("OBJECT"));
            session.close();
            assertNull(processing.getSessionFrames().getFiles());
        }
    }

    @Test
    public void negativeFloatConversionPreservesPixelsPaddingHeadersAndOutputNames() throws Exception {
        File input = folder.newFolder("negative");
        File original = write(input, "sample.fit", new float[][]{{0, -12, 100}, {45, 300, 800}}, false);
        Interaction interaction = new Interaction();
        FitsBatchService batch = new FitsBatchService(input, new FitsVisualizationRenderer());
        int offset = FitsPixelConverter.scanNegativeValues(new File[]{original}, ignored -> {}).offsetToKeepNegativeValues();
        try (FitsImportService importer = new FitsImportService(input, new SessionFrames(), batch, interaction)) {
            ImageProcessing.RedirectImportException redirect = assertThrows(ImageProcessing.RedirectImportException.class,
                    importer::getFitsfileInformation);
            assertEquals("_16bit_converted", redirect.getNewDirectory().getName());
        }
        assertTrue(interaction.questions.contains("Pixel Values Below Zero"));
        File converted = new File(input, "_16bit_converted/sample_16bit.fit");
        try (Fits source = new Fits(original); Fits result = new Fits(converted)) {
            BasicHDU<?> before = ImageProcessing.getImageHDU(source);
            BasicHDU<?> after = ImageProcessing.getImageHDU(result);
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
            assertThrows(ImageProcessing.RedirectImportException.class, importer::getFitsfileInformation);
        }
        try (Fits source = new Fits(original); Fits result = new Fits(new File(input, "_16bit_converted/sample_16bit.fit"))) {
            BasicHDU<?> before = ImageProcessing.getImageHDU(source);
            BasicHDU<?> after = ImageProcessing.getImageHDU(result);
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
            assertEquals(0, importer.getFitsfileInformation().length);
        }
        assertFalse(new File(input, "_16bit_converted").exists());
        assertEquals(0, interaction.openProgress);
    }

    @Test
    public void colorConversionKeepsBothColorAndLuminanceDerivatives() throws Exception {
        File input = SyntheticDatasetFactory.createColor32FloatFitsSequence(folder.getRoot().toPath(), "color", 2).toFile();
        Interaction interaction = new Interaction();
        try (FitsImportService importer = importer(input, interaction)) {
            var redirect = assertThrows(ImageProcessing.RedirectImportException.class, importer::getFitsfileInformation);
            assertEquals("_16bit_mono", redirect.getNewDirectory().getName());
        }
        for (File original : FitsFiles.list(input)) {
            String stem = original.getName().replace(".fit", "");
            try (Fits source = new Fits(original);
                 Fits color = new Fits(new File(input, "_16bit_color/" + stem + "_16bit_color.fit"));
                 Fits mono = new Fits(new File(input, "_16bit_mono/" + stem + "_16bit_mono.fit"))) {
                BasicHDU<?> image = ImageProcessing.getImageHDU(source);
                short[][][] expected = FitsPixelConverter.standardizeTo16BitColor(image.getKernel(), image.getHeader(), 0);
                short[][][] actual = (short[][][]) ImageProcessing.getImageHDU(color).getKernel();
                for (int channel = 0; channel < expected.length; channel++) assertRows(expected[channel], actual[channel]);
                assertRows(FitsPixelConverter.extractLuminance(expected), (short[][]) ImageProcessing.getImageHDU(mono).getKernel());
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
            var redirect = assertThrows(ImageProcessing.RedirectImportException.class, importer::getFitsfileInformation);
            assertEquals("_uncompressed", redirect.getNewDirectory().getName());
        }
        try (Fits converted = new Fits(new File(input, "_uncompressed/sample.fit"))) {
            assertRows(pixels, (short[][]) ImageProcessing.getImageHDU(converted).getKernel());
        }
        try (ImageProcessing processing = ImageProcessing.getInstance(new File(input, "_uncompressed"), settings())) {
            assertEquals(1, processing.getFitsfileInformationHeadless().length);
        }
    }

    @Test
    public void facadeMonoAndStretchKeepPixelsOriginalsAndLegacyOutputNames() throws Exception {
        File input = SyntheticDatasetFactory.createColor16FitsSequence(folder.getRoot().toPath(), "batch", 2).toFile();
        File original = FitsFiles.list(input)[0];
        byte[] untouched = Files.readAllBytes(original.toPath());
        try (ImageProcessing processing = ImageProcessing.getInstance(input, settings())) {
            File generated = processing.batchConvertToMono(true, 10, 2, StretchAlgorithm.ASINH, null);
            assertEquals("_mono", generated.getName());
            String stem = original.getName().replace(".fit", "");
            try (Fits source = new Fits(original);
                 Fits mono = new Fits(new File(input, "_mono/" + stem + "_mono.fit"));
                 Fits stretched = new Fits(new File(input, "_mono_stretched/" + stem + "_mono_stretch.fit"))) {
                BasicHDU<?> image = ImageProcessing.getImageHDU(source);
                short[][] expected = FitsPixelConverter.extractLuminance(
                        FitsPixelConverter.standardizeTo16BitColor(image.getKernel(), image.getHeader()));
                assertRows(expected, (short[][]) ImageProcessing.getImageHDU(mono).getKernel());
                try (Fits expectedFits = FitsPixelConverter.createFitsFromData(expected, image.getHeader())) {
                    new FitsVisualizationRenderer().stretchFitsImage(expectedFits, 10, 2, StretchAlgorithm.ASINH);
                    assertRows((short[][]) ImageProcessing.getImageHDU(expectedFits).getKernel(),
                            (short[][]) ImageProcessing.getImageHDU(stretched).getKernel());
                }
            }
            processing.batchStretch(10, 2, StretchAlgorithm.ASINH, null);
            assertTrue(new File(input, "_stretched/" + stem + "_stretch.fit").isFile());
            Files.delete(new File(input, "_mono_stretched/" + stem + "_mono_stretch.fit").toPath());
            processing.onlyStretch(10, 2, StretchAlgorithm.ASINH);
            assertTrue(new File(input, "_mono_stretched/" + stem + "_mono_stretch.fit").isFile());
        }
        assertArrayEquals(untouched, Files.readAllBytes(original.toPath()));
    }

    private FitsImportService importer(File input, Interaction interaction) {
        return new FitsImportService(input, new SessionFrames(),
                new FitsBatchService(input, new FitsVisualizationRenderer()), interaction);
    }

    private ApplicationSettings settings() {
        return ApplicationSettings.load(new File(folder.getRoot(), "preferences.json"));
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
