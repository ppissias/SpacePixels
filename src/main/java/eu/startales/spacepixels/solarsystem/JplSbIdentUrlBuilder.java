package eu.startales.spacepixels.solarsystem;
import eu.startales.spacepixels.util.SkyMath;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Encapsulates JPL Small-Body Identification URL construction and the associated observer normalization rules: the
 * report's lookup around one finding, and the search of a whole field by {@link SolarSystemClient}. Both ask for the
 * numerical (second) pass only, and both include comets: by default JPL leaves out every object without asteroid
 * magnitude parameters, which is every comet ({@code mag-required=false} keeps them).
 */
public final class JplSbIdentUrlBuilder {

    private JplSbIdentUrlBuilder() {
    }

    /** A search around one position, {@code searchRadiusDegrees} on the sky in each direction; null when incomplete. */
    public static String build(String observerCode,
                        Double observerLatitudeDeg,
                        Double observerLongitudeDeg,
                        Double observerAltitudeMeters,
                        long timestampMillis,
                        double raDegrees,
                        double decDegrees,
                        double searchRadiusDegrees,
                        String smallBodyGroup) {
        if (!Double.isFinite(searchRadiusDegrees) || searchRadiusDegrees <= 0.0d || !Double.isFinite(decDegrees)) {
            return null;
        }
        return buildField(observerCode, observerLatitudeDeg, observerLongitudeDeg, observerAltitudeMeters, timestampMillis,
                raDegrees, decDegrees, estimateJplRaHalfWidthDegrees(searchRadiusDegrees, decDegrees), searchRadiusDegrees,
                null, smallBodyGroup);
    }

    /**
     * A search of a field: half-widths in degrees of RA and of Dec (not on the sky), objects to {@code vmagLimit} (null
     * for all), and optionally one small-body group such as {@code "neo"}. Null when a value is missing or invalid.
     */
    public static String buildField(String observerCode,
                                    Double observerLatitudeDeg,
                                    Double observerLongitudeDeg,
                                    Double observerAltitudeMeters,
                                    long timestampMillis,
                                    double raCenterDegrees,
                                    double decCenterDegrees,
                                    double raHalfWidthDegrees,
                                    double decHalfWidthDegrees,
                                    Double vmagLimit,
                                    String smallBodyGroup) {
        if (timestampMillis <= 0L
                || !Double.isFinite(raCenterDegrees)
                || !Double.isFinite(decCenterDegrees)
                || !Double.isFinite(raHalfWidthDegrees) || raHalfWidthDegrees <= 0.0d
                || !Double.isFinite(decHalfWidthDegrees) || decHalfWidthDegrees <= 0.0d
                || (vmagLimit != null && !Double.isFinite(vmagLimit))) {
            return null;
        }

        StringBuilder url = new StringBuilder("https://ssd-api.jpl.nasa.gov/sb_ident.api?");
        if (!appendObserverParameters(url, observerCode, observerLatitudeDeg, observerLongitudeDeg, observerAltitudeMeters)) {
            return null;
        }
        url.append("&obs-time=").append(urlEncode(formatJplTimestamp(timestampMillis)));
        url.append("&fov-ra-center=").append(urlEncode(formatJplRa(raCenterDegrees)));
        url.append("&fov-dec-center=").append(urlEncode(formatJplDec(decCenterDegrees)));
        url.append("&fov-ra-hwidth=").append(urlEncode(formatDecimal(raHalfWidthDegrees, 4)));
        url.append("&fov-dec-hwidth=").append(urlEncode(formatDecimal(decHalfWidthDegrees, 4)));
        url.append("&two-pass=true");
        url.append("&suppress-first-pass=true");
        url.append("&req-elem=false");
        url.append("&mag-required=false");
        if (vmagLimit != null) {
            url.append("&vmag-lim=").append(urlEncode(formatDecimal(vmagLimit, 1)));
        }
        if (smallBodyGroup != null && !smallBodyGroup.trim().isEmpty()) {
            url.append("&sb-group=").append(urlEncode(smallBodyGroup.trim().toLowerCase(Locale.US)));
        }
        return url.toString();
    }

    public static boolean isJplCompatibleObservatoryCode(String observerCode) {
        String normalizedObserverCode = normalizeObservatoryCode(observerCode);
        return normalizedObserverCode != null && !"500".equals(normalizedObserverCode);
    }

    public static String normalizeObservatoryCode(String observerCode) {
        if (observerCode == null) {
            return null;
        }

        String normalized = observerCode.trim().toUpperCase(Locale.US);
        return normalized.isEmpty() ? null : normalized;
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

        double normalizedLongitude = SkyMath.normalizeSignedDegrees(observerLongitudeDeg);
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
        long ticks = Math.round(SkyMath.normalizeDegrees(raDegrees) / 15.0 * 360000) % 8640000;
        return String.format(Locale.US, "%02d-%02d-%05.2f", ticks / 360000, ticks / 6000 % 60, ticks % 6000 / 100.0);
    }

    private static String formatJplDec(double decDegrees) {
        long ticks = Math.round(Math.abs(decDegrees) * 360000);
        String formatted = String.format(Locale.US, "%02d-%02d-%05.2f", ticks / 360000, ticks / 6000 % 60, ticks % 6000 / 100.0);
        return decDegrees < 0.0 ? "M" + formatted : formatted;
    }

    private static double estimateJplRaHalfWidthDegrees(double decHalfWidthDegrees, double centerDecDegrees) {
        double cosine = Math.cos(Math.toRadians(centerDecDegrees));
        if (Math.abs(cosine) < 0.15) {
            return Math.min(decHalfWidthDegrees * 6.0, 12.0);
        }
        return Math.min(decHalfWidthDegrees / Math.abs(cosine), 12.0);
    }

    private static String formatDecimal(double value, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", value);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
