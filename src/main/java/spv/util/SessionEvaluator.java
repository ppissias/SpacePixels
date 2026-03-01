package spv.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SessionEvaluator {

    // Define how strict we want the outlier rejection to be (e.g., 2.5 sigma)
    private static final double OUTLIER_SIGMA = 2.5;

    public static void rejectOutlierFrames(List<FrameQualityAnalyzer.FrameMetrics> sessionMetrics) {
        if (sessionMetrics.size() < 3) return; // Not enough data for statistical analysis

        // 1. Extract the raw numbers into lists
        List<Double> fwhmValues = new ArrayList<>();
        List<Double> bgValues = new ArrayList<>();
        List<Double> starCounts = new ArrayList<>();

        for (FrameQualityAnalyzer.FrameMetrics m : sessionMetrics) {
            fwhmValues.add(m.medianFWHM);
            bgValues.add(m.backgroundMedian);
            starCounts.add((double) m.starCount);
        }

        // 2. Calculate the Session Medians and Sigma (MAD)
        double[] fwhmStats = calculateMedianAndSigma(fwhmValues);
        double[] bgStats = calculateMedianAndSigma(bgValues);
        double[] starStats = calculateMedianAndSigma(starCounts);

        System.out.println("Session Baseline - FWHM: " + fwhmStats[0] + ", Background: " + bgStats[0] + ", Stars: " + starStats[0]);

        // 3. Evaluate each frame against the global session baseline
        for (int i = 0; i < sessionMetrics.size(); i++) {
            FrameQualityAnalyzer.FrameMetrics m = sessionMetrics.get(i);

            // Rejection Logic: Is it too far from the median?

            // Stars: We only care if it drops too low (clouds). Too many stars is rarely a bad thing.
            if (m.starCount < starStats[0] - (OUTLIER_SIGMA * starStats[1])) {
                reject(m, "Star Count dropped anomalously low");
                continue;
            }

            // FWHM: We only care if it gets too high (blurry/bad focus/wind).
            if (m.medianFWHM > fwhmStats[0] + (OUTLIER_SIGMA * fwhmStats[1])) {
                reject(m, "FWHM spiked (Blurry image)");
                continue;
            }

            // Background: We care if it spikes (clouds reflecting light/car headlights)
            // or drops completely (camera disconnected).
            if (Math.abs(m.backgroundMedian - bgStats[0]) > (OUTLIER_SIGMA * bgStats[1])) {
                reject(m, "Background deviation (Clouds/Light leak)");
            }
        }
    }

    private static void reject(FrameQualityAnalyzer.FrameMetrics m, String reason) {
        m.isRejected = true;
        m.rejectionReason = reason;
    }

    /**
     * Helper to calculate Robust Statistics (Returns [Median, Sigma])
     */
    private static double[] calculateMedianAndSigma(List<Double> values) {
        Collections.sort(values);
        double median = values.get(values.size() / 2);

        List<Double> deviations = new ArrayList<>();
        for (Double val : values) {
            deviations.add(Math.abs(val - median));
        }
        Collections.sort(deviations);
        double mad = deviations.get(deviations.size() / 2);

        double sigma = 1.4826 * mad;

        // Prevent sigma from being 0 if all frames are identical
        if (sigma == 0.0) sigma = 0.001;

        return new double[]{median, sigma};
    }
}