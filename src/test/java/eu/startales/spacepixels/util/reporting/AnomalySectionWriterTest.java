package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.model.AnomalyDetection;
import io.github.ppissias.jtransient.model.AnomalyType;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.model.Track;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ReportInclusion;
import eu.startales.spacepixels.util.WcsSolutionResolver;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class AnomalySectionWriterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final long START = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli();

    @Test public void satelliteQueryCoversTheWholeExposureIncludingFractionalSecondsAndFitsFallback() throws Exception {
        var context = context(5, 2, AnomalyType.PEAK_SIGMA);
        var point = context.anomalies.get(0).object;
        point.timestamp = -1;
        point.exposureDuration = -1;
        context.fitsFiles[2].getFitsHeader().put("EXPTIME", "120.25");
        var target = SatCheckerQueryTargetFactory.buildAnomalySatCheckerQueryTarget(context.astrometryContext, point);
        assertNotNull(target);
        assertEquals(START + 240_000, target.startTimestampMillis);
        assertEquals(START + 360_250, target.endTimestampMillis);
        assertEquals(121, target.durationSeconds);
        String html = render(context);
        String url = lookupUrl(html);
        assertTrue(url, url.contains("duration=121&"));
        double midpointJd = Double.parseDouble(url.split("mid_obs_time_jd=")[1].split("&")[0]);
        double actualMidpoint = (midpointJd - 2440587.5) * 86400000.0;
        double padding = target.durationSeconds * 1000 / 2.0;
        assertTrue(actualMidpoint - padding <= target.startTimestampMillis);
        assertTrue(actualMidpoint + padding >= target.endTimestampMillis);
        assertTrue(html.contains("00:06:00.250 UTC"));
        assertTrue(html.contains("anomaly_1_satchecker_results.json"));
    }

    @Test public void missingSatelliteMetadataExplainsWhatIsNeededWithoutInventingATimeWindow() throws Exception {
        var context = context(1, 0, AnomalyType.PEAK_SIGMA);
        var point = context.anomalies.get(0).object;
        var noWcs = new DetectionReportAstrometry.Context(context.fitsFiles, null, null, null, null, START);
        String html = AstrometryIdentificationHtmlBuilder.buildAnomalySatCheckerHtml(noWcs, point, "slot", "result.json");
        assertTrue(html.contains("plate-solve"));
        var noSite = new DetectionReportAstrometry.Context(context.fitsFiles,
                WcsSolutionResolver.resolveUncorrected(null, context.fitsFiles), null, null, null, START);
        html = AstrometryIdentificationHtmlBuilder.buildAnomalySatCheckerHtml(noSite, point, "slot", "result.json");
        assertTrue(html.contains("observing location"));
        point.exposureDuration = -1;
        context.fitsFiles[0].getFitsHeader().remove("EXPTIME");
        assertNull(SatCheckerQueryTargetFactory.buildAnomalySatCheckerQueryTarget(context.astrometryContext, point));
        html = render(context);
        assertTrue(html.contains("exposure length and sky position are required"));
        assertFalse(html.contains("data-provider='satchecker'"));
        assertTrue(html.contains("<dt>Exposure end</dt><dd>Unknown</dd>"));
        context.fitsFiles[0].getFitsHeader().put("EXPTIME", "60");
        context.fitsFiles[0].getFitsHeader().remove("DATE-OBS");
        point.timestamp = -1;
        assertNull(SatCheckerQueryTargetFactory.buildAnomalySatCheckerQueryTarget(context.astrometryContext, point));
        assertTrue(render(context).contains("<dt>Exposure start</dt><dd>Unknown</dd>"));
    }

    @Test public void comparisonSkipsRejectedFramesAndUsesOneStretchWithoutChangingMeasurements() throws Exception {
        var context = context(5, 2, AnomalyType.INTEGRATED_SIGMA);
        var telemetry = new PipelineTelemetry();
        for (int index : List.of(1, 3)) {
            var stat = new PipelineTelemetry.FrameQualityStat(); stat.frameIndex = index; stat.rejected = true;
            telemetry.frameQualityStats.add(stat);
        }
        context.data = ReportData.create(context, telemetry, null, List.of(), ReportInclusion.all());
        String measurements = new Gson().toJson(context.anomalies);
        var point = context.anomalies.get(0).object;
        var bounds = new TrackCropGeometry.CropBounds(point, context.settings.getTrackCropPadding());
        for (short[][] frame : context.rawFrames) frame[bounds.startY + 10][bounds.startX + 10] = (short) (1500 - 32768);
        String pixels = new Gson().toJson(context.rawFrames);
        String html = render(context);
        assertTrue(html.contains("Before, frame 1"));
        assertTrue(html.contains("After, frame 5"));
        assertFalse(html.contains("Before, frame 2"));
        assertFalse(html.contains("After, frame 4"));
        int commonColor = -1;
        for (String name : List.of("before", "detection", "after")) {
            var image = ImageIO.read(new File(context.exportDir, "anomaly_1_" + name + ".png"));
            int color = image.getRGB((image.getWidth() - bounds.trackBoxWidth) / 2 + 10, 48 + 10);
            if (commonColor == -1) commonColor = color;
            else assertEquals("Identical raw values must look identical in the comparison", commonColor, color);
        }
        assertTrue(html.contains("Strong combined signal across the detected pixels"));
        int details = html.indexOf("<details class='foldable-streak-details anomaly-details'>");
        assertTrue(details > html.indexOf("Check satellites"));
        assertTrue(html.indexOf("Integrated sigma:") > details);
        assertTrue(html.indexOf("anomaly_1_shape.png") > details);
        assertFalse(html.contains("Optical Flashes"));
        assertEquals(measurements, new Gson().toJson(context.anomalies));
        assertEquals(pixels, new Gson().toJson(context.rawFrames));
        assertEquals(List.of(1, 3), context.data.rejectedFrames.stream().sorted().toList());
        try (var input = ImageIO.createImageInputStream(new File(context.exportDir, "anomaly_1_context.gif"))) {
            var reader = ImageIO.getImageReadersByFormatName("gif").next();
            try { reader.setInput(input); assertEquals(3, reader.getNumImages(true)); }
            finally { reader.dispose(); }
        }
        assertTrue(new File(context.exportDir, "anomaly_1_context.gif.poster.png").isFile());
    }

    @Test public void sequenceEdgesAndSingleFrameReportsLabelMissingNeighbours() throws Exception {
        for (int index : List.of(0, 2)) {
            String html = render(context(3, index, AnomalyType.PEAK_SIGMA));
            assertTrue(html.contains(index == 0 ? "No kept frame before" : "No kept frame after"));
            assertTrue(html.contains("Strong peak signal"));
        }
        String html = render(context(1, 0, null));
        assertTrue(html.contains("No kept frame before"));
        assertTrue(html.contains("No kept frame after"));
        assertTrue(html.contains("Retained by the single-frame detection checks"));
        assertTrue(html.contains("id='finding-a1'"));
    }

    @Test public void eventWhereATrackPredictsItsObjectIsMarkedAndLinkedToThatTrack() throws Exception {
        String html = render(context(5, 2, AnomalyType.PEAK_SIGMA, List.of(0, 1, 3, 4), 0, 3));
        assertTrue(html.contains("<a class='track-link-badge' href='#finding-t1'>Likely part of T1</a>"));
        assertTrue(html.contains("High probability: part of moving-object track <a href='#finding-t1'>T1</a>."));
        assertTrue(html.contains("T1 has no detection in frame 3. This event lies 0.0 px"));
        // The frame just after the track still qualifies; the prediction extends its last step.
        assertTrue(render(context(5, 4, AnomalyType.PEAK_SIGMA, List.of(0, 1, 2, 3), 0, 3)).contains("Likely part of T1"));
    }

    @Test public void eventIsNotMarkedWhenTheTrackHasThatFrameOrThePositionOrShapeDiffers() throws Exception {
        assertFalse(render(context(5, 2, AnomalyType.PEAK_SIGMA, List.of(0, 1, 2, 3, 4), 0, 3)).contains("track-link"));
        assertFalse(render(context(5, 2, AnomalyType.PEAK_SIGMA, List.of(0, 1, 3, 4), 4, 3)).contains("track-link"));
        assertFalse(render(context(5, 2, AnomalyType.PEAK_SIGMA, List.of(0, 1, 3, 4), 0, 7)).contains("track-link"));
        assertFalse(render(context(7, 6, AnomalyType.PEAK_SIGMA, List.of(0, 1, 2, 3), 0, 3)).contains("track-link"));
    }

    private String lookupUrl(String html) {
        var matcher = Pattern.compile("data-target='([^']+)'").matcher(html);
        assertTrue(matcher.find());
        return new String(Base64.getUrlDecoder().decode(matcher.group(1)), StandardCharsets.UTF_8);
    }

    private String render(DetectionReportContext context) throws Exception {
        StringWriter text = new StringWriter();
        AnomalySectionWriter.writeSection(new PrintWriter(text), context);
        return text.toString();
    }

    private DetectionReportContext context(int count, int event, AnomalyType type) throws Exception {
        return context(count, event, type, List.of(), 0, 3);
    }

    /**
     * A moving-object track crossing the event position at one pixel per frame in the given frames,
     * offset from the event by {@code offset} pixels, with detections of the given FWHM (the event's is 3).
     */
    private DetectionReportContext context(int count, int event, AnomalyType type,
                                           List<Integer> trackFrames, double offset, double trackFwhm) throws Exception {
        FitsFileInformation[] files = new FitsFileInformation[count];
        List<short[][]> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            files[i] = new FitsFileInformation(new File(temporary.getRoot(), "frame" + i + ".fit").toString(), "frame" + i + ".fit", true, 160, 120);
            var h = files[i].getFitsHeader();
            h.put("DATE-OBS", Instant.ofEpochMilli(START + i * 120_000L).toString()); h.put("EXPTIME", "120");
            h.put("CTYPE1", "RA---TAN"); h.put("CTYPE2", "DEC--TAN");
            h.put("CRPIX1", "80.5"); h.put("CRPIX2", "60.5"); h.put("CRVAL1", "150"); h.put("CRVAL2", "20");
            h.put("CD1_1", "-0.0004166667"); h.put("CD1_2", "0"); h.put("CD2_1", "0"); h.put("CD2_2", "0.0004166667");
            short[][] pixels = new short[120][160];
            for (short[] row : pixels) Arrays.fill(row, (short) (1000 + 500 * i - 32768));
            frames.add(pixels);
        }
        frames.get(event)[60][80] = (short) (12000 - 32768);
        var point = new DetectedObject(80, 60, 10000, 9);
        point.sourceFrameIndex = event; point.sourceFilename = files[event].getFileName();
        point.timestamp = files[event].getObservationTimestamp(); point.exposureDuration = 120_000;
        point.pixelArea = 9; point.peakSigma = 25; point.integratedSigma = 40; point.elongation = 1.2; point.fwhm = 3;
        point.rawPixels = List.of(new Pixel(80, 60, 12000));
        JsonObject json = new JsonObject(); json.add("object", new Gson().toJsonTree(point));
        if (type != null) json.addProperty("type", type.name());
        var anomaly = new Gson().fromJson(json, AnomalyDetection.class);
        List<Track> tracks = new ArrayList<>();
        if (!trackFrames.isEmpty()) {
            var track = new Track();
            for (int frame : trackFrames) {
                var p = new DetectedObject(80 + (frame - event), 60 + offset, 10000, 9);
                p.sourceFrameIndex = frame; p.timestamp = files[frame].getObservationTimestamp(); p.exposureDuration = 120_000;
                p.pixelArea = 9; p.fwhm = trackFwhm;
                track.points.add(p);
            }
            tracks.add(track);
        }
        var astrometry = new DetectionReportAstrometry.Context(files, WcsSolutionResolver.resolveUncorrected(null, files),
                new DetectionReportAstrometry.ObserverSite(0, 0, 0, "Test location"), null, null, START);
        return new DetectionReportContext(new ExportVisualizationSettings(3, 10, 300, 90, 100, 12, 1),
                temporary.newFolder(), frames, files, new DetectionConfig(), astrometry, frames.get(0), null,
                new boolean[120][160], null, null, List.of(), List.of(), null, List.of(), List.of(),
                List.of(anomaly), List.of(), List.of(), tracks);
    }
}
