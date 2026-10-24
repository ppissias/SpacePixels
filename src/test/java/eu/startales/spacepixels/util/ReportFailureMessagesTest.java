package eu.startales.spacepixels.util;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.DetectionRunOptions;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.config.VisualizationSettings;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * A report that cannot be written is reported as a failure with its reason, never as a report the user declined; an
 * iterative pass without a report says so on the index page instead of linking to a missing report.
 */
public class ReportFailureMessagesTest {

    private static final int FRAME_COUNT = 5;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aReportThatCannotBeWrittenFailsWithItsReason() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "mono16", FRAME_COUNT);
        DetectionConfig config = SyntheticDatasetFactory.createSyntheticDetectionConfig();
        try (ImageProcessing processing = ImageProcessing.openProcessor(sequence.toFile())) {
            processing.getFitsfileInformationHeadless();
            // After the detection, before the report: the session folder becomes a file, so no report folder fits in it.
            IOException failure = assertThrows(IOException.class, () -> processing.detectObjects(config, summary -> {
                replaceFolderWithFile(sequence.toFile());
                return true;
            }, null, DetectionRunOptions.defaults(), null));
            assertTrue(failure.getMessage(), failure.getMessage().startsWith("The detection finished, but the report could not be written: "));
        }
    }

    @Test
    public void anIterativePassWithoutAReportSaysSoOnTheIndexPage() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "mono16", FRAME_COUNT);
        DetectionConfig config = SyntheticDatasetFactory.createSyntheticDetectionConfig();
        var request = new IterativeDetectionRequest(sequence.toFile(), config, new AppConfig(),
                VisualizationSettings.defaults(), new IterativeDetectionPlan(5, 5, 0), null, 5, false, false, 0, null);
        // Before the pass writes its report: a file takes the place of the pass's folder.
        File index = new IterativeDetectionPipelineService().run(request, (summary, files, settings) -> {
            File[] passes = sequence.toFile().listFiles(file -> file.getName().startsWith("iterative_detections_"));
            assertNotNull(passes);
            try {
                Files.writeString(new File(passes[0], FRAME_COUNT + "_frames").toPath(), "not a folder");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return true;
        }, null);
        String html = Files.readString(index.toPath(), StandardCharsets.UTF_8);
        assertTrue(html, html.contains("Failed") && html.contains("report could not be written"));
        assertFalse(html, html.contains(FRAME_COUNT + "_frames/detection_report.html"));
    }

    private static void replaceFolderWithFile(File directory) {
        try {
            for (File file : directory.listFiles()) {
                Files.delete(file.toPath());
            }
            Files.delete(directory.toPath());
            Files.writeString(directory.toPath(), "not a folder");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
