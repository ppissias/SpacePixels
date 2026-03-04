package spv.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FrameQualityAnalyzer {

    //values used for statistics for frame quality
    public static double frameQualitySigmaMultiplier = 5;
    public static int frameQualityMinDetectionPixels = 5;

    public static class FrameMetrics {
        public double backgroundMedian;
        public double backgroundNoise;
        public double medianFWHM;
        public double medianEccentricity;
        public int starCount;
        public boolean isRejected = false;
        public String rejectionReason = "OK";
    }

    public static FrameMetrics evaluateFrame(short[][] imageData) {
        FrameMetrics metrics = new FrameMetrics();

        // 1. We use a high threshold (e.g., 5.0) for evaluation.
        // We only want to measure strong, distinct stars, not faint fuzzy noise.
        List<SourceExtractor.DetectedObject> objects =
                SourceExtractor.extractSources(imageData, frameQualitySigmaMultiplier, frameQualityMinDetectionPixels);

        // (Note: You'll need to expose your calculateBackground method in
        // SourceExtractor so we can grab the median/noise directly, or just
        // return it alongside the DetectedObjects).
        SourceExtractor.BackgroundMetrics bg = SourceExtractor.calculateBackgroundSigmaClipped(imageData, imageData[0].length, imageData.length,  frameQualitySigmaMultiplier);

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

    /**
     * Calculates the median elongation of all valid point sources in a frame.
     * A perfect frame is ~1.0. A bumped/trailed frame will be > 1.5.
     */
    public static double calculateFrameEccentricity(List<SourceExtractor.DetectedObject> objectsInFrame) {
        List<Double> elongations = new ArrayList<>();

        for (SourceExtractor.DetectedObject obj : objectsInFrame) {
            // Only measure true point sources (ignore obvious noise and massive satellite streaks)
            if (!obj.isNoise && !obj.isStreak) {
                elongations.add(obj.elongation);
            }
        }

        // If the frame has no stars, it's definitely a bad frame (clouds!)
        if (elongations.isEmpty()) {
            return 999.0;
        }

        // Sort to find the median
        elongations.sort(Double::compareTo);

        int middle = elongations.size() / 2;
        if (elongations.size() % 2 == 1) {
            return elongations.get(middle);
        } else {
            return (elongations.get(middle - 1) + elongations.get(middle)) / 2.0;
        }
    }
}
