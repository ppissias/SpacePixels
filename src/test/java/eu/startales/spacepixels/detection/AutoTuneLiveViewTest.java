package eu.startales.spacepixels.detection;

import eu.startales.spacepixels.config.VisualizationSettings;
import io.github.ppissias.jtransient.engine.AutoTuneSnapshot;
import org.junit.Test;

import java.awt.Color;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Checks detached RGB images, exact marker coordinates, mask geometry and failure-isolated lazy preparation. */
public class AutoTuneLiveViewTest {
    /** Regional detail preserves source-coordinate marker colours and never mutates or retains borrowed pixels. */
    @Test public void recoveryMarkersAndMaskUseDetachedRgbPixels() {
        short[][] pixels = new short[512][512];
        for (short[] row : pixels) Arrays.fill(row, (short) -31000);
        BitSet mask = new BitSet(512 * 512);
        mask.set(200 * 512 + 200);
        var snapshot = new AutoTuneSnapshot(AutoTuneSnapshot.Kind.RECOVERY, "Testing · 1/2 in this crop", pixels, mask,
                List.of(new AutoTuneSnapshot.Marker(100, 100, AutoTuneSnapshot.Mark.RECOVERED),
                        new AutoTuneSnapshot.Marker(150, 150, AutoTuneSnapshot.Mark.MISSED)), List.of());
        var preview = AutoTuneLiveView.render(snapshot, VisualizationSettings.defaults());
        var image = preview.image();
        assertEquals(384, image.getWidth());
        assertEquals(java.awt.image.BufferedImage.TYPE_INT_RGB, image.getType());
        assertEquals(new Color(70, 240, 100).getRGB(), image.getRGB(100 - 64 + 5, 100 - 64));
        assertEquals(new Color(255, 170, 50).getRGB(), image.getRGB(150 - 64 + 5, 150 - 64));
        Color masked = new Color(image.getRGB(200 - 64, 200 - 64));
        assertTrue(masked.getRed() > masked.getGreen());
        assertTrue(preview.caption().contains("central detail"));
        int old = image.getRGB(10, 10);
        pixels[74][74] = 30000;
        mask.clear();
        assertEquals(old, image.getRGB(10, 10));
        assertEquals((short) -31000, pixels[200][200]);
    }

    /** The sink controls all image work; a failed reservation must not fail candidate preparation. */
    @Test public void reservationsAreLazyAndFailuresAreIsolated() {
        AtomicInteger pictures = new AtomicInteger();
        var sink = new RunPreviewSink() {
            @Override public boolean wantsPreview(boolean change) { throw new IllegalStateException("unavailable view"); }
            @Override public void preview(RunPreview preview) { pictures.incrementAndGet(); }
        };
        try (var scope = RunPreviewScope.open(sink)) {
            RunPreviewScope.offer(false, () -> { fail("no picture should be made"); return null; });
        }
        assertEquals(0, pictures.get());
        assertNull(RunPreviewScope.currentSink());
    }
}
