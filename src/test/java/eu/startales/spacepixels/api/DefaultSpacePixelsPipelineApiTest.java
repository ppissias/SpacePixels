package eu.startales.spacepixels.api;

import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import eu.startales.spacepixels.fits.FitsFileInformation;

public class DefaultSpacePixelsPipelineApiTest {

    private static final int FRAME_COUNT = 5;

    /** API conversion never opens a GUI: defaults reject extremes and explicit policies cover both FITS and XISF. */
    @Test public void outlierPolicyIsOptionalAndExplicitForBothInputFormats() throws Exception {
        var defaultRequest = SpacePixelsPipelineRequest.builder(new java.io.File(".")).build();
        assertEquals(eu.startales.spacepixels.fits.ImageScaling.OutlierHandling.REQUIRE_DECISION, defaultRequest.getOutlierHandling());
        for (boolean xisf : new boolean[]{false, true}) {
            Path root = Files.createTempDirectory("spacepixels-api-outliers");
            Path sequence = xisf ? SyntheticDatasetFactory.createGrayFloatXisfSequence(root, "source", FRAME_COUNT)
                    : SyntheticDatasetFactory.createMono32FloatFitsSequence(root, "source", FRAME_COUNT);
            Path changed;
            try (var files = Files.list(sequence)) { changed = files.filter(Files::isRegularFile).sorted().findFirst().orElseThrow(); }
            if (xisf) {
                byte[] bytes = Files.readAllBytes(changed);
                int length = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(8);
                String xml = new String(bytes, 16, length, java.nio.charset.StandardCharsets.UTF_8);
                var location = java.util.regex.Pattern.compile("location=\"attachment:(\\d+):").matcher(xml);
                assertTrue(location.find());
                try (var file = new java.io.RandomAccessFile(changed.toFile(), "rw")) {
                    file.seek(Long.parseLong(location.group(1)));
                    file.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(10.5f).array());
                }
            } else {
                Object pixels; nom.tam.fits.Header header;
                try (var fits = new nom.tam.fits.Fits(changed.toFile())) {
                    var image = eu.startales.spacepixels.fits.FitsInput.getImageHDU(fits); pixels = image.getKernel(); header = image.getHeader();
                }
                ((float[][]) pixels)[0][0] = 10.5f;
                try (var fits = new nom.tam.fits.Fits()) {
                    fits.addHDU(new nom.tam.fits.ImageHDU(header, new nom.tam.fits.ImageData(pixels)));
                    Files.delete(changed); fits.write(changed.toFile());
                }
            }
            byte[] sourceBytes = Files.readAllBytes(changed);
            var api = new DefaultSpacePixelsPipelineApi();
            var refused = SpacePixelsPipelineRequest.builder(sequence.toFile())
                    .inputPreparationMode(InputPreparationMode.AUTO_PREPARE_TO_16BIT_MONO).generateReport(false).build();
            var rejected = org.junit.Assert.assertThrows(SpacePixelsPipelineException.class, () -> api.run(refused));
            assertTrue(rejected.getCause() instanceof eu.startales.spacepixels.fits.ImageScaling.OutlierException);
            var failure = (eu.startales.spacepixels.fits.ImageScaling.OutlierException) rejected.getCause();
            assertTrue(failure.summary().above() > 0);
            assertEquals(FRAME_COUNT, failure.summary().frames().size());
            try (var entries = Files.list(sequence)) { assertFalse(entries.anyMatch(Files::isDirectory)); }
            Path previousOutput = null; byte[] previousBytes = null;
            for (var policy : new eu.startales.spacepixels.fits.ImageScaling.OutlierHandling[]{
                    eu.startales.spacepixels.fits.ImageScaling.OutlierHandling.CLIP_EXTREMES,
                    eu.startales.spacepixels.fits.ImageScaling.OutlierHandling.PRESERVE_ALL}) {
                var result = api.run(SpacePixelsPipelineRequest.builder(sequence.toFile())
                        .detectionConfig(SyntheticDatasetFactory.createSyntheticDetectionConfig())
                        .inputPreparationMode(InputPreparationMode.AUTO_PREPARE_TO_16BIT_MONO)
                        .outlierHandling(policy).generateReport(false).build());
                assertTrue(result.isInputWasPrepared()); assertEquals(FRAME_COUNT, result.getFilesInformation().length);
                Path currentOutput = Path.of(result.getFilesInformation()[0].getFilePath());
                if (previousOutput != null) {
                    assertNotEquals(previousOutput, currentOutput);
                    org.junit.Assert.assertArrayEquals(previousBytes, Files.readAllBytes(previousOutput));
                }
                previousOutput = currentOutput; previousBytes = Files.readAllBytes(currentOutput);
                try (var fits = new nom.tam.fits.Fits(result.getFilesInformation()[0].getFilePath())) {
                    var image = eu.startales.spacepixels.fits.FitsInput.getImageHDU(fits);
                    assertEquals(policy == eu.startales.spacepixels.fits.ImageScaling.OutlierHandling.CLIP_EXTREMES,
                            image.getHeader().getBooleanValue("SPPCLIP"));
                }
            }
            org.junit.Assert.assertArrayEquals(sourceBytes, Files.readAllBytes(changed));
        }
    }

    @Test
    public void runAutoPreparesColor32FitsAndReturnsRawPipelineResult() throws Exception {
        Path tempRoot = Files.createTempDirectory("spacepixels-api-color32");
        Path sequenceDirectory = SyntheticDatasetFactory.createColor32FloatFitsSequence(tempRoot, "color32", FRAME_COUNT);
        DetectionConfig config = SyntheticDatasetFactory.createSyntheticDetectionConfig();

        SpacePixelsPipelineApi api = new DefaultSpacePixelsPipelineApi();
        SpacePixelsPipelineResult result = api.run(
                SpacePixelsPipelineRequest.builder(sequenceDirectory.toFile())
                        .detectionConfig(config)
                        .inputPreparationMode(InputPreparationMode.AUTO_PREPARE_TO_16BIT_MONO)
                        .generateReport(false)
                        .build());

        assertTrue(result.isInputWasPrepared());
        assertNotEquals(result.getOriginalInputDirectory().getCanonicalPath(), result.getPreparedInputDirectory().getCanonicalPath());
        assertNotNull(result.getPipelineResult());
        assertEquals(FRAME_COUNT, result.getFilesInformation().length);
        assertNull(result.getExportDirectory());
        assertNull(result.getReportFile());
        for (eu.startales.spacepixels.fits.FitsFileInformation fileInformation : result.getFilesInformation()) {
            assertTrue(fileInformation.isMonochrome());
        }
    }

    @Test
    public void runGeneratesReportForReadyMono16Fits() throws Exception {
        Path tempRoot = Files.createTempDirectory("spacepixels-api-mono16");
        Path sequenceDirectory = SyntheticDatasetFactory.createMono16FitsSequence(tempRoot, "mono16", FRAME_COUNT);
        DetectionConfig config = SyntheticDatasetFactory.createSyntheticDetectionConfig();

        SpacePixelsPipelineApi api = new DefaultSpacePixelsPipelineApi();
        SpacePixelsPipelineResult result = api.run(
                SpacePixelsPipelineRequest.builder(sequenceDirectory.toFile())
                        .detectionConfig(config)
                        .inputPreparationMode(InputPreparationMode.FAIL_IF_NOT_READY)
                        .generateReport(true)
                        .build());

        assertFalse(result.isInputWasPrepared());
        assertEquals(result.getOriginalInputDirectory().getCanonicalPath(), result.getPreparedInputDirectory().getCanonicalPath());
        assertNotNull(result.getPipelineResult());
        assertNotNull(result.getExportDirectory());
        assertNotNull(result.getReportFile());
        assertTrue(result.getExportDirectory().isDirectory());
        assertTrue(result.getReportFile().isFile());
    }

    /** With two frames the engine stops after quality control, and the report says so instead of failing. */
    @Test
    public void reportExplainsARunWithTooFewFrames() throws Exception {
        Path tempRoot = Files.createTempDirectory("spacepixels-api-two-frames");
        Path sequenceDirectory = SyntheticDatasetFactory.createMono16FitsSequence(tempRoot, "two-frames", 2);

        SpacePixelsPipelineResult result = new DefaultSpacePixelsPipelineApi().run(
                SpacePixelsPipelineRequest.builder(sequenceDirectory.toFile())
                        .detectionConfig(SyntheticDatasetFactory.createSyntheticDetectionConfig())
                        .inputPreparationMode(InputPreparationMode.FAIL_IF_NOT_READY)
                        .generateReport(true)
                        .build());

        assertTrue(result.getPipelineResult().tooFewFrames);
        String report = Files.readString(result.getReportFile().toPath());
        assertTrue(report.contains("Too Few Frames After Quality Control"));
        assertTrue(report.contains("Only <strong>2</strong> frames remained after quality control."));
    }

    @Test
    public void uncalibratedDatasetProducesCalibrationWarning() throws Exception {
        Path tempRoot = Files.createTempDirectory("spacepixels-api-uncalibrated-warn");
        Path sequenceDirectory = SyntheticDatasetFactory.createUncalibratedMono16FitsSequence(tempRoot, "uncal", FRAME_COUNT, 50);

        SpacePixelsPipelineResult result = new DefaultSpacePixelsPipelineApi().run(
                SpacePixelsPipelineRequest.builder(sequenceDirectory.toFile())
                        .detectionConfig(SyntheticDatasetFactory.createSyntheticDetectionConfig())
                        .inputPreparationMode(InputPreparationMode.FAIL_IF_NOT_READY)
                        .generateReport(false)
                        .failIfUncalibratedOrMisaligned(false)
                        .build());

        assertTrue(result.hasCalibrationWarning());
        assertNotNull(result.getCalibrationWarning());
        assertTrue(result.getCalibrationWarning().contains("hot-pixel density"));
    }

    /** Strict policy rejects the quality warning itself, before detection or report output. */
    @Test
    public void uncalibratedDatasetFailsWhenConfigured() throws Exception {
        Path tempRoot = Files.createTempDirectory("spacepixels-api-uncalibrated-fail");
        Path sequenceDirectory = SyntheticDatasetFactory.createUncalibratedMono16FitsSequence(tempRoot, "uncal", FRAME_COUNT, 50);

        var failure = org.junit.Assert.assertThrows(SpacePixelsPipelineException.class, () -> new DefaultSpacePixelsPipelineApi().run(
                SpacePixelsPipelineRequest.builder(sequenceDirectory.toFile())
                        .detectionConfig(SyntheticDatasetFactory.createSyntheticDetectionConfig())
                        .inputPreparationMode(InputPreparationMode.FAIL_IF_NOT_READY)
                        .generateReport(false)
                        .failIfUncalibratedOrMisaligned(true)
                        .build()));
        assertTrue(failure.getMessage(), failure.getMessage().startsWith("Pipeline rejected dataset:"));
        assertTrue(failure.getMessage(), failure.getMessage().contains("hot-pixel density"));
        assertEquals(0, sequenceDirectory.toFile().listFiles(file -> file.isDirectory()
                && file.getName().startsWith("detections_")).length);
    }
}
