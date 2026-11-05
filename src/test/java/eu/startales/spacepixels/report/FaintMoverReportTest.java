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
            int rgb = map.getRGB(x, y), red = (rgb >> 16) & 255, green = (rgb >> 8) & 255, blue = rgb & 255;
            if (green > red + 20 && blue > red + 20 && rgb != 0xff50e1d2) coloured = true;
        }
        assertTrue("The standalone map contains the FM reference marker", coloured);
        assertTrue(html.contains("Search truncated at its time limit"));
        assertTrue(html.contains("<dt>Camera-fixed score</dt><dd>N/A</dd>"));
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
        assertTrue(html.contains("No saved engine review evidence"));
        assertTrue(html.contains("data-fm-stretch"));
        assertTrue(html.contains("Native pixels (1:1)"));
        assertTrue(html.contains("value='1' selected>Fit"));
        assertTrue(html.contains("Motion-centred"));
        assertTrue(output.toPath().resolve("faint_movers/fm1_view.js").toFile().isFile());
    }

    /** Native source samples, engine frame identities and missing halves survive the offline review export. */
    @Test public void reviewExportsNativeSamplesAndActualMaskSupportWithoutEmbeddingPixelsInSummaryJson() throws Exception {
        var files = files();
        var c = candidate(files[0].getObservationTimestamp() + 60_000);
        var tree = new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create().toJsonTree(c).getAsJsonObject();
        var d = new com.google.gson.JsonObject();
        d.addProperty("x", 49); d.addProperty("y", 49); d.addProperty("width", 3); d.addProperty("height", 3);
        d.addProperty("scoreX", 50); d.addProperty("scoreY", 50);
        var frames = new com.google.gson.JsonArray();
        for (int index : new int[]{0, 2}) {
            var f = new com.google.gson.JsonObject();
            f.addProperty("sequenceIndex", index); f.addProperty("midpointMillis", files[index].getObservationTimestamp() + 60_000);
            f.addProperty("sampleX", 50); f.addProperty("sampleY", 50); f.addProperty("unmasked", index == 2);
            f.addProperty("signal", index == 0 ? -0.5f : 1.25f);
            f.add("residual", new Gson().toJsonTree(new float[]{0, 0, 0, 0, index == 0 ? -0.5f : 1.25f, 0, 0, 0, 0}));
            frames.add(f);
        }
        d.add("frames", frames); d.add("secondHalf", new Gson().toJsonTree(c.cutout)); tree.add("diagnostics", d);
        c = new Gson().fromJson(tree, FaintMoverCandidate.class);
        File output = export(files, c, telemetry(), ReportInclusion.all());
        String summary = Files.readString(output.toPath().resolve("report-data.json"));
        assertFalse(summary.contains("\"residual\""));
        var review = com.google.gson.JsonParser.parseString(summary).getAsJsonObject().getAsJsonArray("faintMovers")
                .get(0).getAsJsonObject().getAsJsonObject("review");
        assertEquals(1, review.get("unmaskedFrames").getAsInt());
        assertFalse(review.has("firstHalfStack"));
        assertTrue(output.toPath().resolve(review.get("secondHalfStack").getAsString()).toFile().isFile());
        String asset = Files.readString(output.toPath().resolve(review.get("data").getAsString()));
        var payload = com.google.gson.JsonParser.parseString(asset.substring(asset.indexOf(',') + 1, asset.length() - 2)).getAsJsonObject();
        assertEquals(3, payload.getAsJsonArray("frames").get(1).getAsJsonObject().get("number").getAsInt());
        var source = java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(payload.get("source").getAsString()))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(768 + (49 * 49) % 100, Short.toUnsignedInt(source.getShort()));
        var residual = java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(payload.get("residual").getAsString()))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(-0.5f, residual.getFloat(4 * 4), 0);
        assertEquals(1.25f, residual.getFloat((9 + 4) * 4), 0);
        var samples = review.getAsJsonArray("pathSamples");
        assertFalse(samples.get(0).getAsJsonObject().get("unmasked").getAsBoolean());
        assertEquals(1.25, samples.get(1).getAsJsonObject().get("processedSignal").getAsDouble(), 0);
        var csv = Files.readAllLines(output.toPath().resolve("faint_movers/faint_movers.csv"));
        assertTrue(csv.getFirst().endsWith("scoring_sample_x,scoring_sample_y,centre_unmasked,processed_residual_signal"));
        assertTrue(csv.get(1).endsWith(",50,50,false,-0.5"));
        assertTrue(csv.get(2).endsWith(",50,50,true,1.25"));
        String html = Files.readString(output.toPath().resolve("detection_report.html"));
        assertTrue(html.contains("First half: insufficient unmasked support"));
        assertTrue(html.contains("not a count of individual detections"));
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
        assertTrue(html.contains("Possible identifications (ambiguous):"));
        assertTrue(html.contains("sstr=First%20comparison"));
        assertTrue(html.contains("sstr=Second%20comparison"));
        assertTrue(html.contains("Faint mover FM1 · Unverified"));
        assertTrue(html.contains("Reference epoch for fitted-path lookup"));
        assertTrue(html.contains("2026-10-01T00:01:00"));
        assertTrue(html.contains("Search SkyBoT (Fitted Reference)"));
        assertTrue(html.contains("Render JPL Results Here"));
        assertTrue(html.contains("Stellarium Web (RA/Dec)"));
        assertTrue(html.contains("faint-mover-jpl-FM1"));
        assertTrue(html.contains("not an individual frame detection"));
    }

    /** In-field rows cover the whole sequence, while a fetched border object stays available only in the saved inventory. */
    @Test public void fieldInventoryOmitsBorderObjectsAndKeepsObjectsEnteringLater() throws Exception {
        var files = files();
        var wcs = WcsCoordinateTransformer.fromHeader(files[0].getFitsHeader());
        var c = candidate(files[0].getObservationTimestamp() + 60_000);
        var objects = predictions(c, wcs, files[0]);
        objects.endMillis = files[2].getObservationTimestamp() + files[2].getExposureDurationMillis() / 2;
        for (String name : List.of("Outside border object", "Entering later")) {
            var body = new SolarSystemObjects.Body(); body.name = name;
            for (long time : new long[]{objects.startMillis, objects.endMillis}) {
                double x = name.startsWith("Outside") ? 104 : 110 - 20 * (time - objects.startMillis) / (double) (objects.endMillis - objects.startMillis);
                var sky = wcs.pixelToSky(x, 50);
                var sample = new SolarSystemObjects.Sample(); sample.timeMillis = time;
                sample.ra = sky.getRaDegrees(); sample.dec = sky.getDecDegrees();
                body.samples.add(sample);
            }
            objects.bodies.add(body);
        }
        eu.startales.spacepixels.solarsystem.SolarSystemStore.save(folder.getRoot(), objects);
        File output = export(files, c, telemetry(), ReportInclusion.all());
        String html = Files.readString(output.toPath().resolve("detection_report.html"));
        assertFalse(html.contains("Outside border object"));
        assertTrue(html.contains("Entering later"));
        assertTrue(html.contains("Saved inventory entries without an in-field midpoint omitted from this table: 1"));
        assertTrue(html.contains("Known asteroids &amp; comets<span class='nav-count'>4</span>"));
        var data = JsonParser.parseString(Files.readString(output.toPath().resolve("report-data.json"))).getAsJsonObject();
        assertEquals(5, data.getAsJsonObject("knownSolarSystem").getAsJsonArray("bodies").size());
        assertTrue(data.toString().contains("Outside border object"));
    }

    /** Creates a tiny solved sequence whose RA crosses zero, without writing input images. */
    private FitsFileInformation[] files() {
        var files = new FitsFileInformation[3];
        for (int i = 0; i < files.length; i++) {
            var header = new HashMap<String, String>();
            header.put("DATE-OBS", "2026-10-01T00:0" + (i * 2) + ":00");
            header.put("EXPTIME", "120");
            header.put("SITELAT", "0"); header.put("SITELONG", "0");
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
