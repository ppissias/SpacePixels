package eu.startales.spacepixels.fits;

import eu.startales.spacepixels.util.SkyMath;

import java.util.List;
import java.util.Locale;

/**
 * The observer's place on Earth: from the first frame whose FITS header holds a latitude and longitude, else from the
 * configured fallback (Astrometry Config). A site in the header describes the frames actually loaded, so it also comes
 * before a configured IAU observatory code wherever a lookup accepts coordinates. Latitude is north-positive and longitude east-positive, in degrees;
 * altitude is in metres above the ellipsoid, 0 when unknown.
 */
public record ObservingSite(double latitudeDeg, double longitudeDeg, double altitudeMeters, String source) {

    /** Where the site came from, for {@link #source}. */
    public static final String FROM_HEADER = "FITS header";
    public static final String FROM_CONFIGURATION = "Configuration panel";

    /**
     * Header cards for the latitude, longitude and altitude, in order of preference, as capture programs, the FITS
     * standard and professional pipelines (OBSLAT/OBSLON/OBSALT, for example the Zwicky Transient Facility) write
     * them. Longitudes are east-positive in all of them.
     */
    public static final List<String> LATITUDE_KEYS = List.of("SITELAT", "OBSGEO-B", "LAT-OBS", "OBSLAT");
    public static final List<String> LONGITUDE_KEYS = List.of("SITELONG", "SITELON", "OBSGEO-L", "LONG-OBS", "LON-OBS", "OBSLON");
    public static final List<String> ALTITUDE_KEYS = List.of("SITEELEV", "OBSALT", "ALT-OBS", "ELEVATIO");

    /**
     * The site of these frames, or null when neither a header nor the fallback holds a valid one. The fallback values
     * are text as typed, such as {@code "37.98"} or {@code "24.6272S"}; either may be null.
     */
    public static ObservingSite resolve(FitsFileInformation[] files, String fallbackLatitude, String fallbackLongitude) {
        ObservingSite site = fromHeaders(files);
        return site != null ? site : fromConfiguration(fallbackLatitude, fallbackLongitude);
    }

    /** The site in the first frame whose header holds a valid latitude and longitude; null when none does. */
    public static ObservingSite fromHeaders(FitsFileInformation[] files) {
        if (files == null) {
            return null;
        }
        for (FitsFileInformation file : files) {
            if (file == null) {
                continue;
            }
            Double latitude = parseCoordinate(header(file, LATITUDE_KEYS));
            Double longitude = parseCoordinate(header(file, LONGITUDE_KEYS));
            if (latitude != null && longitude != null) {
                Double altitude = parseNumber(header(file, ALTITUDE_KEYS));
                ObservingSite site = of(latitude, longitude, altitude, FROM_HEADER);
                if (site != null) {
                    return site;
                }
            }
        }
        return null;
    }

    /** The configured fallback site, as typed (either may be null); null when it is not a valid place. */
    public static ObservingSite fromConfiguration(String latitudeText, String longitudeText) {
        Double latitude = parseCoordinate(latitudeText);
        Double longitude = parseCoordinate(longitudeText);
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

    private static String header(FitsFileInformation file, List<String> keys) {
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
