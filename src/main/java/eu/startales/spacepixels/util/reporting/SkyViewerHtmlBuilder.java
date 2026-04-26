package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.WcsCoordinateTransformer;

import java.util.Locale;

/**
 * Builds the interactive Stellarium link sections for astrometric report entries.
 */
final class SkyViewerHtmlBuilder {

    private SkyViewerHtmlBuilder() {
    }

    static String buildSkyViewerLinksHtml(DetectionReportAstrometry.Context astrometryContext,
                                          SolarSystemQueryTarget queryTarget,
                                          String epochLabel) {
        return buildSkyViewerLinksHtml(astrometryContext, queryTarget, epochLabel, null);
    }

    static String buildSkyViewerLinksHtml(DetectionReportAstrometry.Context astrometryContext,
                                          SolarSystemQueryTarget queryTarget,
                                          String epochLabel,
                                          Double preferredTightFovDegrees) {
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

        WcsCoordinateTransformer.SkyCoordinate skyCoordinate =
                astrometryContext.getTransformer().pixelToSky(queryTarget.pixelX, queryTarget.pixelY);
        double tightFovDegrees = preferredTightFovDegrees != null
                && Double.isFinite(preferredTightFovDegrees)
                && preferredTightFovDegrees > 0.0d
                ? DetectionReportAstrometry.clampFieldOfViewDegrees(preferredTightFovDegrees, 0.20d, 12.0d)
                : DetectionReportAstrometry.clampFieldOfViewDegrees(queryTarget.searchRadiusDegrees * 8.0d, 0.5d, 12.0d);
        double wideFovDegrees = DetectionReportAstrometry.clampFieldOfViewDegrees(
                Math.max(queryTarget.searchRadiusDegrees * 22.0d, tightFovDegrees * 6.0d),
                4.0d,
                60.0d);

        DetectionReportAstrometry.ObserverSite observerSite = astrometryContext.getObserverSite();
        DetectionReportAstrometry.HorizontalCoordinate horizontalCoordinate =
                DetectionReportAstrometry.resolveHorizontalCoordinate(
                        observerSite,
                        queryTarget.timestampMillis,
                        skyCoordinate.getRaDegrees(),
                        skyCoordinate.getDecDegrees());

        html.append("Interactive sky viewers:");
        html.append("<div class='id-links'>");
        if (horizontalCoordinate != null && observerSite != null) {
            html.append("<a class='id-link' href='")
                    .append(DetectionReportAstrometry.buildStellariumWebAltAzUrl(
                            observerSite,
                            queryTarget.timestampMillis,
                            horizontalCoordinate,
                            tightFovDegrees))
                    .append("' target='_blank' rel='noopener noreferrer'>Stellarium Web (Alt/Az)</a>");
            html.append("<a class='id-link' href='")
                    .append(DetectionReportAstrometry.buildStellariumWebAltAzUrl(
                            observerSite,
                            queryTarget.timestampMillis,
                            horizontalCoordinate,
                            wideFovDegrees))
                    .append("' target='_blank' rel='noopener noreferrer'>Stellarium Web Wide (Alt/Az)</a>");
        }
        html.append("<a class='id-link' href='")
                .append(buildStellariumWebUrl(observerSite, queryTarget.timestampMillis, skyCoordinate, tightFovDegrees))
                .append("' target='_blank' rel='noopener noreferrer'>Stellarium Web (RA/Dec)</a>");
        html.append("<a class='id-link' href='")
                .append(buildStellariumWebUrl(observerSite, queryTarget.timestampMillis, skyCoordinate, wideFovDegrees))
                .append("' target='_blank' rel='noopener noreferrer'>Stellarium Web Wide (RA/Dec)</a>");
        html.append("</div>");

        html.append("<div class='astro-note' style='margin-top: 6px;'>")
                .append(horizontalCoordinate != null
                        ? "Stellarium links use best-effort observer-site Alt/Az and RA/Dec URL parameters at "
                        : "Stellarium links use best-effort RA/Dec URL parameters at ")
                .append(DetectionReportAstrometry.escapeHtml(DetectionReportAstrometry.formatUtcTimestamp(queryTarget.timestampMillis)))
                .append(" with FOV ")
                .append(DetectionReportAstrometry.escapeHtml(String.format(Locale.US, "%.2f° / %.2f°", tightFovDegrees, wideFovDegrees)))
                .append(".");
        if (epochLabel != null && !epochLabel.isEmpty()) {
            html.append(" ").append(DetectionReportAstrometry.escapeHtml(epochLabel)).append(": ")
                    .append(DetectionReportAstrometry.escapeHtml(DetectionReportAstrometry.formatUtcTimestamp(queryTarget.timestampMillis)))
                    .append(".");
        }
        String horizontalSummary = DetectionReportAstrometry.formatHorizontalCoordinateSummary(horizontalCoordinate);
        if (horizontalSummary != null) {
            html.append(" ").append(DetectionReportAstrometry.escapeHtml(horizontalSummary));
        }
        if (preferredTightFovDegrees != null && Double.isFinite(preferredTightFovDegrees) && preferredTightFovDegrees > 0.0d) {
            html.append(" The tight Stellarium view matches the displayed crop FOV when WCS is available.");
        }
        html.append(" Stellarium Web may display browser-local time even when the observing site is elsewhere.");
        html.append("</div>");
        html.append("</div>");
        return html.toString();
    }

    private static String buildStellariumWebUrl(DetectionReportAstrometry.ObserverSite observerSite,
                                                long timestampMillis,
                                                WcsCoordinateTransformer.SkyCoordinate skyCoordinate,
                                                double fovDegrees) {
        StringBuilder url = new StringBuilder("https://stellarium-web.org/?");
        url.append("date=").append(DetectionReportAstrometry.urlEncode(DetectionReportAstrometry.formatStellariumTimestamp(timestampMillis)));
        url.append("&ra=").append(DetectionReportAstrometry.urlEncode(DetectionReportAstrometry.formatDecimal(skyCoordinate.getRaDegrees(), 6)));
        url.append("&dec=").append(DetectionReportAstrometry.urlEncode(DetectionReportAstrometry.formatDecimal(skyCoordinate.getDecDegrees(), 6)));
        url.append("&fov=").append(DetectionReportAstrometry.urlEncode(DetectionReportAstrometry.formatDecimal(fovDegrees, 4)));
        if (observerSite != null) {
            url.append("&lat=").append(DetectionReportAstrometry.urlEncode(DetectionReportAstrometry.formatDecimal(observerSite.latitudeDeg, 5)));
            url.append("&lng=").append(DetectionReportAstrometry.urlEncode(DetectionReportAstrometry.formatDecimal(observerSite.longitudeDeg, 5)));
            url.append("&elev=").append(DetectionReportAstrometry.urlEncode(DetectionReportAstrometry.formatDecimal(observerSite.altitudeMeters, 1)));
        }
        return url.toString();
    }
}
