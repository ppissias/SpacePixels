package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import io.github.ppissias.jtransient.core.TrackLinker;

import java.util.Locale;

/**
 * Builds the report sections that explain SkyBoT, JPL, and SatChecker identification lookups.
 */
final class AstrometryIdentificationHtmlBuilder {

    private static final double JPL_NEO_RECOVERY_HALF_WIDTH_DEGREES = 5.0d;

    private AstrometryIdentificationHtmlBuilder() {
    }

    static String buildSolarSystemIdentificationHtml(DetectionReportAstrometry.Context astrometryContext,
                                                     SolarSystemQueryTarget queryTarget,
                                                     String epochLabel,
                                                     String buttonLabel,
                                                     String radiusLabel,
                                                     String liveJplRenderSlotId,
                                                     String liveJplRenderSidecarBaseName,
                                                     boolean useSimpleJplLabels) {
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

        DetectionReportAstrometry.ObserverSite observerSite = astrometryContext.getObserverSite();
        WcsCoordinateTransformer.SkyCoordinate skyCoordinate =
                astrometryContext.getTransformer().pixelToSky(queryTarget.pixelX, queryTarget.pixelY);
        html.append("Sky position: <span class='coord-highlight'>RA ")
                .append(DetectionReportAstrometry.escapeHtml(WcsCoordinateTransformer.formatRa(skyCoordinate.getRaDegrees())))
                .append(" | Dec ")
                .append(DetectionReportAstrometry.escapeHtml(WcsCoordinateTransformer.formatDec(skyCoordinate.getDecDegrees())))
                .append("</span>");
        if (queryTarget.summaryLabel != null && !queryTarget.summaryLabel.isEmpty()) {
            html.append("<br>").append(DetectionReportAstrometry.escapeHtml(queryTarget.summaryLabel)).append(".");
        }
        html.append("<br>WCS source: ").append(astrometryContext.getWcsSummary()).append(".");

        String epochText = DetectionReportAstrometry.formatUtcTimestamp(queryTarget.timestampMillis);
        if (queryTarget.timestampMillis > 0L) {
            html.append("<br>").append(DetectionReportAstrometry.escapeHtml(epochLabel)).append(": ")
                    .append(DetectionReportAstrometry.escapeHtml(epochText)).append(".");
        } else {
            html.append("<br>Reference epoch unavailable: no valid frame timestamps were found in the FITS headers.");
        }

        if (astrometryContext.hasSkybotObserverCode()) {
            html.append("<br>Observer used for lookup: IAU code ")
                    .append(DetectionReportAstrometry.escapeHtml(astrometryContext.getSkybotObserverCode()))
                    .append(" (")
                    .append(DetectionReportAstrometry.escapeHtml(astrometryContext.getSkybotObserverSource()))
                    .append(").");
        } else {
            html.append("<br>Observer used for lookup: geocenter (500).");
        }
        if (observerSite != null) {
            html.append("<br>Known site coordinates: ")
                    .append(DetectionReportAstrometry.escapeHtml(String.format(
                            Locale.US,
                            "%.5f, %.5f (%s)",
                            observerSite.latitudeDeg,
                            observerSite.longitudeDeg,
                            observerSite.getSourceLabel())))
                    .append(".");
        }

        String nominalSkybotUrl =
                DetectionReportAstrometry.buildSkybotUrl(astrometryContext, queryTarget, queryTarget.searchRadiusDegrees, false);
        if (nominalSkybotUrl != null) {
            double nominalRadiusArcmin = queryTarget.searchRadiusDegrees * 60.0;
            double widerRadiusDegrees = Math.min(queryTarget.searchRadiusDegrees * 2.5, 2.0);
            double muchWiderRadiusDegrees = Math.min(queryTarget.searchRadiusDegrees * 6.0, 5.0);
            html.append("<div class='id-links'>");
            html.append("<a class='id-link' href='").append(nominalSkybotUrl)
                    .append("' target='_blank' rel='noopener noreferrer'>")
                    .append(DetectionReportAstrometry.escapeHtml(buttonLabel))
                    .append("</a>");
            html.append("<a class='id-link' href='")
                    .append(DetectionReportAstrometry.buildSkybotUrl(astrometryContext, queryTarget, widerRadiusDegrees, false))
                    .append("' target='_blank' rel='noopener noreferrer'>SkyBoT Wide Cone</a>");
            html.append("<a class='id-link' href='")
                    .append(DetectionReportAstrometry.buildSkybotUrl(astrometryContext, queryTarget, muchWiderRadiusDegrees, false))
                    .append("' target='_blank' rel='noopener noreferrer'>SkyBoT Much Wider Cone</a>");
            if (astrometryContext.hasSkybotObserverCode()) {
                html.append("<a class='id-link' href='")
                        .append(DetectionReportAstrometry.buildSkybotUrl(
                                astrometryContext,
                                queryTarget,
                                queryTarget.searchRadiusDegrees,
                                true))
                        .append("' target='_blank' rel='noopener noreferrer'>SkyBoT Geocenter</a>");
            }
            html.append("</div>");
            html.append("<div class='astro-note' style='margin-top: 6px;'>")
                    .append(DetectionReportAstrometry.escapeHtml(radiusLabel)).append(": ")
                    .append(DetectionReportAstrometry.escapeHtml(String.format(Locale.US, "%.2f arcmin", nominalRadiusArcmin)))
                    .append(" | Wide: ")
                    .append(DetectionReportAstrometry.escapeHtml(String.format(Locale.US, "%.2f arcmin", widerRadiusDegrees * 60.0)))
                    .append(" | Much wider: ")
                    .append(DetectionReportAstrometry.escapeHtml(String.format(Locale.US, "%.2f arcmin", muchWiderRadiusDegrees * 60.0)))
                    .append(" (asteroids + comets).</div>");
        }

        String nominalJplUrl = DetectionReportAstrometry.buildJplSbIdentUrl(
                astrometryContext,
                queryTarget,
                queryTarget.searchRadiusDegrees);
        if (nominalJplUrl != null) {
            String neoRecoveryJplUrl = DetectionReportAstrometry.buildJplSbIdentUrl(
                    astrometryContext,
                    queryTarget,
                    JPL_NEO_RECOVERY_HALF_WIDTH_DEGREES,
                    "neo");
            String exactJplLabel = useSimpleJplLabels ? "Open JPL Raw JSON in Browser" : "JPL Exact FOV Raw JSON";
            String neoJplLabel = useSimpleJplLabels ? "Open JPL NEO Recovery Raw JSON in Browser" : "JPL NEO Recovery Raw JSON";
            html.append("<div class='id-links'>");
            if (liveJplRenderSlotId != null && !liveJplRenderSlotId.isEmpty()) {
                html.append(DetectionReportAstrometry.buildLiveRenderButtonHtml(
                        "jpl",
                        nominalJplUrl,
                        liveJplRenderSlotId,
                        "Render JPL Results Here",
                        DetectionReportAstrometry.buildLiveRenderSidecarFileName(liveJplRenderSidecarBaseName, "exact"),
                        "JPL Exact FOV Results"));
                if (neoRecoveryJplUrl != null) {
                    html.append(DetectionReportAstrometry.buildLiveRenderButtonHtml(
                            "jpl",
                            neoRecoveryJplUrl,
                            liveJplRenderSlotId,
                            "Render JPL NEO Recovery Results Here",
                            DetectionReportAstrometry.buildLiveRenderSidecarFileName(liveJplRenderSidecarBaseName, "neo"),
                            "JPL NEO Recovery Results"));
                }
            }
            html.append("<a class='id-link' href='").append(nominalJplUrl)
                    .append("' target='_blank' rel='noopener noreferrer'>")
                    .append(exactJplLabel)
                    .append("</a>");
            if (neoRecoveryJplUrl != null) {
                html.append("<a class='id-link' href='").append(neoRecoveryJplUrl)
                        .append("' target='_blank' rel='noopener noreferrer'>")
                        .append(neoJplLabel)
                        .append("</a>");
            }
            html.append("</div>");
            html.append("<div class='astro-note' style='margin-top: 6px;'>JPL Small-Body Identification uses ");
            if (DetectionReportAstrometry.isJplCompatibleObservatoryCode(astrometryContext.getSkybotObserverCode())) {
                html.append("MPC observatory code ").append(DetectionReportAstrometry.escapeHtml(
                        JplSbIdentUrlBuilder.normalizeObservatoryCode(astrometryContext.getSkybotObserverCode())));
            } else if (observerSite != null) {
                html.append("topocentric site coordinates from ")
                        .append(DetectionReportAstrometry.escapeHtml(observerSite.getSourceLabel()));
            }
            if (useSimpleJplLabels && liveJplRenderSlotId != null && !liveJplRenderSlotId.isEmpty()) {
                html.append(". <strong>Render JPL Results Here</strong> fetches the exact-FOV result into this report first. <strong>Render JPL NEO Recovery Results Here</strong> fetches the wider fallback for fast nearby NEOs that JPL's first pass can miss in a small field. <strong>Open JPL Raw JSON in Browser</strong> and <strong>Open JPL NEO Recovery Raw JSON in Browser</strong> open the original raw JSON API replies in a new tab. It uses a fixed ");
            } else {
                html.append(". <strong>Render JPL Results Here</strong> fetches the exact-FOV result into this report first. <strong>JPL Exact FOV Raw JSON</strong> opens the original raw JSON API reply for the tight measured footprint. <strong>Render JPL NEO Recovery Results Here</strong> fetches the wider fallback for fast nearby NEOs that JPL's first pass can miss in a small field, and <strong>JPL NEO Recovery Raw JSON</strong> opens that fallback raw JSON API reply in a new tab. It uses a fixed ");
            }
            html.append(DetectionReportAstrometry.escapeHtml(String.format(Locale.US, "%.1f", JPL_NEO_RECOVERY_HALF_WIDTH_DEGREES)))
                    .append("&deg; half-width and limits results to NEOs.</div>");
            if (liveJplRenderSlotId != null && !liveJplRenderSlotId.isEmpty()) {
                html.append(DetectionReportAstrometry.buildLiveRenderContainerHtml(liveJplRenderSlotId));
            }
        } else {
            String jplUnavailableReason = buildJplUnavailableReason(astrometryContext);
            if (jplUnavailableReason != null) {
                html.append("<div class='astro-note' style='margin-top: 6px;'>")
                        .append(DetectionReportAstrometry.escapeHtml(jplUnavailableReason))
                        .append("</div>");
            }
        }

        html.append(SkyViewerHtmlBuilder.buildSkyViewerLinksHtml(astrometryContext, queryTarget, epochLabel));
        html.append("</div>");
        return html.toString();
    }

