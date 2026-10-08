package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SourceExtractor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UnclassifiedTransientSectionWriterTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void remainingDetectionsExcludeLocalRescuePointsAndDuplicateReferences() {
        SourceExtractor.DetectedObject first = detection(8, 10, 0);
        SourceExtractor.DetectedObject rescued = detection(20, 22, 1);
        SourceExtractor.DetectedObject second = detection(30, 32, 1);
        List<List<SourceExtractor.DetectedObject>> unclassified = Arrays.asList(
                Arrays.asList(first, first),
                Arrays.asList(rescued, second));
        ResidualTransientAnalysis.LocalRescueCandidate rescue = new ResidualTransientAnalysis.LocalRescueCandidate(
                ResidualTransientAnalysis.LocalRescueKind.MICRO_DRIFT,
                Collections.singletonList(rescued),
                null,
                1.0);

        List<List<SourceExtractor.DetectedObject>> remaining = UnclassifiedTransientSectionWriter.selectRemaining(
                unclassified, Collections.singletonList(rescue));

        assertEquals(2, remaining.size());
        assertEquals(Collections.singletonList(first), remaining.get(0));
        assertEquals(Collections.singletonList(second), remaining.get(1));
    }

    @Test
    public void reportExportsInteractiveViewAndStaticComposite() throws Exception {
        File exportDirectory = temporaryFolder.newFolder();
        short[][] medianStack = new short[64][64];
        for (int row = 0; row < medianStack.length; row++) {
            for (int column = 0; column < medianStack[row].length; column++) {
                medianStack[row][column] = PixelEncoding.fromShiftedPositiveInt(1000 + (row + column) % 20);
            }
        }

        SourceExtractor.DetectedObject classified = detection(8, 10, 0);
        SourceExtractor.DetectedObject remaining = detection(30, 32, 1);
        remaining.sourceFilename = "<script>alert(1)</script>.fits";
        remaining.pixelCount = 0;
        remaining.pixelArea = 3;
        remaining.rawPixels = Arrays.asList(
                new SourceExtractor.Pixel(35, 32, 1200),
                new SourceExtractor.Pixel(36, 32, 1200),
                new SourceExtractor.Pixel(35, 33, 1200));
        SourceExtractor.DetectedObject rescued = detection(40, 42, 1);
        ResidualTransientAnalysis.LocalRescueCandidate rescue = new ResidualTransientAnalysis.LocalRescueCandidate(
                ResidualTransientAnalysis.LocalRescueKind.MICRO_DRIFT,
                Collections.singletonList(rescued),
                null,
                1.0);
        DetectionReportContext context = new DetectionReportContext(
                new ExportVisualizationSettings(0.5, 5.0, 100, 10, 100, false, 12, 1.0f),
                exportDirectory,
                Collections.emptyList(),
                null,
                new DetectionConfig(),
                null,
                medianStack,
                null,
                null,
                null,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                Collections.singletonList(rescue),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        List<List<SourceExtractor.DetectedObject>> allTransients = Arrays.asList(
                Collections.singletonList(classified),
                Arrays.asList(remaining, rescued));
        List<List<SourceExtractor.DetectedObject>> unclassified = Arrays.asList(
                Collections.emptyList(),
                Arrays.asList(remaining, rescued));
        StringWriter html = new StringWriter();

        UnclassifiedTransientSectionWriter.writeSection(
                new PrintWriter(html), context, allTransients, unclassified);

        assertTrue(new File(exportDirectory, "unclassified_median_background.png").isFile());
        assertTrue(new File(exportDirectory, "unclassified_transients.png").isFile());
        BufferedImage background = ImageIO.read(new File(exportDirectory, "unclassified_median_background.png"));
        BufferedImage composite = ImageIO.read(new File(exportDirectory, "unclassified_transients.png"));
        assertTrue(background.getRGB(35, 32) != composite.getRGB(35, 32));
        assertTrue(html.toString().contains("Unclassified Transient Inspector"));
        assertTrue(html.toString().contains("<svg"));
        assertEquals(1, html.toString().split("<circle class='unclassified-marker'", -1).length - 1);
        assertTrue(html.toString().contains("data-pixels='3'"));
        assertTrue(html.toString().contains("M35 32h1v1h-1z"));
        assertTrue(html.toString().contains("new Path2D"));
        assertTrue(html.toString().contains("&lt;script&gt;alert(1)&lt;/script&gt;.fits"));
        assertFalse(html.toString().contains("<script>alert(1)</script>.fits"));
    }

    @Test
    public void reportExportsFrameStripsAroundTheDetection() throws Exception {
        File exportDirectory = temporaryFolder.newFolder();
        List<short[][]> rawFrames = new java.util.ArrayList<>();
        for (int frame = 0; frame < 4; frame++) {
            short[][] pixels = new short[100][100];
            for (int row = 0; row < 100; row++) {
                for (int column = 0; column < 100; column++) {
                    pixels[row][column] = PixelEncoding.fromShiftedPositiveInt(1000 + (row * 7 + column * 3 + frame) % 15);
                }
            }
            if (frame == 1) {
                pixels[50][60] = PixelEncoding.fromShiftedPositiveInt(5000);
            }
            rawFrames.add(pixels);
        }
        SourceExtractor.DetectedObject flash = detection(60, 50, 1);
        DetectionReportContext context = new DetectionReportContext(
                new ExportVisualizationSettings(0.5, 5.0, 100, 10, 100, false, 12, 1.0f),
                exportDirectory, rawFrames, null, new DetectionConfig(), null, rawFrames.get(0), null, null, null, null,
                Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        List<List<SourceExtractor.DetectedObject>> transients = Arrays.asList(
                Collections.emptyList(), Collections.singletonList(flash), Collections.emptyList(), Collections.emptyList());
        StringWriter html = new StringWriter();

        UnclassifiedTransientSectionWriter.writeSection(new PrintWriter(html), context, transients, transients);

        // Frames 1-4 (index 0-3): two after the detection exist, one before; the crop stays inside the 100 px image.
        File strip = new File(exportDirectory, "unclassified_frames/U1.png");
        assertTrue(strip.isFile());
        BufferedImage image = ImageIO.read(strip);
        assertEquals(80, image.getWidth());
        assertEquals(4 * 80, image.getHeight());
        assertTrue(html.toString().contains("data-strip='unclassified_frames/U1.png' data-strip-frames='1,2,3,4' data-strip-count='4' data-strip-detection='1'"));
        assertTrue(html.toString().contains("data-strip-left='20' data-strip-top='10'"));
        // The flash is bright only in the detection frame of the strip.
        int flashInDetectionFrame = image.getRaster().getSample(60 - 20, 80 + 50 - 10, 0);
        int samePlaceNextFrame = image.getRaster().getSample(60 - 20, 2 * 80 + 50 - 10, 0);
        assertEquals(255, flashInDetectionFrame);
        assertTrue(samePlaceNextFrame < 255);
    }

    @Test
    public void animationSkipsFramesRejectedByQualityControl() {
        Set<Integer> rejected = new HashSet<>(Arrays.asList(12, 15));
        // Detection in frame 14 (index 13) of 18: frame 13 (index 12) and frame 16 (index 15) were rejected.
        assertEquals(Arrays.asList(10, 11, 13, 14, 16), UnclassifiedTransientSectionWriter.animationFrames(13, 18, rejected));
        // Near the ends of the sequence fewer frames are available on that side.
        assertEquals(Arrays.asList(0, 1, 2), UnclassifiedTransientSectionWriter.animationFrames(0, 18, rejected));
        assertEquals(Arrays.asList(15, 16, 17), UnclassifiedTransientSectionWriter.animationFrames(17, 18, Collections.emptySet()));
    }

    @Test
    public void detectionsBeyondTheAnimationLimitShowTheStaticCutout() throws Exception {
        int originalLimit = UnclassifiedTransientSectionWriter.maxAnimatedMarkers;
        UnclassifiedTransientSectionWriter.maxAnimatedMarkers = 0;
        try {
            File exportDirectory = temporaryFolder.newFolder();
            short[][] pixels = new short[40][40];
            DetectionReportContext context = new DetectionReportContext(
                    new ExportVisualizationSettings(0.5, 5.0, 100, 10, 100, false, 12, 1.0f),
                    exportDirectory, Collections.singletonList(pixels), null, new DetectionConfig(), null, pixels, null, null, null, null,
                    Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList());
            List<List<SourceExtractor.DetectedObject>> transients = Collections.singletonList(Collections.singletonList(detection(20, 20, 0)));
            StringWriter html = new StringWriter();

            UnclassifiedTransientSectionWriter.writeSection(new PrintWriter(html), context, transients, transients);

            assertFalse(new File(exportDirectory, "unclassified_frames").exists());
            assertFalse(html.toString().contains("data-strip="));
        } finally {
            UnclassifiedTransientSectionWriter.maxAnimatedMarkers = originalLimit;
        }
    }

    private static SourceExtractor.DetectedObject detection(double x, double y, int frameIndex) {
        SourceExtractor.DetectedObject detection = new SourceExtractor.DetectedObject(x, y, 100.0, 4);
        detection.sourceFrameIndex = frameIndex;
        detection.sourceFilename = "frame_" + frameIndex + ".fits";
        return detection;
    }
}
