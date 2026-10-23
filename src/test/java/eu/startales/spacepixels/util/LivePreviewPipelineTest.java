package eu.startales.spacepixels.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.session.SessionFrames;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.reporting.DetectionReportGenerator;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A detection run shows its loading pictures, what the engine has found so far and the report pictures, and the live
 * view does not change the results.
 */
public class LivePreviewPipelineTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Wants every picture, as a live view with no throttle would; the live findings arrive from their own worker. */
    private static final class EverySink implements RunPreviewSink {
        final List<RunPreview> received = new CopyOnWriteArrayList<>();

        @Override
        public boolean wantsPreview(boolean stageChange) {
            return true;
        }

        @Override
        public void preview(RunPreview preview) {
            received.add(preview);
        }
    }

    @Test
    public void aRunShowsItsStagesAndTheResultsAreTheSameWithAndWithoutTheLiveView() throws Exception {
        File source = SyntheticDatasetFactory.createSolvedMono16FitsSequence(folder.getRoot().toPath(), "live", 9).toFile();
        IterativeDetectionRunTest.addMovingTargetAndRejectedFrame(source);
        DetectionConfig config = SyntheticDatasetFactory.createSyntheticDetectionConfig();
        config.strictExposureKinematics = false;
        config.enableCommonMotionRejection = false;
        config.enableCameraFixedRejection = false;
        config.starCountSigmaDeviation = 1.5;
        AppConfig app = new AppConfig();
        FitsFileInformation[] files;
        try (ImageProcessing processing = ImageProcessing.getInstance(source)) {
            files = processing.getFitsfileInformationHeadless();
        }

        File plain = folder.newFolder("without");
        new StandardDetectionPipelineService(app, new SessionFrames()).run(config.clone(), files, null, null, plain,
                DetectionReportGenerator.captureExportSettings(app.variableStarReportOptions));

        EverySink sink = new EverySink();
        File withPreview = folder.newFolder("with");
        try (RunPreviewScope scope = RunPreviewScope.open(sink)) {
            new StandardDetectionPipelineService(app, new SessionFrames()).run(config.clone(), files, null, null, withPreview,
                    DetectionReportGenerator.captureExportSettings(app.variableStarReportOptions));
        }

        List<String> captions = sink.received.stream().map(RunPreview::caption).toList();
        assertTrue(captions.toString(), captions.get(0).startsWith("Frame 1 of 9"));
        assertTrue(captions.toString(), captions.contains("Frame 9 of 9") || captions.stream().anyMatch(c -> c.startsWith("Frame 9 of 9")));
        int running = captions.indexOf("Picture from loading · the detection is running");
        assertTrue("the engine stage keeps the last frame, marked", running > 0 && sink.received.get(running).keepImage());
        assertNull(sink.received.get(running).image());
        int firstReport = captions.size();
        for (int index = 0; index < captions.size(); index++) {
            if (captions.get(index).startsWith("Report picture: ")) {
                firstReport = Math.min(firstReport, index);
            }
        }
        assertTrue(captions.toString(), firstReport < captions.size());
        // The engine's stages hand their pictures to a worker, which finishes them before the report starts.
        assertTrue(captions.toString(), captions.subList(running + 1, firstReport).stream()
                .anyMatch(caption -> caption.startsWith("Stationary-sky reference: the median of the kept frames")));
        for (int index = running + 1; index < firstReport; index++) {
            String caption = captions.get(index);
            assertTrue(caption, caption.startsWith("Stationary-sky reference: the median of the kept frames")
                    || caption.startsWith("Star mask: ") || caption.contains("provisional") || caption.startsWith("No "));
        }
        for (String caption : captions.subList(firstReport, captions.size())) {
            assertTrue("no live finding after the report started: " + caption, caption.startsWith("Report picture: "));
            assertFalse("shape and mask diagrams are skipped", caption.contains("shape") || caption.contains("mask"));
        }
        for (RunPreview preview : sink.received) {
            for (RunPreview picture : preview.slides().isEmpty() ? List.of(preview) : preview.slides()) {
                if (picture.image() != null) {
                    assertTrue(picture.caption(), Math.max(picture.image().getWidth(), picture.image().getHeight()) <= PreviewThumbnails.LONG_SIDE);
                }
            }
        }

        JsonObject plainData = json(new File(plain, "report-data.json"));
        JsonObject previewData = json(new File(withPreview, "report-data.json"));
        assertFalse("the fixture must contain findings", plainData.getAsJsonArray("findings").isEmpty());
        for (String key : List.of("findings", "rejectedFrames", "includedGroups", "frameCount")) {
            assertEquals(key, plainData.get(key), previewData.get(key));
        }
        assertEquals(json(new File(plain, "detection_config.json")), json(new File(withPreview, "detection_config.json")));
        try (Stream<Path> csvs = Files.list(plain.toPath())) {
            for (Path csv : csvs.filter(path -> path.getFileName().toString().endsWith(".csv")).toList()) {
                assertEquals(csv.getFileName().toString(), Files.readString(csv),
                        Files.readString(withPreview.toPath().resolve(csv.getFileName())));
            }
        }
    }

    private static JsonObject json(File file) throws Exception {
        return JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject();
    }
}
