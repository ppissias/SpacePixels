package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.photometry.VariabilityTier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PhotometryReportSectionWriterTest {

    private static final int SIZE = 300;
    private static final int FRAMES = 30;
    private static final double SKY = 1000.0;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writesVerdictChartsCandidateCardAndCsvExports() throws Exception {
        List<ImageFrame> frames = new ArrayList<>();
        List<short[][]> rawFrames = new ArrayList<>();
        createSession(frames, rawFrames);
        DetectionConfig config = new DetectionConfig();
        config.enableVariableStarDetection = true;
        config.enableSlowMoverDetection = false;
        config.starCountSigmaDeviation = 100.0;
        config.fwhmSigmaDeviation = 100.0;
        config.backgroundSigmaDeviation = 100.0;

        JTransientEngine engine = new JTransientEngine();
        PipelineResult result;
        try {
            result = engine.runPipeline(frames, config, null, null);
        } finally {
            engine.shutdown();
        }
        assertEquals(VariabilityTier.HIGH_CONFIDENCE, result.variableStarAnalysis.candidates.get(0).tier);

        File exportDir = temporaryFolder.newFolder();
        String html = render(result, rawFrames, config, exportDir);

        assertTrue(html.contains("Variable-Star Photometry"));
        assertTrue(html.contains("Ready"));
        assertTrue(html.contains("Photometry: Readiness Checks"));
        assertTrue(html.contains("Noise model"));
        assertTrue(html.contains("V1 &middot; High confidence"));
        assertTrue(html.contains("<svg"));
        assertTrue(html.contains("Photometry: Per-Frame Measurements"));
        assertTrue(new File(exportDir, "photometry_stars.csv").isFile());
        assertTrue(new File(exportDir, "photometry_lightcurves.csv").isFile());
        assertTrue(new File(exportDir, "photometry_frames.csv").isFile());
        assertTrue(new File(exportDir, "photometry_v1_brightest.png").isFile());
        assertEquals(FRAMES + 1, Files.readAllLines(new File(exportDir, "photometry_frames.csv").toPath()).size());

        // Optional: keep a standalone page for visual review.
        String previewDir = System.getenv("SPACEPIXELS_PHOTOMETRY_PREVIEW_DIR");
        if (previewDir != null) {
            File out = new File(previewDir);
            out.mkdirs();
            render(result, rawFrames, config, out);
        }
    }

    @Test
    public void disabledPhotometryWritesShortNote() throws Exception {
        DetectionConfig config = new DetectionConfig();
        StringWriter output = new StringWriter();
        PhotometryReportSectionWriter.writeSection(new PrintWriter(output), context(config, Collections.emptyList(), temporaryFolder.newFolder()), null);
        assertTrue(output.toString().contains("disabled for this session"));
    }

    private static String render(PipelineResult result, List<short[][]> rawFrames, DetectionConfig config, File exportDir) throws Exception {
        StringWriter output = new StringWriter();
        try (PrintWriter writer = new PrintWriter(output)) {
            DetectionReportDocumentWriter.appendDetectionReportStart(writer);
            PhotometryReportSectionWriter.writeSection(writer, context(config, rawFrames, exportDir), result.variableStarAnalysis);
            writer.println("</body></html>");
        }
        Files.write(new File(exportDir, "photometry_preview.html").toPath(), output.toString().getBytes("UTF-8"));
        return output.toString();
    }

    private static DetectionReportContext context(DetectionConfig config, List<short[][]> rawFrames, File exportDir) {
        return new DetectionReportContext(
                new ExportVisualizationSettings(3.0, 10.0, 100, 10, 100, false, 12, 1.0f),
                exportDir,
                rawFrames,
                null,
                config,
                null,
                null,
                null,
                null,
                null,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
    }

    /** Isolated Gaussian stars, a transparency wave, random seeing and one sinusoidal variable. */
    private static void createSession(List<ImageFrame> frames, List<short[][]> rawFrames) {
        Random random = new Random(7);
        List<double[]> stars = new ArrayList<>();
        for (double y = 25; y < SIZE - 25; y += 20) {
            for (double x = 25; x < SIZE - 25; x += 20) {
                stars.add(new double[]{x + 2 * (random.nextDouble() - 0.5), y + 2 * (random.nextDouble() - 0.5),
                        3000.0 * Math.pow(300_000.0 / 3000.0, random.nextDouble())});
            }
        }
        double[] variable = stars.get(stars.size() / 2 + 3);
        variable[2] = 30_000.0;
        long start = 1_760_000_000_000L;
        for (int j = 0; j < FRAMES; j++) {
            double sigma = (2.8 + 0.6 * random.nextDouble()) / 2.355;
            double transparency = Math.pow(10, -0.4 * 0.15 * Math.sin(2 * Math.PI * j / FRAMES));
            double[][] image = new double[SIZE][SIZE];
            for (double[] row : image) java.util.Arrays.fill(row, SKY);
            for (double[] star : stars) {
                double flux = star[2] * transparency;
                if (star == variable) flux *= Math.pow(10, -0.4 * 0.2 * Math.sin(2 * Math.PI * j / 20.0));
                render(image, star[0], star[1], flux, sigma);
            }
            short[][] pixels = new short[SIZE][SIZE];
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    double v = image[y][x] + random.nextGaussian() * Math.sqrt(image[y][x] + 25);
                    pixels[y][x] = PixelEncoding.fromShiftedPositiveInt((int) Math.max(0, Math.min(65535, Math.round(v))));
                }
            }
            frames.add(new ImageFrame(j, "frame" + j + ".fits", pixels, start + j * 120_000L, 60_000L));
            rawFrames.add(pixels);
        }
    }

    private static void render(double[][] image, double cx, double cy, double flux, double sigma) {
        int r = (int) Math.ceil(5 * sigma);
        double total = 0;
        double[][] kernel = new double[2 * r + 1][2 * r + 1];
        int x0 = (int) Math.round(cx);
        int y0 = (int) Math.round(cy);
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                double px = x0 + dx - cx;
                double py = y0 + dy - cy;
                kernel[dy + r][dx + r] = Math.exp(-(px * px + py * py) / (2 * sigma * sigma));
                total += kernel[dy + r][dx + r];
            }
        }
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                image[y0 + dy][x0 + dx] += flux * kernel[dy + r][dx + r] / total;
            }
        }
    }
}