    static String buildTrackSatCheckerHtml(DetectionReportAstrometry.Context astrometryContext,
                                           TrackLinker.Track track,
                                           String liveRenderSlotId,
                                           String liveRenderSidecarFileName,
                                           boolean useSimpleLabels) {
        StringBuilder html = new StringBuilder();
        html.append("<div class='astro-note'>");

        if (astrometryContext == null || !astrometryContext.hasAstrometricSolution()) {
            html.append("SatChecker link unavailable: no reusable aligned-frame WCS solution was found for this session.");
            html.append("</div>");
            return html.toString();
        }

        DetectionReportAstrometry.ObserverSite observerSite = astrometryContext.getObserverSite();
        if (observerSite == null) {
            html.append("SatChecker link unavailable: observer site coordinates are required and were not found in the FITS headers or configuration panel.");
            html.append("</div>");
            return html.toString();
        }

        SatCheckerQueryTarget queryTarget = SatCheckerQueryTargetFactory.buildTrackSatCheckerQueryTarget(astrometryContext, track);
        if (queryTarget == null) {
            html.append("SatChecker link unavailable: no valid streak-track time window or usable FOV geometry could be resolved.");
            html.append("</div>");
            return html.toString();
        }

        long queryMidpointTimestampMillis = SatCheckerQueryTargetFactory.resolveTimeWindowMidpointTimestamp(
                queryTarget.startTimestampMillis,
                queryTarget.endTimestampMillis);
        long candidateDurationSeconds = SatCheckerQueryTargetFactory.resolveSatCheckerCandidateDurationSeconds(queryTarget.durationSeconds);
        String satCheckerUrl = DetectionReportAstrometry.buildSatCheckerFovUrl(
                observerSite,
                queryMidpointTimestampMillis,
                candidateDurationSeconds,
                queryTarget.raDegrees,
                queryTarget.decDegrees,
                queryTarget.fovRadiusDegrees,
                false);
        if (satCheckerUrl == null) {
            html.append("SatChecker link unavailable: the streak-track query parameters could not be normalized into a valid request.");
            html.append("</div>");
            return html.toString();
        }

        html.append("Satellite identification (SatChecker): <span class='coord-highlight'>RA ")
                .append(DetectionReportAstrometry.escapeHtml(WcsCoordinateTransformer.formatRa(queryTarget.raDegrees)))
                .append(" | Dec ")
                .append(DetectionReportAstrometry.escapeHtml(WcsCoordinateTransformer.formatDec(queryTarget.decDegrees)))
                .append("</span>");
        if (queryTarget.summaryLabel != null && !queryTarget.summaryLabel.isEmpty()) {
            html.append("<br>").append(DetectionReportAstrometry.escapeHtml(queryTarget.summaryLabel)).append(".");
        }
        html.append("<br>Observed track span: ")
                .append(DetectionReportAstrometry.escapeHtml(DetectionReportAstrometry.formatUtcTimestamp(queryTarget.startTimestampMillis)))
                .append(" to ")
                .append(DetectionReportAstrometry.escapeHtml(DetectionReportAstrometry.formatUtcTimestamp(queryTarget.endTimestampMillis)))
                .append(" (")
                .append(DetectionReportAstrometry.escapeHtml(DetectionReportAstrometry.formatDurationSeconds(queryTarget.durationSeconds)))
                .append(").");
        html.append("<br>Tight candidate query: midpoint ")
                .append(DetectionReportAstrometry.escapeHtml(DetectionReportAstrometry.formatUtcTimestamp(queryMidpointTimestampMillis)))
                .append(", duration ")
                .append(DetectionReportAstrometry.escapeHtml(DetectionReportAstrometry.formatDurationSeconds(candidateDurationSeconds)))
                .append(".");
        html.append("<br>SatChecker site: ")
                .append(DetectionReportAstrometry.escapeHtml(String.format(
                        Locale.US,
                        "%.5f, %.5f @ %.1f m (%s)",
                        observerSite.latitudeDeg,
                        observerSite.longitudeDeg,
                        observerSite.altitudeMeters,
                        observerSite.getSourceLabel())))
                .append(".");
        html.append("<div class='id-links'>");
        if (liveRenderSlotId != null && !liveRenderSlotId.isEmpty()) {
            html.append(DetectionReportAstrometry.buildLiveRenderButtonHtml(
                    "satchecker",
                    satCheckerUrl,
                    liveRenderSlotId,
                    "Render SatChecker Results Here",
                    liveRenderSidecarFileName,
                    "SatChecker Tight Candidate Results"));
        }
        html.append("<a class='id-link' href='")
                .append(satCheckerUrl)
                .append("' target='_blank' rel='noopener noreferrer'>")
                .append(useSimpleLabels ? "Open SatChecker Raw JSON in Browser" : "SatChecker Tight Candidate Raw JSON")
                .append("</a>");
        html.append("</div>");
        if (useSimpleLabels && liveRenderSlotId != null && !liveRenderSlotId.isEmpty()) {
            html.append("<div class='astro-note' style='margin-top: 6px;'>")
                    .append("<strong>Render SatChecker Results Here</strong> asks SpacePixels to fetch the same result and show it inside this report first. ")
                    .append("<strong>Open SatChecker Raw JSON in Browser</strong> opens the original raw JSON reply in a new tab. ")
                    .append("Query radius: ")
                    .append(DetectionReportAstrometry.escapeHtml(String.format(Locale.US, "%.2f°", queryTarget.fovRadiusDegrees)))
                    .append("; grouping: satellite; TLE payload omitted.</div>");
        } else {
            html.append("<div class='astro-note' style='margin-top: 6px;'><strong>Render SatChecker Results Here</strong> fetches the tight result into this report first. <strong>SatChecker Tight Candidate Raw JSON</strong> opens the original raw JSON reply in a new tab. This uses a tighter SatChecker FOV request centered on the measured streak midpoint with <strong>mid_obs_time_jd</strong>, a short duration window, and <strong>async=False</strong> so the browser waits for the JSON response directly. Query radius: ")
                    .append(DetectionReportAstrometry.escapeHtml(String.format(Locale.US, "%.2f°", queryTarget.fovRadiusDegrees)))
                    .append("; grouping: satellite; TLE payload omitted.</div>");
        }
        if (liveRenderSlotId != null && !liveRenderSlotId.isEmpty()) {
            html.append(DetectionReportAstrometry.buildLiveRenderContainerHtml(liveRenderSlotId));
        }
        html.append("</div>");
        return html.toString();
    }

    private static String buildJplUnavailableReason(DetectionReportAstrometry.Context astrometryContext) {
        if (astrometryContext == null) {
            return null;
        }

        String observerCode = JplSbIdentUrlBuilder.normalizeObservatoryCode(astrometryContext.getSkybotObserverCode());
        if ("500".equals(observerCode) && astrometryContext.getObserverSite() == null) {
            return "JPL Small-Body Identification link unavailable: JPL does not accept MPC code 500. Configure a real MPC observatory code or site latitude/longitude.";
        }
        if (observerCode == null && astrometryContext.getObserverSite() == null) {
            return "JPL Small-Body Identification link unavailable: configure a real MPC observatory code or site latitude/longitude.";
        }
        return null;
    }
}
