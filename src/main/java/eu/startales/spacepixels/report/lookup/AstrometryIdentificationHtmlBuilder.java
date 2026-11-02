package eu.startales.spacepixels.report.lookup;
import eu.startales.spacepixels.solarsystem.JplSbIdentUrlBuilder;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;

import java.util.Locale;
import eu.startales.spacepixels.report.DetectionReportAstrometry;

/**
 * Builds the report sections that explain SkyBoT, JPL, and SatChecker identification lookups.
 */
public final class AstrometryIdentificationHtmlBuilder {

    private static final double JPL_NEO_RECOVERY_HALF_WIDTH_DEGREES = 5.0d;

    private AstrometryIdentificationHtmlBuilder() {
    }

    public static String buildSolarSystemIdentificationHtml(DetectionReportAstrometry.Context astrometryContext,
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
            if (JplSbIdentUrlBuilder.isJplCompatibleObservatoryCode(astrometryContext.getSkybotObserverCode())) {
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

    public static String buildAnomalySatCheckerHtml(DetectionReportAstrometry.Context context,
                                             DetectedObject point,
                                             String slotId,
                                             String sidecarFileName) {
        String unavailable = null;
        if (context == null || !context.hasAstrometricSolution()) {
            unavailable = "plate-solve the session to locate the event on the sky";
        } else if (context.getObserverSite() == null) {
            unavailable = "add the observing location in Astrometry Config or the FITS headers";
        }
        SatCheckerQueryTarget target = unavailable == null
                ? SatCheckerQueryTargetFactory.buildAnomalySatCheckerQueryTarget(context, point) : null;
        if (unavailable == null && target == null) {
            unavailable = "a valid exposure start, exposure length and sky position are required";
        }
        String url = target == null ? null : DetectionReportAstrometry.buildSatCheckerFovUrl(
                context.getObserverSite(),
                SatCheckerQueryTargetFactory.resolveTimeWindowMidpointTimestamp(target.startTimestampMillis, target.endTimestampMillis),
                target.durationSeconds, target.raDegrees, target.decDegrees, target.fovRadiusDegrees, false);
        if (unavailable == null && url == null) unavailable = "check the observing location and exposure metadata";
        if (unavailable != null) {
            return "<div class='astro-note'>Satellite check unavailable: " + unavailable + ".</div>";
        }
        return "<div class='anomaly-identification'><div class='id-links'>"
                + DetectionReportAstrometry.buildLiveRenderButtonHtml("satchecker", url, slotId,
                        "Check satellites", sidecarFileName, "Possible satellite associations")
                + "<a class='id-link' href='" + DetectionReportAstrometry.escapeHtml(url)
                + "' target='_blank' rel='noopener noreferrer'>Open SatChecker response</a></div>"
                + "<p class='astro-note'>Checks for predicted satellite passages within "
                + String.format(Locale.US, "%.2f&deg;", target.fovRadiusDegrees)
                + " of this position over the whole exposure. The flash time within the exposure is unknown. "
                + "Keep SpacePixels running to show and save results here. "
                + "A possible association does not confirm a satellite flash; no match does not rule one out.</p>"
                + DetectionReportAstrometry.buildLiveRenderContainerHtml(slotId) + "</div>";
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
