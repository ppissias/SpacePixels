package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;

/**
 * Builds solar-system lookup targets from detections and tracks without coupling that math to report HTML assembly.
 */
final class SolarSystemQueryTargetFactory {

    private SolarSystemQueryTargetFactory() {
    }

    static SolarSystemQueryTarget buildSingleDetectionQueryTarget(DetectionReportAstrometry.Context astrometryContext,
                                                                  SourceExtractor.DetectedObject detection) {
        if (astrometryContext == null || detection == null) {
            return null;
        }

        long timestampMillis = astrometryContext.getSessionMidpointTimestampMillis();
        double radiusDegrees = estimateSkybotSearchRadiusDegrees(
                astrometryContext,
                detection.x,
                detection.y,
                detection.pixelArea,
                detection.elongation);
        return new SolarSystemQueryTarget(detection.x, detection.y, timestampMillis, radiusDegrees, null);
    }

    static SolarSystemQueryTarget buildFrameDetectionQueryTarget(DetectionReportAstrometry.Context astrometryContext,
                                                                 SourceExtractor.DetectedObject detection) {
        if (astrometryContext == null || detection == null) {
            return null;
        }

        long timestampMillis = astrometryContext.getFrameTimestampMillis(detection.sourceFrameIndex);
        if (timestampMillis <= 0L) {
            timestampMillis = astrometryContext.getSessionMidpointTimestampMillis();
        }

        double radiusDegrees = estimateSkybotSearchRadiusDegrees(
                astrometryContext,
                detection.x,
                detection.y,
                detection.pixelArea,
                detection.elongation);
        return new SolarSystemQueryTarget(detection.x, detection.y, timestampMillis, radiusDegrees, "Single-frame detection");
    }

    static SolarSystemQueryTarget buildTrackQueryTarget(DetectionReportAstrometry.Context astrometryContext,
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
        return new SolarSystemQueryTarget(
                pixelX,
                pixelY,
                timestampMillis,
                radiusDegrees,
                "Track midpoint from " + count + " detections");
    }

    private static double estimateSkybotSearchRadiusDegrees(DetectionReportAstrometry.Context astrometryContext,
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
}
