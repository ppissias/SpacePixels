package spv.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FrameQualityAnalyzer {

    public static class FrameMetrics {
        public double backgroundMedian;
        public double backgroundNoise;
        public double medianFWHM;
        public int starCount;
        public boolean isRejected = false;
        public String rejectionReason = "OK";
    }

    public static FrameMetrics evaluateFrame(short[][] imageData) {
        FrameMetrics metrics = new FrameMetrics();

        // 1. We use a high threshold (e.g., 5.0) for evaluation.
        // We only want to measure strong, distinct stars, not faint fuzzy noise.
        List<SourceExtractor.DetectedObject> objects =
                SourceExtractor.extractSources(imageData, 5.0, 5);

        // (Note: You'll need to expose your calculateBackground method in
        // SourceExtractor so we can grab the median/noise directly, or just
        // return it alongside the DetectedObjects).
        SourceExtractor.BackgroundMetrics bg = SourceExtractor.calculateBackground(imageData, imageData.length, imageData[0].length, 5.0);

        metrics.backgroundMedian = bg.median;
        metrics.backgroundNoise = bg.sigma;
        metrics.starCount = objects.size();

        // 2. Calculate Median FWHM from ROUND stars only
        List<Double> fwhmValues = new ArrayList<>();
        for (SourceExtractor.DetectedObject obj : objects) {
            // Ignore streaks and heavily distorted stars when judging focus
            if (!obj.isStreak && obj.elongation < 1.5) {
                fwhmValues.add(obj.fwhm);
            }
        }

        if (!fwhmValues.isEmpty()) {
            Collections.sort(fwhmValues);
            metrics.medianFWHM = fwhmValues.get(fwhmValues.size() / 2);
        } else {
            metrics.medianFWHM = 999.0; // Terrible score if no round stars exist
        }

        return metrics;
    }
}
