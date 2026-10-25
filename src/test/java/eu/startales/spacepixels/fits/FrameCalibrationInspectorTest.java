package eu.startales.spacepixels.fits;

import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FrameCalibrationInspectorTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void cleanSequenceHasNoWarnings() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "clean", 3);
        File[] files = FitsInput.list(sequence.toFile());
        FrameCalibrationInspector.InspectionResult result = FrameCalibrationInspector.inspect(files);

        assertNotNull(result);
        assertFalse("Clean sequence should not be uncalibrated", result.isUncalibrated());
        assertFalse("Clean sequence should not be misaligned", result.isMisaligned());
        assertFalse("Clean sequence should have no warning", result.hasWarning());
    }

    @Test
    public void uncalibratedSequenceWithHotPixelsTriggersWarning() throws Exception {
        Path sequence = SyntheticDatasetFactory.createUncalibratedMono16FitsSequence(folder.getRoot().toPath(), "uncalibrated", 3, 40);
        File[] files = FitsInput.list(sequence.toFile());
        FrameCalibrationInspector.InspectionResult result = FrameCalibrationInspector.inspect(files);

        assertNotNull(result);
        assertTrue("Sequence with hot pixel spikes should be flagged as uncalibrated", result.isUncalibrated());
        assertTrue(result.hasWarning());
        assertTrue(result.hotPixelCount() >= 30);
        assertTrue(result.hotPixelsPerMegapixel() >= 5.0);
        assertNotNull(result.warningMessage());
        assertTrue(result.warningMessage().contains("Uncalibrated hot-pixel density"));
        assertTrue(result.warningDialogText().contains("High Hot-Pixel Density"));
    }

    @Test
    public void misalignedSequenceTriggersWarning() throws Exception {
        // Shift frame 1 and 2 by 4 px
        Path sequence = SyntheticDatasetFactory.createMisalignedMono16FitsSequence(folder.getRoot().toPath(), "misaligned", 3, 4, 0);
        File[] files = FitsInput.list(sequence.toFile());
        FrameCalibrationInspector.InspectionResult result = FrameCalibrationInspector.inspect(files);

        assertNotNull(result);
        assertTrue("Sequence with 4 px star shift should be flagged as misaligned", result.isMisaligned());
        assertTrue(result.hasWarning());
        assertTrue(result.starShiftPixels() > 2.5);
        assertNotNull(result.warningMessage());
        assertTrue(result.warningMessage().contains("Misaligned frame shift"));
        assertTrue(result.warningDialogText().contains("Frame Misalignment"));
    }

    @Test
    public void realBenchmarkDatasetWhynotTriggersUncalibratedWarning() throws Exception {
        String testDataRoot = System.getProperty("spacepixels.testdata.root");
        if (testDataRoot == null || testDataRoot.isBlank()) {
            File fallback = new File("C:\\Users\\petro\\Desktop\\astrophotography\\02_spacepixels-test-data");
            if (fallback.isDirectory()) {
                testDataRoot = fallback.getAbsolutePath();
            }
        }
        Assume.assumeTrue("Test data root is not available", testDataRoot != null && Files.isDirectory(Path.of(testDataRoot)));

        File whynotDir = new File(testDataRoot, "whynot-nocalibration-wrongdrift");
        Assume.assumeTrue("whynot dataset is missing", whynotDir.isDirectory());

        File[] files = FitsInput.list(whynotDir);
        FrameCalibrationInspector.InspectionResult result = FrameCalibrationInspector.inspect(files);

        assertNotNull(result);
        assertTrue("whynot-nocalibration-wrongdrift must be flagged as uncalibrated", result.isUncalibrated());
        assertTrue("whynot-nocalibration-wrongdrift must trigger a quality warning", result.hasWarning());
        assertTrue("whynot hot pixel count must be substantial", result.hotPixelCount() >= 50);
    }

    @Test
    public void realBenchmarkDatasetKlangwolke2HasNoWarnings() throws Exception {
        String testDataRoot = System.getProperty("spacepixels.testdata.root");
        if (testDataRoot == null || testDataRoot.isBlank()) {
            File fallback = new File("C:\\Users\\petro\\Desktop\\astrophotography\\02_spacepixels-test-data");
            if (fallback.isDirectory()) {
                testDataRoot = fallback.getAbsolutePath();
            }
        }
        Assume.assumeTrue("Test data root is not available", testDataRoot != null && Files.isDirectory(Path.of(testDataRoot)));

        File kw2Dir = new File(new File(testDataRoot, "klangwolke-2"), "_16bit_converted");
        Assume.assumeTrue("klangwolke-2 _16bit_converted dataset is missing", kw2Dir.isDirectory());

        File[] files = FitsInput.list(kw2Dir);
        FrameCalibrationInspector.InspectionResult result = FrameCalibrationInspector.inspect(files);

        assertNotNull(result);
        assertFalse("klangwolke-2 must NOT be flagged as uncalibrated", result.isUncalibrated());
        assertFalse("klangwolke-2 must NOT be flagged as misaligned", result.isMisaligned());
        assertFalse("klangwolke-2 must not trigger any quality warnings", result.hasWarning());
    }

    @Test
    public void allCalibratedBenchmarkDatasetsStayUnflagged() throws Exception {
        String testDataRoot = System.getProperty("spacepixels.testdata.root");
        if (testDataRoot == null || testDataRoot.isBlank()) {
            File fallback = new File("C:\\Users\\petro\\Desktop\\astrophotography\\02_spacepixels-test-data");
            if (fallback.isDirectory()) {
                testDataRoot = fallback.getAbsolutePath();
            }
        }
        Assume.assumeTrue("Test data root is not available", testDataRoot != null && Files.isDirectory(Path.of(testDataRoot)));

        String[] benchmarkDatasets = {
                "klangwolke-1",
                "dc-reg",
                "m101-reg",
                "helix-reg",
                "Eagle-noisy",
                "Apophis",
                "Africano",
                "Iwamoto-huge",
                "M81-kumar-L-dataset-registered",
                "UG 6524-kumar-L-dataset-registeed"
        };

        for (String ds : benchmarkDatasets) {
            File dir = new File(testDataRoot, ds);
            if (!dir.isDirectory()) continue;
            File[] files = FitsInput.list(dir);
            FrameCalibrationInspector.InspectionResult result = FrameCalibrationInspector.inspect(files);
            assertNotNull(result);
            assertFalse(ds + " must NOT be flagged as uncalibrated (hot pixels: " + result.hotPixelCount() + ", "
                    + String.format(Locale.ROOT, "%.1f", result.hotPixelsPerMegapixel()) + "/MP)", result.isUncalibrated());
            assertFalse(ds + " must NOT be flagged as misaligned (shift: "
                    + String.format(Locale.ROOT, "%.1f", result.starShiftPixels()) + " px)", result.isMisaligned());
            assertFalse(ds + " must not trigger any quality warning", result.hasWarning());
        }
    }
}
