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
}
