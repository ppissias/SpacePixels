package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.util.FitsFileInformation;
import io.github.ppissias.jtransient.model.DetectedObject;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** The detection markers of the frame viewers: their size comes from the visualization settings a window supplies. */
public class FrameViewMarkersTest {
    /** A detection's box takes its size from the marker settings the window supplies, read again at each paint. */
    @Test public void detectionBoxesFollowTheSuppliedMarkerSettings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FrameView view = new FrameView();
            view.setSize(400, 320);
            FitsFileInformation info = new FitsFileInformation("markers.fit", "markers.fit", true, 200, 160);
            view.showFrame(BlinkSequence.fromMono(info, new short[160][200]), new byte[1][65536], false);
            view.setDetections(List.of(new DetectedObject(100, 80, 1000, 9)));
            int builtIn = markerWidth(view);

            AtomicReference<VisualizationSettings> settings = new AtomicReference<>(VisualizationSettings.defaults());
            view.setMarkerSettings(settings::get);
            assertEquals(builtIn, markerWidth(view));
            settings.set(VisualizationSettings.defaults().withPointSourceMinBoxRadius(30));
            int larger = markerWidth(view);

            assertTrue(builtIn + " then " + larger, larger > 2.5 * builtIn);
        });
    }

    /** The width of the green detection box drawn on the black frame, in screen pixels. */
    private static int markerWidth(FrameView view) {
        BufferedImage image = new BufferedImage(400, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        view.paint(graphics);
        graphics.dispose();
        int left = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                if (green > 100 && green > red + 50) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
        }
        assertTrue("a detection box is drawn", right >= left);
        return right - left + 1;
    }
}
