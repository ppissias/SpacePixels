/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.solarsystem;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.ObservingSite;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.fits.WcsSolutionResolver;

/**
 * What {@link SolarSystemClient} needs to predict the known asteroids and comets of a session: the frames' plate
 * solution and size, the observing span (UTC milliseconds, first exposure start to last exposure end), the faintest
 * predicted magnitude, and where the observer was.
 */
public record SolarSystemQuery(WcsCoordinateTransformer transformer, int width, int height,
                               long startMillis, long endMillis, double magnitudeLimit, Observer observer) {

    /**
     * The observer: an IAU (MPC) observatory code, or a site in degrees (north and east positive) with its altitude
     * in metres. {@code description} names the source without the coordinates.
     */
    public record Observer(String observatoryCode, ObservingSite site, String description) {
    }

    /**
     * The query for these frames with the application settings: the plate solution from the FITS headers, the
     * observatory code when one is set, else the site of the headers or of the settings.
     *
     * @throws IllegalArgumentException with a sentence for the user when something needed is missing
     */
    public static SolarSystemQuery of(FitsFileInformation[] files, AppConfig config) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("No frames are imported.");
        }
        WcsSolutionResolver.ResolvedWcsSolution solution = WcsSolutionResolver.resolveUncorrected(null, files);
        if (solution == null) {
            throw new IllegalArgumentException("No frame has a plate solution in its FITS header.");
        }
        long[] span = observingSpan(files);
        if (span == null) {
            throw new IllegalArgumentException("The frames have no observation time (DATE-OBS), which the predictions need.");
        }
        return new SolarSystemQuery(solution.getTransformer(), files[0].getSizeWidth(), files[0].getSizeHeight(),
                span[0], span[1], config.solarSystemMagnitude(), observer(files, config));
    }

    /** The configured observatory code, else the site; throws with a sentence for the user when there is neither. */
    static Observer observer(FitsFileInformation[] files, AppConfig config) {
        String code = JplSbIdentUrlBuilder.normalizeObservatoryCode(config.observatoryCode);
        if (JplSbIdentUrlBuilder.isJplCompatibleObservatoryCode(code)) {
            return new Observer(code, null, "IAU code " + code);
        }
        ObservingSite site = ObservingSite.resolve(files, config.siteLat, config.siteLong);
        if (site == null) {
            throw new IllegalArgumentException("No observing site: the FITS headers have none. Enter the IAU observatory "
                    + "code, or the site latitude and longitude, in the Astrometry Config tab.");
        }
        return new Observer(null, site, ObservingSite.FROM_HEADER.equals(site.source())
                ? "site from the FITS header" : "site from the Astrometry Config tab");
    }

    /**
     * The first exposure's start and the last exposure's end, in UTC milliseconds, over the frames that have an
     * observation time; null when none has one. A frame without an exposure time counts as an instant.
     */
    public static long[] observingSpan(FitsFileInformation[] files) {
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (FitsFileInformation file : files) {
            long timestamp = file == null ? -1 : file.getObservationTimestamp();
            if (timestamp <= 0) {
                continue;
            }
            start = Math.min(start, timestamp);
            end = Math.max(end, timestamp + Math.max(0, file.getExposureDurationMillis()));
        }
        return start == Long.MAX_VALUE ? null : new long[]{start, end};
    }
}
