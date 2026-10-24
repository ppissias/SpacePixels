package eu.startales.spacepixels.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Headless summary text, eligibility and report-only selection compatibility. */
public class DetectionSummaryTest {

    @Test
    public void largestGroupNamesTheBiggestKindOfResult() {
        ImageProcessing.DetectionSummary summary = new ImageProcessing.DetectionSummary(
                80, 2, 1, 4, 50, 5, 6, 2, "READY", 3, 1, 4, 12, 3);

        assertEquals("single-frame anomalies (50)", summary.largestGroup());
    }

    @Test
    public void reportChoiceIsOfferedOnlyWhenTheDetectionRanThatGroup() {
        assertFalse(ImageProcessing.DetectionSummary.offerInReport(false, 40));
        assertFalse(ImageProcessing.DetectionSummary.offerInReport(true, 0));
        assertTrue(ImageProcessing.DetectionSummary.offerInReport(true, 40));
    }

    /** Keeps the legacy confirmation entry point safe without implicitly opening a window. */
    @SuppressWarnings("deprecation")
    @Test
    public void compatibilityConfirmationDeclinesLargeReportsWithoutInteraction() {
        ImageProcessing.DetectionSummary summary = new ImageProcessing.DetectionSummary(
                51, 0, 0, 51, 0, 0, 0, 0, null, 0, 0, 0, 0, 0);
        ReportInclusion selected = new ReportInclusion(false, true, true, true, true, true, true);
        summary.includeInReport(selected);
        org.junit.Assert.assertSame(selected, summary.reportInclusion());
        assertEquals(51, summary.totalDetections);
    }
}
