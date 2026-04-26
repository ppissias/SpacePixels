/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */

package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.WcsSolutionResolver;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Builds astrometric context and report-ready sky-identification markup for detections and tracks using FITS
 * metadata, WCS solutions, and observatory settings.
 */
final class DetectionReportAstrometry {
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

        Context(FitsFileInformation[] fitsFiles,
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

        ObserverSite getObserverSite() {
            return observerSite;
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
        final double latitudeDeg;
        final double longitudeDeg;
        final double altitudeMeters;
        private final String sourceLabel;

        ObserverSite(double latitudeDeg, double longitudeDeg, double altitudeMeters, String sourceLabel) {
            this.latitudeDeg = latitudeDeg;
            this.longitudeDeg = longitudeDeg;
            this.altitudeMeters = altitudeMeters;
            this.sourceLabel = sourceLabel;
        }

        String getSourceLabel() {
            return sourceLabel;
        }
    }

    private DetectionReportAstrometry() {
    }

    static Context buildContext(FitsFileInformation[] fitsFiles, AppConfig appConfig) {
        return AstrometryContextFactory.buildContext(fitsFiles, appConfig);
    }

    static String buildDeepStackIdentificationHtml(Context astrometryContext,
                                                   SourceExtractor.DetectedObject detection) {
        return buildDeepStackIdentificationHtml(astrometryContext, detection, null);
    }

    static String buildDeepStackIdentificationHtml(Context astrometryContext,
                                                   SourceExtractor.DetectedObject detection,
                                                   String liveRenderSlotId) {
        return buildDeepStackIdentificationHtml(astrometryContext, detection, liveRenderSlotId, null);
    }

    static String buildDeepStackIdentificationHtml(Context astrometryContext,
                                                   SourceExtractor.DetectedObject detection,
                                                   String liveRenderSlotId,
                                                   String liveRenderSidecarBaseName) {
        SolarSystemQueryTarget queryTarget = SolarSystemQueryTargetFactory.buildSingleDetectionQueryTarget(astrometryContext, detection);
        return buildSolarSystemIdentificationHtml(astrometryContext, queryTarget,
                "Reference epoch for stack lookup",
                "Search SkyBoT",
                "SkyBoT search radius",
                liveRenderSlotId,
                liveRenderSidecarBaseName,
                false);
    }

    static String buildTrackSolarSystemIdentificationHtml(Context astrometryContext,
                                                          TrackLinker.Track track) {
        return buildTrackSolarSystemIdentificationHtml(astrometryContext, track, null);
    }

    static String buildTrackSolarSystemIdentificationHtml(Context astrometryContext,
                                                          TrackLinker.Track track,
                                                          String liveRenderSlotId) {
        return buildTrackSolarSystemIdentificationHtml(astrometryContext, track, liveRenderSlotId, null);
    }

    static String buildTrackSolarSystemIdentificationHtml(Context astrometryContext,
                                                          TrackLinker.Track track,
                                                          String liveRenderSlotId,
                                                          String liveRenderSidecarBaseName) {
        SolarSystemQueryTarget queryTarget = SolarSystemQueryTargetFactory.buildTrackQueryTarget(astrometryContext, track);
        return buildSolarSystemIdentificationHtml(astrometryContext, queryTarget,
                "Reference epoch for track lookup",
                "Search SkyBoT (Track Midpoint)",
                "SkyBoT search radius",
                liveRenderSlotId,
                liveRenderSidecarBaseName,
                false);
    }

    static String buildMovingTrackSolarSystemIdentificationHtml(Context astrometryContext,
                                                                TrackLinker.Track track,
                                                                String liveRenderSlotId) {
        return buildMovingTrackSolarSystemIdentificationHtml(astrometryContext, track, liveRenderSlotId, null);
    }

    static String buildMovingTrackSolarSystemIdentificationHtml(Context astrometryContext,
                                                                TrackLinker.Track track,
                                                                String liveRenderSlotId,
                                                                String liveRenderSidecarBaseName) {
        SolarSystemQueryTarget queryTarget = SolarSystemQueryTargetFactory.buildTrackQueryTarget(astrometryContext, track);
        return buildSolarSystemIdentificationHtml(astrometryContext, queryTarget,
                "Reference epoch for track lookup",
                "Search SkyBoT (Track Midpoint)",
                "SkyBoT search radius",
                liveRenderSlotId,
                liveRenderSidecarBaseName,
                true);
    }

    static String buildTrackSatCheckerHtml(Context astrometryContext,
                                           TrackLinker.Track track) {
        return buildTrackSatCheckerHtml(astrometryContext, track, null, false);
    }

    static String buildConfirmedStreakTrackSatCheckerHtml(Context astrometryContext,
                                                          TrackLinker.Track track,
                                                          String liveRenderSlotId) {
        return buildConfirmedStreakTrackSatCheckerHtml(astrometryContext, track, liveRenderSlotId, null);
    }

    static String buildConfirmedStreakTrackSatCheckerHtml(Context astrometryContext,
                                                          TrackLinker.Track track,
                                                          String liveRenderSlotId,
                                                          String liveRenderSidecarFileName) {
        return buildTrackSatCheckerHtml(astrometryContext, track, liveRenderSlotId, liveRenderSidecarFileName, true);
    }

    private static String buildTrackSatCheckerHtml(Context astrometryContext,
                                                   TrackLinker.Track track,
                                                   String liveRenderSlotId,
                                                   String liveRenderSidecarFileName,
                                                   boolean useSimpleLabels) {
        return AstrometryIdentificationHtmlBuilder.buildTrackSatCheckerHtml(
                astrometryContext,
                track,
                liveRenderSlotId,
                liveRenderSidecarFileName,
                useSimpleLabels);
    }

    private static String buildTrackSatCheckerHtml(Context astrometryContext,
                                                   TrackLinker.Track track,
                                                   String liveRenderSlotId,
                                                   boolean useSimpleLabels) {
        return buildTrackSatCheckerHtml(astrometryContext, track, liveRenderSlotId, null, useSimpleLabels);
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
        SolarSystemQueryTarget queryTarget = SolarSystemQueryTargetFactory.buildFrameDetectionQueryTarget(astrometryContext, detection);
        return buildSkyViewerLinksHtml(astrometryContext, queryTarget, epochLabel);
    }

    static String buildTrackSkyViewerHtml(Context astrometryContext,
                                          TrackLinker.Track track,
                                          String epochLabel) {
        return buildTrackSkyViewerHtml(astrometryContext, track, epochLabel, null);
    }

    static String buildTrackSkyViewerHtml(Context astrometryContext,
                                          TrackLinker.Track track,
                                          String epochLabel,
                                          Double preferredTightFovDegrees) {
        SolarSystemQueryTarget queryTarget = SolarSystemQueryTargetFactory.buildTrackQueryTarget(astrometryContext, track);
        return buildSkyViewerLinksHtml(astrometryContext, queryTarget, epochLabel, preferredTightFovDegrees);
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
                                                             String radiusLabel,
                                                             String liveJplRenderSlotId,
                                                             String liveJplRenderSidecarBaseName,
                                                             boolean useSimpleJplLabels) {
        return AstrometryIdentificationHtmlBuilder.buildSolarSystemIdentificationHtml(
                astrometryContext,
                queryTarget,
                epochLabel,
                buttonLabel,
                radiusLabel,
                liveJplRenderSlotId,
                liveJplRenderSidecarBaseName,
                useSimpleJplLabels);
    }

    static long resolveTrackPointStartTimestamp(Context astrometryContext,
                                                SourceExtractor.DetectedObject point) {
        return SatCheckerQueryTargetFactory.resolveTrackPointStartTimestamp(astrometryContext, point);
    }

    static long resolveTrackPointExposureMillis(SourceExtractor.DetectedObject point) {
        return SatCheckerQueryTargetFactory.resolveTrackPointExposureMillis(point);
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
            long midpointTimestamp = SatCheckerQueryTargetFactory.resolveTrackPointMidpointTimestamp(astrometryContext, point);
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

    static String buildSkybotUrl(Context astrometryContext,
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

    static String buildJplSbIdentUrl(Context astrometryContext,
                                     SolarSystemQueryTarget queryTarget,
                                     double searchRadiusDegrees) {
        return buildJplSbIdentUrl(astrometryContext, queryTarget, searchRadiusDegrees, null);
    }

    static String buildJplSbIdentUrl(Context astrometryContext,
                                     SolarSystemQueryTarget queryTarget,
                                     double searchRadiusDegrees,
                                     String smallBodyGroup) {
        if (astrometryContext == null || !astrometryContext.hasAstrometricSolution() || queryTarget == null || queryTarget.timestampMillis <= 0L) {
            return null;
        }

        WcsCoordinateTransformer.SkyCoordinate skyCoordinate = astrometryContext.getTransformer().pixelToSky(queryTarget.pixelX, queryTarget.pixelY);
        ObserverSite observerSite = astrometryContext.getObserverSite();
        return JplSbIdentUrlBuilder.build(
                astrometryContext.getSkybotObserverCode(),
                observerSite != null ? observerSite.latitudeDeg : null,
                observerSite != null ? observerSite.longitudeDeg : null,
                observerSite != null ? observerSite.altitudeMeters : null,
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
        return JplSbIdentUrlBuilder.build(observerCode, observerLatitudeDeg, observerLongitudeDeg, observerAltitudeMeters,
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
        return JplSbIdentUrlBuilder.build(
                observerCode,
                observerLatitudeDeg,
                observerLongitudeDeg,
                observerAltitudeMeters,
                timestampMillis,
                raDegrees,
                decDegrees,
                searchRadiusDegrees,
                smallBodyGroup);
    }

    private static String buildSkyViewerLinksHtml(Context astrometryContext,
                                                  SolarSystemQueryTarget queryTarget,
                                                  String epochLabel) {
        return SkyViewerHtmlBuilder.buildSkyViewerLinksHtml(astrometryContext, queryTarget, epochLabel);
    }

    private static String buildSkyViewerLinksHtml(Context astrometryContext,
                                                  SolarSystemQueryTarget queryTarget,
                                                  String epochLabel,
                                                  Double preferredTightFovDegrees) {
        return SkyViewerHtmlBuilder.buildSkyViewerLinksHtml(astrometryContext, queryTarget, epochLabel, preferredTightFovDegrees);
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

    private static String formatSkybotTimestamp(long timestampMillis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(timestampMillis));
    }

    static String formatStellariumTimestamp(long timestampMillis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(timestampMillis));
    }

    static String formatUtcTimestamp(long timestampMillis) {
        if (timestampMillis <= 0L) {
            return "Unknown";
        }

        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(timestampMillis));
    }

    static String formatDurationSeconds(long durationSeconds) {
        if (durationSeconds <= 0L) {
            return "0 s";
        }
        if (durationSeconds < 60L) {
            return durationSeconds + " s";
        }
        long minutes = durationSeconds / 60L;
        long seconds = durationSeconds % 60L;
        return String.format(Locale.US, "%d min %02d s", minutes, seconds);
    }

    static String formatDecimal(double value, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", value);
    }

    static double clampFieldOfViewDegrees(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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
        return JplSbIdentUrlBuilder.isJplCompatibleObservatoryCode(observerCode);
    }

    static String buildSatCheckerFovUrl(ObserverSite observerSite,
                                        long midpointTimestampMillis,
                                        long durationSeconds,
                                        double raDegrees,
                                        double decDegrees,
                                        double fovRadiusDegrees,
                                        boolean asyncResponse) {
        return SatCheckerUrlBuilder.buildFovUrl(
                observerSite,
                midpointTimestampMillis,
                durationSeconds,
                raDegrees,
                decDegrees,
                fovRadiusDegrees,
                asyncResponse);
    }

    private static double normalizeDegrees(double degrees) {
        double normalized = degrees % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    static double normalizeLongitudeDegrees(double longitudeDegrees) {
        return JplSbIdentUrlBuilder.normalizeLongitudeDegrees(longitudeDegrees);
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String buildLiveRenderButtonHtml(String provider,
                                            String targetUrl,
                                            String slotId,
                                            String buttonLabel) {
        return buildLiveRenderButtonHtml(provider, targetUrl, slotId, buttonLabel, null, null);
    }

    static String buildLiveRenderButtonHtml(String provider,
                                            String targetUrl,
                                            String slotId,
                                            String buttonLabel,
                                            String sidecarFileName) {
        return buildLiveRenderButtonHtml(provider, targetUrl, slotId, buttonLabel, sidecarFileName, null);
    }

    static String buildLiveRenderButtonHtml(String provider,
                                            String targetUrl,
                                            String slotId,
                                            String buttonLabel,
                                            String sidecarFileName,
                                            String renderTitle) {
        if (provider == null || provider.isEmpty()
                || targetUrl == null || targetUrl.isEmpty()
                || slotId == null || slotId.isEmpty()
                || buttonLabel == null || buttonLabel.isEmpty()) {
            return "";
        }

        String encodedTarget = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(targetUrl.getBytes(StandardCharsets.UTF_8));

        return "<button type='button' class='id-link render-live-link' data-provider='"
                + escapeHtml(provider)
                + "' data-target='"
                + escapeHtml(encodedTarget)
                + "' data-slot-id='"
                + escapeHtml(slotId)
                + "' data-sidecar-file='"
                + escapeHtml(normalizeLiveRenderSidecarFileName(sidecarFileName))
                + "' data-render-title='"
                + escapeHtml(renderTitle)
                + "'>"
                + escapeHtml(buttonLabel)
                + "</button>";
    }

    private static String normalizeLiveRenderSidecarFileName(String sidecarFileName) {
        String trimmed = sidecarFileName == null ? null : sidecarFileName.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.US).endsWith(".json") ? trimmed : trimmed + ".json";
    }

    static String buildLiveRenderSidecarFileName(String sidecarBaseName, String variantSuffix) {
        String trimmedBaseName = sidecarBaseName == null ? null : sidecarBaseName.trim();
        if (trimmedBaseName != null && trimmedBaseName.isEmpty()) {
            trimmedBaseName = null;
        }
        String trimmedVariantSuffix = variantSuffix == null ? null : variantSuffix.trim();
        if (trimmedVariantSuffix != null && trimmedVariantSuffix.isEmpty()) {
            trimmedVariantSuffix = null;
        }
        if (trimmedBaseName == null) {
            return null;
        }
        return trimmedVariantSuffix == null
                ? trimmedBaseName + ".json"
                : trimmedBaseName + "_" + trimmedVariantSuffix + ".json";
    }

    static String buildLiveRenderContainerHtml(String slotId) {
        if (slotId == null || slotId.isEmpty()) {
            return "";
        }
        return "<div class='live-result-slot' id='" + escapeHtml(slotId) + "'></div>";
    }

    static String escapeHtml(String value) {
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
