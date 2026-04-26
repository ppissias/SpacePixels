package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;

/**
 * Builds SatChecker lookup targets and resolves the track timing windows that feed those lookups.
 */
final class SatCheckerQueryTargetFactory {

    private static final double MIN_FOV_RADIUS_DEGREES = 0.25d;
    private static final double MAX_FOV_RADIUS_DEGREES = 1.5d;
    private static final double TRACK_FOV_PADDING_DEGREES = 0.20d;
    private static final long TIGHT_QUERY_MIN_DURATION_SECONDS = 5L;
    private static final long TIGHT_QUERY_MAX_DURATION_SECONDS = 15L;

    private SatCheckerQueryTargetFactory() {
    }

    static SatCheckerQueryTarget buildTrackSatCheckerQueryTarget(DetectionReportAstrometry.Context astrometryContext,
                                                                 TrackLinker.Track track) {
        if (astrometryContext == null || !astrometryContext.hasAstrometricSolution()
                || astrometryContext.getObserverSite() == null) {
            return null;
        }

        TrackTimeWindow timeWindow = resolveTrackTimeWindow(astrometryContext, track);
        if (timeWindow == null) {
            return null;
        }

        return buildTrackCenteredSatCheckerQueryTarget(astrometryContext, track, timeWindow);
    }

    static long resolveTrackPointStartTimestamp(DetectionReportAstrometry.Context astrometryContext,
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

    static long resolveTrackPointMidpointTimestamp(DetectionReportAstrometry.Context astrometryContext,
                                                   SourceExtractor.DetectedObject point) {
        long startTimestamp = resolveTrackPointStartTimestamp(astrometryContext, point);
        if (startTimestamp <= 0L) {
            return -1L;
        }
        return startTimestamp + (resolveTrackPointExposureMillis(point) / 2L);
    }

    static long resolveTimeWindowMidpointTimestamp(long startTimestampMillis,
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

    static long resolveSatCheckerCandidateDurationSeconds(long observedDurationSeconds) {
        if (observedDurationSeconds <= 0L) {
            return TIGHT_QUERY_MIN_DURATION_SECONDS;
        }
        return Math.max(
                TIGHT_QUERY_MIN_DURATION_SECONDS,
                Math.min(TIGHT_QUERY_MAX_DURATION_SECONDS, observedDurationSeconds));
    }

    private static TrackTimeWindow resolveTrackTimeWindow(DetectionReportAstrometry.Context astrometryContext,
                                                          TrackLinker.Track track) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return null;
        }

        long earliestStartTimestamp = Long.MAX_VALUE;
        long latestEndTimestamp = Long.MIN_VALUE;

        for (SourceExtractor.DetectedObject point : track.points) {
            long startTimestamp = resolveTrackPointStartTimestamp(astrometryContext, point);
            if (startTimestamp <= 0L) {
                continue;
            }

            long endTimestamp = startTimestamp + resolveTrackPointExposureMillis(point);
            if (endTimestamp < startTimestamp) {
                endTimestamp = startTimestamp;
            }

            earliestStartTimestamp = Math.min(earliestStartTimestamp, startTimestamp);
            latestEndTimestamp = Math.max(latestEndTimestamp, endTimestamp);
        }

        if (earliestStartTimestamp == Long.MAX_VALUE) {
            return null;
        }

        if (latestEndTimestamp < earliestStartTimestamp) {
            latestEndTimestamp = earliestStartTimestamp;
        }

        long durationMillis = Math.max(1L, latestEndTimestamp - earliestStartTimestamp);
        long durationSeconds = Math.max(1L, (long) Math.ceil(durationMillis / 1000.0d));
        return new TrackTimeWindow(earliestStartTimestamp, latestEndTimestamp, durationSeconds);
    }

    private static SatCheckerQueryTarget buildTrackCenteredSatCheckerQueryTarget(DetectionReportAstrometry.Context astrometryContext,
                                                                                 TrackLinker.Track track,
                                                                                 TrackTimeWindow timeWindow) {
        SolarSystemQueryTarget trackQueryTarget = SolarSystemQueryTargetFactory.buildTrackQueryTarget(astrometryContext, track);
        if (trackQueryTarget == null) {
            return null;
        }

        WcsCoordinateTransformer transformer = astrometryContext.getTransformer();
        WcsCoordinateTransformer.SkyCoordinate midpointSky = transformer.pixelToSky(trackQueryTarget.pixelX, trackQueryTarget.pixelY);
        if (midpointSky == null || !Double.isFinite(midpointSky.getRaDegrees()) || !Double.isFinite(midpointSky.getDecDegrees())) {
            return null;
        }

        double maxTrackSeparationDegrees = 0.0d;
        for (SourceExtractor.DetectedObject point : track.points) {
            if (point == null) {
                continue;
            }

            WcsCoordinateTransformer.SkyCoordinate pointSky = transformer.pixelToSky(point.x, point.y);
            double separationDegrees = computeAngularSeparationDegrees(
                    midpointSky.getRaDegrees(),
                    midpointSky.getDecDegrees(),
                    pointSky.getRaDegrees(),
                    pointSky.getDecDegrees());
            if (!Double.isFinite(separationDegrees)) {
                continue;
            }
            maxTrackSeparationDegrees = Math.max(maxTrackSeparationDegrees, separationDegrees);
        }

        double fovRadiusDegrees = clampFieldOfViewDegrees(
                Math.max(trackQueryTarget.searchRadiusDegrees * 1.5d, maxTrackSeparationDegrees + TRACK_FOV_PADDING_DEGREES),
                MIN_FOV_RADIUS_DEGREES,
                MAX_FOV_RADIUS_DEGREES);
        return new SatCheckerQueryTarget(
                midpointSky.getRaDegrees(),
                midpointSky.getDecDegrees(),
                fovRadiusDegrees,
                timeWindow.startTimestampMillis,
                timeWindow.endTimestampMillis,
                timeWindow.durationSeconds,
                "SatChecker tight query centered on the measured streak-track midpoint");
    }

    private static double clampFieldOfViewDegrees(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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

    private static final class TrackTimeWindow {
        private final long startTimestampMillis;
        private final long endTimestampMillis;
        private final long durationSeconds;

        private TrackTimeWindow(long startTimestampMillis,
                                long endTimestampMillis,
                                long durationSeconds) {
            this.startTimestampMillis = startTimestampMillis;
            this.endTimestampMillis = endTimestampMillis;
            this.durationSeconds = durationSeconds;
        }
    }
}
