package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.DisplayStretch;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.config.VisualizationSettings;
import io.github.ppissias.jtransient.model.DetectedObject;
import org.junit.Test;
import javax.swing.SwingUtilities;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.List;
import static org.junit.Assert.*;

/** Export crops are device-pixel screenshots of frozen viewport state, including visible detection markers. */
public class FrameViewExportTest {
    /** Zoomed-out, full-size and zoomed-in views retain exact crop geometry and HiDPI rendering after live edits. */
    @Test public void cropMatchesDisplayedPixelsAcrossZoomAndDisplayScale() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            short[][] data = new short[640][800];
            for (int y = 0; y < data.length; y++) for (int x = 0; x < data[y].length; x++)
                data[y][x] = (short) ((x * 60 + y) % 65536 - 32768);
            var info = new FitsFileInformation("export.fit", "export.fit", true, 800, 640);
            FrameView view = new FrameView(); view.setSize(400, 320);
            view.showFrame(BlinkSequence.fromMono(info, data), new byte[][]{DisplayStretch.linearLookupTable()}, false);
            view.setDetections(List.of(new DetectedObject(400, 320, 1000, 9)));
            var track = new io.github.ppissias.jtransient.model.Track();
            for (int index = 0; index < 3; index++) {
                var point = new DetectedObject(370 + index * 20, 290 + index * 20, 1000, 9);
                point.sourceFrameIndex = index; point.sourceFilename = "export.fit"; track.addPoint(point);
            }
            var star = new com.google.gson.Gson().fromJson("{\"id\":1,\"x\":440,\"y\":340,\"tier\":\"HIGH_CONFIDENCE\",\"amplitude\":0.6}",
                    io.github.ppissias.jtransient.photometry.StarLightCurve.class);
            var analysis = new io.github.ppissias.jtransient.photometry.VariableStarAnalysis(
                    new io.github.ppissias.jtransient.photometry.PhotometricReadiness(), List.of(star),
                    new io.github.ppissias.jtransient.telemetry.PipelineTelemetry.PhotometryTelemetry());
            var result = io.github.ppissias.jtransient.engine.PipelineResult.builder().tracks(List.of(track)).variableStarAnalysis(analysis).build();
            var tracks = eu.startales.spacepixels.detection.TrackOverlay.from(result);
            var variables = eu.startales.spacepixels.detection.VariableStarOverlay.from(result, new FitsFileInformation[]{info}, null, null);
            Rectangle region = new Rectangle(31, 27, 281, 223);
            for (double zoom : new double[]{0.5, 1, 2}) for (double dpi : new double[]{1, 1.5, 2}) {
                view.fit(); view.zoomToScale(zoom); view.setDetectionsVisible(true);
                view.setOverlay(tracks); view.setOverlayVisible(true);
                view.setVariableStarOverlay(variables); view.setVariableStarsVisible(true);
                view.setSkyMarks(List.of(new SkyMark(SkyMark.Kind.DEEP_SKY, 350, 350, 9, 15, 10, 0, "Test galaxy", "Synthetic galaxy")), 15);
                assertEquals(zoom * 100, view.zoomPercent(), 1e-8);
                view.setMarkerSettings(VisualizationSettings::defaults);
                AffineTransform device = AffineTransform.getScaleInstance(dpi, dpi);
                BufferedImage full = new BufferedImage((int) Math.ceil(400 * dpi), (int) Math.ceil(320 * dpi), BufferedImage.TYPE_INT_RGB);
                var graphics = full.createGraphics(); graphics.scale(dpi, dpi); view.paint(graphics); graphics.dispose();
                FrameView frozen = view.copyForExport();
                view.fit(); view.setDetectionsVisible(false);
                view.setOverlay(null); view.setVariableStarOverlay(null); view.setSkyMarks(null, 15);
                view.setMarkerSettings(() -> VisualizationSettings.defaults().withPointSourceMinBoxRadius(30));
                BufferedImage crop = frozen.renderRegion(region, device);
                Rectangle expected = FrameView.deviceBounds(region, device);
                assertEquals(expected.width, crop.getWidth()); assertEquals(expected.height, crop.getHeight());
                assertArrayEquals(full.getRGB(expected.x, expected.y, expected.width, expected.height, null, 0, expected.width),
                        crop.getRGB(0, 0, crop.getWidth(), crop.getHeight(), null, 0, crop.getWidth()));
                frozen.clear();
            }
        });
    }
}
