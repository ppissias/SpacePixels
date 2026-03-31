package eu.startales.spacepixels.util;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

public class ImageProcessingTest {

    @Test
    public void createsEphemeralConfigAndClampsSlowMoverFractionBelowMaximumForFiveFrames() {
        DetectionConfig baseConfig = new DetectionConfig();
        baseConfig.enableSlowMoverDetection = true;
        baseConfig.slowMoverStackMiddleFraction = 0.75;

        DetectionConfig effectiveConfig = ImageProcessing.createEffectiveDetectionConfig(baseConfig, 5);

        assertNotSame(baseConfig, effectiveConfig);
        assertEquals(0.75, baseConfig.slowMoverStackMiddleFraction, 0.0);
        assertEquals(0.60, effectiveConfig.slowMoverStackMiddleFraction, 1.0e-10);
        assertEquals(4, ImageProcessing.computeSlowMoverStackOrderIndex(5, baseConfig.slowMoverStackMiddleFraction));
        assertEquals(3, ImageProcessing.computeSlowMoverStackOrderIndex(5, effectiveConfig.slowMoverStackMiddleFraction));
    }

    @Test
    public void leavesAlreadySafeSlowMoverFractionUntouched() {
        DetectionConfig baseConfig = new DetectionConfig();
        baseConfig.enableSlowMoverDetection = true;
        baseConfig.slowMoverStackMiddleFraction = 0.60;

        DetectionConfig effectiveConfig = ImageProcessing.createEffectiveDetectionConfig(baseConfig, 7);

        assertEquals(0.60, effectiveConfig.slowMoverStackMiddleFraction, 1.0e-10);
        assertEquals(5, ImageProcessing.computeSlowMoverStackOrderIndex(7, effectiveConfig.slowMoverStackMiddleFraction));
    }

    @Test
    public void keepsFourFrameSlowMoverFractionAtThirdSortedSample() {
        DetectionConfig baseConfig = new DetectionConfig();
        baseConfig.enableSlowMoverDetection = true;
        baseConfig.slowMoverStackMiddleFraction = 0.75;

        DetectionConfig effectiveConfig = ImageProcessing.createEffectiveDetectionConfig(baseConfig, 4);

        assertEquals(0.75, effectiveConfig.slowMoverStackMiddleFraction, 1.0e-10);
        assertEquals(2, ImageProcessing.computeSlowMoverStackOrderIndex(4, baseConfig.slowMoverStackMiddleFraction));
        assertEquals(2, ImageProcessing.computeSlowMoverStackOrderIndex(4, effectiveConfig.slowMoverStackMiddleFraction));
    }
}
