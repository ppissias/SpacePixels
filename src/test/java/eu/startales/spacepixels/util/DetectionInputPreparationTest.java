package eu.startales.spacepixels.util;

import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class DetectionInputPreparationTest {

    private static final int FRAME_COUNT = 5;

    @Test
    public void prepareInputDirectoryLeavesReadyMono16FitsUnchanged() throws Exception {
        Path tempRoot = Files.createTempDirectory("spacepixels-ready-mono");
        Path sequenceDirectory = SyntheticDatasetFactory.createMono16FitsSequence(tempRoot, "mono16", FRAME_COUNT);

        DetectionInputPreparation.PreparedDirectory preparedDirectory =
                DetectionInputPreparation.prepareInputDirectory(sequenceDirectory.toFile(), true, null);

        assertFalse(preparedDirectory.isInputWasPrepared());
        assertEquals(sequenceDirectory.toFile().getCanonicalPath(), preparedDirectory.getPreparedInputDirectory().getCanonicalPath());
        assertDetectionReadyDirectory(preparedDirectory.getPreparedInputDirectory(), FRAME_COUNT);
    }

    @Test
    public void prepareInputDirectoryConvertsFloatMonoFitsToMono16() throws Exception {
        Path tempRoot = Files.createTempDirectory("spacepixels-float-mono");
        Path sequenceDirectory = SyntheticDatasetFactory.createMono32FloatFitsSequence(tempRoot, "float32mono", FRAME_COUNT);

        DetectionInputPreparation.PreparedDirectory preparedDirectory =
                DetectionInputPreparation.prepareInputDirectory(sequenceDirectory.toFile(), true, null);

        assertTrue(preparedDirectory.isInputWasPrepared());
        assertNotEquals(sequenceDirectory.toFile().getCanonicalPath(), preparedDirectory.getPreparedInputDirectory().getCanonicalPath());
        assertDetectionReadyDirectory(preparedDirectory.getPreparedInputDirectory(), FRAME_COUNT);
    }

    @Test
    public void prepareInputDirectoryConvertsColorFitsToMono16() throws Exception {
        Path tempRoot = Files.createTempDirectory("spacepixels-color16");
        Path sequenceDirectory = SyntheticDatasetFactory.createColor16FitsSequence(tempRoot, "color16", FRAME_COUNT);

        DetectionInputPreparation.PreparedDirectory preparedDirectory =
                DetectionInputPreparation.prepareInputDirectory(sequenceDirectory.toFile(), true, null);

        assertTrue(preparedDirectory.isInputWasPrepared());
        assertDetectionReadyDirectory(preparedDirectory.getPreparedInputDirectory(), FRAME_COUNT);
    }

    @Test
    public void prepareInputDirectoryConvertsGrayFloatXisfToMono16Fits() throws Exception {
        Path tempRoot = Files.createTempDirectory("spacepixels-xisf-seq");
        Path sequenceDirectory = SyntheticDatasetFactory.createGrayFloatXisfSequence(tempRoot, "xisf", FRAME_COUNT);

        DetectionInputPreparation.PreparedDirectory preparedDirectory =
                DetectionInputPreparation.prepareInputDirectory(sequenceDirectory.toFile(), true, null);

        assertTrue(preparedDirectory.isInputWasPrepared());
        assertDetectionReadyDirectory(preparedDirectory.getPreparedInputDirectory(), FRAME_COUNT);
    }

    private static void assertDetectionReadyDirectory(File directory, int expectedFrameCount) throws Exception {
        assertTrue(directory.isDirectory());
        File[] fitsFiles = directory.listFiles((dir, name) -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(".fit") || lower.endsWith(".fits") || lower.endsWith(".fts");
        });
        assertTrue(fitsFiles != null);
        Arrays.sort(fitsFiles, java.util.Comparator.comparing(File::getName));
        assertEquals(expectedFrameCount, fitsFiles.length);

        for (File fitsFile : fitsFiles) {
            try (Fits fits = new Fits(fitsFile)) {
                BasicHDU<?> hdu = ImageProcessing.getImageHDU(fits);
                assertEquals(16, hdu.getHeader().getIntValue("BITPIX", 0));
                assertEquals(2, hdu.getAxes().length);
                assertTrue(hdu.getKernel() instanceof short[][]);
            }
        }
    }

    @Test
    public void preparationAppliesUnsigned32BitOffsetWithoutLosingPrecision() throws Exception {
        assertScaledPreparation(new int[][]{{Integer.MIN_VALUE + 1000, Integer.MIN_VALUE + 4000}},
                2147483648.0, 1.0, new int[]{1000, 4000});
    }

    @Test
    public void preparationAppliesFloatingPointScalingBeforeNormalization() throws Exception {
        assertScaledPreparation(new float[][]{{1.0f, 2.0f}}, 100.0, 900.0, new int[]{1000, 1900});
    }

    @Test
    public void preparationAppliesColorScalingBeforeExtractingLuminance() throws Exception {
        int[] values = {Integer.MIN_VALUE + 1000, Integer.MIN_VALUE + 4000};
        assertScaledPreparation(new int[][][]{{values}, {values}, {values}},
                2147483648.0, 1.0, new int[]{1000, 4000});
    }

    @Test
    public void preparationNormalizesScaled16BitDataInsteadOfRelabelingRawPixels() throws Exception {
        assertScaledPreparation(new short[][]{{-6000, 0, 2000}}, 10000.0, 2.0, new int[]{0, 10000, 14000});
    }

    private static void assertScaledPreparation(Object kernel, double offset, double scale, int[] expected) throws Exception {
        Path inputDirectory = Files.createTempDirectory("spacepixels-scaled-fits");
        File originalFile = inputDirectory.resolve("scaled.fit").toFile();
        try (Fits fits = new Fits()) {
            BasicHDU<?> hdu = Fits.makeHDU(kernel);
            hdu.getHeader().addValue("BZERO", offset, null);
            hdu.getHeader().addValue("BSCALE", scale, null);
            hdu.getHeader().addValue("DATE-OBS", "2026-04-08T01:00:00", null);
            fits.addHDU(hdu);
            fits.write(originalFile);
        }
        byte[] originalBytes = Files.readAllBytes(originalFile.toPath());
        DetectionInputPreparation.PreparedDirectory prepared =
                DetectionInputPreparation.prepareInputDirectory(inputDirectory.toFile(), true, null);
        assertTrue(prepared.isInputWasPrepared());
        try (Fits fits = new Fits(new File(prepared.getPreparedInputDirectory(), "scaled.fit"))) {
            BasicHDU<?> hdu = ImageProcessing.getImageHDU(fits);
            short[][] data = (short[][]) hdu.getKernel();
            assertEquals(32768.0, hdu.getHeader().getDoubleValue("BZERO"), 0.0);
            assertEquals(1.0, hdu.getHeader().getDoubleValue("BSCALE"), 0.0);
            assertEquals("2026-04-08T01:00:00", hdu.getHeader().getStringValue("DATE-OBS"));
            for (int column = 0; column < expected.length; column++) {
                assertEquals(expected[column], data[0][column] + 32768);
            }
        }
        org.junit.Assert.assertArrayEquals(originalBytes, Files.readAllBytes(originalFile.toPath()));
    }
}
