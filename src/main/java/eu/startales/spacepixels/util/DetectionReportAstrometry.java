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
    private static final double JPL_NEO_RECOVERY_HALF_WIDTH_DEGREES = 5.0d;
    private static final double SIDEREAL_DAY_SECONDS = 86164.0905d;
    private static final double SIDEREAL_RATE_ARCSEC_PER_HOUR = (360.0d * 3600.0d * 3600.0d) / SIDEREAL_DAY_SECONDS;
    private static final double SIDEREAL_RATE_MATCH_FRACTION = 0.10d;

    static final class TrackSkyRateSummary {
        private final double raRateArcsecPerHour;
        private final double decRateArcsecPerHour;
        private final double skySpeedArcsecPerHour;

        private TrackSkyRateSummary(double raRateArcsecPerHour,
                                    double decRateArcsecPerHour,
                                    double skySpeedArcsecPerHour) {
            this.raRateArcsecPerHour = raRateArcsecPerHour;
            this.decRateArcsecPerHour = decRateArcsecPerHour;
            this.skySpeedArcsecPerHour = skySpeedArcsecPerHour;
        }
    }

    static final class HorizontalCoordinate {
        final double altitudeDeg;
        final double azimuthDeg;

        private HorizontalCoordinate(double altitudeDeg, double azimuthDeg) {
            this.altitudeDeg = altitudeDeg;
            this.azimuthDeg = azimuthDeg;
        }
    }

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

    static final class ObserverSite {
        private final double latitudeDeg;
        private final double longitudeDeg;
        private final double altitudeMeters;
        private final String sourceLabel;

        ObserverSite(double latitudeDeg, double longitudeDeg, double altitudeMeters, String sourceLabel) {
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

    static String buildTrackSkyRateSummaryHtml(Context astrometryContext,
                                               TrackLinker.Track track) {
        if (astrometryContext == null || !astrometryContext.hasAstrometricSolution()) {
            return "";
        }

        TrackSkyRateSummary skyRateSummary = resolveTrackSkyRateSummary(track, astrometryContext);
        StringBuilder html = new StringBuilder();
        html.append("Avg RA Rate: <span style='color:#fff;'>")
                .append(escapeHtml(formatSkyRateArcsecPerHour(skyRateSummary != null ? skyRateSummary.raRateArcsecPerHour : Double.NaN)))
                .append("</span><br>");
        html.append("Avg Dec Rate: <span style='color:#fff;'>")
                .append(escapeHtml(formatSkyRateArcsecPerHour(skyRateSummary != null ? skyRateSummary.decRateArcsecPerHour : Double.NaN)))
                .append("</span><br>");
        html.append("Apparent Sky Speed: <span style='color:#fff;'>")
                .append(escapeHtml(formatApparentSkySpeed(skyRateSummary != null ? skyRateSummary.skySpeedArcsecPerHour : Double.NaN)))
                .append("</span><br>");
        String motionClass = classifyTrackMotionByRaRate(skyRateSummary != null ? skyRateSummary.raRateArcsecPerHour : Double.NaN);
        if (motionClass != null) {
            html.append("Motion Class: <span style='color:#ffcc33;'>")
                    .append(escapeHtml(motionClass))
                    .append("</span><br>");
        }
        return html.toString();
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

        String nominalJplUrl = buildJplSbIdentUrl(astrometryContext, queryTarget, queryTarget.searchRadiusDegrees);
        if (nominalJplUrl != null) {
            String neoRecoveryJplUrl = buildJplSbIdentUrl(astrometryContext, queryTarget, JPL_NEO_RECOVERY_HALF_WIDTH_DEGREES, "neo");
            html.append("<div class='id-links'>");
            html.append("<a class='id-link' href='").append(nominalJplUrl).append("' target='_blank' rel='noopener noreferrer'>JPL Exact FOV</a>");
            if (neoRecoveryJplUrl != null) {
                html.append("<a class='id-link' href='").append(neoRecoveryJplUrl).append("' target='_blank' rel='noopener noreferrer'>JPL NEO Recovery</a>");
            }
            html.append("</div>");
            html.append("<div class='astro-note' style='margin-top: 6px;'>JPL Small-Body Identification uses ");
            if (isJplCompatibleObservatoryCode(astrometryContext.getSkybotObserverCode())) {
                html.append("MPC observatory code ").append(escapeHtml(normalizeObservatoryCode(astrometryContext.getSkybotObserverCode())));
            } else if (astrometryContext.observerSite != null) {
                html.append("topocentric site coordinates from ").append(escapeHtml(astrometryContext.observerSite.sourceLabel));
            }
            html.append(". These JPL links open the raw JSON API response. <strong>JPL Exact FOV</strong> keeps the search tight to the measured image footprint. <strong>JPL NEO Recovery</strong> is the fallback for fast nearby NEOs that JPL's first pass can miss in a small field; it uses a fixed ")
                    .append(escapeHtml(String.format(Locale.US, "%.1f", JPL_NEO_RECOVERY_HALF_WIDTH_DEGREES)))
                    .append("&deg; half-width and limits results to NEOs.</div>");
        } else {
            String jplUnavailableReason = buildJplUnavailableReason(astrometryContext);
            if (jplUnavailableReason != null) {
                html.append("<div class='astro-note' style='margin-top: 6px;'>")
                        .append(escapeHtml(jplUnavailableReason))
                        .append("</div>");
            }
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

    static long resolveTrackPointStartTimestamp(Context astrometryContext,
                                                SourceExtractor.DetectedObject point) {
        if (point == null) {
            return -1L;
        }
        if (point.timestamp > 0L) {
            return point.timestamp;
        }
        return astrometryContext != null ? astrometryContext.getFrameTimestampMillis(point.sourceFrameIndex) : -1L;
    }

    static long resolveTrackPointExposureMillis(SourceExtractor.DetectedObject point) {
        return point != null ? Math.max(point.exposureDuration, 0L) : 0L;
    }

    private static TrackSkyRateSummary resolveTrackSkyRateSummary(TrackLinker.Track track,
                                                                  Context astrometryContext) {
        if (track == null || track.points == null || track.points.isEmpty()
                || astrometryContext == null || !astrometryContext.hasAstrometricSolution()) {
            return null;
        }

        SourceExtractor.DetectedObject earliestPoint = null;
        SourceExtractor.DetectedObject latestPoint = null;
        long earliestMidpointTimestamp = Long.MAX_VALUE;
        long latestMidpointTimestamp = Long.MIN_VALUE;

        for (SourceExtractor.DetectedObject point : track.points) {
            long midpointTimestamp = resolveTrackPointMidpointTimestamp(astrometryContext, point);
            if (midpointTimestamp <= 0L) {
                continue;
            }
            if (midpointTimestamp < earliestMidpointTimestamp) {
                earliestMidpointTimestamp = midpointTimestamp;
                earliestPoint = point;
            }
            if (midpointTimestamp > latestMidpointTimestamp) {
                latestMidpointTimestamp = midpointTimestamp;
                latestPoint = point;
            }
        }

        if (earliestPoint == null || latestPoint == null || latestMidpointTimestamp <= earliestMidpointTimestamp) {
            return null;
        }

        WcsCoordinateTransformer.SkyCoordinate earliestSky = astrometryContext.getTransformer().pixelToSky(earliestPoint.x, earliestPoint.y);
        WcsCoordinateTransformer.SkyCoordinate latestSky = astrometryContext.getTransformer().pixelToSky(latestPoint.x, latestPoint.y);
        double elapsedHours = (latestMidpointTimestamp - earliestMidpointTimestamp) / 3_600_000.0d;
        if (!Double.isFinite(elapsedHours) || elapsedHours <= 0.0d) {
            return null;
        }

        double raDeltaDegrees = normalizeAngleDeltaDegrees(latestSky.getRaDegrees() - earliestSky.getRaDegrees());
        double decDeltaDegrees = latestSky.getDecDegrees() - earliestSky.getDecDegrees();
        double skySeparationDegrees = computeAngularSeparationDegrees(
                earliestSky.getRaDegrees(),
                earliestSky.getDecDegrees(),
                latestSky.getRaDegrees(),
                latestSky.getDecDegrees());
        return new TrackSkyRateSummary(
                (raDeltaDegrees * 3600.0d) / elapsedHours,
                (decDeltaDegrees * 3600.0d) / elapsedHours,
                (skySeparationDegrees * 3600.0d) / elapsedHours);
    }

    private static long resolveTrackPointMidpointTimestamp(Context astrometryContext,
                                                           SourceExtractor.DetectedObject point) {
        long startTimestamp = resolveTrackPointStartTimestamp(astrometryContext, point);
        if (startTimestamp <= 0L) {
            return -1L;
        }
        return startTimestamp + (resolveTrackPointExposureMillis(point) / 2L);
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

    private static String buildJplSbIdentUrl(Context astrometryContext,
                                             SolarSystemQueryTarget queryTarget,
                                             double searchRadiusDegrees) {
        return buildJplSbIdentUrl(astrometryContext, queryTarget, searchRadiusDegrees, null);
    }

    private static String buildJplSbIdentUrl(Context astrometryContext,
                                             SolarSystemQueryTarget queryTarget,
                                             double searchRadiusDegrees,
                                             String smallBodyGroup) {
        if (astrometryContext == null || !astrometryContext.hasAstrometricSolution() || queryTarget == null || queryTarget.timestampMillis <= 0L) {
            return null;
        }

        WcsCoordinateTransformer.SkyCoordinate skyCoordinate = astrometryContext.getTransformer().pixelToSky(queryTarget.pixelX, queryTarget.pixelY);
        return buildJplSbIdentUrl(
                astrometryContext.getSkybotObserverCode(),
                astrometryContext.observerSite != null ? astrometryContext.observerSite.latitudeDeg : null,
                astrometryContext.observerSite != null ? astrometryContext.observerSite.longitudeDeg : null,
                astrometryContext.observerSite != null ? astrometryContext.observerSite.altitudeMeters : null,
                queryTarget.timestampMillis,
                skyCoordinate.getRaDegrees(),
                skyCoordinate.getDecDegrees(),
                searchRadiusDegrees,
                smallBodyGroup);
    }

    static String buildJplSbIdentUrl(String observerCode,
                                     Double observerLatitudeDeg,
                                     Double observerLongitudeDeg,
                                     Double observerAltitudeMeters,
                                     long timestampMillis,
                                     double raDegrees,
                                     double decDegrees,
                                     double searchRadiusDegrees) {
        return buildJplSbIdentUrl(observerCode, observerLatitudeDeg, observerLongitudeDeg, observerAltitudeMeters,
                timestampMillis, raDegrees, decDegrees, searchRadiusDegrees, null);
    }

    static String buildJplSbIdentUrl(String observerCode,
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
        if (!appendJplObserverParameters(url, observerCode, observerLatitudeDeg, observerLongitudeDeg, observerAltitudeMeters)) {
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

    private static boolean appendJplObserverParameters(StringBuilder url,
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

        HorizontalCoordinate horizontalCoordinate = resolveHorizontalCoordinate(
                astrometryContext != null ? astrometryContext.observerSite : null,
                queryTarget.timestampMillis,
                skyCoordinate.getRaDegrees(),
                skyCoordinate.getDecDegrees());

        html.append("Interactive sky viewers:");
        html.append("<div class='id-links'>");
        if (horizontalCoordinate != null && astrometryContext != null && astrometryContext.observerSite != null) {
            html.append("<a class='id-link' href='")
                    .append(buildStellariumWebAltAzUrl(
                            astrometryContext.observerSite,
                            queryTarget.timestampMillis,
                            horizontalCoordinate,
                            tightFovDegrees))
                    .append("' target='_blank' rel='noopener noreferrer'>Stellarium Web (Alt/Az)</a>");
            html.append("<a class='id-link' href='")
                    .append(buildStellariumWebAltAzUrl(
                            astrometryContext.observerSite,
                            queryTarget.timestampMillis,
                            horizontalCoordinate,
                            wideFovDegrees))
                    .append("' target='_blank' rel='noopener noreferrer'>Stellarium Web Wide (Alt/Az)</a>");
        }
        html.append("<a class='id-link' href='")
                .append(buildStellariumWebUrl(astrometryContext, queryTarget, skyCoordinate, tightFovDegrees))
                .append("' target='_blank' rel='noopener noreferrer'>Stellarium Web (RA/Dec)</a>");
        html.append("<a class='id-link' href='")
                .append(buildStellariumWebUrl(astrometryContext, queryTarget, skyCoordinate, wideFovDegrees))
                .append("' target='_blank' rel='noopener noreferrer'>Stellarium Web Wide (RA/Dec)</a>");
        html.append("</div>");

        html.append("<div class='astro-note' style='margin-top: 6px;'>")
                .append(horizontalCoordinate != null
                        ? "Stellarium links use best-effort observer-site Alt/Az and RA/Dec URL parameters at "
                        : "Stellarium links use best-effort RA/Dec URL parameters at ")
                .append(escapeHtml(formatUtcTimestamp(queryTarget.timestampMillis)))
                .append(" with FOV ")
                .append(escapeHtml(String.format(Locale.US, "%.2f° / %.2f°", tightFovDegrees, wideFovDegrees)))
                .append(".");
        if (epochLabel != null && !epochLabel.isEmpty()) {
            html.append(" ").append(escapeHtml(epochLabel)).append(": ")
                    .append(escapeHtml(formatUtcTimestamp(queryTarget.timestampMillis)))
                    .append(".");
        }
        String horizontalSummary = formatHorizontalCoordinateSummary(horizontalCoordinate);
        if (horizontalSummary != null) {
            html.append(" ").append(escapeHtml(horizontalSummary));
        }
        html.append(" Stellarium Web may display browser-local time even when the observing site is elsewhere.");
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

    static String buildStellariumWebAltAzUrl(ObserverSite observerSite,
                                             long timestampMillis,
                                             HorizontalCoordinate horizontalCoordinate,
                                             double fovDegrees) {
        if (observerSite == null || horizontalCoordinate == null || timestampMillis <= 0L
                || !Double.isFinite(horizontalCoordinate.altitudeDeg)
                || !Double.isFinite(horizontalCoordinate.azimuthDeg)
                || !Double.isFinite(fovDegrees) || fovDegrees <= 0.0d) {
            return null;
        }

        StringBuilder url = new StringBuilder("https://stellarium-web.org/?");
        url.append("date=").append(urlEncode(formatStellariumTimestamp(timestampMillis)));
        url.append("&alt=").append(urlEncode(formatDecimal(horizontalCoordinate.altitudeDeg, 6)));
        url.append("&az=").append(urlEncode(formatDecimal(horizontalCoordinate.azimuthDeg, 6)));
        url.append("&fov=").append(urlEncode(formatDecimal(fovDegrees, 4)));
        url.append("&lat=").append(urlEncode(formatDecimal(observerSite.latitudeDeg, 5)));
        url.append("&lng=").append(urlEncode(formatDecimal(observerSite.longitudeDeg, 5)));
        url.append("&elev=").append(urlEncode(formatDecimal(observerSite.altitudeMeters, 1)));
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
        return appConfig != null ? normalizeObservatoryCode(appConfig.observatoryCode) : null;
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
                    ObserverSite observerSite = createObserverSite(latitude, longitude, altitude, "FITS header");
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

    private static ObserverSite createObserverSite(Double latitudeDeg,
                                                   Double longitudeDeg,
                                                   Double altitudeMeters,
                                                   String sourceLabel) {
        if (latitudeDeg == null || longitudeDeg == null
                || !Double.isFinite(latitudeDeg) || !Double.isFinite(longitudeDeg)
                || latitudeDeg < -90.0d || latitudeDeg > 90.0d) {
            return null;
        }

        double normalizedLongitude = normalizeLongitudeDegrees(longitudeDeg);
        if (!Double.isFinite(normalizedLongitude) || normalizedLongitude < -180.0d || normalizedLongitude > 180.0d) {
            return null;
        }

        double normalizedAltitudeMeters = altitudeMeters != null && Double.isFinite(altitudeMeters)
                ? altitudeMeters
                : 0.0d;
        return new ObserverSite(latitudeDeg, normalizedLongitude, normalizedAltitudeMeters, sourceLabel);
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

    private static double estimateJplRaHalfWidthDegrees(double decHalfWidthDegrees, double centerDecDegrees) {
        double cosine = Math.cos(Math.toRadians(centerDecDegrees));
        if (Math.abs(cosine) < 0.15) {
            return Math.min(decHalfWidthDegrees * 6.0, 12.0);
        }
        return Math.min(decHalfWidthDegrees / Math.abs(cosine), 12.0);
    }

    private static double normalizeAngleDeltaDegrees(double deltaDegrees) {
        while (deltaDegrees <= -180.0d) {
            deltaDegrees += 360.0d;
        }
        while (deltaDegrees > 180.0d) {
            deltaDegrees -= 360.0d;
        }
        return deltaDegrees;
    }

    private static String formatSkyRateArcsecPerHour(double rateArcsecPerHour) {
        if (!Double.isFinite(rateArcsecPerHour)) {
            return "n/a";
        }
        return String.format(Locale.US, "%+.1f arcsec/h", rateArcsecPerHour);
    }

    private static String formatUnsignedSkyRateArcsecPerHour(double rateArcsecPerHour) {
        if (!Double.isFinite(rateArcsecPerHour)) {
            return "n/a";
        }
        return String.format(Locale.US, "%.1f arcsec/h", Math.abs(rateArcsecPerHour));
    }

    static String formatApparentSkySpeed(double skySpeedArcsecPerHour) {
        if (!Double.isFinite(skySpeedArcsecPerHour)) {
            return "n/a";
        }
        double absoluteArcsecPerHour = Math.abs(skySpeedArcsecPerHour);
        double degreesPerHour = absoluteArcsecPerHour / 3600.0d;
        double arcsecPerSecond = absoluteArcsecPerHour / 3600.0d;
        return String.format(Locale.US, "%.2f deg/h (%.2f arcsec/s)", degreesPerHour, arcsecPerSecond);
    }

    static String classifyTrackMotionByRaRate(double raRateArcsecPerHour) {
        if (!isNearSiderealRaRate(raRateArcsecPerHour)) {
            return null;
        }
        return "GEO-like apparent motion (RA drift close to sidereal; possible geosynchronous satellite)";
    }

    static String formatSiderealLikeTrackNote(double raRateArcsecPerHour) {
        return classifyTrackMotionByRaRate(raRateArcsecPerHour);
    }

    static boolean isNearSiderealRaRate(double raRateArcsecPerHour) {
        if (!Double.isFinite(raRateArcsecPerHour)) {
            return false;
        }
        return Math.abs(Math.abs(raRateArcsecPerHour) - SIDEREAL_RATE_ARCSEC_PER_HOUR)
                <= (SIDEREAL_RATE_ARCSEC_PER_HOUR * SIDEREAL_RATE_MATCH_FRACTION);
    }

    static String formatHorizontalCoordinateSummary(ObserverSite observerSite,
                                                    long timestampMillis,
                                                    double raDegrees,
                                                    double decDegrees) {
        HorizontalCoordinate horizontalCoordinate = resolveHorizontalCoordinate(
                observerSite,
                timestampMillis,
                raDegrees,
                decDegrees);
        return formatHorizontalCoordinateSummary(horizontalCoordinate);
    }

    static String formatHorizontalCoordinateSummary(HorizontalCoordinate horizontalCoordinate) {
        if (horizontalCoordinate == null) {
            return null;
        }
        return String.format(
                Locale.US,
                "Alt / Az at observer site: Alt %+.1f°, Az %.1f°.",
                horizontalCoordinate.altitudeDeg,
                horizontalCoordinate.azimuthDeg);
    }

    static HorizontalCoordinate resolveHorizontalCoordinate(ObserverSite observerSite,
                                                            long timestampMillis,
                                                            double raDegrees,
                                                            double decDegrees) {
        if (observerSite == null || timestampMillis <= 0L
                || !Double.isFinite(raDegrees) || !Double.isFinite(decDegrees)) {
            return null;
        }
        if (!Double.isFinite(observerSite.latitudeDeg) || !Double.isFinite(observerSite.longitudeDeg)) {
            return null;
        }

        double localSiderealTimeDeg = computeLocalSiderealTimeDegrees(timestampMillis, observerSite.longitudeDeg);
        double hourAngleDeg = normalizeAngleDeltaDegrees(localSiderealTimeDeg - normalizeDegrees(raDegrees));

        double latitudeRad = Math.toRadians(observerSite.latitudeDeg);
        double declinationRad = Math.toRadians(decDegrees);
        double hourAngleRad = Math.toRadians(hourAngleDeg);

        double sinAltitude = (Math.sin(declinationRad) * Math.sin(latitudeRad))
                + (Math.cos(declinationRad) * Math.cos(latitudeRad) * Math.cos(hourAngleRad));
        sinAltitude = Math.max(-1.0d, Math.min(1.0d, sinAltitude));
        double altitudeRad = Math.asin(sinAltitude);
        double cosAltitude = Math.max(1.0e-12d, Math.cos(altitudeRad));

        double sinAzimuth = -(Math.cos(declinationRad) * Math.sin(hourAngleRad)) / cosAltitude;
        double cosAzimuth = (Math.sin(declinationRad) - (Math.sin(altitudeRad) * Math.sin(latitudeRad)))
                / (cosAltitude * Math.cos(latitudeRad));
        sinAzimuth = Math.max(-1.0d, Math.min(1.0d, sinAzimuth));
        cosAzimuth = Math.max(-1.0d, Math.min(1.0d, cosAzimuth));

        double azimuthDeg = normalizeDegrees(Math.toDegrees(Math.atan2(sinAzimuth, cosAzimuth)));
        double altitudeDeg = Math.toDegrees(altitudeRad);
        return new HorizontalCoordinate(altitudeDeg, azimuthDeg);
    }

    private static double computeLocalSiderealTimeDegrees(long timestampMillis, double longitudeDegrees) {
        double julianDate = (timestampMillis / 86400000.0d) + 2440587.5d;
        double centuriesSinceJ2000 = (julianDate - 2451545.0d) / 36525.0d;
        double gmstDegrees = 280.46061837d
                + (360.98564736629d * (julianDate - 2451545.0d))
                + (0.000387933d * centuriesSinceJ2000 * centuriesSinceJ2000)
                - ((centuriesSinceJ2000 * centuriesSinceJ2000 * centuriesSinceJ2000) / 38710000.0d);
        return normalizeDegrees(gmstDegrees + longitudeDegrees);
    }

    private static double computeAngularSeparationDegrees(double ra1Degrees,
                                                          double dec1Degrees,
                                                          double ra2Degrees,
                                                          double dec2Degrees) {
        if (!Double.isFinite(ra1Degrees) || !Double.isFinite(dec1Degrees)
                || !Double.isFinite(ra2Degrees) || !Double.isFinite(dec2Degrees)) {
            return Double.NaN;
        }

        double ra1Rad = Math.toRadians(ra1Degrees);
        double dec1Rad = Math.toRadians(dec1Degrees);
        double ra2Rad = Math.toRadians(ra2Degrees);
        double dec2Rad = Math.toRadians(dec2Degrees);

        double sinDec1 = Math.sin(dec1Rad);
        double sinDec2 = Math.sin(dec2Rad);
        double cosDec1 = Math.cos(dec1Rad);
        double cosDec2 = Math.cos(dec2Rad);
        double cosDeltaRa = Math.cos(ra2Rad - ra1Rad);
        double cosine = (sinDec1 * sinDec2) + (cosDec1 * cosDec2 * cosDeltaRa);
        cosine = Math.max(-1.0d, Math.min(1.0d, cosine));
        return Math.toDegrees(Math.acos(cosine));
    }

    static boolean isJplCompatibleObservatoryCode(String observerCode) {
        String normalizedObserverCode = normalizeObservatoryCode(observerCode);
        return normalizedObserverCode != null && !"500".equals(normalizedObserverCode);
    }

    private static String normalizeObservatoryCode(String observerCode) {
        if (observerCode == null) {
            return null;
        }

        String normalized = observerCode.trim().toUpperCase(Locale.US);
        return normalized.isEmpty() ? null : normalized;
    }

    private static double normalizeDegrees(double degrees) {
        double normalized = degrees % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
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

    private static String buildJplUnavailableReason(Context astrometryContext) {
        if (astrometryContext == null) {
            return null;
        }

        String observerCode = normalizeObservatoryCode(astrometryContext.getSkybotObserverCode());
        if ("500".equals(observerCode) && astrometryContext.observerSite == null) {
            return "JPL Small-Body Identification link unavailable: JPL does not accept MPC code 500. Configure a real MPC observatory code or site latitude/longitude.";
        }
        if (observerCode == null && astrometryContext.observerSite == null) {
            return "JPL Small-Body Identification link unavailable: configure a real MPC observatory code or site latitude/longitude.";
        }
        return null;
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
