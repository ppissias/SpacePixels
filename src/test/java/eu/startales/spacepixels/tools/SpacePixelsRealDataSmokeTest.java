package eu.startales.spacepixels.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.startales.spacepixels.api.DefaultSpacePixelsPipelineApi;
import eu.startales.spacepixels.api.InputPreparationMode;
import eu.startales.spacepixels.api.SpacePixelsPipelineRequest;
import eu.startales.spacepixels.api.SpacePixelsPipelineResult;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.fits.FitsInput;
import io.github.ppissias.jtransient.photometry.PhotometricReadiness;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

/**
 * Release smoke for Tier 1 of the local reference inventory, on scratch copies.
 * Outcomes use the distributed detection profile. Photometry is enabled only for m5-reg.
 * Klangwolke-2 is the golden reference and is outside this task.
 */
public class SpacePixelsRealDataSmokeTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** The short asteroid sequence exercises tracking and complete report export. */
    @Test public void apophisTrackingAndReport() throws Exception {
        var result = checkDataset("Apophis", 15, 14, false, false);
        assertMovingTarget(result, 14, 619.71, 400.35, 604.34, 368.48);
    }

    /** The compact spacecraft sequence keeps two streak tracks on the default profile. */
    @Test public void integralStreakAndReport() throws Exception {
        var result = checkDataset("Integral-spacecraft-challenge", 35, 35, false, false);
        assertEquals("Integral streak tracks", 2L,
                result.getPipelineResult().tracks.stream().filter(track -> track.isStreakTrack).count());
        assertEquals("Integral anomalies", 0, result.getPipelineResult().anomalies.size());
    }

    /**
     * The six-frame session warns about uncalibrated hot pixels and, on the default profile,
     * keeps one anomaly and no track. The auto-tuned baseline is a separate, heavier result.
     */
    @Test public void iTelescopeHotPixelsAndReport() throws Exception {
        var result = checkDataset("iTelescope-slow-mover-hot-pixels", 6, 6, false, false);
        assertTrue("iTelescope tracks", result.getPipelineResult().tracks.isEmpty());
        assertEquals("iTelescope anomalies", 1, result.getPipelineResult().anomalies.size());
    }

    /** The small Filter3 session is the empty-result control. */
    @Test public void m31bEmptyResultAndReport() throws Exception {
        var result = checkDataset("m31-b-reg", 38, 32, false, true);
        assertTrue("m31-b tracks", result.getPipelineResult().tracks.isEmpty());
        assertTrue("m31-b anomalies", result.getPipelineResult().anomalies.isEmpty());
    }

    /** The crowded stellar field exercises photometry and its CSV exports. */
    @Test public void m5PhotometryAndReport() throws Exception {
        var result = checkDataset("m5-reg", 36, 32, true, false);
        var variables = result.getPipelineResult().variableStarAnalysis;
        assertEquals(PhotometricReadiness.Verdict.LIMITED, variables.readiness.verdict);
        assertEquals("Measured stars in the reference field", 418, variables.stars.size());
        assertEquals("Scored candidates in the reference run", 1, variables.candidates.size());
    }

    /** Copies only source images, isolates settings, and runs preparation, detection and reporting synchronously. */
    private SpacePixelsPipelineResult checkDataset(String name, int expectedFrames, int expectedKept,
                                                   boolean photometry, boolean allowEmptyFindings) throws Exception {
        Path source = dataset(name);
        Path copy = temporary.newFolder("input").toPath();
        var images = FitsInput.list(source.toFile());
        assertEquals(name + " source frame count", expectedFrames, images.length);
        for (var image : images) {
            Files.copy(image.toPath(), copy.resolve(image.getName()));
        }
        Path home = temporary.newFolder("home").toPath();
        String previousHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            SpacePixelsDetectionProfile profile;
            try (var reader = Files.newBufferedReader(Path.of("src/dist/config/default_detection_profile.json"))) {
                profile = SpacePixelsDetectionProfileIO.load(reader);
            }
            var config = profile.getDetectionConfig();
            config.enableVariableStarDetection = photometry;
            SpacePixelsPipelineResult result = new DefaultSpacePixelsPipelineApi().run(
                    SpacePixelsPipelineRequest.builder(copy.toFile()).detectionConfig(config)
                            .inputPreparationMode(InputPreparationMode.AUTO_PREPARE_TO_16BIT_MONO)
                            .generateReport(true).build());
            assertEquals(name + " source frame count", expectedFrames, result.getFilesInformation().length);
            var pipeline = result.getPipelineResult();
            assertFalse(name + " stopped after quality control", pipeline.tooFewFrames);
            assertEquals(name + " retained frames", expectedKept, pipeline.telemetry.totalFramesKept);
            System.out.printf(Locale.ROOT, "Real-data smoke %s: kept=%d, tracks=%d, anomalies=%d, photometry=%s, stars=%d, candidates=%d%n",
                    name, pipeline.telemetry.totalFramesKept, pipeline.tracks.size(), pipeline.anomalies.size(),
                    pipeline.variableStarAnalysis.readiness.verdict, pipeline.variableStarAnalysis.stars.size(),
                    pipeline.variableStarAnalysis.candidates.size());
            verifyReport(result, expectedFrames, photometry, allowEmptyFindings);
            return result;
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    /** Checks the reference target's track endpoints in source pixels, allowing two pixels of centroid variation. */
    private static void assertMovingTarget(SpacePixelsPipelineResult result, int points,
                                          double firstX, double firstY, double lastX, double lastY) throws Exception {
        assertTrue("Reference moving target track is missing", result.getPipelineResult().tracks.stream().anyMatch(track -> {
            if (track.isStreakTrack || track.points.size() != points) return false;
            var first = track.points.getFirst();
            var last = track.points.getLast();
            return Math.hypot(first.x - firstX, first.y - firstY) <= 2.0
                    && Math.hypot(last.x - lastX, last.y - lastY) <= 2.0;
        }));
        var data = JsonParser.parseString(Files.readString(result.getExportDirectory().toPath()
                .resolve("report-data.json"))).getAsJsonObject();
        assertTrue("Reference target is missing from the report", data.getAsJsonArray("findings").asList().stream()
                .map(element -> element.getAsJsonObject()).anyMatch(finding ->
                        "Moving target".equals(finding.get("category").getAsString())
                                && finding.getAsJsonArray("frames").size() == points
                                && Math.hypot(finding.get("x").getAsDouble() - firstX,
                                        finding.get("y").getAsDouble() - firstY) <= 2.0));
    }

    /** Missing optional datasets are visible skips; present but invalid inputs fail the check. */
    private static Path dataset(String name) {
        String root = System.getProperty("spacepixels.testdata.root");
        if (root == null || root.isBlank()) {
            System.out.println("Real-data smoke SKIPPED " + name + ": realDataRoot is not configured.");
            Assume.assumeTrue("realDataRoot is not configured", false);
        }
        Path directory = Path.of(root).resolve(name);
        if (Files.notExists(directory)) {
            System.out.println("Real-data smoke SKIPPED " + name + ": dataset is not installed.");
            Assume.assumeTrue(name + " is not installed", false);
        }
        assertTrue(name + " must be a dataset directory", Files.isDirectory(directory));
        return directory;
    }

    /** Validates structured output and readable rendered assets, plus photometry exports when enabled. */
    private static void verifyReport(SpacePixelsPipelineResult result, int expectedFrames,
                                     boolean photometry, boolean allowEmptyFindings) throws Exception {
        assertTrue("HTML report", result.getReportFile().isFile());
        assertTrue(Files.readString(result.getReportFile().toPath()).contains("<html"));
        Path output = result.getExportDirectory().toPath();
        JsonObject data = JsonParser.parseString(Files.readString(output.resolve("report-data.json"))).getAsJsonObject();
        assertEquals(1, data.get("schemaVersion").getAsInt());
        assertEquals(expectedFrames, data.get("frameCount").getAsInt());
        assertEquals(expectedFrames - result.getPipelineResult().telemetry.totalFramesKept,
                data.getAsJsonArray("rejectedFrames").size());
        if (allowEmptyFindings) {
            assertTrue("Empty-result control still reports findings", data.getAsJsonArray("findings").isEmpty());
        } else {
            assertFalse("Report findings", data.getAsJsonArray("findings").isEmpty());
        }
        assertTrue(Files.size(output.resolve("detection_config.json")) > 0);
        List<Path> images;
        try (var paths = Files.walk(output)) {
            images = paths.filter(path -> path.toString().endsWith(".png") || path.toString().endsWith(".gif")).toList();
        }
        assertFalse("Report image assets", images.isEmpty());
        for (Path image : images) assertNotNull("Unreadable report image " + image.getFileName(), ImageIO.read(image.toFile()));
        if (photometry) {
            for (String csv : List.of("photometry_stars.csv", "photometry_lightcurves.csv", "photometry_frames.csv")) {
                assertTrue(csv, Files.size(output.resolve("variable_stars").resolve(csv)) > 0);
            }
            var analysis = result.getPipelineResult().variableStarAnalysis;
            assertEquals("All measured stars exported", analysis.stars.size() + 1,
                    Files.readAllLines(output.resolve("variable_stars/photometry_stars.csv")).size());
            assertEquals("Per-frame photometry exported", analysis.frames.size() + 1,
                    Files.readAllLines(output.resolve("variable_stars/photometry_frames.csv")).size());
        }
    }
}
