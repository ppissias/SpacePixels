package eu.startales.spacepixels.report.lookup;
import eu.startales.spacepixels.util.SkyMath;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;

import java.util.Locale;
import eu.startales.spacepixels.report.DetectionReportAstrometry;

/**
 * Builds the interactive Stellarium link sections for astrometric report entries.
 */
public final class SkyViewerHtmlBuilder {

    public static final String LOCAL_STELLARIUM_MANUAL_URL =
            "https://github.com/ppissias/SpacePixels/blob/master/MANUAL.md#annex-local-stellarium-integration";

    private SkyViewerHtmlBuilder() {
    }

    public static String buildSkyViewerLinksHtml(DetectionReportAstrometry.Context astrometryContext,
                                          SolarSystemQueryTarget queryTarget,
                                          String epochLabel) {
        return buildSkyViewerLinksHtml(astrometryContext, queryTarget, epochLabel, null);
    }

    public static String buildSkyViewerLinksHtml(DetectionReportAstrometry.Context astrometryContext,
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
                ? SkyMath.clampFieldOfViewDegrees(preferredTightFovDegrees, 0.20d, 12.0d)
                : SkyMath.clampFieldOfViewDegrees(queryTarget.searchRadiusDegrees * 8.0d, 0.5d, 12.0d);
        double wideFovDegrees = SkyMath.clampFieldOfViewDegrees(
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
        html.append(buildLocalStellariumButtonHtml(
                "Stellarium Local",
                observerSite,
                queryTarget.timestampMillis,
                skyCoordinate,
                tightFovDegrees));
        html.append(buildLocalStellariumButtonHtml(
                "Stellarium Local Wide",
                observerSite,
                queryTarget.timestampMillis,
                skyCoordinate,
                wideFovDegrees));
        html.append("</div>");
        html.append("<div class='astro-note stellarium-local-status' style='display:none; margin-top: 6px;'></div>");

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
        html.append(" Stellarium Web may display browser-local time even when the observing site is elsewhere.")
                .append(" <strong>Stellarium Local</strong> slews a running desktop Stellarium installation via its Remote Control plugin (see the <a class='live-link-inline' href='")
                .append(LOCAL_STELLARIUM_MANUAL_URL)
                .append("' target='_blank' rel='noopener noreferrer'>setup instructions in the SpacePixels manual</a>).");
        html.append("</div>");
        html.append("</div>");
        return html.toString();
    }

    private static String buildLocalStellariumButtonHtml(String label,
                                                         DetectionReportAstrometry.ObserverSite observerSite,
                                                         long timestampMillis,
                                                         WcsCoordinateTransformer.SkyCoordinate skyCoordinate,
                                                         double fovDegrees) {
        StringBuilder button = new StringBuilder();
        button.append("<button type='button' class='id-link stellarium-local-link'")
                .append(" data-time='").append(timestampMillis).append("'")
                .append(" data-utc='").append(DetectionReportAstrometry.escapeHtml(DetectionReportAstrometry.formatUtcTimestamp(timestampMillis))).append("'")
                .append(" data-ra='").append(DetectionReportAstrometry.formatDecimal(skyCoordinate.getRaDegrees(), 6)).append("'")
                .append(" data-dec='").append(DetectionReportAstrometry.formatDecimal(skyCoordinate.getDecDegrees(), 6)).append("'")
                .append(" data-fov='").append(DetectionReportAstrometry.formatDecimal(fovDegrees, 4)).append("'");
        if (observerSite != null) {
            button.append(" data-lat='").append(DetectionReportAstrometry.formatDecimal(observerSite.latitudeDeg, 5)).append("'")
                    .append(" data-lng='").append(DetectionReportAstrometry.formatDecimal(observerSite.longitudeDeg, 5)).append("'")
                    .append(" data-elev='").append(DetectionReportAstrometry.formatDecimal(observerSite.altitudeMeters, 1)).append("'");
        }
        button.append(">").append(DetectionReportAstrometry.escapeHtml(label)).append("</button>");
        return button.toString();
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
