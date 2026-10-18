package eu.startales.spacepixels.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DetectionSummaryTest {

    @Test
    public void warningKeepsSlowMoversAndLocalRescueApart() {
        ImageProcessing.DetectionSummary summary = new ImageProcessing.DetectionSummary(
                77, 2, 1, 4, 50, 5, 6, 2, "READY", 3, 1, 4, 12, 7);
        String message = summary.warningMessage(true, 50);

        assertTrue(message.contains("Slow-mover candidates (maximum stack): 5"));
        assertTrue(message.contains("Local rescue candidates: 6"));
        assertFalse("no combined slow-mover figure", message.contains(": 11"));
        assertTrue(message.contains("Variable stars: 3 candidates, 1 possible"));
        assertTrue(message.contains("a plate-solved frame is available"));
        assertTrue(message.contains("Largest group: single-frame anomalies (50)"));
        assertTrue(message.contains("Common-motion groups: 4"));
        assertTrue(message.contains("Still unclassified, not counted above: 12"));
        assertTrue(message.contains("Single-frame anomalies: 50 (7 low confidence)"));
    }

    @Test
    public void warningSaysWhenPhotometryAndAstrometryAreMissing() {
        ImageProcessing.DetectionSummary off = new ImageProcessing.DetectionSummary(
                60, 0, 0, 60, 0, 0, 0, 0, null, 0, 0, 0, 0, 0);
        assertTrue(off.warningMessage(false, 50).contains("Variable stars: off"));
        assertTrue(off.warningMessage(false, 50).contains("no plate-solved frame"));

        ImageProcessing.DetectionSummary notReady = new ImageProcessing.DetectionSummary(
                60, 0, 0, 60, 0, 0, 0, 0, "NOT_READY", 0, 0, 0, 0, 0);
        assertTrue(notReady.warningMessage(false, 50).contains("not ready for photometry"));
    }

    @Test
    public void reportChoiceIsOfferedOnlyWhenTheDetectionRanThatGroup() {
        assertFalse(ImageProcessing.DetectionSummary.offerInReport(false, 40));
        assertFalse(ImageProcessing.DetectionSummary.offerInReport(true, 0));
        assertTrue(ImageProcessing.DetectionSummary.offerInReport(true, 40));
    }
}
