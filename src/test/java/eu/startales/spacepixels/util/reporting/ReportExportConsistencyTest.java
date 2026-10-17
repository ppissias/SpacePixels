package eu.startales.spacepixels.util.reporting;

import com.google.gson.*;
import eu.startales.spacepixels.util.*;
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

public class ReportExportConsistencyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void exportedIdsLinksAndInclusionAgreeWithoutChangingMeasurements() throws Exception {
        FitsFileInformation[] files = new FitsFileInformation[3];
        List<short[][]> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            files[i] = new FitsFileInformation(new File(temporary.getRoot(), "frame" + i + ".fit").toString(), "frame" + i + ".fit", true, 160, 120);
            files[i].getFitsHeader().put("DATE-OBS", "2026-10-01T00:0" + (2 * i) + ":00");
            files[i].getFitsHeader().put("EXPTIME", "120");
            files[i].getFitsHeader().put("FILTER", i == 0 ? "R" : "L");
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
        var track = new TrackLinker.Track();
        for (int i : List.of(0, 2)) {
            var point = new SourceExtractor.DetectedObject(60 + i * 10, 60, 1000, 10); point.sourceFrameIndex = i;
            point.timestamp = files[i].getObservationTimestamp(); point.exposureDuration = 120000; track.addPoint(point);
        }
        var result = new PipelineResult(List.of(track), telemetry, frames.get(0), List.of(), null, null, null, List.of(), List.of(), List.of(), List.of(), ResidualTransientAnalysis.empty(), null, List.of(), frames.get(0), analysis);
        var config = new DetectionConfig(); config.enableVariableStarDetection = true; config.enableSlowMoverDetection = false;
        boolean creative = DetectionReportGenerator.includeAiCreativeReportSections;
        String measurements = new GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(analysis);
        try {
            DetectionReportGenerator.includeAiCreativeReportSections = true;
            File included = temporary.newFolder("included");
            DetectionReportGenerator.exportTrackVisualizations(result, frames, files, included, config, null);
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
            assertTrue(Files.readString(new File(included, "photometry_stars.csv").toPath()).contains(",candidate_label,ra_deg,dec_deg,vsx_matches,nearest_vsx_arcsec"));
            assertTrue(new File(included, "moving_track_1_star_centric.gif.poster.png").isFile());
            File excluded = temporary.newFolder("excluded");
            DetectionReportGenerator.exportTrackVisualizations(result, frames, files, excluded, config, null, new ReportInclusion(true, false, false, false, false, false));
            html = Files.readString(new File(excluded, "detection_report.html").toPath());
            assertFalse(html.contains("V1 (0.60 mag)")); assertFalse(html.contains("<section id='variables'>"));
            assertFalse(Files.readString(new File(excluded, "report-data.json").toPath()).contains("Variable-star candidate"));
            File variablesOnly = temporary.newFolder("variables-only");
            var noTracks = new PipelineResult(List.of(), telemetry, frames.get(0), List.of(), null, null, null, List.of(), List.of(), List.of(), List.of(), ResidualTransientAnalysis.empty(), null, List.of(), frames.get(0), analysis);
            DetectionReportGenerator.exportTrackVisualizations(noTracks, frames, files, variablesOnly, config, null);
            html = Files.readString(new File(variablesOnly, "detection_report.html").toPath());
            assertTrue(html.contains("Map of All Detections"));
            assertTrue(html.contains("data-report-category='V'"));
            assertTrue(html.contains("href='#variable-v1'"));
            assertTrue(new File(variablesOnly, "global_track_map.png").isFile());
            assertEquals(measurements, new GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(analysis));
        } finally { DetectionReportGenerator.includeAiCreativeReportSections = creative; }
    }
}
