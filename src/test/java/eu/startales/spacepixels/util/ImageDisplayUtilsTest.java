package eu.startales.spacepixels.util;

import eu.startales.spacepixels.config.AppConfig;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverAnalysis;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImageDisplayUtilsTest {

    @Test
    public void exportTrackVisualizationsHandlesMissingTrackerTelemetryAfterQualitySuppression() throws Exception {
        Path exportDir = Files.createTempDirectory("spacepixels-report");
        try {
            PipelineTelemetry telemetry = new PipelineTelemetry();
            telemetry.totalFramesLoaded = 5;
            telemetry.totalFramesKept = 2;
            telemetry.totalFramesRejected = 3;

            PipelineResult result = new PipelineResult(
                    Collections.emptyList(),
                    telemetry,
                    null,
                    Collections.emptyList(),
                    SlowMoverAnalysis.empty(),
                    null,
                    null,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    ResidualTransientAnalysis.empty(),
                    null,
                    Collections.emptyList(),
                    null);

            ImageDisplayUtils.exportTrackVisualizations(
                    result,
                    Collections.emptyList(),
                    new FitsFileInformation[0],
                    exportDir.toFile(),
                    new DetectionConfig(),
                    new AppConfig());

            Path reportPath = exportDir.resolve("detection_report.html");
            assertTrue(Files.exists(reportPath));

            String reportHtml = Files.readString(reportPath, StandardCharsets.UTF_8);
            assertTrue(reportHtml.contains("Stationary-star purification telemetry was not available for this run"));
            assertFalse(reportHtml.contains("href='master_stack.png'"));
            assertFalse(reportHtml.contains("href='master_mask_overlay.png'"));
        } finally {
            if (Files.exists(exportDir)) {
                Files.walk(exportDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                                // Best-effort cleanup for temporary test exports.
                            }
                        });
            }
        }
    }
}
