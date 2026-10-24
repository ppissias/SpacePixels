package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import eu.startales.spacepixels.util.ReportInclusion;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.CameraFixedRejector;
import io.github.ppissias.jtransient.core.FrameRegistration;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
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
        DetectedObject first = detection(8, 10, 0);
        DetectedObject rescued = detection(20, 22, 1);
        DetectedObject second = detection(30, 32, 1);
        List<List<DetectedObject>> unclassified = Arrays.asList(
                Arrays.asList(first, first),
                Arrays.asList(rescued, second));
        ResidualTransientAnalysis.LocalRescueCandidate rescue = new ResidualTransientAnalysis.LocalRescueCandidate(
                ResidualTransientAnalysis.LocalRescueKind.MICRO_DRIFT,
                Collections.singletonList(rescued),
                null,
                1.0);

        List<List<DetectedObject>> remaining = UnclassifiedTransientSectionWriter.selectRemaining(
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

        DetectedObject classified = detection(8, 10, 0);
        DetectedObject remaining = detection(30, 32, 1);
        remaining.sourceFilename = "<script>alert(1)</script>.fits";
        remaining.pixelCount = 0;
        remaining.pixelArea = 3;
        remaining.rawPixels = Arrays.asList(
                new Pixel(35, 32, 1200),
                new Pixel(36, 32, 1200),
                new Pixel(35, 33, 1200));
        DetectedObject rescued = detection(40, 42, 1);
        ResidualTransientAnalysis.LocalRescueCandidate rescue = new ResidualTransientAnalysis.LocalRescueCandidate(
                ResidualTransientAnalysis.LocalRescueKind.MICRO_DRIFT,
                Collections.singletonList(rescued),
                null,
                1.0);
        DetectionReportContext context = new DetectionReportContext(
                new ExportVisualizationSettings(0.5, 5.0, 100, 10, 100, 12, 1.0f),
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
                Collections.emptyList());
        List<List<DetectedObject>> allTransients = Arrays.asList(
                Collections.singletonList(classified),
                Arrays.asList(remaining, rescued));
        List<List<DetectedObject>> unclassified = Arrays.asList(
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
    public void groupsLeftOutOfTheReportStayOffTheMapAndAreCounted() throws Exception {
        DetectedObject trackPoint = detection(5, 5, 0);
        DetectedObject commonMotion = detection(10, 30, 1);
        DetectedObject rescued = detection(30, 10, 1);
        DetectedObject left = detection(30, 30, 2);
        // A hot pixel at camera x 10 that the dither moves by 5 px per frame.
        List<List<DetectedObject>> hotPixel = Arrays.asList(
                new java.util.ArrayList<>(Collections.singletonList(detection(10, 20, 0))),
                new java.util.ArrayList<>(Collections.singletonList(detection(15, 20, 1))),
                new java.util.ArrayList<>(Collections.singletonList(detection(20, 20, 2))));
        java.util.Map<Integer, FrameRegistration> registrations = new java.util.HashMap<>();
        for (int frame = 0; frame < 3; frame++) {
            registrations.put(frame, FrameRegistration.of(frame, 5.0 * frame, 0, 0));
        }
        List<CameraFixedRejector.Group> cameraFixed = CameraFixedRejector.reject(hotPixel, registrations);
        assertEquals(1, cameraFixed.size());
        ResidualTransientAnalysis residual = new ResidualTransientAnalysis(
                Collections.singletonList(new ResidualTransientAnalysis.LocalRescueCandidate(
                        ResidualTransientAnalysis.LocalRescueKind.MICRO_DRIFT, Collections.singletonList(rescued), null, 1.0)),
                Collections.emptyList(),
                Collections.singletonList(new ResidualTransientAnalysis.CommonMotionGroup(
                        Collections.singletonList(Collections.singletonList(commonMotion)), 1)));
        // The engine already took the camera-fixed points out of every list and the common-motion point out of the
        // unclassified list.
        List<List<DetectedObject>> allTransients = Arrays.asList(
                Collections.singletonList(trackPoint), Arrays.asList(commonMotion, rescued), Collections.singletonList(left));
        List<List<DetectedObject>> unclassified = Arrays.asList(
                Collections.emptyList(), Collections.singletonList(rescued), Collections.singletonList(left));
        ReportInclusion leftOut = new ReportInclusion(true, true, false, true, false, false, true);

        String html = inspectorHtml(allTransients, unclassified, residual, cameraFixed, leftOut);

        assertEquals(1, html.split("<circle class='unclassified-marker'", -1).length - 1);
        assertTrue(html.contains("data-x='30.00' data-y='30.00'"));
        assertTrue(html.contains("<strong>7</strong> Post-veto detections"));
        assertTrue(html.contains("<strong>1</strong> Tracks, streaks and anomalies"));
        assertTrue(html.contains("<strong>1</strong> Common motion, not in this report"));
        assertTrue(html.contains("<strong>3</strong> Camera-fixed, not in this report"));
        assertTrue(html.contains("<strong>1</strong> Local rescue points, not in this report"));
        assertTrue(html.contains("<strong>1</strong> Still unclassified"));
        assertTrue(html.contains("Groups left out of this report are counted here but not shown."));

        String included = inspectorHtml(allTransients, unclassified, residual, cameraFixed, ReportInclusion.all());

        assertEquals(1, included.split("<circle class='unclassified-marker'", -1).length - 1);
        assertTrue(included.contains("<strong>3</strong> Camera-fixed</div>"));
        assertFalse(included.contains("not in this report"));
        assertFalse(included.contains("Groups left out"));
    }

    private String inspectorHtml(List<List<DetectedObject>> allTransients,
                                 List<List<DetectedObject>> unclassified,
                                 ResidualTransientAnalysis residual,
                                 List<CameraFixedRejector.Group> cameraFixed,
                                 ReportInclusion inclusion) throws Exception {
        short[][] medianStack = new short[40][40];
        DetectionReportContext context = new DetectionReportContext(
                new ExportVisualizationSettings(0.5, 5.0, 100, 10, 100, 12, 1.0f),
                temporaryFolder.newFolder(), Collections.emptyList(), null, new DetectionConfig(), null, medianStack,
                null, null, null, null, Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        StringWriter html = new StringWriter();
        UnclassifiedTransientSectionWriter.writeSection(new PrintWriter(html), context, allTransients, unclassified,
                Collections.emptySet(), residual, cameraFixed, inclusion);
        return html.toString();
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
        DetectedObject flash = detection(60, 50, 1);
        DetectionReportContext context = new DetectionReportContext(
                new ExportVisualizationSettings(0.5, 5.0, 100, 10, 100, 12, 1.0f),
                exportDirectory, rawFrames, null, new DetectionConfig(), null, rawFrames.get(0), null, null, null, null,
                Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        List<List<DetectedObject>> transients = Arrays.asList(
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
                    new ExportVisualizationSettings(0.5, 5.0, 100, 10, 100, 12, 1.0f),
                    exportDirectory, Collections.singletonList(pixels), null, new DetectionConfig(), null, pixels, null, null, null, null,
                    Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
            List<List<DetectedObject>> transients = Collections.singletonList(Collections.singletonList(detection(20, 20, 0)));
            StringWriter html = new StringWriter();

            UnclassifiedTransientSectionWriter.writeSection(new PrintWriter(html), context, transients, transients);

            assertFalse(new File(exportDirectory, "unclassified_frames").exists());
            assertFalse(html.toString().contains("data-strip="));
        } finally {
            UnclassifiedTransientSectionWriter.maxAnimatedMarkers = originalLimit;
        }
    }

    private static DetectedObject detection(double x, double y, int frameIndex) {
        DetectedObject detection = new DetectedObject(x, y, 100.0, 4);
        detection.sourceFrameIndex = frameIndex;
        detection.sourceFilename = "frame_" + frameIndex + ".fits";
        return detection;
    }
}
