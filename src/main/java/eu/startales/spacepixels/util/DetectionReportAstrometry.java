/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */

package eu.startales.spacepixels.util;

import eu.startales.spacepixels.config.AppConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DetectionReportAstrometry {

    static final class Context {
        private final FitsFileInformation[] fitsFiles;
        private final WcsSolutionResolver.ResolvedWcsSolution wcsSolution;
        private final ObserverSite observerSite;
        private final String skybotObserverCode;
        private final String skybotObserverSource;
        private final long sessionMidpointTimestampMillis;

        private Context(FitsFileInformation[] fitsFiles,
                        WcsSolutionResolver.ResolvedWcsSolution wcsSolution,
                        ObserverSite observerSite,
                        String skybotObserverCode,
                        String skybotObserverSource,
                        long sessionMidpointTimestampMillis) {
            this.fitsFiles = fitsFiles;
            this.wcsSolution = wcsSolution;
            this.observerSite = observerSite;
            this.skybotObserverCode = skybotObserverCode;
            this.skybotObserverSource = skybotObserverSource;
            this.sessionMidpointTimestampMillis = sessionMidpointTimestampMillis;
        }

        WcsCoordinateTransformer getTransformer() {
            return wcsSolution != null ? wcsSolution.getTransformer() : null;
        }

        boolean hasAstrometricSolution() {
            return getTransformer() != null;
        }

        String getWcsSummary() {
            if (wcsSolution == null) {
                return "No aligned-frame WCS solution available";
            }
            String scope = wcsSolution.isSharedAcrossAlignedSet() ? "shared aligned WCS" : "native frame WCS";
            return scope + " from " + escapeHtml(wcsSolution.getSourceFileName()) + " (" + escapeHtml(wcsSolution.getSourceType()) + ")";
        }

        long getFrameTimestampMillis(int frameIndex) {
            if (fitsFiles == null || frameIndex < 0 || frameIndex >= fitsFiles.length || fitsFiles[frameIndex] == null) {
                return -1L;
            }
            return fitsFiles[frameIndex].getObservationTimestamp();
        }

        boolean hasSkybotObserverCode() {
            return skybotObserverCode != null && !skybotObserverCode.isEmpty();
        }

        String getPreferredSkybotObserver() {
            return hasSkybotObserverCode() ? skybotObserverCode : "500";
        }

        long getSessionMidpointTimestampMillis() {
            return sessionMidpointTimestampMillis;
        }

        String getSkybotObserverCode() {
            return skybotObserverCode;
        }

        String getSkybotObserverSource() {
            return skybotObserverSource;
        }

        String getObserverSiteSourceLabel() {
            return observerSite != null ? observerSite.sourceLabel : null;
        }
    }

    private static final class ObserverSite {
        private final double latitudeDeg;
        private final double longitudeDeg;
        private final double altitudeMeters;
        private final String sourceLabel;

        private ObserverSite(double latitudeDeg, double longitudeDeg, double altitudeMeters, String sourceLabel) {
            this.latitudeDeg = latitudeDeg;
            this.longitudeDeg = longitudeDeg;
            this.altitudeMeters = altitudeMeters;
            this.sourceLabel = sourceLabel;
        }
    }

    private static final class SolarSystemQueryTarget {
        private final double pixelX;
        private final double pixelY;
        private final long timestampMillis;
        private final double searchRadiusDegrees;
        private final String summaryLabel;

        private SolarSystemQueryTarget(double pixelX, double pixelY, long timestampMillis, double searchRadiusDegrees, String summaryLabel) {
            this.pixelX = pixelX;
            this.pixelY = pixelY;
            this.timestampMillis = timestampMillis;
            this.searchRadiusDegrees = searchRadiusDegrees;
            this.summaryLabel = summaryLabel;
        }
    }

    private DetectionReportAstrometry() {
    }

    static Context buildContext(FitsFileInformation[] fitsFiles, AppConfig appConfig) {
        WcsSolutionResolver.ResolvedWcsSolution wcsSolution = WcsSolutionResolver.resolve(null, fitsFiles);
        ObserverSite observerSite = resolveObserverSite(fitsFiles, appConfig);
        String observerCode = resolveSkybotObserverCode(appConfig);
        String observerCodeSource = observerCode != null ? "Configuration panel" : null;
        long sessionMidpointTimestampMillis = resolveSessionMidpointTimestamp(fitsFiles);
        return new Context(fitsFiles, wcsSolution, observerSite, observerCode, observerCodeSource, sessionMidpointTimestampMillis);
    }

    static String buildDeepStackIdentificationHtml(Context astrometryContext,
                                                   SourceExtractor.DetectedObject detection) {
        SolarSystemQueryTarget queryTarget = buildSingleDetectionQueryTarget(astrometryContext, detection);
        return buildSolarSystemIdentificationHtml(astrometryContext, queryTarget,
                "Reference epoch for stack lookup",
                "Search SkyBoT",
                "SkyBoT search radius");
    }

    static String buildTrackSolarSystemIdentificationHtml(Context astrometryContext,
                                                          TrackLinker.Track track) {
        SolarSystemQueryTarget queryTarget = buildTrackQueryTarget(astrometryContext, track);
        return buildSolarSystemIdentificationHtml(astrometryContext, queryTarget,
                "Reference epoch for track lookup",
                "Search SkyBoT (Track Midpoint)",
                "SkyBoT search radius");
    }

    static String buildSingleFrameSkyViewerHtml(Context astrometryContext,
                                                SourceExtractor.DetectedObject detection,
                                                String epochLabel) {
        SolarSystemQueryTarget queryTarget = buildFrameDetectionQueryTarget(astrometryContext, detection);
        return buildSkyViewerLinksHtml(astrometryContext, queryTarget, epochLabel);
    }

    static String buildTrackSkyViewerHtml(Context astrometryContext,
                                          TrackLinker.Track track,
                                          String epochLabel) {
        SolarSystemQueryTarget queryTarget = buildTrackQueryTarget(astrometryContext, track);
        return buildSkyViewerLinksHtml(astrometryContext, queryTarget, epochLabel);
    }

    static String formatPixelCoordinateWithSky(Context astrometryContext, double pixelX, double pixelY) {
        String pixel = String.format(Locale.US, "X: %.1f, Y: %.1f", pixelX, pixelY);
        String sky = formatSkyCoordinateInline(astrometryContext, pixelX, pixelY);
        return sky == null ? pixel : pixel + " | " + sky;
    }

    static String buildSourceCoordinateListEntry(String fileLabel,
                                                 Context astrometryContext,
                                                 double pixelX,
                                                 double pixelY,
                                                 String metricsText) {
        StringBuilder html = new StringBuilder();
        html.append("<li>");
        html.append("<div class='source-file'>").append(escapeHtml(fileLabel)).append("</div>");
        html.append(formatPixelCoordinateBlockHtml(astrometryContext, pixelX, pixelY));
        if (metricsText != null && !metricsText.isEmpty()) {
            html.append("<div class='source-metrics'>").append(escapeHtml(metricsText)).append("</div>");
        }
        html.append("</li>");
        return html.toString();
    }

    private static String buildSolarSystemIdentificationHtml(Context astrometryContext,
                                                             SolarSystemQueryTarget queryTarget,
                                                             String epochLabel,
                                                             String buttonLabel,
                                                             String radiusLabel) {
        StringBuilder html = new StringBuilder();
        html.append("<div class='astro-note'>");

        if (astrometryContext == null || !astrometryContext.hasAstrometricSolution()) {
            html.append("Solar-system identification link unavailable: no reusable aligned-frame WCS solution was found for this session.");
            html.append("</div>");
            return html.toString();
        }

        if (queryTarget == null) {
            html.append("Solar-system identification link unavailable: no valid timestamp could be resolved for this detection.");
            html.append("</div>");
            return html.toString();
        }

        WcsCoordinateTransformer.SkyCoordinate skyCoordinate = astrometryContext.getTransformer().pixelToSky(queryTarget.pixelX, queryTarget.pixelY);
        html.append("Sky position: <span class='coord-highlight'>RA ")
                .append(escapeHtml(WcsCoordinateTransformer.formatRa(skyCoordinate.getRaDegrees())))
                .append(" | Dec ")
                .append(escapeHtml(WcsCoordinateTransformer.formatDec(skyCoordinate.getDecDegrees())))
                .append("</span>");
        if (queryTarget.summaryLabel != null && !queryTarget.summaryLabel.isEmpty()) {
            html.append("<br>").append(escapeHtml(queryTarget.summaryLabel)).append(".");
        }
        html.append("<br>WCS source: ").append(astrometryContext.getWcsSummary()).append(".");

        String epochText = formatUtcTimestamp(queryTarget.timestampMillis);
        if (queryTarget.timestampMillis > 0L) {
            html.append("<br>").append(escapeHtml(epochLabel)).append(": ").append(escapeHtml(epochText)).append(".");
        } else {
            html.append("<br>Reference epoch unavailable: no valid frame timestamps were found in the FITS headers.");
        }

        if (astrometryContext.hasSkybotObserverCode()) {
            html.append("<br>Observer used for lookup: IAU code ")
                    .append(escapeHtml(astrometryContext.skybotObserverCode))
                    .append(" (")
                    .append(escapeHtml(astrometryContext.skybotObserverSource))
                    .append(").");
        } else {
            html.append("<br>Observer used for lookup: geocenter (500).");
        }
        if (astrometryContext.observerSite != null) {
            html.append("<br>Known site coordinates: ")
                    .append(escapeHtml(String.format(Locale.US, "%.5f, %.5f (%s)",
                            astrometryContext.observerSite.latitudeDeg,
                            astrometryContext.observerSite.longitudeDeg,
                            astrometryContext.observerSite.sourceLabel)))
                    .append(".");
        }

        String nominalSkybotUrl = buildSkybotUrl(astrometryContext, queryTarget, queryTarget.searchRadiusDegrees, false);
        if (nominalSkybotUrl != null) {
            double nominalRadiusArcmin = queryTarget.searchRadiusDegrees * 60.0;
            double widerRadiusDegrees = Math.min(queryTarget.searchRadiusDegrees * 2.5, 2.0);
            double muchWiderRadiusDegrees = Math.min(queryTarget.searchRadiusDegrees * 6.0, 5.0);
            html.append("<div class='id-links'>");
            html.append("<a class='id-link' href='").append(nominalSkybotUrl).append("' target='_blank' rel='noopener noreferrer'>").append(escapeHtml(buttonLabel)).append("</a>");
            html.append("<a class='id-link' href='").append(buildSkybotUrl(astrometryContext, queryTarget, widerRadiusDegrees, false)).append("' target='_blank' rel='noopener noreferrer'>SkyBoT Wide Cone</a>");
            html.append("<a class='id-link' href='").append(buildSkybotUrl(astrometryContext, queryTarget, muchWiderRadiusDegrees, false)).append("' target='_blank' rel='noopener noreferrer'>SkyBoT Much Wider Cone</a>");
            if (astrometryContext.hasSkybotObserverCode()) {
                html.append("<a class='id-link' href='").append(buildSkybotUrl(astrometryContext, queryTarget, queryTarget.searchRadiusDegrees, true)).append("' target='_blank' rel='noopener noreferrer'>SkyBoT Geocenter</a>");
            }
            html.append("</div>");
            html.append("<div class='astro-note' style='margin-top: 6px;'>")
                    .append(escapeHtml(radiusLabel)).append(": ")
                    .append(escapeHtml(String.format(Locale.US, "%.2f arcmin", nominalRadiusArcmin)))
                    .append(" | Wide: ")
                    .append(escapeHtml(String.format(Locale.US, "%.2f arcmin", widerRadiusDegrees * 60.0)))
                    .append(" | Much wider: ")
                    .append(escapeHtml(String.format(Locale.US, "%.2f arcmin", muchWiderRadiusDegrees * 60.0)))
                    .append(" (asteroids + comets).</div>");
        }

        html.append(buildSkyViewerLinksHtml(astrometryContext, queryTarget, epochLabel));

        html.append("</div>");
        return html.toString();
    }

    private static SolarSystemQueryTarget buildSingleDetectionQueryTarget(Context astrometryContext,
                                                                          SourceExtractor.DetectedObject detection) {
        if (astrometryContext == null || detection == null) {
            return null;
        }

        long timestampMillis = astrometryContext.sessionMidpointTimestampMillis;
        double radiusDegrees = estimateSkybotSearchRadiusDegrees(astrometryContext, detection.x, detection.y, detection.pixelArea, detection.elongation);
        return new SolarSystemQueryTarget(detection.x, detection.y, timestampMillis, radiusDegrees, null);
    }

    private static SolarSystemQueryTarget buildFrameDetectionQueryTarget(Context astrometryContext,
                                                                         SourceExtractor.DetectedObject detection) {
        if (astrometryContext == null || detection == null) {
            return null;
        }

        long timestampMillis = astrometryContext.getFrameTimestampMillis(detection.sourceFrameIndex);
        if (timestampMillis <= 0L) {
            timestampMillis = astrometryContext.sessionMidpointTimestampMillis;
        }

        double radiusDegrees = estimateSkybotSearchRadiusDegrees(astrometryContext, detection.x, detection.y, detection.pixelArea, detection.elongation);
        return new SolarSystemQueryTarget(detection.x, detection.y, timestampMillis, radiusDegrees, "Single-frame detection");
    }

    private static SolarSystemQueryTarget buildTrackQueryTarget(Context astrometryContext,
                                                                TrackLinker.Track track) {
        if (astrometryContext == null || track == null || track.points == null || track.points.isEmpty()) {
            return null;
        }

        double sumX = 0.0;
        double sumY = 0.0;
        double sumArea = 0.0;
        double sumElongation = 0.0;
        int count = 0;
        long timestampAccumulator = 0L;
        int timestampCount = 0;

        for (SourceExtractor.DetectedObject point : track.points) {
            if (point == null) {
                continue;
            }
            sumX += point.x;
            sumY += point.y;
            sumArea += Math.max(1.0, point.pixelArea);
            sumElongation += Math.max(1.0, point.elongation);
            count++;

            long timestampMillis = astrometryContext.getFrameTimestampMillis(point.sourceFrameIndex);
            if (timestampMillis > 0L) {
                timestampAccumulator += timestampMillis;
                timestampCount++;
            }
        }

        if (count == 0) {
            return null;
        }

        long timestampMillis = timestampCount > 0 ? Math.round((double) timestampAccumulator / timestampCount) : -1L;
        double pixelX = sumX / count;
        double pixelY = sumY / count;
        double avgArea = sumArea / count;
        double avgElongation = sumElongation / count;
        double radiusDegrees = estimateSkybotSearchRadiusDegrees(astrometryContext, pixelX, pixelY, avgArea, avgElongation);
        return new SolarSystemQueryTarget(pixelX, pixelY, timestampMillis, radiusDegrees,
                "Track midpoint from " + count + " detections");
    }

    private static String buildSkybotUrl(Context astrometryContext,
                                         SolarSystemQueryTarget queryTarget,
                                         double searchRadiusDegrees,
                                         boolean forceGeocenterObserver) {
        if (astrometryContext == null || !astrometryContext.hasAstrometricSolution() || queryTarget == null || queryTarget.timestampMillis <= 0L) {
            return null;
        }

        WcsCoordinateTransformer.SkyCoordinate skyCoordinate = astrometryContext.getTransformer().pixelToSky(queryTarget.pixelX, queryTarget.pixelY);
        String observer = forceGeocenterObserver ? "500" : astrometryContext.getPreferredSkybotObserver();

        StringBuilder url = new StringBuilder("https://ssp.imcce.fr/webservices/skybot/api/conesearch.php?");
        url.append("-ep=").append(urlEncode(formatSkybotTimestamp(queryTarget.timestampMillis)));
        url.append("&-ra=").append(urlEncode(formatDecimal(skyCoordinate.getRaDegrees(), 6)));
        url.append("&-dec=").append(urlEncode(formatDecimal(skyCoordinate.getDecDegrees(), 6)));
        url.append("&-rd=").append(urlEncode(formatDecimal(searchRadiusDegrees, 6)));
        url.append("&-mime=html");
        url.append("&-output=all");
        url.append("&-observer=").append(urlEncode(observer));
        url.append("&-objFilter=101");
        url.append("&-from=SpacePixels");
        return url.toString();
    }

    private static String buildSkyViewerLinksHtml(Context astrometryContext,
                                                  SolarSystemQueryTarget queryTarget,
                                                  String epochLabel) {
        StringBuilder html = new StringBuilder();
        html.append("<div class='astro-note' style='margin-top: 8px;'>");

        if (astrometryContext == null || !astrometryContext.hasAstrometricSolution()) {
            html.append("Interactive sky-viewer links unavailable: no reusable aligned-frame WCS solution was found for this session.");
            html.append("</div>");
            return html.toString();
        }

        if (queryTarget == null || queryTarget.timestampMillis <= 0L) {
            html.append("Interactive sky-viewer links unavailable: no valid timestamp could be resolved for this detection.");
            html.append("</div>");
            return html.toString();
        }

        WcsCoordinateTransformer.SkyCoordinate skyCoordinate = astrometryContext.getTransformer().pixelToSky(queryTarget.pixelX, queryTarget.pixelY);
        double tightFovDegrees = clampFieldOfViewDegrees(queryTarget.searchRadiusDegrees * 8.0, 0.5, 12.0);
        double wideFovDegrees = clampFieldOfViewDegrees(queryTarget.searchRadiusDegrees * 22.0, 4.0, 60.0);

        html.append("Interactive sky viewers:");
        html.append("<div class='id-links'>");
        html.append("<a class='id-link' href='")
                .append(buildStellariumWebUrl(astrometryContext, queryTarget, skyCoordinate, tightFovDegrees))
                .append("' target='_blank' rel='noopener noreferrer'>Stellarium Web (RA/Dec)</a>");
        html.append("<a class='id-link' href='")
                .append(buildStellariumWebUrl(astrometryContext, queryTarget, skyCoordinate, wideFovDegrees))
                .append("' target='_blank' rel='noopener noreferrer'>Stellarium Web Wide</a>");
        html.append("</div>");

        html.append("<div class='astro-note' style='margin-top: 6px;'>")
                .append("Stellarium links use best-effort RA/Dec URL parameters at ")
                .append(escapeHtml(formatUtcTimestamp(queryTarget.timestampMillis)))
                .append(" with FOV ")
                .append(escapeHtml(String.format(Locale.US, "%.2f° / %.2f°", tightFovDegrees, wideFovDegrees)))
                .append(".");
        if (epochLabel != null && !epochLabel.isEmpty()) {
            html.append(" ").append(escapeHtml(epochLabel)).append(": ")
                    .append(escapeHtml(formatUtcTimestamp(queryTarget.timestampMillis)))
                    .append(".");
        }
        html.append("</div>");
        html.append("</div>");
        return html.toString();
    }

    private static String buildStellariumWebUrl(Context astrometryContext,
                                                SolarSystemQueryTarget queryTarget,
                                                WcsCoordinateTransformer.SkyCoordinate skyCoordinate,
                                                double fovDegrees) {
        StringBuilder url = new StringBuilder("https://stellarium-web.org/?");
        url.append("date=").append(urlEncode(formatStellariumTimestamp(queryTarget.timestampMillis)));
        url.append("&ra=").append(urlEncode(formatDecimal(skyCoordinate.getRaDegrees(), 6)));
        url.append("&dec=").append(urlEncode(formatDecimal(skyCoordinate.getDecDegrees(), 6)));
        url.append("&fov=").append(urlEncode(formatDecimal(fovDegrees, 4)));
        if (astrometryContext != null && astrometryContext.observerSite != null) {
            url.append("&lat=").append(urlEncode(formatDecimal(astrometryContext.observerSite.latitudeDeg, 5)));
            url.append("&lng=").append(urlEncode(formatDecimal(astrometryContext.observerSite.longitudeDeg, 5)));
            url.append("&elev=").append(urlEncode(formatDecimal(astrometryContext.observerSite.altitudeMeters, 1)));
        }
        return url.toString();
    }

    private static double estimateSkybotSearchRadiusDegrees(Context astrometryContext,
                                                            double pixelX,
                                                            double pixelY,
                                                            double pixelArea,
                                                            double elongation) {
        double pixelScaleArcsec = estimateLocalPixelScaleArcsec(astrometryContext.getTransformer(), pixelX, pixelY);
        if (!Double.isFinite(pixelScaleArcsec) || pixelScaleArcsec <= 0.0) {
            pixelScaleArcsec = 2.0;
        }

        double objectRadiusPixels = pixelArea > 0
                ? Math.sqrt((pixelArea * Math.max(elongation, 1.0)) / Math.PI)
                : 15.0;
        double radiusArcsec = Math.max(90.0, objectRadiusPixels * pixelScaleArcsec * 6.0);
        radiusArcsec = Math.min(radiusArcsec, 900.0);
        return radiusArcsec / 3600.0;
    }

    private static double estimateLocalPixelScaleArcsec(WcsCoordinateTransformer transformer, double pixelX, double pixelY) {
        if (transformer == null) {
            return Double.NaN;
        }

        WcsCoordinateTransformer.SkyCoordinate center = transformer.pixelToSky(pixelX, pixelY);
        WcsCoordinateTransformer.SkyCoordinate offsetX = transformer.pixelToSky(pixelX + 1.0, pixelY);
        WcsCoordinateTransformer.SkyCoordinate offsetY = transformer.pixelToSky(pixelX, pixelY + 1.0);
        double stepX = angularSeparationArcsec(center.getRaDegrees(), center.getDecDegrees(), offsetX.getRaDegrees(), offsetX.getDecDegrees());
        double stepY = angularSeparationArcsec(center.getRaDegrees(), center.getDecDegrees(), offsetY.getRaDegrees(), offsetY.getDecDegrees());
        return (stepX + stepY) / 2.0;
    }

    private static double angularSeparationArcsec(double ra1Deg, double dec1Deg, double ra2Deg, double dec2Deg) {
        double ra1 = Math.toRadians(ra1Deg);
        double dec1 = Math.toRadians(dec1Deg);
        double ra2 = Math.toRadians(ra2Deg);
        double dec2 = Math.toRadians(dec2Deg);
        double sinHalfDec = Math.sin((dec2 - dec1) / 2.0);
        double sinHalfRa = Math.sin((ra2 - ra1) / 2.0);
        double a = (sinHalfDec * sinHalfDec) + (Math.cos(dec1) * Math.cos(dec2) * sinHalfRa * sinHalfRa);
        double c = 2.0 * Math.asin(Math.min(1.0, Math.sqrt(Math.max(0.0, a))));
        return Math.toDegrees(c) * 3600.0;
    }

    private static String formatPixelCoordinateBlockHtml(Context astrometryContext, double pixelX, double pixelY) {
        StringBuilder html = new StringBuilder();
        html.append("<span class='coord-highlight coord-stack'>");
        html.append("<span class='coord-line'>")
                .append(escapeHtml(String.format(Locale.US, "X: %.1f, Y: %.1f", pixelX, pixelY)))
                .append("</span>");
        if (astrometryContext != null && astrometryContext.hasAstrometricSolution()) {
            WcsCoordinateTransformer.SkyCoordinate skyCoordinate = astrometryContext.getTransformer().pixelToSky(pixelX, pixelY);
            html.append("<span class='coord-line'>RA ")
                    .append(escapeHtml(WcsCoordinateTransformer.formatRa(skyCoordinate.getRaDegrees())))
                    .append(" | Dec ")
                    .append(escapeHtml(WcsCoordinateTransformer.formatDec(skyCoordinate.getDecDegrees())))
                    .append("</span>");
        }
        html.append("</span>");
        return html.toString();
    }

    private static String formatSkyCoordinateInline(Context astrometryContext, double pixelX, double pixelY) {
        if (astrometryContext == null || !astrometryContext.hasAstrometricSolution()) {
            return null;
        }

        WcsCoordinateTransformer.SkyCoordinate skyCoordinate = astrometryContext.getTransformer().pixelToSky(pixelX, pixelY);
        return "RA " + WcsCoordinateTransformer.formatRa(skyCoordinate.getRaDegrees())
                + " | Dec " + WcsCoordinateTransformer.formatDec(skyCoordinate.getDecDegrees());
    }

    private static String resolveSkybotObserverCode(AppConfig appConfig) {
        if (appConfig == null || appConfig.observatoryCode == null) {
            return null;
        }
        String code = appConfig.observatoryCode.trim();
        if (code.isEmpty()) {
            return null;
        }
        return code.toUpperCase(Locale.US);
    }

    private static ObserverSite resolveObserverSite(FitsFileInformation[] fitsFiles, AppConfig appConfig) {
        if (fitsFiles != null) {
            for (FitsFileInformation file : fitsFiles) {
                if (file == null) {
                    continue;
                }
                Double latitude = parseCoordinateValue(getFirstHeaderValue(file, "SITELAT", "OBSGEO-B", "LAT-OBS"));
                Double longitude = parseCoordinateValue(getFirstHeaderValue(file, "SITELONG", "SITELON", "OBSGEO-L", "LONG-OBS", "LON-OBS"));
                if (latitude != null && longitude != null) {
                    Double altitude = parseDoubleValue(getFirstHeaderValue(file, "SITEELEV", "OBSALT", "ALT-OBS", "ELEVATIO"));
                    return new ObserverSite(latitude, longitude, altitude != null ? altitude : 0.0, "FITS header");
                }
            }
        }

        if (appConfig != null) {
            Double latitude = parseCoordinateValue(appConfig.siteLat);
            Double longitude = parseCoordinateValue(appConfig.siteLong);
            if (latitude != null && longitude != null) {
                return new ObserverSite(latitude, longitude, 0.0, "Configuration panel");
            }
        }

        return null;
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

    private static String formatSkybotTimestamp(long timestampMillis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(timestampMillis));
    }

    private static String formatStellariumTimestamp(long timestampMillis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(timestampMillis));
    }

    private static String formatUtcTimestamp(long timestampMillis) {
        if (timestampMillis <= 0L) {
            return "Unknown";
        }

        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(timestampMillis));
    }

    private static String formatDecimal(double value, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", value);
    }

    private static double clampFieldOfViewDegrees(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
