package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.WcsSolutionResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the reusable astrometry/report context from FITS metadata and user-configured observatory settings.
 */
final class AstrometryContextFactory {

    private AstrometryContextFactory() {
    }

    static DetectionReportAstrometry.Context buildContext(FitsFileInformation[] fitsFiles, AppConfig appConfig) {
        WcsSolutionResolver.ResolvedWcsSolution wcsSolution = WcsSolutionResolver.resolve(null, fitsFiles);
        DetectionReportAstrometry.ObserverSite observerSite = resolveObserverSite(fitsFiles, appConfig);
        String observerCode = resolveSkybotObserverCode(appConfig);
        String observerCodeSource = observerCode != null ? "Configuration panel" : null;
        long sessionMidpointTimestampMillis = resolveSessionMidpointTimestamp(fitsFiles);
        return new DetectionReportAstrometry.Context(
                fitsFiles,
                wcsSolution,
                observerSite,
                observerCode,
                observerCodeSource,
                sessionMidpointTimestampMillis);
    }

    private static String resolveSkybotObserverCode(AppConfig appConfig) {
        return appConfig != null ? JplSbIdentUrlBuilder.normalizeObservatoryCode(appConfig.observatoryCode) : null;
    }

    private static DetectionReportAstrometry.ObserverSite resolveObserverSite(FitsFileInformation[] fitsFiles, AppConfig appConfig) {
        if (fitsFiles != null) {
            for (FitsFileInformation file : fitsFiles) {
                if (file == null) {
                    continue;
                }
                Double latitude = parseCoordinateValue(getFirstHeaderValue(file, "SITELAT", "OBSGEO-B", "LAT-OBS"));
                Double longitude = parseCoordinateValue(getFirstHeaderValue(file, "SITELONG", "SITELON", "OBSGEO-L", "LONG-OBS", "LON-OBS"));
                if (latitude != null && longitude != null) {
                    Double altitude = parseDoubleValue(getFirstHeaderValue(file, "SITEELEV", "OBSALT", "ALT-OBS", "ELEVATIO"));
                    DetectionReportAstrometry.ObserverSite observerSite = createObserverSite(latitude, longitude, altitude, "FITS header");
                    if (observerSite != null) {
                        return observerSite;
                    }
                }
            }
        }

        if (appConfig != null) {
            Double latitude = parseCoordinateValue(appConfig.siteLat);
            Double longitude = parseCoordinateValue(appConfig.siteLong);
            if (latitude != null && longitude != null) {
                return createObserverSite(latitude, longitude, 0.0, "Configuration panel");
            }
        }

        return null;
    }

    private static DetectionReportAstrometry.ObserverSite createObserverSite(Double latitudeDeg,
                                                                             Double longitudeDeg,
                                                                             Double altitudeMeters,
                                                                             String sourceLabel) {
        if (latitudeDeg == null || longitudeDeg == null
                || !Double.isFinite(latitudeDeg) || !Double.isFinite(longitudeDeg)
                || latitudeDeg < -90.0d || latitudeDeg > 90.0d) {
            return null;
        }

        double normalizedLongitude = DetectionReportAstrometry.normalizeLongitudeDegrees(longitudeDeg);
        if (!Double.isFinite(normalizedLongitude) || normalizedLongitude < -180.0d || normalizedLongitude > 180.0d) {
            return null;
        }

        double normalizedAltitudeMeters = altitudeMeters != null && Double.isFinite(altitudeMeters)
                ? altitudeMeters
                : 0.0d;
        return new DetectionReportAstrometry.ObserverSite(latitudeDeg, normalizedLongitude, normalizedAltitudeMeters, sourceLabel);
    }

    private static long resolveSessionMidpointTimestamp(FitsFileInformation[] fitsFiles) {
        if (fitsFiles == null || fitsFiles.length == 0) {
            return -1L;
        }

        List<Long> timestamps = new ArrayList<>();
        for (FitsFileInformation file : fitsFiles) {
            if (file == null) {
                continue;
            }
            long timestamp = file.getObservationTimestamp();
            if (timestamp > 0L) {
                timestamps.add(timestamp);
            }
        }

        if (timestamps.isEmpty()) {
            return -1L;
        }

        return timestamps.get(timestamps.size() / 2);
    }

    private static String getFirstHeaderValue(FitsFileInformation file, String... keys) {
        if (file == null || file.getFitsHeader() == null || keys == null) {
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

    private static Double parseCoordinateValue(String rawValue) {
        if (rawValue == null) {
            return null;
        }

        String cleaned = rawValue.replace("'", "").trim().toUpperCase(Locale.US);
        if (cleaned.isEmpty()) {
            return null;
        }

        boolean forceNegative = cleaned.endsWith("S") || cleaned.endsWith("W");
        boolean forcePositive = cleaned.endsWith("N") || cleaned.endsWith("E");
        if (forceNegative || forcePositive) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }

        cleaned = cleaned.replace(",", ".");
        Double parsed = parseDoubleValue(cleaned);
        if (parsed == null) {
            return null;
        }
        if (forceNegative) {
            return -Math.abs(parsed);
        }
        if (forcePositive) {
            return Math.abs(parsed);
        }
        return parsed;
    }

    private static Double parseDoubleValue(String rawValue) {
        if (rawValue == null) {
            return null;
        }

        String cleaned = rawValue.replace("'", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }

        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
