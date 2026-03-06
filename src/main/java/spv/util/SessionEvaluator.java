package spv.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SessionEvaluator {

    // =================================================================
    // CONFIGURATION PARAMETERS
    // =================================================================

    /** * The minimum number of frames required in a session to perform meaningful statistical analysis.
     * If the session has fewer frames than this, outlier rejection is safely skipped.
     */
    public static int minFramesForAnalysis = 3;

    /** * How many standard deviations (Sigma) a frame's star count can drop below the session median
     * before being rejected. A low star count usually indicates passing clouds or heavy haze.
     */
    public static double starCountSigmaDeviation = 2.0;

    /** * How many standard deviations a frame's FWHM (Full Width at Half Maximum) can spike above
     * the session median before being rejected. A high FWHM indicates bad focus, wind blur, or poor seeing.
     */
    public static double fwhmSigmaDeviation = 2.5;

    /** * How many standard deviations a frame's eccentricity can spike above the session median
     * before being rejected. High eccentricity means stars are trailing (mount tracking error, wind bump).
     */
    public static double eccentricitySigmaDeviation = 3.0;

    /** * How many standard deviations the background sky brightness can deviate (up or down) from
     * the session median. A spike means stray light (car headlights, moonrise), a drop means thick clouds.
     */
    public static double backgroundSigmaDeviation = 3.0;

    /**
     * Fallback value used if all frames in a session are mathematically identical (Sigma = 0).
     * Prevents division by zero or overly aggressive rejection in perfect/synthetic datasets.
     */
    public static double zeroSigmaFallback = 0.001;

    // =================================================================
    // CORE EVALUATION LOGIC
    // =================================================================

    public static void rejectOutlierFrames(List<FrameQualityAnalyzer.FrameMetrics> sessionMetrics) {
        // Parameterized minimum frames check
        if (sessionMetrics.size() < minFramesForAnalysis) return;

        // 1. Extract the raw numbers into lists
        List<Double> fwhmValues = new ArrayList<>();
        List<Double> bgValues = new ArrayList<>();
        List<Double> starCounts = new ArrayList<>();
        List<Double> eccValues = new ArrayList<>();

        for (FrameQualityAnalyzer.FrameMetrics m : sessionMetrics) {
            fwhmValues.add(m.medianFWHM);
            bgValues.add(m.backgroundMedian);
            starCounts.add((double) m.starCount);
            eccValues.add(m.medianEccentricity);
        }

        // 2. Calculate the Session Medians and Sigma (MAD)
        double[] fwhmStats = calculateMedianAndSigma(fwhmValues);
        double[] bgStats = calculateMedianAndSigma(bgValues);
        double[] starStats = calculateMedianAndSigma(starCounts);
        double[] eccStats = calculateMedianAndSigma(eccValues);

        System.out.println(String.format(
                "Session Baseline - FWHM: %.2f, Background: %.2f, Stars: %.0f, Eccentricity: %.2f",
                fwhmStats[0], bgStats[0], starStats[0], eccStats[0]
        ));

        // 3. Evaluate each frame against the global session baseline
        for (int i = 0; i < sessionMetrics.size(); i++) {
            FrameQualityAnalyzer.FrameMetrics m = sessionMetrics.get(i);

            // Stars: We only care if it drops too low (clouds).
            if (m.starCount < starStats[0] - (starCountSigmaDeviation * starStats[1])) {
                reject(m, "Star Count dropped anomalously low");
                continue;
            }

            // FWHM: We only care if it gets too high (blurry/bad focus/wind).
            if (m.medianFWHM > fwhmStats[0] + (fwhmSigmaDeviation * fwhmStats[1])) {
                reject(m, "FWHM spiked (Blurry image)");
                continue;
            }

            // Eccentricity: We only care if it gets too high (tracking error, mount bump, wind).
            if (m.medianEccentricity > eccStats[0] + (eccentricitySigmaDeviation * eccStats[1])) {
                reject(m, "Eccentricity spiked (Tracking error/Wind)");
                continue;
            }

            // Background: We care if it spikes (car headlights) or drops completely.
            if (Math.abs(m.backgroundMedian - bgStats[0]) > (backgroundSigmaDeviation * bgStats[1])) {
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

        // 1.4826 is the constant to convert Median Absolute Deviation (MAD) to standard deviation (Sigma)
        double sigma = 1.4826 * mad;

        // Prevent sigma from being 0 if all frames are identical
        if (sigma == 0.0) sigma = zeroSigmaFallback;

        return new double[]{median, sigma};
    }
}