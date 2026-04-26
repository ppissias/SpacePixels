package eu.startales.spacepixels.util.reporting;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Encapsulates JPL Small-Body Identification URL construction and the associated observer normalization rules.
 */
final class JplSbIdentUrlBuilder {

    private JplSbIdentUrlBuilder() {
    }

    static String build(String observerCode,
                        Double observerLatitudeDeg,
                        Double observerLongitudeDeg,
                        Double observerAltitudeMeters,
                        long timestampMillis,
                        double raDegrees,
                        double decDegrees,
                        double searchRadiusDegrees,
                        String smallBodyGroup) {
        if (timestampMillis <= 0L
                || !Double.isFinite(raDegrees)
                || !Double.isFinite(decDegrees)
                || !Double.isFinite(searchRadiusDegrees)
                || searchRadiusDegrees <= 0.0d) {
            return null;
        }

        double raHalfWidthDegrees = estimateJplRaHalfWidthDegrees(searchRadiusDegrees, decDegrees);
        StringBuilder url = new StringBuilder("https://ssd-api.jpl.nasa.gov/sb_ident.api?");
        if (!appendObserverParameters(url, observerCode, observerLatitudeDeg, observerLongitudeDeg, observerAltitudeMeters)) {
            return null;
        }
        url.append("&obs-time=").append(urlEncode(formatJplTimestamp(timestampMillis)));
        url.append("&fov-ra-center=").append(urlEncode(formatJplRa(raDegrees)));
        url.append("&fov-dec-center=").append(urlEncode(formatJplDec(decDegrees)));
        url.append("&fov-ra-hwidth=").append(urlEncode(formatDecimal(raHalfWidthDegrees, 4)));
        url.append("&fov-dec-hwidth=").append(urlEncode(formatDecimal(searchRadiusDegrees, 4)));
        url.append("&two-pass=true");
        url.append("&suppress-first-pass=true");
        url.append("&req-elem=false");
        if (smallBodyGroup != null && !smallBodyGroup.trim().isEmpty()) {
            url.append("&sb-group=").append(urlEncode(smallBodyGroup.trim().toLowerCase(Locale.US)));
        }
        return url.toString();
    }

    static boolean isJplCompatibleObservatoryCode(String observerCode) {
        String normalizedObserverCode = normalizeObservatoryCode(observerCode);
        return normalizedObserverCode != null && !"500".equals(normalizedObserverCode);
    }

    static String normalizeObservatoryCode(String observerCode) {
        if (observerCode == null) {
            return null;
        }

        String normalized = observerCode.trim().toUpperCase(Locale.US);
        return normalized.isEmpty() ? null : normalized;
    }

    static double normalizeLongitudeDegrees(double longitudeDegrees) {
        if (!Double.isFinite(longitudeDegrees)) {
            return Double.NaN;
        }

        double normalized = longitudeDegrees % 360.0d;
        if (normalized > 180.0d) {
            normalized -= 360.0d;
        }
        if (normalized < -180.0d) {
            normalized += 360.0d;
        }
        return normalized;
    }

    private static boolean appendObserverParameters(StringBuilder url,
                                                    String observerCode,
                                                    Double observerLatitudeDeg,
                                                    Double observerLongitudeDeg,
                                                    Double observerAltitudeMeters) {
        String normalizedObserverCode = normalizeObservatoryCode(observerCode);
        if (isJplCompatibleObservatoryCode(normalizedObserverCode)) {
            url.append("mpc-code=").append(urlEncode(normalizedObserverCode));
            return true;
        }

        if (observerLatitudeDeg == null || observerLongitudeDeg == null
                || !Double.isFinite(observerLatitudeDeg) || !Double.isFinite(observerLongitudeDeg)
                || observerLatitudeDeg < -90.0d || observerLatitudeDeg > 90.0d) {
            return false;
        }

        double normalizedLongitude = normalizeLongitudeDegrees(observerLongitudeDeg);
        if (!Double.isFinite(normalizedLongitude) || normalizedLongitude < -180.0d || normalizedLongitude > 180.0d) {
            return false;
        }

        double altitudeKm = observerAltitudeMeters != null && Double.isFinite(observerAltitudeMeters)
                ? observerAltitudeMeters / 1000.0d
                : 0.0d;
        url.append("lat=").append(urlEncode(formatDecimal(observerLatitudeDeg, 6)));
        url.append("&lon=").append(urlEncode(formatDecimal(normalizedLongitude, 6)));
        url.append("&alt=").append(urlEncode(formatDecimal(altitudeKm, 4)));
        return true;
    }

    private static String formatJplTimestamp(long timestampMillis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(timestampMillis));
    }

    private static String formatJplRa(double raDegrees) {
        double totalSeconds = normalizeDegrees(raDegrees) / 15.0 * 3600.0;
        int hours = (int) (totalSeconds / 3600.0);
        totalSeconds -= hours * 3600.0;
        int minutes = (int) (totalSeconds / 60.0);
        double seconds = totalSeconds - (minutes * 60.0);
        return String.format(Locale.US, "%02d-%02d-%05.2f", hours, minutes, seconds);
    }

    private static String formatJplDec(double decDegrees) {
        double absolute = Math.abs(decDegrees);
        int degrees = (int) absolute;
        double remainingMinutes = (absolute - degrees) * 60.0;
        int minutes = (int) remainingMinutes;
        double seconds = (remainingMinutes - minutes) * 60.0;
        String formatted = String.format(Locale.US, "%02d-%02d-%05.2f", degrees, minutes, seconds);
        return decDegrees < 0.0 ? "M" + formatted : formatted;
    }

    private static double estimateJplRaHalfWidthDegrees(double decHalfWidthDegrees, double centerDecDegrees) {
        double cosine = Math.cos(Math.toRadians(centerDecDegrees));
        if (Math.abs(cosine) < 0.15) {
            return Math.min(decHalfWidthDegrees * 6.0, 12.0);
        }
        return Math.min(decHalfWidthDegrees / Math.abs(cosine), 12.0);
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
