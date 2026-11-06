package eu.startales.spacepixels.detection;

import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.SkyMath;
import io.github.ppissias.jtransient.config.DetectionConfig;
import java.util.Arrays;
import java.util.Locale;

/** Approximate pre-run resource information from metadata; never selects the actual engine search domain. */
public final class FaintMoverRuntimeEstimate {
    /** Pre-run display assumption in pixels; the engine independently measures its actual seeing width. */
    public static final double ASSUMED_FWHM_PIXELS = 2.5;

    /** Largest imported session's timing metadata; quality selection and measured seeing are not yet available. */
    public record ImportedSession(int frames, double spanMinutes, double halfSpanMinutes, String unavailableReason) {
        /** Whether a finite, positive timing span supports display-only motion conversions. */
        public boolean hasSpan() {
            return unavailableReason == null && spanMinutes > 0 && Double.isFinite(spanMinutes)
                    && halfSpanMinutes > 0 && Double.isFinite(halfSpanMinutes);
        }
    }

    /** Prevents construction of this metadata-only presentation helper. */
    private FaintMoverRuntimeEstimate() { }

    /** Returns the first usable header's field-centre arcseconds/pixel, or NaN without a WCS; reads no image pixels. */
    public static double angularPixelScale(FitsFileInformation[] files) {
        if (files == null) return Double.NaN;
        for (var file : files) {
            if (file == null) continue;
            double scale = angularPixelScale(WcsCoordinateTransformer.fromHeader(file.getFitsHeader()),
                    file.getSizeWidth(), file.getSizeHeight());
            if (Double.isFinite(scale)) return scale;
        }
        return Double.NaN;
    }

    /** Converts an X-axis pixel at the field centre through the supplied WCS; this is a local angular-rate equivalent. */
    public static double angularPixelScale(WcsCoordinateTransformer wcs, int width, int height) {
        if (wcs == null || width <= 0 || height <= 0) return Double.NaN;
        var a = wcs.pixelToSky(width / 2.0, height / 2.0);
        var b = wcs.pixelToSky(width / 2.0 + 1, height / 2.0);
        if (a == null || b == null) return Double.NaN;
        double scale = SkyMath.separationArcsec(a.getRaDegrees(), a.getDecDegrees(), b.getRaDegrees(), b.getDecDegrees());
        return Double.isFinite(scale) && scale > 0 ? scale : Double.NaN;
    }

    /** Reads only headers to choose the most populated session, with gaps over two hours separating sessions. */
    public static ImportedSession importedSession(FitsFileInformation[] files) {
        if (files == null || files.length == 0) return new ImportedSession(0, 0, 0, "Import frames to estimate the additional search time.");
        double[] times = Arrays.stream(files).filter(java.util.Objects::nonNull)
                .filter(f -> f.getObservationTimestamp() != -1L)
                .mapToDouble(f -> f.getObservationTimestamp() + Math.max(0, f.getExposureDurationMillis()) / 2.0).sorted().toArray();
        if (times.length != files.length) return new ImportedSession(times.length, 0, 0, "Capture times are required; the engine will report why a search is skipped.");
        int start = 0, bestStart = 0, bestEnd = 0;
        for (int end = 1; end <= times.length; end++) {
            if (end == times.length || times[end] - times[end - 1] > 120 * 60000.0) {
                if (end - start > bestEnd - bestStart) { bestStart = start; bestEnd = end; }
                start = end;
            }
        }
        int n = bestEnd - bestStart;
        double mean = Arrays.stream(times, bestStart, bestEnd).average().orElse(0);
        double halfSpan = Math.max(mean - times[bestStart], times[bestEnd - 1] - mean) / 60000.0;
        double span = (times[bestEnd - 1] - times[bestStart]) / 60000.0;
        return new ImportedSession(n, span, halfSpan, null);
    }

    /** Estimates an additional-time range from headers; quality selection and measured seeing are not yet known. */
    public static String describe(FitsFileInformation[] files, DetectionConfig config) {
        var session = importedSession(files);
        if (session.unavailableReason() != null) return session.unavailableReason();
        int n = session.frames();
        if (n < 8) return "At least 8 kept frames in one session are needed; quality control may reduce the available frames.";
        if (!session.hasSpan()) return "Different capture times are required to estimate the search.";
        double fwhm = ASSUMED_FWHM_PIXELS, span = session.spanMinutes(), halfSpan = session.halfSpanMinutes();
        double step = fwhm / halfSpan, min = config.faintMoverMinDisplacementFwhm * fwhm / span;
        double motions = Math.PI * Math.max(0, config.faintMoverMaxSpeedPixelsPerMinute * config.faintMoverMaxSpeedPixelsPerMinute - min * min) / (step * step);
        double seconds = 7e-11 * motions * n * files[0].getSizeWidth() * files[0].getSizeHeight();
        double margin = Math.ceil(config.faintMoverMaxSpeedPixelsPerMinute * halfSpan) + 16;
        double mib = Math.pow(512 + 2 * margin, 2) * (4 * n + 12) / (1024 * 1024);
        String time = seconds * 2 < 6 ? "under 0.1 min" : String.format(Locale.ROOT, "%.1f–%.1f min",
                Math.max(0.1, seconds / 120), Math.max(0.1, seconds / 30));
        return String.format(Locale.ROOT,
                "Approximate extra time: %s; limit %.1f min. About %.0f MiB per search worker. "
                        + "Based on %d imported frames and an assumed %s px FWHM over %.2f min. "
                        + "Estimated minimum speed = %.2f FWHM × %s px / span = %.5f px/min. "
                        + "CPU, seeing, masks and kept frames change this estimate.",
                time, config.faintMoverMaxSeconds / 60, mib, n, fwhm, span, config.faintMoverMinDisplacementFwhm, fwhm, min);
    }
}
