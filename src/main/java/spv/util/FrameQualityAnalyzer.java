package spv.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FrameQualityAnalyzer {

    // =================================================================
    // CONFIGURATION PARAMETERS
    // =================================================================

    /** * The sigma multiplier used to extract sources specifically for frame quality evaluation.
     * We use a high threshold (e.g., 5.0) because we only want to measure strong, distinct stars
     * with high Signal-to-Noise Ratios, completely ignoring faint fuzzy noise.
     */
    public static double qualitySigmaMultiplier = 5.0;

    /** * The minimum number of contiguous pixels a source must have to be considered a valid star
     * during the quality evaluation phase.
     */
    public static int qualityMinDetectionPixels = 5;

    /**
     * The maximum elongation ratio (length/width) a star can have to be included in the
     * FWHM (focus) calculation. If a star is stretched beyond this (e.g., > 1.5),
     * its FWHM metric is corrupted by trailing, so we exclude it from focus evaluations.
     */
    public static double maxElongationForFwhm = 1.5;

    /**
     * The fallback metric value assigned when a frame is completely devoid of valid stars
     * (e.g., due to thick clouds, a closed dome, or a massive tracking failure).
     * This artificially high number ensures the SessionEvaluator instantly rejects it.
     */
    public static double errorFallbackValue = 999.0;


    // =================================================================
    // DATA MODELS
    // =================================================================

    public static class FrameMetrics {
        public double backgroundMedian;
        public double backgroundNoise;
        public double medianFWHM;
        public double medianEccentricity;
        public int starCount;
        public boolean isRejected = false;
        public String rejectionReason = "OK";
    }

    // =================================================================
    // EVALUATION LOGIC
    // =================================================================

    public static FrameMetrics evaluateFrame(short[][] imageData) {
        FrameMetrics metrics = new FrameMetrics();

        // 1. Extract sources using the strict quality-evaluation parameters
        List<SourceExtractor.DetectedObject> objects =
                SourceExtractor.extractSources(imageData, qualitySigmaMultiplier, qualityMinDetectionPixels);

        // Calculate the background using the same strict parameter
        SourceExtractor.BackgroundMetrics bg = SourceExtractor.calculateBackgroundSigmaClipped(
                imageData, imageData[0].length, imageData.length, qualitySigmaMultiplier);

        metrics.backgroundMedian = bg.median;
        metrics.backgroundNoise = bg.sigma;
        metrics.starCount = objects.size();

        // 2. Calculate Median FWHM from ROUND stars only
        List<Double> fwhmValues = new ArrayList<>();
        for (SourceExtractor.DetectedObject obj : objects) {

            // Parameterized check: Ignore streaks and heavily distorted stars when judging focus
            if (!obj.isStreak && obj.elongation < maxElongationForFwhm) {
                fwhmValues.add(obj.fwhm);
            }
        }

        if (!fwhmValues.isEmpty()) {
            Collections.sort(fwhmValues);
            metrics.medianFWHM = fwhmValues.get(fwhmValues.size() / 2);
        } else {
            // Parameterized fallback: Terrible score if no round stars exist
            metrics.medianFWHM = errorFallbackValue;
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

        // Parameterized fallback: If the frame has no stars, it's definitely a bad frame (clouds!)
        if (elongations.isEmpty()) {
            return errorFallbackValue;
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