package eu.startales.spacepixels.report;

import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.fits.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class TargetVisualizationSectionWriterTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void computeWideDimensionsEnforcesClampingAndFrameBounds() {
        // Clamping up to at least 400x400 when 2x is smaller
        int[] dimsSmall = TargetVisualizationSectionWriter.computeWideDimensions(100, 150, 2000, 2000);
        assertEquals(400, dimsSmall[0]);
        assertEquals(400, dimsSmall[1]);

        // 2x scaling when within [400, 1000]
        int[] dimsNormal = TargetVisualizationSectionWriter.computeWideDimensions(250, 300, 2000, 2000);
        assertEquals(500, dimsNormal[0]);
        assertEquals(600, dimsNormal[1]);

        // Clamping down to at most 1000x1000 when 2x is larger
        int[] dimsLarge = TargetVisualizationSectionWriter.computeWideDimensions(600, 700, 2000, 2000);
        assertEquals(1000, dimsLarge[0]);
        assertEquals(1000, dimsLarge[1]);

        // Clamping to frame bounds when frame is smaller than 1000
        int[] dimsFrameLimited = TargetVisualizationSectionWriter.computeWideDimensions(300, 300, 500, 450);
        assertEquals(500, dimsFrameLimited[0]);
        assertEquals(450, dimsFrameLimited[1]);

        // Clamping to frame bounds even when frame is smaller than 400
        int[] dimsTinyFrame = TargetVisualizationSectionWriter.computeWideDimensions(100, 100, 300, 250);
        assertEquals(300, dimsTinyFrame[0]);
        assertEquals(250, dimsTinyFrame[1]);

        // Unspecified frame bounds (0) fall back to [400, 1000] clamping
        int[] dimsNoFrame = TargetVisualizationSectionWriter.computeWideDimensions(100, 150, 0, 0);
        assertEquals(400, dimsNoFrame[0]);
        assertEquals(400, dimsNoFrame[1]);
    }

    @Test
    public void writesMovingTargetCleanNarrowAndWideAnimationsAndDownloadControls() throws Exception {
        int width = 600;
        int height = 500;
        List<short[][]> rawFrames = new ArrayList<>();
        FitsFileInformation[] files = new FitsFileInformation[3];
        for (int i = 0; i < 3; i++) {
            short[][] frame = new short[height][width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    frame[y][x] = (short) 100;
                }
            }
            // Put a bright target pixel
            frame[250][200 + i * 20] = (short) 2000;
            rawFrames.add(frame);
            files[i] = new FitsFileInformation("frame" + i + ".fit", "frame" + i + ".fit", true, width, height,
                    Map.of("DATE-OBS", "2026-10-01T00:0" + (2 * i) + ":00", "EXPTIME", "120"));
        }

        Track track = new Track();
        for (int i = 0; i < 3; i++) {
            DetectedObject point = new DetectedObject(200 + i * 20, 250, 1000, 8);
            point.sourceFrameIndex = i;
            point.timestamp = files[i].getObservationTimestamp();
            point.exposureDuration = files[i].getExposureDurationMillis();
            track.addPoint(point);
        }

        var telemetry = new PipelineTelemetry();
        telemetry.totalFramesLoaded = 3;
        telemetry.totalFramesKept = 3;

        var result = PipelineResult.builder()
                .tracks(List.of(track))
                .telemetry(telemetry)
                .masterStackData(rawFrames.get(0))
                .masterStars(List.of())
                .slowMoverCandidates(List.of())
                .anomalies(List.of())
                .allTransients(List.of())
                .unclassifiedTransients(List.of())
                .residualTransientAnalysis(null)
                .driftPoints(List.of())
                .maximumStackData(rawFrames.get(0))
                .variableStarAnalysis(null)
                .build();

        File exportDir = temporary.newFolder("report_export");
        DetectionReportContext context = ReportContextFixtures.create(
                VisualizationSettings.defaults(),
                exportDir,
                rawFrames,
                files,
                new DetectionConfig(),
                result);

        StringWriter sw = new StringWriter();
        try (PrintWriter writer = new PrintWriter(sw)) {
            TargetVisualizationSectionWriter.writeSection(writer, context);
        }

        String html = sw.toString();

        // 1. Verify download controls are in HTML with both links
        assertTrue("Must contain download-controls container", html.contains("<div class='download-controls'"));
        assertTrue("Must contain 'Download animation:' label", html.contains("<strong style='color: #ccc;'>Download animation:</strong>"));
        assertTrue("Must contain Narrow download link",
                html.contains("<a class='id-link' href='moving_track_1_clean_narrow.gif' download='moving_track_1_clean_narrow.gif' target='_blank'>Narrow</a>"));
        assertTrue("Must contain Wide download link",
                html.contains("<a class='id-link' href='moving_track_1_clean_wide.gif' download='moving_track_1_clean_wide.gif' target='_blank'>Wide</a>"));

        // 2. Verify clean GIF files exist on disk
        File narrowGifFile = new File(exportDir, "moving_track_1_clean_narrow.gif");
        File wideGifFile = new File(exportDir, "moving_track_1_clean_wide.gif");
        File starCentricGifFile = new File(exportDir, "moving_track_1_star_centric.gif");

        assertTrue("Narrow clean GIF must exist on disk", narrowGifFile.isFile());
        assertTrue("Wide clean GIF must exist on disk", wideGifFile.isFile());
        assertTrue("Star-centric GIF must exist on disk", starCentricGifFile.isFile());

        // 3. Verify Narrow clean GIF properties
        List<BufferedImage> narrowFrames = readGifFrames(narrowGifFile);
        assertEquals("Narrow GIF must have 3 frames", 3, narrowFrames.size());

        TrackCropGeometry.CropBounds cropBounds = new TrackCropGeometry.CropBounds(track, context.settings.getTrackCropPadding());
        assertEquals("Narrow GIF width must match track box width", cropBounds.trackBoxWidth, narrowFrames.get(0).getWidth());
        assertEquals("Narrow GIF height must match track box height", cropBounds.trackBoxHeight, narrowFrames.get(0).getHeight());

        // 4. Verify Wide clean GIF properties
        List<BufferedImage> wideFrames = readGifFrames(wideGifFile);
        assertEquals("Wide GIF must have 3 frames", 3, wideFrames.size());

        int[] expectedWideDims = TargetVisualizationSectionWriter.computeWideDimensions(
                cropBounds.trackBoxWidth, cropBounds.trackBoxHeight, width, height);
        assertEquals("Wide GIF width must match expected wide width", expectedWideDims[0], wideFrames.get(0).getWidth());
        assertEquals("Wide GIF height must match expected wide height", expectedWideDims[1], wideFrames.get(0).getHeight());

        // 5. Verify no highlight circle and no text label in clean narrow GIF compared to star-centric GIF
        List<BufferedImage> starFrames = readGifFrames(starCentricGifFile);
        assertEquals(3, starFrames.size());

        // Star-centric frame draws frame label string near top-left: Frame 1...
        // Check pixel (10, 18) or nearby text area: in star-centric it is non-black text, in clean narrow it is uniform background
        boolean starCentricHasLabelText = false;
        boolean cleanNarrowHasLabelText = false;
        for (int x = 10; x <= 40; x++) {
            for (int y = 10; y <= 20; y++) {
                int starRgb = starFrames.get(0).getRGB(x, y);
                int narrowRgb = narrowFrames.get(0).getRGB(x, y);
                // Check if starRgb is close to white label text (R > 200, G > 200, B > 200)
                int rStar = (starRgb >> 16) & 0xff;
                int rNarrow = (narrowRgb >> 16) & 0xff;
                if (rStar > 200) starCentricHasLabelText = true;
                if (rNarrow > 200) cleanNarrowHasLabelText = true;
            }
        }
        assertTrue("Star-centric GIF must contain text label overlay", starCentricHasLabelText);
        assertFalse("Clean narrow GIF must NOT contain text label overlay", cleanNarrowHasLabelText);

        // 6. Verify animations registry contains the clean GIFs
        assertTrue("Animations registry must include clean narrow GIF",
                context.animations.contains("moving_track_1_clean_narrow.gif"));
        assertTrue("Animations registry must include clean wide GIF",
                context.animations.contains("moving_track_1_clean_wide.gif"));
    }

    private static List<BufferedImage> readGifFrames(File gifFile) throws Exception {
        List<BufferedImage> frames = new ArrayList<>();
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        assertTrue("GIF image reader must be available", readers.hasNext());
        ImageReader reader = readers.next();
        try (ImageInputStream in = ImageIO.createImageInputStream(gifFile)) {
            reader.setInput(in, false, false);
            int count = reader.getNumImages(true);
            for (int i = 0; i < count; i++) {
                frames.add(reader.read(i));
            }
        } finally {
            reader.dispose();
        }
        return frames;
    }
}
