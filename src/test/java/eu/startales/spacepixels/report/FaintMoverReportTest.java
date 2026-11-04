package eu.startales.spacepixels.report;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import eu.startales.spacepixels.config.DetectionRunOptions;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.skycatalog.SkyField;
import eu.startales.spacepixels.skycatalog.SkySnapshot;
import eu.startales.spacepixels.solarsystem.SolarSystemObjects;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.FaintMoverCandidate;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.telemetry.FaintMoverSearchTelemetry;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import static org.junit.Assert.*;

/** Exercises experimental report evidence, searched-frame coverage and independent catalogue comparisons. */
public class FaintMoverReportTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** Unavailable engine evidence stays unavailable in JSON/CSV/HTML, with fitted points only on searched frames. */
    @Test public void exportsFiniteEvidenceAssetsAndOriginalFrameNumbers() throws Exception {
        var files = files();
        var candidate = candidate(files[0].getObservationTimestamp() + 60_000);
        var telemetry = telemetry();
        telemetry.truncated = true;
        File output = export(files, candidate, telemetry, ReportInclusion.all());
        String html = Files.readString(output.toPath().resolve("detection_report.html"));
        String json = Files.readString(output.toPath().resolve("report-data.json"));
        assertFalse(json.contains("NaN"));
        assertFalse(json.contains("Infinity"));
        var data = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("truncated", data.getAsJsonObject("faintMoverSearch").get("status").getAsString());
        assertEquals("[1,3]", data.getAsJsonObject("faintMoverSearch").get("frameNumbers").toString());
        var entry = data.getAsJsonArray("faintMovers").get(0).getAsJsonObject();
        assertEquals("FM1", entry.get("id").getAsString());
        assertFalse(entry.get("firstHalfAvailable").getAsBoolean());
        assertFalse(entry.get("cameraComparisonAvailable").getAsBoolean());
        assertFalse(entry.get("catalogueComparisonAvailable").getAsBoolean());
        assertEquals("unavailable", data.getAsJsonObject("faintMoverSearch").get("catalogueComparisonStatus").getAsString());
        assertEquals(2, entry.getAsJsonArray("fittedPositions").size());
        assertEquals(3, entry.getAsJsonArray("fittedPositions").get(1).getAsJsonObject().get("frameNumber").getAsInt());
        assertTrue(html.contains("id='finding-fm1'"));
        assertTrue(html.contains("Map of All Detections"));
        assertTrue(html.contains("data-category='FM'"));
        assertTrue(html.contains("href='#finding-fm1'"));
        var map = javax.imageio.ImageIO.read(output.toPath().resolve("field/global_track_map.png").toFile());
        boolean coloured = false;
        for (int y = 0; y < map.getHeight(); y++) for (int x = 0; x < map.getWidth(); x++) {
            if ((map.getRGB(x, y) & 0xffffff) == 0x50e1d2) coloured = true;
        }
        assertTrue("The standalone map contains the FM reference marker", coloured);
        assertTrue(html.contains("Search truncated at its time limit"));
        assertTrue(html.contains("camera-fixed N/A"));
        assertTrue(html.contains("score units"));
        assertTrue(html.contains("unavailable for this field or reference time"));
        assertTrue(html.contains("not individual frame detections"));
        assertTrue(output.toPath().resolve("faint_movers/fm1_stack.png").toFile().isFile());
        assertTrue(output.toPath().resolve("faint_movers/fm1_blink.gif.poster.png").toFile().isFile());
        var csv = Files.readAllLines(output.toPath().resolve("faint_movers/faint_movers.csv"));
        assertEquals(3, csv.size());
        assertTrue(csv.get(1).contains(",,8.0,2.0,,"));
        assertFalse(csv.toString().contains("NaN"));
        assertFalse(csv.toString().contains("Infinity"));
        assertTrue(data.getAsJsonArray("findings").toString().contains("finding-fm1"));
    }

    /** A skip is distinct from a completed empty search, while report omission removes candidates and their assets. */
    @Test public void skipAndReportExclusionHaveNoCandidateAssets() throws Exception {
        var files = files();
        var telemetry = telemetry();
        telemetry.skippedReason = "Need at least 8 kept frames";
        telemetry.searchedFrameIndices = List.of();
        File skipped = export(files, null, telemetry, ReportInclusion.all());
        String html = Files.readString(skipped.toPath().resolve("detection_report.html"));
        assertTrue(html.contains("Search skipped:"));
        assertFalse(html.contains("Search completed"));
        assertFalse(new File(skipped, "faint_movers").exists());
        var excluded = new ReportInclusion(true, true, true, true, true, true, true, false);
        File omitted = export(files, candidate(files[0].getObservationTimestamp()), telemetry(), excluded);
        assertFalse(new File(omitted, "faint_movers").exists());
        assertFalse(Files.readString(omitted.toPath().resolve("detection_report.html")).contains("id='finding-fm1'"));
        assertFalse(Files.readString(omitted.toPath().resolve("detection_report.html")).contains("data-category='FM'"));
        var data = JsonParser.parseString(Files.readString(omitted.toPath().resolve("report-data.json"))).getAsJsonObject();
        assertEquals(0, data.getAsJsonArray("faintMovers").size());
        assertFalse(data.getAsJsonObject("includedGroups").get("faintMovers").getAsBoolean());
    }

    /** RA wrapping and multiple plausible bodies preserve ambiguity; wrong motions and out-of-span data cannot match. */
    @Test public void comparisonsKeepAllCompatiblePredictionsAndRejectWrongMotionOrTime() throws Exception {
        var files = files();
        var wcs = WcsCoordinateTransformer.fromHeader(files[0].getFitsHeader());
        assertNotNull(wcs);
        var c = candidate(files[0].getObservationTimestamp() + 60_000);
        var objects = predictions(c, wcs, files[0]);
        assertEquals(2, FaintMoverPresentation.compare(c, wcs, objects, 2.5, objects.startMillis, objects.endMillis).size());
        var sky = FaintMoverPresentation.sky(c, wcs, c.referenceTimeMillis);
        assertEquals(0.4, sky.speedArcsecPerMinute(), 0.00001);
        assertEquals(270, sky.positionAngleDegrees(), 0.01);
        objects.startMillis = c.referenceTimeMillis + 1;
        assertTrue(FaintMoverPresentation.compare(c, wcs, objects, 2.5, objects.startMillis, objects.endMillis).isEmpty());
    }

    /** Admission detaches public mutable prediction lists, and rejects a saved prediction for another field. */
    @Test public void admittedPredictionsAreDetachedAndFieldChecked() throws Exception {
        var files = files();
        var wcs = WcsCoordinateTransformer.fromHeader(files[0].getFitsHeader());
        var c = candidate(files[0].getObservationTimestamp() + 60_000);
        var objects = predictions(c, wcs, files[0]);
        var snapshot = new SkySnapshot(folder.getRoot(), null, null, objects);
        var context = ReportRunContext.capture(DetectionRunOptions.defaults(), files, snapshot);
        assertNotNull(context.solarSystem());
        objects.bodies.getFirst().name = "changed after admission";
        objects.bodies.clear();
        assertEquals("First comparison", context.solarSystem().bodies.getFirst().name);
        objects.field = null;
        assertNull(ReportRunContext.capture(DetectionRunOptions.defaults(), files, snapshot).solarSystem());
    }

    /** An incomplete provider fetch remains explicit rather than making unmatched paths look like new objects. */
    @Test public void partialCatalogueProvenanceAndAmbiguousNamesSurviveExport() throws Exception {
        var files = files();
        var c = candidate(files[0].getObservationTimestamp() + 60_000);
        var objects = predictions(c, WcsCoordinateTransformer.fromHeader(files[0].getFitsHeader()), files[0]);
        objects.source = "Synthetic prediction provider";
        objects.fetchedAtUtc = "2026-10-01T00:00:00Z";
        objects.problems.add("One <request> failed");
        eu.startales.spacepixels.solarsystem.SolarSystemStore.save(folder.getRoot(), objects);
        File output = export(files, c, telemetry(), ReportInclusion.all());
        var data = JsonParser.parseString(Files.readString(output.toPath().resolve("report-data.json"))).getAsJsonObject();
        var search = data.getAsJsonObject("faintMoverSearch");
        assertEquals("partial-fetch", search.get("catalogueComparisonStatus").getAsString());
        assertEquals(objects.source, search.get("catalogueSource").getAsString());
        var entry = data.getAsJsonArray("faintMovers").get(0).getAsJsonObject();
        assertTrue(entry.get("catalogueComparisonAvailable").getAsBoolean());
        assertEquals(2, entry.getAsJsonArray("catalogueComparisons").size());
        String html = Files.readString(output.toPath().resolve("detection_report.html"));
        assertTrue(html.contains("Saved prediction fetch was incomplete"));
        assertTrue(html.contains("One &lt;request&gt; failed"));
        assertTrue(html.contains("First comparison; Second comparison"));
    }

    /** Creates a tiny solved sequence whose RA crosses zero, without writing input images. */
    private FitsFileInformation[] files() {
        var files = new FitsFileInformation[3];
        for (int i = 0; i < files.length; i++) {
            var header = new HashMap<String, String>();
            header.put("DATE-OBS", "2026-10-01T00:0" + (i * 2) + ":00");
            header.put("EXPTIME", "120");
            header.put("CTYPE1", "RA---TAN"); header.put("CTYPE2", "DEC--TAN");
            header.put("CRPIX1", "51"); header.put("CRPIX2", "51");
            header.put("CRVAL1", "0.00001"); header.put("CRVAL2", "0");
            header.put("CD1_1", "-0.000277777777778"); header.put("CD1_2", "0");
            header.put("CD2_1", "0"); header.put("CD2_2", "0.000277777777778");
            files[i] = new FitsFileInformation(new File(folder.getRoot(), "frame" + i + ".fit").toString(),
                    "frame" + i + ".fit", true, 100, 100, header);
        }
        return files;
    }

    /** Models one engine candidate with unavailable first-half and camera evidence, including its score cutout. */
    private static FaintMoverCandidate candidate(long time) {
        var c = new Gson().fromJson("{\"x\":50,\"y\":50,\"vx\":0.4,\"vy\":0,\"referenceTimeMillis\":" + time
                + ",\"score\":12,\"firstHalfScore\":\"NaN\",\"secondHalfScore\":8,\"stationaryScore\":2,"
                + "\"cameraScore\":\"-Infinity\",\"cutout\":" + new Gson().toJson(new float[31][31]) + "}", FaintMoverCandidate.class);
        c.cutout[15][15] = 12;
        return c;
    }

    /** Selects two nonadjacent original indices to catch kept-index renumbering mistakes. */
    private static FaintMoverSearchTelemetry telemetry() {
        var t = new FaintMoverSearchTelemetry();
        t.frames = 2; t.framesOutsideSession = 1; t.searchedFrameIndices = List.of(0, 2);
        t.fwhmPixels = 2.5; t.tiles = 2; t.tilesSearched = 1; t.candidates = 1;
        return t;
    }

    /** Produces a report using only small synthetic arrays and captured metadata. */
    private File export(FitsFileInformation[] files, FaintMoverCandidate candidate, FaintMoverSearchTelemetry t,
                        ReportInclusion inclusion) throws Exception {
        List<short[][]> frames = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            short[][] pixels = new short[100][100];
            for (int y = 0; y < 100; y++) for (int x = 0; x < 100; x++) pixels[y][x] = (short) (-32000 + (x * y) % 100);
            frames.add(pixels);
        }
        var telemetry = new PipelineTelemetry();
        telemetry.totalFramesLoaded = files.length; telemetry.totalFramesKept = files.length;
        telemetry.faintMoverSearchTelemetry = t;
        var result = PipelineResult.builder().tracks(List.of()).telemetry(telemetry).masterStackData(frames.getFirst())
                .maximumStackData(frames.getFirst()).masterStars(List.of()).slowMoverCandidates(List.of())
                .anomalies(List.of()).allTransients(List.of()).unclassifiedTransients(List.of())
                .faintMoverCandidates(candidate == null ? List.of() : List.of(candidate)).build();
        var config = new DetectionConfig();
        config.enableFaintMoverSearch = true;
        File output = folder.newFolder();
        ReportExporter.export(new ReportRequest(result, frames, files, output, config,
                ReportRunContext.capture(DetectionRunOptions.defaults(), files), inclusion, null));
        return output;
    }

    /** Builds ambiguous matching predictions and one deliberately incompatible motion using the same WCS. */
    private static SolarSystemObjects predictions(FaintMoverCandidate c, WcsCoordinateTransformer wcs, FitsFileInformation file) {
        var objects = new SolarSystemObjects();
        objects.field = SkyField.of(wcs, file.getSizeWidth(), file.getSizeHeight());
        objects.startMillis = c.referenceTimeMillis - 60_000; objects.endMillis = c.referenceTimeMillis + 60_000;
        objects.magnitudeLimit = 19;
        for (String name : List.of("First comparison", "Second comparison", "Wrong motion")) {
            var body = new SolarSystemObjects.Body(); body.name = name;
            for (long time : new long[]{objects.startMillis, objects.endMillis}) {
                double[] p = c.positionAt(time);
                if (name.equals("Wrong motion")) p[0] += (time - c.referenceTimeMillis) / 60_000.0;
                var sky = wcs.pixelToSky(p[0], p[1]);
                var sample = new SolarSystemObjects.Sample();
                sample.timeMillis = time; sample.ra = sky.getRaDegrees(); sample.dec = sky.getDecDegrees(); sample.magnitude = 18.5;
                body.samples.add(sample);
            }
            objects.bodies.add(body);
        }
        return objects;
    }
}
