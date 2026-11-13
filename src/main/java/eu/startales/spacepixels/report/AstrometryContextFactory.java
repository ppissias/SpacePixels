package eu.startales.spacepixels.report;
import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;
import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.ObservingSite;
import eu.startales.spacepixels.fits.WcsSolutionResolver;

import java.util.ArrayList;
import java.util.List;
import eu.startales.spacepixels.solarsystem.JplSbIdentUrlBuilder;

/**
 * Builds the reusable astrometry/report context from FITS metadata and user-configured observatory settings.
 */
final class AstrometryContextFactory {

    private AstrometryContextFactory() {
    }

    /** Resolves astrometry with the run's correction, which may be null; no session file is read. */
    static DetectionReportAstrometry.Context buildContext(FitsFileInformation[] fitsFiles, AppConfig appConfig,
            PlateSolutionCorrection correction) {
        return buildResolvedContext(fitsFiles, appConfig, WcsSolutionResolver.resolve(null, fitsFiles, correction));
    }

    /** Combines resolved WCS with the captured site and copied frame timing metadata. */
    private static DetectionReportAstrometry.Context buildResolvedContext(FitsFileInformation[] fitsFiles,
            AppConfig appConfig, WcsSolutionResolver.ResolvedWcsSolution wcsSolution) {
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

    /** The site from the frames' headers, else the configured fallback; null when neither has one. */
    private static DetectionReportAstrometry.ObserverSite resolveObserverSite(FitsFileInformation[] fitsFiles, AppConfig appConfig) {
        ObservingSite site = ObservingSite.resolve(fitsFiles,
                appConfig == null ? null : appConfig.siteLat, appConfig == null ? null : appConfig.siteLong,
                appConfig == null ? null : appConfig.siteAltitude);
        return site == null ? null : new DetectionReportAstrometry.ObserverSite(
                site.latitudeDeg(), site.longitudeDeg(), site.altitudeMeters(), site.source());
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
}
