package eu.startales.spacepixels.fits;

import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import nom.tam.fits.ImageHDU;
import nom.tam.image.compression.hdu.CompressedImageHDU;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;
import eu.startales.spacepixels.detection.DetectionInputPreparation;

/** Compares interactive and automatic preparation pixels, metadata and preservation of original inputs. */
public class FitsPreparationParityTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void signedScaledIntegerFloatAndColorInputsHaveMatchingPixelsAndMetadata() throws Exception {
        List<Object> kernels = List.of(
                new short[][]{{0, -10, 10, 100, 0}, {0, 5, 20, 200, 0}},
                new int[][]{{0, -10, 10, 100, 0}, {0, 5, 20, 200, 0}},
                new float[][]{{0, -10, 10, 100, 0}, {0, 5, 20, 200, 0}});
        for (Object kernel : kernels) {
            for (boolean color : new boolean[]{false, true}) {
                for (boolean keepNegative : new boolean[]{false, true}) {
                    File input = folder.newFolder();
                    Object pixels = color ? color(kernel) : kernel;
                    File original = write(input, "later.fit", pixels, 0, 1, "2026-10-22T01:00:02", false);
                    write(input, "earlier.fit", pixels, 0, 1, "2026-10-22T01:00:01", false);
                    assertParity(input, keepNegative);
                    try (Fits source = new Fits(original)) {
                        assertEquals(0, FitsInput.getImageHDU(source).getHeader().getDoubleValue("BZERO"), 0);
                    }
                }
            }
        }
        for (boolean keepNegative : new boolean[]{false, true}) {
            File input = folder.newFolder();
            write(input, "later.fit", new short[][]{{0, -10, 10, 100, 0}}, 100, 10, "2026-10-22T01:00:02", false);
            write(input, "earlier.fit", new short[][]{{0, -20, 10, 100, 0}}, 100, 10, "2026-10-22T01:00:01", false);
            File interactive = assertParity(input, keepNegative);
            try (Fits later = new Fits(new File(interactive, "later_16bit.fit"));
                 Fits earlier = new Fits(new File(interactive, "earlier_16bit.fit"))) {
                BasicHDU<?> laterImage = FitsInput.getImageHDU(later);
                BasicHDU<?> earlierImage = FitsInput.getImageHDU(earlier);
                int expectedOffset = keepNegative ? 100 : 0;
                assertEquals(expectedOffset, laterImage.getHeader().getIntValue("SPOFFSET", 0));
                assertEquals(expectedOffset, earlierImage.getHeader().getIntValue("SPOFFSET", 0));
                assertEquals(expectedOffset, ((short[][]) laterImage.getKernel())[0][1] + 32768);
                assertEquals(0, ((short[][]) earlierImage.getKernel())[0][1] + 32768);
                assertEquals(100 + expectedOffset, ((short[][]) laterImage.getKernel())[0][0] + 32768);
            }
        }
    }

    @Test
    public void compressedUnsigned16OnlyDecompressesAndInteger32ConvertsDirectly() throws Exception {
        File ready = folder.newFolder();
        short[][] unsigned = {{-32768, -30000, 0}, {-32768, -20000, 1000}};
        write(ready, "frame.fit.fz", unsigned, 32768, 1, "2026-10-22T01:00:01", true);
        File decompressed = assertParity(ready, true);
        assertEquals("_uncompressed", decompressed.getName());
        try (Fits result = new Fits(new File(decompressed, "frame.fit"))) {
            assertRows(unsigned, (short[][]) FitsInput.getImageHDU(result).getKernel());
        }

        File integer = folder.newFolder();
        write(integer, "frame.fit.fz", new int[][]{{0, -10, 100, 0}}, 0, 1, "2026-10-22T01:00:01", true);
        File normalized = assertParity(integer, true);
        assertEquals("_16bit_converted", normalized.getName());
        assertEquals(integer, normalized.getParentFile());
        assertFalse(new File(integer, "_uncompressed").exists());
    }

    /** Compressed integer/float mono/color sequences match API preparation and the previous two-step pixels. */
    @Test public void combinedCompressedPreparationMatchesDecompressThenConvert() throws Exception {
        for (Object kernel : List.of(new int[][]{{0, -10, 100, 0}, {0, 5, 20, 0}},
                new float[][]{{0, -0.001f, 0.01f, 0}, {0, 0.0005f, 0.002f, 0}})) {
            for (boolean color : new boolean[]{false, true}) {
                for (boolean keepNegative : new boolean[]{false, true}) {
                    File input = folder.newFolder();
                    write(input, "frame.fit.fz", color ? color(kernel) : kernel, 0, 1,
                            "2026-10-22T01:00:01", true);
                    File direct = assertParity(input, keepNegative);
                    assertEquals(input, direct.getParentFile());
                    assertFalse(new File(input, "_uncompressed").exists());
                    int offset = keepNegative ? FitsPixelConverter.scanNegativeValues(
                            FitsInput.list(input), null).offsetToKeepNegativeValues() : 0;
                    FitsBatchService batch = new FitsBatchService(input, new FitsVisualizationRenderer());
                    File plain = batch.batchDecompress(FitsInput.list(input), null);
                    ImageScaling.Plan scaling = ImageScaling.plan(ImageScaling.inspect(FitsInput.list(plain), null), keepNegative,
                            ImageScaling.policy(ImageScaling.OutlierHandling.PRESERVE_ALL));
                    File previous = batch.batchStandardizeTo16Bit(FitsInput.list(plain), color, scaling, null);
                    String filename = color ? "frame_16bit_mono.fit" : "frame_16bit.fit";
                    assertSamePreparedImage(new File(previous, filename), new File(direct, filename));
                    if (color) {
                        assertSamePreparedImage(new File(plain, "_16bit_color/frame_16bit_color.fit"),
                                new File(input, "_16bit_color/frame_16bit_color.fit"));
                    }
                }
            }
        }
    }

    /** Refusal/cancellation cannot install compressed metadata or write an intermediate sequence. */
    @Test public void combinedPreparationDeclineAndNegativeValueCancellationKeepOriginals() throws Exception {
        for (boolean decline : new boolean[]{false, true}) {
            File input = folder.newFolder();
            File original = write(input, "frame.fit.fz", new int[][]{{0, -10, 100, 0}}, 0, 1,
                    "2026-10-22T01:00:01", true);
            byte[] bytes = Files.readAllBytes(original.toPath());
            Interaction interaction = new Interaction(true);
            interaction.confirm = decline ? 1 : 0;
            interaction.negativeChoice = 2;
            try (FitsImportService importer = importer(input, interaction)) {
                assertEquals(0, importer.readFiles().length);
            }
            try (FitsImportService strict = new FitsImportService(input, null, null)) {
                assertThrows(Exception.class, strict::readFiles);
            }
            assertEquals(List.of("Combined FITS Preparation"), interaction.confirmationTitles);
            assertEquals(1, input.listFiles().length);
            assertArrayEquals(bytes, Files.readAllBytes(original.toPath()));
            assertEquals(0, interaction.openProgress);
        }
    }

    /** A compressed sequence uses one offset from its lowest frame and keeps registration zeros untouched. */
    @Test public void compressedSequenceUsesOneOffsetAndValidatesBeforeConversion() throws Exception {
        File input = folder.newFolder();
        write(input, "later.fit.fz", new int[][]{{0, -10, 100, 0}}, 0, 1, "2026-10-22T01:00:02", true);
        write(input, "earlier.fit.fz", new int[][]{{0, -20, 100, 0}}, 0, 1, "2026-10-22T01:00:01", true);
        File output = assertParity(input, true);
        try (Fits later = new Fits(new File(output, "later_16bit.fit"));
             Fits earlier = new Fits(new File(output, "earlier_16bit.fit"))) {
            BasicHDU<?> laterImage = FitsInput.getImageHDU(later);
            BasicHDU<?> earlierImage = FitsInput.getImageHDU(earlier);
            assertEquals(20, laterImage.getHeader().getIntValue("SPOFFSET"));
            assertEquals(20, earlierImage.getHeader().getIntValue("SPOFFSET"));
            assertEquals(10, ((short[][]) laterImage.getKernel())[0][1] + 32768);
            assertEquals(0, ((short[][]) earlierImage.getKernel())[0][1] + 32768);
            assertEquals(0, ((short[][]) laterImage.getKernel())[0][0] + 32768);
        }
        File mixed = folder.newFolder();
        write(mixed, "first.fit.fz", new int[][]{{0, -10, 100, 0}}, 0, 1, "2026-10-22T01:00:01", true);
        write(mixed, "second.fit.fz", new float[][]{{0, -10, 100, 0}}, 0, 1, "2026-10-22T01:00:02", true);
        Interaction interaction = new Interaction(true);
        try (FitsImportService importer = importer(mixed, interaction)) {
            assertThrows(java.io.IOException.class, importer::readFiles);
        }
        assertTrue(interaction.confirmationTitles.isEmpty());
        assertEquals(2, mixed.listFiles().length);
    }

    /** Compares final pixels and relevant headers, including all color channels and the sequence offset. */
    private static void assertSamePreparedImage(File expected, File actual) throws Exception {
        try (Fits baseline = new Fits(expected); Fits combined = new Fits(actual)) {
            BasicHDU<?> baselineImage = FitsInput.getImageHDU(baseline);
            BasicHDU<?> combinedImage = FitsInput.getImageHDU(combined);
            if (baselineImage.getKernel() instanceof short[][][] baselineColor) {
                short[][][] combinedColor = (short[][][]) combinedImage.getKernel();
                assertEquals(baselineColor.length, combinedColor.length);
                for (int channel = 0; channel < baselineColor.length; channel++) {
                    assertRows(baselineColor[channel], combinedColor[channel]);
                }
            } else {
                assertRows((short[][]) baselineImage.getKernel(), (short[][]) combinedImage.getKernel());
            }
            for (String key : List.of("OBJECT", "CRVAL1", "CRVAL2", "DATE-OBS", "EXPTIME", "BZERO", "BSCALE", "SPOFFSET")) {
                var baselineCard = baselineImage.getHeader().findCard(key);
                var combinedCard = combinedImage.getHeader().findCard(key);
                assertEquals(key, baselineCard == null ? null : baselineCard.getValue(),
                        combinedCard == null ? null : combinedCard.getValue());
            }
        }
    }

    @Test
    public void readyInputsAndXisfUseTheSameDiscoveryAndMetadataRules() throws Exception {
        File ready = SyntheticDatasetFactory.createSolvedMono16FitsSequence(folder.getRoot().toPath(), "ready", 3).toFile();
        assertEquals(ready, assertParity(ready, true));
        File xisf = SyntheticDatasetFactory.createGrayFloatXisfSequence(folder.getRoot().toPath(), "xisf", 3).toFile();
        assertEquals("_spacepixels_xisf_mono16", assertParity(xisf, true).getName());
    }

    @Test
    public void strictPreparationAndMetadataLoadingRejectInputsWithoutWritingOutput() throws Exception {
        File input = folder.newFolder();
        write(input, "frame.fit", new int[][]{{0, -10, 100, 0}}, 0, 1, "2026-10-22T01:00:01", false);
        DetectionInputPreparation.PreparedDirectory result = DetectionInputPreparation.prepareInputDirectory(input, false, null);
        assertEquals(input, result.getPreparedInputDirectory());
        assertFalse(result.isInputWasPrepared());
        try (FitsImportService importer = new FitsImportService(input, null, null)) {
            Exception failure = assertThrows(Exception.class, importer::readFiles);
            assertTrue(failure.getMessage(), failure.getMessage().contains("BITPIX=32"));
        }
        assertEquals(1, input.listFiles().length);
    }

    @Test
    public void decliningConversionWritesNothingAndKeepsInteractionResourcesClosed() throws Exception {
        File input = folder.newFolder();
        write(input, "frame.fit", new int[][]{{0, -10, 100, 0}}, 0, 1, "2026-10-22T01:00:01", false);
        Interaction interaction = new Interaction(true);
        interaction.confirm = 1;
        try (FitsImportService importer = new FitsImportService(input,
                new FitsBatchService(input, new FitsVisualizationRenderer()), interaction)) {
            assertEquals(0, importer.readFiles().length);
        }
        assertEquals(1, input.listFiles().length);
        assertEquals(0, interaction.openProgress);
    }

    @Test
    public void cancelledDecompressionDoesNotFallThroughToMetadataInstallation() throws Exception {
        File input = folder.newFolder();
        File original = write(input, "frame.fit.fz", new short[][]{{-32768, -30000, -32768}},
                32768, 1, "2026-10-22T01:00:01", true);
        byte[] originalBytes = Files.readAllBytes(original.toPath());
        Interaction interaction = new Interaction(true);
        interaction.cancelProgress = true;
        try (FitsImportService importer = new FitsImportService(input,
                new FitsBatchService(input, new FitsVisualizationRenderer()), interaction)) {
            assertThrows(java.util.concurrent.CancellationException.class, importer::readFiles);
        }
        assertEquals(0, interaction.openProgress);
        assertEquals(1, input.listFiles().length);
        assertArrayEquals(originalBytes, Files.readAllBytes(original.toPath()));
    }

    private File assertParity(File input, boolean keepNegative) throws Exception {
        File[] originals = input.listFiles(File::isFile);
        Map<File, byte[]> originalBytes = new TreeMap<>();
        for (File original : originals) originalBytes.put(original, Files.readAllBytes(original.toPath()));
        Interaction interaction = new Interaction(keepNegative);
        File interactive = XisfImageConverter.prepareDirectoryForFitsImport(input);
        for (int attempt = 0; attempt < 4; attempt++) {
            try (FitsImportService importer = importer(interactive, interaction)) {
                importer.readFiles();
                break;
            } catch (RedirectImportException redirect) {
                interactive = redirect.getNewDirectory();
            }
        }
        File automatic = DetectionInputPreparation.prepareInputDirectory(input, true, keepNegative, null,
                ImageScaling.policy(ImageScaling.OutlierHandling.PRESERVE_ALL)).getPreparedInputDirectory();
        FitsFileInformation[] interactiveFiles;
        FitsFileInformation[] automaticFiles;
        try (FitsImportService importer = new FitsImportService(interactive, null, null);
             FitsImportService api = new FitsImportService(automatic, null, null)) {
            interactiveFiles = importer.readFiles();
            automaticFiles = api.readFiles();
        }
        assertEquals(interactiveFiles.length, automaticFiles.length);
        for (int index = 0; index < interactiveFiles.length; index++) {
            FitsFileInformation gui = interactiveFiles[index];
            FitsFileInformation api = automaticFiles[index];
            assertEquals(gui.getObservationTimestamp(), api.getObservationTimestamp());
            assertEquals(gui.getExposureDurationMillis(), api.getExposureDurationMillis());
            try (Fits guiFits = new Fits(gui.getFilePath()); Fits apiFits = new Fits(api.getFilePath())) {
                BasicHDU<?> guiImage = FitsInput.getImageHDU(guiFits);
                BasicHDU<?> apiImage = FitsInput.getImageHDU(apiFits);
                assertRows((short[][]) guiImage.getKernel(), (short[][]) apiImage.getKernel());
                for (String key : List.of("OBJECT", "CRVAL1", "CRVAL2", "DATE-OBS", "EXPTIME", "BZERO", "BSCALE", "SPOFFSET")) {
                    assertEquals(key, gui.getFitsHeader().get(key), api.getFitsHeader().get(key));
                }
            }
        }
        for (Map.Entry<File, byte[]> original : originalBytes.entrySet()) {
            assertArrayEquals(original.getValue(), Files.readAllBytes(original.getKey().toPath()));
        }
        assertEquals(0, interaction.openProgress);
        return interactive;
    }

    private FitsImportService importer(File input, Interaction interaction) {
        return new FitsImportService(input, new FitsBatchService(input, new FitsVisualizationRenderer()), interaction);
    }

    private static Object color(Object mono) {
        if (mono instanceof short[][] pixels) return new short[][][]{pixels, pixels, pixels};
        if (mono instanceof int[][] pixels) return new int[][][]{pixels, pixels, pixels};
        float[][] pixels = (float[][]) mono;
        return new float[][][]{pixels, pixels, pixels};
    }

    private static File write(File input, String filename, Object pixels, double bzero, double bscale,
                              String timestamp, boolean compressed) throws Exception {
        File file = new File(input, filename);
        try (Fits output = new Fits()) {
            ImageHDU image = (ImageHDU) Fits.makeHDU(pixels);
            Header header = image.getHeader();
            header.addValue("BZERO", bzero, null);
            header.addValue("BSCALE", bscale, null);
            header.addValue("DATE-OBS", timestamp, null);
            header.addValue("EXPTIME", 60, null);
            header.addValue("CRVAL1", 100, null);
            header.addValue("CRVAL2", 20, null);
            header.addValue("OBJECT", "parity", null);
            if (compressed) {
                CompressedImageHDU packed = CompressedImageHDU.fromImageHDU(image);
                packed.setCompressAlgorithm("GZIP_1");
                packed.compress();
                output.addHDU(packed);
            } else {
                output.addHDU(image);
            }
            output.write(file);
        }
        return file;
    }

    private static void assertRows(short[][] expected, short[][] actual) {
        assertEquals(expected.length, actual.length);
        for (int row = 0; row < expected.length; row++) assertArrayEquals(expected[row], actual[row]);
    }

    private static class Interaction implements FitsImportInteraction {
        private final boolean keepNegative;
        private int confirm;
        private int openProgress;
        private boolean cancelProgress;
        private int negativeChoice = -1;
        private final java.util.List<String> confirmationTitles = new java.util.ArrayList<>();

        Interaction(boolean keepNegative) { this.keepNegative = keepNegative; }
        /** Records preparation/load prompts without displaying dialogs. */
        @Override public int confirm(String message, String title) {
            confirmationTitles.add(title);
            return confirm;
        }
        /** Selects the requested offset policy or simulates cancelling the negative-value question. */
        @Override public int choose(String message, String title, String[] options) {
            if (title.equals("Extreme pixel values")) return 1;
            return negativeChoice >= 0 ? negativeChoice : keepNegative ? 0 : 1;
        }
        @Override public void error(String message, String title) { throw new AssertionError(title + ": " + message); }
        @Override public Progress progress(String title) {
            openProgress++;
            return new Progress() {
                @Override public void onProgressUpdate(int percent, String message) {
                    if (cancelProgress) throw new java.util.concurrent.CancellationException();
                }
                @Override public void close() { openProgress--; }
            };
        }
    }
}
