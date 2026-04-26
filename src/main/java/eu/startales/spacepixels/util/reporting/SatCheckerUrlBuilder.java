package eu.startales.spacepixels.util.reporting;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Encapsulates SatChecker field-of-view URL construction for report links and live-render actions.
 */
final class SatCheckerUrlBuilder {

    private SatCheckerUrlBuilder() {
    }

    static String buildFovUrl(DetectionReportAstrometry.ObserverSite observerSite,
                              long midpointTimestampMillis,
                              long durationSeconds,
                              double raDegrees,
                              double decDegrees,
                              double fovRadiusDegrees,
                              boolean asyncResponse) {
        if (observerSite == null
                || midpointTimestampMillis <= 0L
                || durationSeconds <= 0L
                || !Double.isFinite(observerSite.latitudeDeg)
                || !Double.isFinite(observerSite.longitudeDeg)
                || observerSite.latitudeDeg < -90.0d
                || observerSite.latitudeDeg > 90.0d
                || !Double.isFinite(raDegrees)
                || !Double.isFinite(decDegrees)
                || decDegrees < -90.0d
                || decDegrees > 90.0d
                || !Double.isFinite(fovRadiusDegrees)
                || fovRadiusDegrees <= 0.0d) {
            return null;
        }

        double normalizedLongitude = JplSbIdentUrlBuilder.normalizeLongitudeDegrees(observerSite.longitudeDeg);
        if (!Double.isFinite(normalizedLongitude)) {
            return null;
        }

        double elevationMeters = Double.isFinite(observerSite.altitudeMeters)
                ? observerSite.altitudeMeters
                : 0.0d;

        StringBuilder url = new StringBuilder("https://satchecker.cps.iau.org/fov/satellite-passes/?");
        url.append("latitude=").append(urlEncode(formatDecimal(observerSite.latitudeDeg, 6)));
        url.append("&longitude=").append(urlEncode(formatDecimal(normalizedLongitude, 6)));
        url.append("&elevation=").append(urlEncode(formatDecimal(elevationMeters, 1)));
        url.append("&mid_obs_time_jd=").append(urlEncode(formatJulianDate(midpointTimestampMillis)));
        url.append("&duration=").append(durationSeconds);
        url.append("&ra=").append(urlEncode(formatDecimal(normalizeDegrees(raDegrees), 6)));
        url.append("&dec=").append(urlEncode(formatDecimal(decDegrees, 6)));
        url.append("&fov_radius=").append(urlEncode(formatDecimal(fovRadiusDegrees, 4)));
        url.append("&group_by=satellite");
        url.append("&data_source=any");
        url.append("&async=").append(asyncResponse ? "True" : "False");
        return url.toString();
    }

    private static String formatJulianDate(long timestampMillis) {
        double julianDate = (timestampMillis / 86400000.0d) + 2440587.5d;
        return formatDecimal(julianDate, 8);
    }

    private static double normalizeDegrees(double degrees) {
        double normalized = degrees % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    private static String formatDecimal(double value, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", value);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
