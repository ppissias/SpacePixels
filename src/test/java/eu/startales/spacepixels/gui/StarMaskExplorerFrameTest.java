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
    public void reduceKeepsEveryNthPixel() {
        short[][] data = new short[9][9];
        data[6][3] = 77;
        short[][] reduced = StarMaskExplorerFrame.reduce(data, 3);
        assertEquals(3, reduced.length);
        assertEquals(77, reduced[2][1]);
    }
}
