package eu.startales.spacepixels.detection;

import eu.startales.spacepixels.fits.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import java.util.Arrays;
import java.util.Locale;

/** Approximate pre-run resource information from metadata; never selects the actual engine search domain. */
public final class FaintMoverRuntimeEstimate {
    /** Prevents construction of this metadata-only presentation helper. */
    private FaintMoverRuntimeEstimate() { }

    /** Estimates an additional-time range from headers; quality selection and measured seeing are not yet known. */
    public static String describe(FitsFileInformation[] files, DetectionConfig config) {
        if (files == null || files.length == 0) return "Import frames to estimate the additional search time.";
        double[] times = Arrays.stream(files).filter(java.util.Objects::nonNull)
                .filter(f -> f.getObservationTimestamp() != -1L)
                .mapToDouble(f -> f.getObservationTimestamp() + Math.max(0, f.getExposureDurationMillis()) / 2.0).sorted().toArray();
        if (times.length != files.length) return "Capture times are required; the engine will report why a search is skipped.";
        int start = 0, bestStart = 0, bestEnd = 0;
        for (int end = 1; end <= times.length; end++) {
            if (end == times.length || times[end] - times[end - 1] > 120 * 60000.0) {
                if (end - start > bestEnd - bestStart) { bestStart = start; bestEnd = end; }
                start = end;
            }
        }
        int n = bestEnd - bestStart;
        if (n < 8) return "At least 8 kept frames in one session are needed; quality control may reduce the available frames.";
        double mean = Arrays.stream(times, bestStart, bestEnd).average().orElse(0);
        double halfSpan = Math.max(mean - times[bestStart], times[bestEnd - 1] - mean) / 60000.0;
        if (!(halfSpan > 0)) return "Different capture times are required to estimate the search.";
        double fwhm = 2.5, span = (times[bestEnd - 1] - times[bestStart]) / 60000.0;
        double step = fwhm / halfSpan, min = config.faintMoverMinDisplacementFwhm * fwhm / span;
        double motions = Math.PI * Math.max(0, config.faintMoverMaxSpeedPixelsPerMinute * config.faintMoverMaxSpeedPixelsPerMinute - min * min) / (step * step);
        double seconds = 7e-11 * motions * n * files[0].getSizeWidth() * files[0].getSizeHeight();
        double margin = Math.ceil(config.faintMoverMaxSpeedPixelsPerMinute * halfSpan) + 16;
        double mib = Math.pow(512 + 2 * margin, 2) * (4 * n + 12) / (1024 * 1024);
        String time = seconds * 2 < 6 ? "under 0.1 min" : String.format(Locale.ROOT, "%.1f–%.1f min",
                Math.max(0.1, seconds / 120), Math.max(0.1, seconds / 30));
        return String.format(Locale.ROOT,
                "Approximate extra time: %s; limit %.1f min. About %.0f MiB per search worker. "
                        + "Based on %d imported frames and an assumed 2.5 px FWHM; CPU, seeing, masks and kept frames change this estimate.",
                time, config.faintMoverMaxSeconds / 60, mib, n);
    }
}
