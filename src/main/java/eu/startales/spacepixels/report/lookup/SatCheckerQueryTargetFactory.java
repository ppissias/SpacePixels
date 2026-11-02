package eu.startales.spacepixels.report.lookup;

import io.github.ppissias.jtransient.model.DetectedObject;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.report.DetectionReportAstrometry;

/**
 * Builds single-frame SatChecker lookup targets and resolves point timing shared by report links.
 */
public final class SatCheckerQueryTargetFactory {

    private static final double MIN_FOV_RADIUS_DEGREES = 0.25d;

    private SatCheckerQueryTargetFactory() {
    }

    /**
     * A flash can occur anywhere in its exposure, so its candidate search covers the whole exposure.
     */
    public static SatCheckerQueryTarget buildAnomalySatCheckerQueryTarget(DetectionReportAstrometry.Context context,
                                                                          DetectedObject point) {
        if (context == null || !context.hasAstrometricSolution() || context.getObserverSite() == null
                || point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y)) {
            return null;
        }
        long start = resolveTrackPointStartTimestamp(context, point);
        long exposure = resolveTrackPointExposureMillis(point);
        if (exposure <= 0L) exposure = context.getFrameExposureMillis(point.sourceFrameIndex);
        if (start <= 0L || exposure <= 0L || start > Long.MAX_VALUE - exposure) return null;

        WcsCoordinateTransformer.SkyCoordinate sky = context.getTransformer().pixelToSky(point.x, point.y);
        if (sky == null || !Double.isFinite(sky.getRaDegrees()) || !Double.isFinite(sky.getDecDegrees())) return null;
        return new SatCheckerQueryTarget(sky.getRaDegrees(), sky.getDecDegrees(), MIN_FOV_RADIUS_DEGREES,
                start, start + exposure, (long) Math.ceil(exposure / 1000.0d),
                "Search centered on the event position over the whole exposure");
    }

    public static long resolveTrackPointStartTimestamp(DetectionReportAstrometry.Context astrometryContext,
                                                       DetectedObject point) {
        if (point == null) {
            return -1L;
        }
        if (point.timestamp > 0L) {
            return point.timestamp;
        }
        return astrometryContext != null ? astrometryContext.getFrameTimestampMillis(point.sourceFrameIndex) : -1L;
    }

    public static long resolveTrackPointExposureMillis(DetectedObject point) {
        return point != null ? Math.max(point.exposureDuration, 0L) : 0L;
    }

    public static long resolveTrackPointMidpointTimestamp(DetectionReportAstrometry.Context astrometryContext,
                                                          DetectedObject point) {
        long startTimestamp = resolveTrackPointStartTimestamp(astrometryContext, point);
        if (startTimestamp <= 0L) {
            return -1L;
        }
        long exposure = resolveTrackPointExposureMillis(point);
        if (exposure == 0 && astrometryContext != null) {
            exposure = astrometryContext.getFrameExposureMillis(point.sourceFrameIndex);
        }
        return startTimestamp + (exposure / 2L);
    }

    public static long resolveTimeWindowMidpointTimestamp(long startTimestampMillis,
                                                          long endTimestampMillis) {
        if (startTimestampMillis <= 0L && endTimestampMillis <= 0L) {
            return -1L;
        }
        if (startTimestampMillis <= 0L) {
            return endTimestampMillis;
        }
        if (endTimestampMillis <= startTimestampMillis) {
            return startTimestampMillis;
        }
        return startTimestampMillis + ((endTimestampMillis - startTimestampMillis) / 2L);
    }

}
