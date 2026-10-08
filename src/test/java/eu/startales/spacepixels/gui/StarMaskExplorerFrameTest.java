package eu.startales.spacepixels.gui;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;

public class StarMaskExplorerFrameTest {

    @Test
    public void trialValuesReplaceOnlyTheStarMaskSettings() {
        DetectionConfig base = new DetectionConfig();
        base.detectionSigmaMultiplier = 4.2;
        DetectionConfig trial = StarMaskExplorerFrame.configFor(base, new StarMaskExplorerFrame.Settings(3.0, 1.5, 7, 2.5));
        assertEquals(3.0, trial.masterSigmaMultiplier, 1e-12);
        assertEquals(1.5, trial.masterGrowSigmaMultiplier, 1e-12);
        assertEquals(7, trial.masterMinDetectionPixels);
        assertEquals(2.5, trial.maxStarJitter, 1e-12);
        assertEquals(4.2, trial.detectionSigmaMultiplier, 1e-12);
        assertEquals("the base is not changed", new DetectionConfig().masterSigmaMultiplier, base.masterSigmaMultiplier, 1e-12);
    }

    @Test
    public void overlayMarksMaskedPixelsAndKeepsThemWhenReduced() {
        boolean[][] mask = new boolean[10][10];
        mask[3][5] = true;
        Color colour = new Color(255, 140, 0, 125);

        BufferedImage full = StarMaskExplorerFrame.overlay(mask, 1, colour);
        assertEquals(125, full.getRGB(5, 3) >>> 24);
        assertEquals(0, full.getRGB(4, 3) >>> 24);

        // Reduced by 4: the block containing (5, 3) is masked, so a single masked pixel never disappears.
        BufferedImage reduced = StarMaskExplorerFrame.overlay(mask, 4, colour);
        assertEquals(3, reduced.getWidth());
        assertEquals(125, reduced.getRGB(1, 0) >>> 24);
        assertEquals(0, reduced.getRGB(0, 0) >>> 24);
    }

    @Test
    public void overlayLevelsHalveDownToAbout512PixelsAndKeepMaskedPixels() {
        boolean[][] mask = new boolean[3000][4000];
        mask[1500][2001] = true;
        java.util.List<BufferedImage> levels = StarMaskExplorerFrame.overlayLevels(mask, new Color(255, 140, 0, 125));
        // 4000 / 2^k > 512 for k = 0..2, plus the first level at or below 512 px: 4000, 2000, 1000, 500.
        assertEquals(4, levels.size());
        assertEquals(4000, levels.get(0).getWidth());
        assertEquals(500, levels.get(3).getWidth());
        assertEquals(125, levels.get(3).getRGB(2001 / 8, 1500 / 8) >>> 24);
    }

    @Test
    public void zoomedOutViewsDrawFromTheMatchingReducedCopy() {
        assertEquals(0, ZoomableImageView.levelFor(1.0));
        assertEquals(0, ZoomableImageView.levelFor(4.0));
        assertEquals(0, ZoomableImageView.levelFor(0.6));
        assertEquals(1, ZoomableImageView.levelFor(0.5));
        assertEquals(3, ZoomableImageView.levelFor(0.11));
    }
}
