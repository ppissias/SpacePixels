package eu.startales.spacepixels.report;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import com.google.gson.*;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.*;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.photometry.*;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.Assert.*;
import eu.startales.spacepixels.fits.FitsFileInformation;

public class ReportExportConsistencyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void exportedIdsLinksAndInclusionAgreeWithoutChangingMeasurements() throws Exception {
        FitsFileInformation[] files = new FitsFileInformation[3];
        List<short[][]> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            files[i] = new FitsFileInformation(new File(temporary.getRoot(), "frame" + i + ".fit").toString(), "frame" + i + ".fit", true, 160, 120,
                    Map.of("DATE-OBS", "2026-10-01T00:0" + (2 * i) + ":00", "EXPTIME", "120", "FILTER", i == 0 ? "R" : "L"));
            short[][] pixels = new short[120][160];
            for (int y = 0; y < 120; y++) for (int x = 0; x < 160; x++) pixels[y][x] = (short) (-32000 + (x * y) % 100);
            frames.add(pixels);
        }
        var photo = new PipelineTelemetry.PhotometryTelemetry(); photo.verdict = "READY"; photo.highConfidence = 1;
        for (int i = 0; i < 3; i++) {
            var frame = new PipelineTelemetry.PhotometryFrameStat(); frame.frameIndex = i; frame.filename = files[i].getFileName();
            frame.julianDate = 2460000 + i / 720.0; frame.used = true; frame.fwhm = 3;
            photo.frames.add(frame);
        }
        StarLightCurve star = new Gson().fromJson("{\"id\":7,\"x\":40,\"y\":40,\"tier\":\"HIGH_CONFIDENCE\",\"amplitude\":0.6,\"scatterZ\":10,\"deltaMag\":[-0.3,0,0.3],\"flags\":[0,0,0],\"magError\":[0.01,0.01,0.01],\"failedGates\":[]}", StarLightCurve.class);
        var readiness = new PhotometricReadiness(); readiness.verdict = PhotometricReadiness.Verdict.READY;
        var analysis = new VariableStarAnalysis(readiness, List.of(star), photo);
        var telemetry = new PipelineTelemetry(); telemetry.totalFramesLoaded = 3; telemetry.totalFramesKept = 3; telemetry.photometryTelemetry = photo;
        var track = new Track();
        for (int i : List.of(0, 2)) {
            var point = new DetectedObject(60 + i * 10, 60, 1000, 10); point.sourceFrameIndex = i;
            point.timestamp = files[i].getObservationTimestamp(); point.exposureDuration = 120000; track.addPoint(point);
        }
        var result = PipelineResult.builder()
                .tracks(List.of(track))
                .telemetry(telemetry)
                .masterStackData(frames.get(0))
                .masterStars(List.of())
                .slowMoverCandidates(List.of())
                .anomalies(List.of())
                .allTransients(List.of())
                .unclassifiedTransients(List.of())
                .residualTransientAnalysis(ResidualTransientAnalysis.empty())
                .driftPoints(List.of())
                .maximumStackData(frames.get(0))
                .variableStarAnalysis(analysis)
                .build();
        var config = new DetectionConfig(); config.enableVariableStarDetection = true; config.enableSlowMoverDetection = false;
        String measurements = new GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(analysis);
        File included = temporary.newFolder("included");
        ReportExporter.export(new ReportRequest(result, frames, files, included, config, runContext(files), ReportInclusion.all(), null));
        String html = Files.readString(new File(included, "detection_report.html").toPath());
        assertTrue(html.contains("mixed filters")); assertTrue(html.contains("photometry-error-bar"));
        assertTrue(html.contains("loading='lazy'"));
        Set<String> ids = new HashSet<>();
        var matcher = Pattern.compile("(?<![\\w-])id=['\"]([^'\"]+)['\"]").matcher(html);
        while (matcher.find()) assertTrue("Duplicate id: " + matcher.group(1), ids.add(matcher.group(1)));
        matcher = Pattern.compile("href=['\"]#([^'\"]+)['\"]").matcher(html);
        while (matcher.find()) assertTrue("Missing target: " + matcher.group(1), ids.contains(matcher.group(1)));
        JsonObject data = JsonParser.parseString(Files.readString(new File(included, "report-data.json").toPath())).getAsJsonObject();
        for (var finding : data.getAsJsonArray("findings")) assertTrue(ids.contains(finding.getAsJsonObject().get("anchor").getAsString()));
        assertTrue(Files.readString(new File(included, "variable_stars/photometry_stars.csv").toPath()).contains(",candidate_label,ra_deg,dec_deg,vsx_matches,nearest_vsx_arcsec"));
        assertTrue(new File(included, "moving_targets/moving_track_1_star_centric.gif.poster.png").isFile());
        assertReportFilesResolve(included);
        File excluded = temporary.newFolder("excluded");
        ReportExporter.export(new ReportRequest(result, frames, files, excluded, config, runContext(files), new ReportInclusion(true, false, false, false, false, false, false, false), null));
        html = Files.readString(new File(excluded, "detection_report.html").toPath());
        assertFalse(html.contains("V1 (0.60 mag)")); assertFalse(html.contains("<section id='variables'>"));
        assertFalse(Files.readString(new File(excluded, "report-data.json").toPath()).contains("Variable-star candidate"));
        File variablesOnly = temporary.newFolder("variables-only");
        var noTracks = PipelineResult.builder()
                .tracks(List.of())
                .telemetry(telemetry)
                .masterStackData(frames.get(0))
                .masterStars(List.of())
                .slowMoverCandidates(List.of())
                .anomalies(List.of())
                .allTransients(List.of())
                .unclassifiedTransients(List.of())
                .residualTransientAnalysis(ResidualTransientAnalysis.empty())
                .driftPoints(List.of())
                .maximumStackData(frames.get(0))
                .variableStarAnalysis(analysis)
                .build();
        ReportExporter.export(new ReportRequest(noTracks, frames, files, variablesOnly, config, runContext(files), ReportInclusion.all(), null));
        html = Files.readString(new File(variablesOnly, "detection_report.html").toPath());
        assertTrue(html.contains("Map of All Detections"));
        assertTrue(html.contains("data-report-category='V'"));
        assertTrue(html.contains("href='#variable-v1'"));
        assertTrue(new File(variablesOnly, "field/global_track_map.png").isFile());
        assertEquals(measurements, new GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(analysis));
    }
    @Test public void localRescueAndCommonMotionCanBeOmittedIndependentlyWithoutGeneratingTheirAssets() throws Exception {
        FitsFileInformation[] files = new FitsFileInformation[3];
        List<short[][]> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            files[i] = new FitsFileInformation("frame" + i + ".fit", "frame" + i + ".fit", true, 160, 120,
                    Map.of("DATE-OBS", "2026-10-01T00:0" + (2 * i) + ":00", "EXPTIME", "120"));
            short[][] pixels = new short[120][160];
            for (int y = 0; y < 120; y++) for (int x = 0; x < 160; x++) pixels[y][x] = (short) (-32000 + (x * y) % 100);
            frames.add(pixels);
        }
        List<DetectedObject> rescued = new ArrayList<>(), commonA = new ArrayList<>(), commonB = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            for (var source : List.of(rescued, commonA, commonB)) {
                var point = new DetectedObject(source == rescued ? 70 + 2 * i : source == commonA ? 30 + i : 120 + i,
                        source == rescued ? 60 : source == commonA ? 30 : 90, 1000, 10);
                point.sourceFrameIndex = i; point.timestamp = files[i].getObservationTimestamp(); point.exposureDuration = 120000;
                source.add(point);
            }
        }
        var metrics = new ResidualTransientAnalysis.LocalTransientMetrics(3, 3, 3, 0, 4, 2, 2, 0, 2, 1000, 1, 72, 60);
        var candidate = new ResidualTransientAnalysis.LocalRescueCandidate(ResidualTransientAnalysis.LocalRescueKind.MICRO_DRIFT, rescued, metrics, 1);
        var group = new ResidualTransientAnalysis.CommonMotionGroup(List.of(commonA, commonB), 3);
        var residual = new ResidualTransientAnalysis(List.of(candidate), List.of(), List.of(group));
        var telemetry = new PipelineTelemetry(); telemetry.totalFramesLoaded = 3; telemetry.totalFramesKept = 3;
        var result = PipelineResult.builder()
                .tracks(List.of())
                .telemetry(telemetry)
                .masterStackData(frames.get(0))
                .masterStars(List.of())
                .slowMoverCandidates(List.of())
                .anomalies(List.of())
                .allTransients(List.of())
                .unclassifiedTransients(List.of())
                .residualTransientAnalysis(residual)
                .driftPoints(List.of())
                .maximumStackData(frames.get(0))
                .variableStarAnalysis(VariableStarAnalysis.empty())
                .build();
        var config = new DetectionConfig(); config.enableSlowMoverDetection = false;
        String original = new GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(residual);
        for (boolean rescue : List.of(false, true)) for (boolean motion : List.of(false, true)) {
            File export = temporary.newFolder("rescue-" + rescue + "-motion-" + motion);
            ReportExporter.export(new ReportRequest(result, frames, files, export, config, runContext(files),
                    new ReportInclusion(true, false, rescue, false, motion, false, false, false), null));
            String html = Files.readString(new File(export, "detection_report.html").toPath());
            String data = Files.readString(new File(export, "report-data.json").toPath());
            assertEquals(rescue, html.contains("id='finding-lr1'"));
            assertEquals(motion, html.contains("id='finding-cm1'"));
            assertEquals(rescue, data.contains("\"id\": \"LR1\""));
            assertEquals(motion, data.contains("\"id\": \"CM1\""));
            // Each category's pictures are in its own folder, which exists only when the category has pictures.
            var names = Arrays.asList(Objects.requireNonNull(export.list()));
            assertEquals(rescue, names.contains("local_rescue"));
            assertEquals(motion, names.contains("common_motion"));
            assertFalse(names.stream().anyMatch(n -> n.startsWith("micro_drift_") || n.startsWith("local_rescue_") || n.startsWith("common_motion_")));
            assertEquals(rescue, new File(export, "local_rescue/local_rescue_1_context.gif").isFile());
            assertEquals(motion, new File(export, "common_motion/common_motion_1.gif").isFile());
            // Common motion is folded under Likely Artifacts after the results; local rescue stays with the results.
            int artifacts = html.indexOf("<details class='report-group' id='likely-artifacts'>");
            assertEquals(motion, artifacts >= 0);
            assertEquals(motion, html.contains("href='#likely-artifacts'"));
            if (motion) {
                assertTrue(html.indexOf("id='finding-cm1'") > artifacts);
                assertTrue(hasColour(new File(export, "common_motion/common_motion_1_trail.png")));
            }
            if (rescue && motion) assertTrue(html.indexOf("id='finding-lr1'") < artifacts);
            assertReportFilesResolve(export);
        }
        assertEquals(original, new GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(residual));
    }

    /**
     * The report folder layout (F19): only the entry points and known folders at the top; every local link, picture and
     * frame strip in the HTML names an existing file, and every animation has its metadata, poster and frame sheets
     * beside it.
     */
    private static void assertReportFilesResolve(File report) throws IOException {
        Set<String> folders = new HashSet<>(List.of(ReportFiles.FIELD, ReportFiles.DIAGNOSTICS, EnhancedStackFile.FOLDER, "unclassified_frames"));
        for (FindingCategory category : FindingCategory.values()) folders.add(category.folder());
        for (File entry : Objects.requireNonNull(report.listFiles())) {
            if (entry.isDirectory()) assertTrue("Unknown folder " + entry.getName(), folders.contains(entry.getName()));
            else assertTrue("Unexpected top-level file " + entry.getName(),
                    List.of("detection_report.html", "report-data.json", "detection_config.json").contains(entry.getName()));
        }
        String html = Files.readString(new File(report, "detection_report.html").toPath());
        var matcher = Pattern.compile("(?:src|href|data-strip)=['\"]([^'\"#][^'\"]*)['\"]").matcher(html);
        int local = 0;
        while (matcher.find()) {
            String link = matcher.group(1);
            if (link.matches("^(?:https?:|data:|javascript:|mailto:).*")) continue;
            local++;
            assertTrue("Missing " + link, new File(report, link.split("[?#]")[0]).isFile());
        }
        assertTrue(local > 0);
        int start = html.indexOf("<script type='application/json' id='report-animations-data'>");
        JsonObject animations = JsonParser.parseString(html.substring(html.indexOf('>', start) + 1,
                html.indexOf("</script>", start))).getAsJsonObject();
        matcher = Pattern.compile("src='([^']+\\.gif)'").matcher(html);
        while (matcher.find()) {
            File gif = new File(report, matcher.group(1));
            assertTrue("Animation metadata for " + gif.getName(), animations.has(gif.getName()));
            assertTrue(new File(gif.getParentFile(), gif.getName() + ".poster.png").isFile());
            for (var sheet : animations.getAsJsonObject(gif.getName()).getAsJsonArray("sheets")) {
                assertTrue("Frame sheet " + sheet.getAsString(), new File(gif.getParentFile(), sheet.getAsString()).isFile());
            }
        }
    }

    private static boolean hasColour(File png) throws IOException {
        var image = javax.imageio.ImageIO.read(png);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int rgb = image.getRGB(x, y), r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
            if (r != g || g != b) return true;
        }
        return false;
    }

    /** The run context a default application captures for {@code files}, as the application's export receives it. */
    private static ReportRunContext runContext(FitsFileInformation[] files) {
        return ReportRunContext.capture(eu.startales.spacepixels.config.DetectionRunOptions.capture(null, eu.startales.spacepixels.config.SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES), files);
    }
}
