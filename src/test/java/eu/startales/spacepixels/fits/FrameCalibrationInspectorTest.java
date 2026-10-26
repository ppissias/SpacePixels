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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import nom.tam.fits.Fits;
import io.github.ppissias.jtransient.execution.CancellationScope;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Checks sample quality heuristics; real data is opt-in through spacepixels.testdata.root. */
public class FrameCalibrationInspectorTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /** Tiny but valid images lack alignment evidence; they must remain accepted by preparation/import. */
    @Test public void tinyImagesDoNotInventAlignmentEvidenceOrReadOutsideTheirBounds() throws Exception {
        for (int size : new int[]{1, 2, 5}) {
            File frame = new File(folder.getRoot(), "tiny-" + size + ".fit");
            float[][] pixels = new float[size][size];
            pixels[0][0] = 1;
            writeImage(frame, pixels);
            assertFalse(FrameCalibrationInspector.inspect(new File[]{frame, frame}).hasWarning());
        }
    }

    /** A clean synthetic sequence produces no quality warning. */
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

    /** Numerous isolated sensor spikes produce a calibration warning. */
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

    /** A four-pixel star translation produces an alignment warning. */
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

    /** Floating storage must retain its noise scale, including a colour cube's first plane. */
    @Test public void normalizedFloatingPointFramesRetainTheirAlignmentShift() throws Exception {
        File[] input = FitsInput.list(SyntheticDatasetFactory.createMisalignedMono16FitsSequence(
                folder.getRoot().toPath(), "float-shift", 3, 4, 0).toFile());
        for (double gain : new double[]{1.0 / 65535, 10.0}) for (boolean colour : new boolean[]{false, true}) {
            File[] converted = new File[input.length];
            for (int i = 0; i < input.length; i++) {
                float[][] pixels;
                try (Fits fits = new Fits(input[i])) {
                    var hdu = FitsInput.getImageHDU(fits);
                    short[][] original = (short[][]) hdu.getKernel();
                    pixels = new float[original.length][original[0].length];
                    for (int y = 0; y < pixels.length; y++) for (int x = 0; x < pixels[y].length; x++) {
                        pixels[y][x] = (float) ((original[y][x] * hdu.getHeader().getDoubleValue("BSCALE", 1)
                                + hdu.getHeader().getDoubleValue("BZERO", 0)) * gain);
                    }
                }
                converted[i] = new File(folder.getRoot(), "floating-" + gain + "-" + colour + "-" + i + ".fit");
                writeImage(converted[i], colour ? new float[][][]{pixels, pixels, pixels} : pixels);
            }
            var result = FrameCalibrationInspector.inspect(converted);
            assertTrue("A four-pixel translation is measured in normalized storage", result.isMisaligned());
            assertEquals(4.0, result.starShiftPixels(), 0.15);
        }
    }

    /** Cloud attenuation changes brightness, not star positions; vanished stars provide no displacement evidence. */
    @Test public void fadingOrMissingStarsDoNotInventMisalignment() throws Exception {
        File[] files = FitsInput.list(SyntheticDatasetFactory.createMono16FitsSequence(
                folder.getRoot().toPath(), "fading", 3).toFile());
        for (double attenuation : new double[]{0.1, 0.0}) {
            File[] changed = files.clone();
            for (int i = 1; i < files.length; i++) {
                short[][] dimmed;
                try (Fits fits = new Fits(files[i])) {
                    short[][] original = (short[][]) FitsInput.getImageHDU(fits).getKernel();
                    dimmed = new short[original.length][original[0].length];
                    for (int y = 0; y < dimmed.length; y++) for (int x = 0; x < dimmed[y].length; x++) {
                        dimmed[y][x] = (short) (640 + attenuation * (original[y][x] - 640));
                    }
                }
                changed[i] = new File(folder.getRoot(), "dimmed-" + attenuation + "-" + i + ".fit");
                writeImage(changed[i], dimmed);
            }
            var result = FrameCalibrationInspector.inspect(changed);
            assertFalse("Brightness changes do not demonstrate bad registration", result.isMisaligned());
            assertEquals(0.0, result.starShiftPixels(), 0.15);
        }
    }

    /** Matching the star pattern measures shifts beyond the retired eight-pixel search window. */
    @Test public void largerTranslationsAreMeasuredFromCoherentStars() throws Exception {
        File[] files = FitsInput.list(SyntheticDatasetFactory.createMisalignedMono16FitsSequence(
                folder.getRoot().toPath(), "large-shift", 3, 15, 0).toFile());
        var result = FrameCalibrationInspector.inspect(files);
        assertTrue(result.isMisaligned());
        assertEquals(15.0, result.starShiftPixels(), 0.15);
    }

    /** A cancellation request is observed during pixel traversal, without swallowing the cancellation signal. */
    @Test public void cancellationInterruptsAnInspectionInProgress() throws Exception {
        File[] files = FitsInput.list(SyntheticDatasetFactory.createMono16FitsSequence(
                folder.getRoot().toPath(), "cancelled", 3).toFile());
        AtomicInteger checks = new AtomicInteger();
        try (var scope = CancellationScope.open(() -> checks.incrementAndGet() > 5)) {
            assertThrows(CancellationException.class, () -> FrameCalibrationInspector.inspect(files));
        }
        assertTrue(checks.get() > 5);
    }

    /** An unreadable sample is a failed check, never a clean calibration verdict. */
    @Test public void missingSamplesFailRatherThanReportClean() {
        var failure = assertThrows(IllegalStateException.class,
                () -> FrameCalibrationInspector.inspect(new File[]{new File(folder.getRoot(), "missing.fit")}));
        assertTrue(failure.getMessage().contains("inspection failed"));
    }

    /** Writes only disposable test images, preserving the synthetic unsigned-short convention. */
    private static void writeImage(File file, Object pixels) throws Exception {
        try (Fits fits = new Fits()) {
            var hdu = Fits.makeHDU(pixels);
            if (pixels instanceof short[][]) hdu.getHeader().addValue("BZERO", 32768, null);
            fits.addHDU(hdu);
            fits.write(file);
        }
    }

    /** Opt-in positive benchmark must warn about uncalibrated sensor defects. */
    @Test
    public void realBenchmarkDatasetWhynotTriggersUncalibratedWarning() throws Exception {
        String testDataRoot = System.getProperty("spacepixels.testdata.root");
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

    /** Opt-in main calibrated benchmark must stay unflagged. */
    @Test
    public void realBenchmarkDatasetKlangwolke2HasNoWarnings() throws Exception {
        String testDataRoot = System.getProperty("spacepixels.testdata.root");
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

    /** Opt-in reference coverage includes every other required clean dataset. */
    @Test
    public void allCalibratedBenchmarkDatasetsStayUnflagged() throws Exception {
        String testDataRoot = System.getProperty("spacepixels.testdata.root");
        Assume.assumeTrue("Test data root is not available", testDataRoot != null && Files.isDirectory(Path.of(testDataRoot)));

        String[] benchmarkDatasets = {
                "klangwolke-1",
                "dc-reg",
                "m101-reg",
                "ic-5070-reg",
                "m31-b-reg",
                "m31-g-reg",
                "m31-red-reg",
                "m5-reg",
                "m81-reg",
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
            assertTrue("Required reference dataset is missing: " + ds, dir.isDirectory());
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
