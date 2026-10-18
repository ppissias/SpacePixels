package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrameRejectionExplanationTest {
    @Test public void lowStarCountUsesTheRejectedFrameAndTheAppliedMinimum() {
        PipelineTelemetry telemetry = new PipelineTelemetry();
        var other = frame(2); other.starCount = 900;
        var rejected = frame(7); rejected.starCount = 120;
        telemetry.frameQualityStats.add(other);
        telemetry.frameQualityStats.add(rejected);
        telemetry.qualityThresholds.available = true;
        telemetry.qualityThresholds.minAllowedStarCount = 400;
        String details = FrameRejectionExplanation.details(telemetry, rejection(7, "Star Count dropped anomalously low"));
        assertTrue(details, details.contains("120"));
        assertTrue(details, details.contains("400.0"));
        assertTrue(details, details.contains("70.0% below"));
        assertFalse(details, details.contains("900"));
    }

    @Test public void shapeRejectionsShowTheirOwnMeasurementsAndLimits() {
        PipelineTelemetry t = new PipelineTelemetry();
        var f = frame(0); f.medianFWHM = 4.2; f.medianEccentricity = .72; f.brightStarMedianEccentricity = .81;
        t.frameQualityStats.add(f);
        t.qualityThresholds.available = true;
        t.qualityThresholds.maxAllowedFwhm = 3.5;
        t.qualityThresholds.maxAllowedEccentricity = .65;
        t.qualityThresholds.maxAllowedBrightStarEccentricity = .75;
        assertTrue(FrameRejectionExplanation.details(t, rejection(0, "FWHM spiked (Blurry image)")).contains("4.200 px; maximum allowed: 3.500 px"));
        assertTrue(FrameRejectionExplanation.details(t, rejection(0, "Eccentricity spiked (Tracking error/Wind)")).contains("0.720; maximum allowed: 0.650"));
        assertTrue(FrameRejectionExplanation.details(t, rejection(0, "Bright-star eccentricity spiked (Tracking error/Wind)")).contains("0.810; maximum allowed: 0.750"));
    }

    @Test public void backgroundRejectionShowsBaselineDeviationAndAllowedRange() {
        PipelineTelemetry t = new PipelineTelemetry();
        var f = frame(0); f.backgroundMedian = 1240;
        t.frameQualityStats.add(f);
        var limits = t.qualityThresholds;
        limits.available = true;
        limits.backgroundMedianBaseline = 1000;
        limits.maxAllowedBackgroundDeviation = 100;
        limits.minAllowedBackgroundMedian = 900;
        limits.maxAllowedBackgroundMedian = 1100;
        String text = FrameRejectionExplanation.details(t, rejection(0, "Background deviation (Clouds/Light leak)"));
        assertTrue(text, text.contains("deviation: 240.00 ADU"));
        assertTrue(text, text.contains("maximum allowed deviation: 100.00 ADU"));
        assertTrue(text, text.contains("900.00–1100.00 ADU"));
    }

    @Test public void unavailableMeasurementsDoNotInventLimits() {
        PipelineTelemetry t = new PipelineTelemetry();
        var rejection = rejection(0, "Star Count dropped anomalously low");
        assertEquals("", FrameRejectionExplanation.details(t, rejection));
        var f = frame(0); f.starCount = 120; t.frameQualityStats.add(f);
        assertEquals("Stars detected: 120.", FrameRejectionExplanation.details(t, rejection));
        assertTrue(FrameRejectionExplanation.details(t, rejection(0, "Blank or failed registration (less than half of the pixels hold image data)")).contains("50%"));
    }

    private static PipelineTelemetry.FrameQualityStat frame(int index) {
        var frame = new PipelineTelemetry.FrameQualityStat(); frame.frameIndex = index; return frame;
    }
    private static PipelineTelemetry.FrameRejectionStat rejection(int index, String reason) {
        var rejection = new PipelineTelemetry.FrameRejectionStat(); rejection.frameIndex = index; rejection.reason = reason; return rejection;
    }
}
