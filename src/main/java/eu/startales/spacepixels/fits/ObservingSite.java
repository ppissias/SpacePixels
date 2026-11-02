package eu.startales.spacepixels.fits;

import eu.startales.spacepixels.util.SkyMath;

import java.util.Locale;

/**
 * The observer's place on Earth: from the first frame whose FITS header holds a latitude and longitude, else from the
 * configured fallback (Astrometry Config). Latitude is north-positive and longitude east-positive, in degrees;
 * altitude is in metres above the ellipsoid, 0 when unknown.
 */
public record ObservingSite(double latitudeDeg, double longitudeDeg, double altitudeMeters, String source) {

    /** Where the site came from, for {@link #source}. */
    public static final String FROM_HEADER = "FITS header";
    public static final String FROM_CONFIGURATION = "Configuration panel";

    /**
     * The site of these frames, or null when neither a header nor the fallback holds a valid one. The fallback values
     * are text as typed, such as {@code "37.98"} or {@code "24.6272S"}; either may be null.
     */
    public static ObservingSite resolve(FitsFileInformation[] files, String fallbackLatitude, String fallbackLongitude) {
        if (files != null) {
            for (FitsFileInformation file : files) {
                if (file == null) {
                    continue;
                }
                Double latitude = parseCoordinate(header(file, "SITELAT", "OBSGEO-B", "LAT-OBS"));
                Double longitude = parseCoordinate(header(file, "SITELONG", "SITELON", "OBSGEO-L", "LONG-OBS", "LON-OBS"));
                if (latitude != null && longitude != null) {
                    Double altitude = parseNumber(header(file, "SITEELEV", "OBSALT", "ALT-OBS", "ELEVATIO"));
                    ObservingSite site = of(latitude, longitude, altitude, FROM_HEADER);
                    if (site != null) {
                        return site;
                    }
                }
            }
        }
        Double latitude = parseCoordinate(fallbackLatitude);
        Double longitude = parseCoordinate(fallbackLongitude);
        return latitude == null || longitude == null ? null : of(latitude, longitude, 0.0, FROM_CONFIGURATION);
    }

    /** A checked site with the longitude brought into [-180, 180], or null when the values are not a place on Earth. */
    private static ObservingSite of(Double latitudeDeg, Double longitudeDeg, Double altitudeMeters, String source) {
        if (!Double.isFinite(latitudeDeg) || !Double.isFinite(longitudeDeg) || latitudeDeg < -90.0 || latitudeDeg > 90.0) {
            return null;
        }
        double longitude = SkyMath.normalizeSignedDegrees(longitudeDeg);
        if (!Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
            return null;
        }
        double altitude = altitudeMeters != null && Double.isFinite(altitudeMeters) ? altitudeMeters : 0.0;
        return new ObservingSite(latitudeDeg, longitude, altitude, source);
    }

    private static String header(FitsFileInformation file, String... keys) {
        if (file.getFitsHeader() == null) {
            return null;
        }
        for (String key : keys) {
            String value = file.getFitsHeader().get(key);
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** Decimal degrees, signed or with a hemisphere letter ("24.6272S"); a comma may be the decimal mark. Null when not a number. */
    static Double parseCoordinate(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String cleaned = rawValue.replace("'", "").trim().toUpperCase(Locale.US);
        if (cleaned.isEmpty()) {
            return null;
        }
        boolean negative = cleaned.endsWith("S") || cleaned.endsWith("W");
        boolean positive = cleaned.endsWith("N") || cleaned.endsWith("E");
        if (negative || positive) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        Double parsed = parseNumber(cleaned.replace(",", "."));
        if (parsed == null) {
            return null;
        }
        return negative ? -Math.abs(parsed) : positive ? Math.abs(parsed) : parsed;
    }

    private static Double parseNumber(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String cleaned = rawValue.replace("'", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
