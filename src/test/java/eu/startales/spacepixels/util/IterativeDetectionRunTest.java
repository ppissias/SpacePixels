package eu.startales.spacepixels.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.reporting.DetectionReportGenerator;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.*;

public class IterativeDetectionRunTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void freezesSettingsAndUsesOnlyEachPlannedSubset() throws Exception {
        File source = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "source", 9).toFile();
        DetectionConfig config = SyntheticDatasetFactory.createSyntheticDetectionConfig();
        config.enableVariableStarDetection = true;
        AppConfig application = new AppConfig();
        application.siteLat = "37";
        IterativeDetectionRequest request = request(source, config, application, new IterativeDetectionPlan(5, 3, 8));
        config.enableVariableStarDetection = false;
        application.siteLat = "wrong";
        request.config().enableVariableStarDetection = false;
        request.appConfig().siteLat = "also wrong";
        List<Integer> sizes = new ArrayList<>();
        List<String> progressMessages = new ArrayList<>();
        IterativeDetectionPipelineService service = new IterativeDetectionPipelineService(null,
                (settings, files, app, prompt, progress, output, visualization) -> {
                    sizes.add(files.length);
                    assertTrue(settings.enableVariableStarDetection);
                    assertEquals("37", app.siteLat);
                    assertEquals(files.length, Arrays.stream(files).map(FitsFileInformation::getFileName).distinct().count());
                    settings.enableVariableStarDetection = false;
                    progress.onProgressUpdate(30, "running");
                    return completed(output);
                });
        File index = service.run(request, null, (percent, message) -> progressMessages.add(message));
        assertEquals(List.of(5, 8), sizes);
        assertTrue(progressMessages.contains("Pass 5 frames: running"));
        assertTrue(progressMessages.contains("Pass 8 frames: running"));
        JsonObject record = record(index);
        assertEquals("COMPLETED", record.get("outcome").getAsString());
        assertTrue(record.getAsJsonObject("config").get("enableVariableStarDetection").getAsBoolean());
        JsonArray passes = record.getAsJsonArray("passes");
        assertEquals(5, inputs(index, passes.get(0).getAsJsonObject()).size());
        assertEquals(8, inputs(index, passes.get(1).getAsJsonObject()).size());
        assertFalse(Files.readString(new File(index.getParentFile(), "run-data.json").toPath()).contains(source.getAbsolutePath()));
        assertFalse(Files.readString(index.toPath()).contains("wrong"));
    }

    @Test public void declinedAndFailedPassesAreRecordedAndLaterPassesStillRun() throws Exception {
        File source = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "outcomes", 9).toFile();
        IterativeDetectionPipelineService service = new IterativeDetectionPipelineService(null,
                (config, files, app, prompt, progress, output, visualization) -> {
                    if (files.length == 5) return new StandardDetectionPipelineService.RunResult(null, summary());
                    if (files.length == 7) throw new IOException("export <failed>");
                    return completed(output);
                });
        File index = service.run(request(source, new DetectionConfig(), new AppConfig(), new IterativeDetectionPlan(5, 2, 9)), null, null);
        JsonObject record = record(index);
        assertEquals("FINISHED_WITH_ISSUES", record.get("outcome").getAsString());
        JsonArray passes = record.getAsJsonArray("passes");
        assertEquals("REPORT_DECLINED", passes.get(0).getAsJsonObject().get("outcome").getAsString());
        assertEquals("FAILED", passes.get(1).getAsJsonObject().get("outcome").getAsString());
        assertEquals("COMPLETED", passes.get(2).getAsJsonObject().get("outcome").getAsString());
        assertTrue(passes.get(0).getAsJsonObject().get("report").isJsonNull());
        assertTrue(Files.readString(index.toPath()).contains("export &lt;failed&gt;"));
    }

    @Test public void cancellationRetainsCompletedReportsAndMarksRemainingPasses() throws Exception {
        File source = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "cancel", 9).toFile();
        IterativeDetectionPipelineService service = new IterativeDetectionPipelineService(null,
                (config, files, app, prompt, progress, output, visualization) -> {
                    if (files.length == 7) {
                        Files.createDirectories(output.toPath());
                        Files.writeString(new File(output, "partial.txt").toPath(), "partial");
                        throw new CancellationException();
                    }
                    return completed(output);
                });
        assertThrows(CancellationException.class, () -> service.run(
                request(source, new DetectionConfig(), new AppConfig(), new IterativeDetectionPlan(5, 2, 9)), null, null));
        JsonObject record = record(service.getIndexFile());
        assertEquals("CANCELLED", record.get("outcome").getAsString());
        JsonArray passes = record.getAsJsonArray("passes");
        assertEquals("COMPLETED", passes.get(0).getAsJsonObject().get("outcome").getAsString());
        assertEquals("CANCELLED", passes.get(1).getAsJsonObject().get("outcome").getAsString());
        assertEquals("NOT_RUN", passes.get(2).getAsJsonObject().get("outcome").getAsString());
        assertTrue(new File(service.getIndexFile().getParentFile(), "5_frames/detection_report.html").isFile());
        assertFalse(new File(service.getIndexFile().getParentFile(), "7_frames").exists());
    }

    @Test public void eachPassClearsTheLiveViewAndNamesItself() throws Exception {
        File source = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "live-passes", 9).toFile();
        List<RunPreview> previews = new ArrayList<>();
        RunPreviewSink sink = new RunPreviewSink() {
            @Override public boolean wantsPreview(boolean stageChange) { return true; }
            @Override public void preview(RunPreview preview) { previews.add(preview); }
        };
        IterativeDetectionPipelineService service = new IterativeDetectionPipelineService(null,
                (config, files, app, prompt, progress, output, visualization) -> completed(output));
        try (RunPreviewScope scope = RunPreviewScope.open(sink)) {
            service.run(request(source, new DetectionConfig(), new AppConfig(), new IterativeDetectionPlan(5, 3, 8)), null, null);
        }
        assertEquals(List.of("Pass 1 of 2 · 5 frames", "Pass 2 of 2 · 8 frames"),
                previews.stream().map(RunPreview::caption).toList());
        assertTrue("a pass does not show the previous pass's picture",
                previews.stream().allMatch(preview -> preview.image() == null && !preview.keepImage()));
    }

    @Test public void aCancelledRunWithNoFinishedPassRemovesTheCopyItConverted() throws Exception {
        File source = SyntheticDatasetFactory.createMono32FloatFitsSequence(folder.getRoot().toPath(), "float-cancel", 9).toFile();
        IterativeDetectionPipelineService service = new IterativeDetectionPipelineService(null,
                (config, files, app, prompt, progress, output, visualization) -> {
                    throw new CancellationException();
                });
        assertThrows(CancellationException.class, () -> service.run(
                request(source, new DetectionConfig(), new AppConfig(), new IterativeDetectionPlan(5, 2, 9)), null, null));
        assertNull(service.getIndexFile());
        assertEquals("only the original frames stay", 9, source.listFiles().length);
    }

    @Test public void aCancelledRunThatPublishedAnIndexKeepsTheCopyItLinks() throws Exception {
        File source = SyntheticDatasetFactory.createMono32FloatFitsSequence(folder.getRoot().toPath(), "float-keep", 9).toFile();
        IterativeDetectionPipelineService service = new IterativeDetectionPipelineService(null,
                (config, files, app, prompt, progress, output, visualization) -> {
                    if (files.length == 7) throw new CancellationException();
                    return completed(output);
                });
        assertThrows(CancellationException.class, () -> service.run(
                request(source, new DetectionConfig(), new AppConfig(), new IterativeDetectionPlan(5, 2, 9)), null, null));
        String prepared = record(service.getIndexFile()).get("preparedDirectory").getAsString();
        assertTrue("the index names the converted copy", prepared.startsWith("_spacepixels_prepared_mono16_"));
        assertTrue(new File(source, prepared).isDirectory());
    }

    @Test public void everyRealPassMatchesAnIndependentNormalRunOnThoseInputs() throws Exception {
        File source = SyntheticDatasetFactory.createSolvedMono16FitsSequence(folder.getRoot().toPath(), "parity", 9).toFile();
        addMovingTargetAndRejectedFrame(source);
        DetectionConfig config = SyntheticDatasetFactory.createSyntheticDetectionConfig();
        config.enableVariableStarDetection = true;
        config.strictExposureKinematics = false;
        config.enableCommonMotionRejection = false;
        config.enableCameraFixedRejection = false;
        config.starCountSigmaDeviation = 1.5;
        AppConfig app = new AppConfig();
        IterativeDetectionRequest request = request(source, config, app, new IterativeDetectionPlan(5, 3, 8));
        File index = new IterativeDetectionPipelineService().run(request, null, null);
        JsonArray passes = record(index).getAsJsonArray("passes");
        for (int passIndex = 0; passIndex < passes.size(); passIndex++) {
            JsonObject pass = passes.get(passIndex).getAsJsonObject();
            List<FitsFileInformation> subset = new ArrayList<>();
            try (ImageProcessing processing = ImageProcessing.getInstance(source)) {
                FitsFileInformation[] all = processing.getFitsfileInformationHeadless();
                for (var frame : inputs(index, pass)) {
                    String filename = frame.getAsJsonObject().get("filename").getAsString();
                    subset.add(Arrays.stream(all).filter(file -> file.getFileName().equals(filename)).findFirst().orElseThrow());
                }
            }
            File normal = folder.newFolder("normal" + passIndex);
            new StandardDetectionPipelineService(app, new eu.startales.spacepixels.session.SessionFrames())
                    .run(config.clone(), subset.toArray(FitsFileInformation[]::new), null, null, normal, request.visualization());
            File actual = new File(index.getParentFile(), pass.get("frameCount").getAsInt() + "_frames");
            JsonObject iterativeData = json(new File(actual, "report-data.json"));
            JsonObject normalData = json(new File(normal, "report-data.json"));
            assertEquals(subset.size(), iterativeData.get("frameCount").getAsInt());
            for (String key : List.of("findings", "rejectedFrames", "includedGroups")) {
                assertEquals(key, normalData.get(key), iterativeData.get(key));
            }
            assertEquals(json(new File(normal, "detection_config.json")), json(new File(actual, "detection_config.json")));
            assertTrue(json(new File(actual, "detection_config.json")).get("enableVariableStarDetection").getAsBoolean());
            assertFalse("The parity fixture must contain actual findings.", iterativeData.getAsJsonArray("findings").isEmpty());
            if (subset.size() == 5) assertFalse(iterativeData.getAsJsonArray("rejectedFrames").isEmpty());
            assertEquals(subset.size(), inputs(index, pass).size());
            try (var normalFiles = Files.list(normal.toPath())) {
                for (var csv : normalFiles.filter(path -> path.getFileName().toString().endsWith(".csv")).toList()) {
                    assertEquals(csv.getFileName().toString(), Files.readString(csv), Files.readString(actual.toPath().resolve(csv.getFileName())));
                }
            }
        }
    }

    @Test public void autoTuneRunsOnceAndItsSettingsAreFrozenAcrossPasses() throws Exception {
        File source = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "tune", 9).toFile();
        DetectionConfig config = SyntheticDatasetFactory.createSyntheticDetectionConfig();
        AppConfig app = new AppConfig();
        IterativeDetectionRequest request = new IterativeDetectionRequest(source, config, app,
                DetectionReportGenerator.captureExportSettings(app.variableStarReportOptions), new IterativeDetectionPlan(5, 3, 8),
                io.github.ppissias.jtransient.engine.AutoTuneProfile.LOW, 5, true, true, 9);
        List<String> passSettings = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger tuningCalls = new java.util.concurrent.atomic.AtomicInteger();
        IterativeDetectionPipelineService service = new IterativeDetectionPipelineService(null,
                (settings, files, application, prompt, progress, output, visualization) -> {
                    passSettings.add(new com.google.gson.Gson().toJson(settings));
                    assertFalse(messages.isEmpty());
                    progress.onProgressUpdate(10, "run");
                    return completed(output);
                }, (files, settings, limit, profile, progress) -> {
                    tuningCalls.incrementAndGet();
                    assertEquals(5, limit);
                    progress.onProgressUpdate(20, "Auto-Tune: calibrating");
                    settings.detectionSigmaMultiplier = 3.3;
                    return new IterativeDetectionPipelineService.Tuned(settings, "Tuned once");
                });
        File index = service.run(request, null, (percent, message) -> messages.add(message));
        assertEquals(1, tuningCalls.get());
        assertEquals(2, passSettings.size());
        assertEquals(passSettings.get(0), passSettings.get(1));
        int firstPass = -1;
        for (int indexOfMessage = 0; indexOfMessage < messages.size(); indexOfMessage++) {
            if (messages.get(indexOfMessage).startsWith("Pass ")) { firstPass = indexOfMessage; break; }
        }
        assertTrue(firstPass > 0);
        assertTrue(messages.subList(firstPass, messages.size()).stream().noneMatch(message -> message.startsWith("Auto-Tune:")));
        assertEquals("LOW", record(index).get("autoTuneProfile").getAsString());
        assertEquals(5, record(index).get("autoTuneSampleLimit").getAsInt());
        assertEquals(JsonParser.parseString(passSettings.getFirst()), record(index).get("config"));
        assertTrue(new File(index.getParentFile(), "auto_tune.txt").isFile());
    }

    @Test public void changedInputCountAndStrictPreparationFailBeforeAnyPass() throws Exception {
        File source = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "changed", 8).toFile();
        IterativeDetectionPipelineService service = new IterativeDetectionPipelineService();
        IOException changed = assertThrows(IOException.class, () -> service.run(
                request(source, new DetectionConfig(), new AppConfig(), new IterativeDetectionPlan(5, 3, 8)), null, null));
        assertTrue(changed.getMessage().contains("frame count changed"));
        assertNull(service.getIndexFile());
        File floating = SyntheticDatasetFactory.createMono32FloatFitsSequence(folder.getRoot().toPath(), "strict", 5).toFile();
        AppConfig app = new AppConfig();
        IterativeDetectionRequest strict = new IterativeDetectionRequest(floating, new DetectionConfig(), app,
                DetectionReportGenerator.captureExportSettings(app.variableStarReportOptions), new IterativeDetectionPlan(5, 5, 5),
                null, 5, false, true, 5);
        Exception refusal = assertThrows(Exception.class, () -> service.run(strict, null, null));
        assertTrue(refusal.getMessage(), refusal.getMessage().contains("BITPIX=-32"));
        assertEquals(5, floating.listFiles().length);
    }

    static void addMovingTargetAndRejectedFrame(File source) throws Exception {
        File[] frames = FitsFiles.list(source);
        int[][] kernel = {{0, 1, 2, 1, 0}, {1, 3, 5, 3, 1}, {2, 5, 9, 5, 2}, {1, 3, 5, 3, 1}, {0, 1, 2, 1, 0}};
        for (int frameIndex = 0; frameIndex < frames.length; frameIndex++) {
            nom.tam.fits.Fits converted;
            try (nom.tam.fits.Fits original = new nom.tam.fits.Fits(frames[frameIndex])) {
                var image = ImageProcessing.getImageHDU(original);
                short[][] pixels = (short[][]) image.getKernel();
                if (frameIndex == 4) for (short[] row : pixels) Arrays.fill(row, (short) 640);
                int center = 15 + frameIndex * 6;
                for (int row = 0; row < kernel.length; row++) {
                    for (int column = 0; column < kernel[row].length; column++) {
                        pixels[55 + row - 2][center + column - 2] += (short) (1200 * kernel[row][column]);
                    }
                }
                converted = FitsPixelConverter.createFitsFromData(pixels, image.getHeader());
            }
            try (converted) { StagedFile.write(frames[frameIndex], staged -> converted.write(staged.toFile())); }
        }
    }

    private IterativeDetectionRequest request(File source, DetectionConfig config, AppConfig app, IterativeDetectionPlan plan) {
        return new IterativeDetectionRequest(source, config, app,
                DetectionReportGenerator.captureExportSettings(app.variableStarReportOptions), plan, null, 30, true, true, 9);
    }

    private static StandardDetectionPipelineService.RunResult completed(File output) throws IOException {
        Files.createDirectories(output.toPath());
        File report = new File(output, "detection_report.html");
        Files.writeString(report.toPath(), "complete");
        return new StandardDetectionPipelineService.RunResult(report, summary());
    }

    private static ImageProcessing.DetectionSummary summary() {
        return new ImageProcessing.DetectionSummary(0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0);
    }

    private static JsonObject record(File index) throws IOException { return json(new File(index.getParentFile(), "run-data.json")); }
    private static JsonArray inputs(File index, JsonObject pass) throws IOException {
        return JsonParser.parseString(Files.readString(new File(index.getParentFile(), pass.get("inputManifest").getAsString()).toPath())).getAsJsonArray();
    }
    private static JsonObject json(File file) throws IOException { return JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject(); }
}
