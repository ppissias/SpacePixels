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

public class DefaultSpacePixelsPipelineApiTest {

    private static final int FRAME_COUNT = 5;

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
        for (eu.startales.spacepixels.util.FitsFileInformation fileInformation : result.getFilesInformation()) {
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
}
