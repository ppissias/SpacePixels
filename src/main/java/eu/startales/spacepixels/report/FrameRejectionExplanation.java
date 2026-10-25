package eu.startales.spacepixels.report;

import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import java.util.Locale;

/** Explains the engine's recorded rejection using its measured values and actual applied limits. */
final class FrameRejectionExplanation {
    private FrameRejectionExplanation() { }

    static String details(PipelineTelemetry telemetry, PipelineTelemetry.FrameRejectionStat rejection) {
        String reason = rejection.reason == null ? "" : rejection.reason.toLowerCase(Locale.ROOT);
        if (reason.startsWith("blank or failed registration")) {
            return "Required image coverage: at least 50% of the frame.";
        }
        var frame = telemetry.frameQualityStats.stream()
                .filter(f -> f.frameIndex == rejection.frameIndex).findFirst().orElse(null);
        if (frame == null) return "";
        var limits = telemetry.qualityThresholds;
        boolean available = limits != null && limits.available;
        if (reason.startsWith("star count")) {
            String observed = "Stars detected: " + frame.starCount;
            if (!available || !Double.isFinite(limits.minAllowedStarCount)) return observed + ".";
            double minimum = limits.minAllowedStarCount;
            return observed + String.format(Locale.US, "; required minimum: %.1f", minimum)
                    + (minimum > 0 && frame.starCount < minimum
                    ? String.format(Locale.US, " (%.1f%% below the minimum)", 100 * (minimum - frame.starCount) / minimum) : "") + ".";
        }
        if (reason.startsWith("fwhm")) {
            return upperLimit("FWHM", frame.medianFWHM, available ? limits.maxAllowedFwhm : Double.NaN, " px");
        }
        if (reason.startsWith("bright-star eccentricity")) {
            return upperLimit("Bright-star median eccentricity", frame.brightStarMedianEccentricity,
                    available ? limits.maxAllowedBrightStarEccentricity : Double.NaN, "");
        }
        if (reason.startsWith("eccentricity")) {
            return upperLimit("Median eccentricity", frame.medianEccentricity,
                    available ? limits.maxAllowedEccentricity : Double.NaN, "");
        }
        if (reason.startsWith("background deviation") && Double.isFinite(frame.backgroundMedian)) {
            String observed = String.format(Locale.US, "Sky background: %.2f ADU", frame.backgroundMedian);
            if (!available || !Double.isFinite(limits.backgroundMedianBaseline)
                    || !Double.isFinite(limits.maxAllowedBackgroundDeviation)) return observed + ".";
            return observed + String.format(Locale.US,
                    "; session baseline: %.2f ADU; deviation: %.2f ADU; maximum allowed deviation: %.2f ADU (allowed range %.2f–%.2f ADU).",
                    limits.backgroundMedianBaseline, Math.abs(frame.backgroundMedian - limits.backgroundMedianBaseline),
                    limits.maxAllowedBackgroundDeviation, limits.minAllowedBackgroundMedian, limits.maxAllowedBackgroundMedian);
        }
        return "";
    }

    private static String upperLimit(String label, double observed, double limit, String unit) {
        if (!Double.isFinite(observed)) return "";
        return String.format(Locale.US, "%s: %.3f%s", label, observed, unit)
                + (Double.isFinite(limit) ? String.format(Locale.US, "; maximum allowed: %.3f%s", limit, unit) : "") + ".";
    }
}
