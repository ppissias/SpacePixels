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

import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;

/**
 * Computes crop bounds and footprint-driven sizing for per-detection and per-track visualizations.
 */
final class TrackCropGeometry {

    private TrackCropGeometry() {
    }

    static final class CropBounds {
        final int trackBoxWidth;
        final int trackBoxHeight;
        final int fixedCenterX;
        final int fixedCenterY;
        final int startX;
        final int startY;

        CropBounds(TrackLinker.Track track, int padding) {
            this(resolveTrackCropExtents(track), padding);
        }

        CropBounds(SourceExtractor.DetectedObject detection, int padding) {
            this(resolveDetectionCropExtents(detection), padding);
        }

        private CropBounds(double[] bounds, int padding) {
            double minX = bounds[0];
            double maxX = bounds[1];
            double minY = bounds[2];
            double maxY = bounds[3];
            trackBoxWidth = (int) Math.round(maxX - minX) + padding;
            trackBoxHeight = (int) Math.round(maxY - minY) + padding;
            fixedCenterX = (int) Math.round((minX + maxX) / 2.0);
            fixedCenterY = (int) Math.round((minY + maxY) / 2.0);
            startX = fixedCenterX - (trackBoxWidth / 2);
            startY = fixedCenterY - (trackBoxHeight / 2);
        }
    }

    static double computeFootprintRadius(double pixelArea, boolean useElongation, double elongation) {
        if (pixelArea <= 0.0) {
            return 0.0;
        }
        double effectiveArea = useElongation ? pixelArea * elongation : pixelArea;
        return Math.sqrt(effectiveArea / Math.PI);
    }

    static double computeBoundingFootprintRadius(SourceExtractor.DetectedObject detection) {
        if (detection == null) {
            return 0.0;
        }
        return computeFootprintRadius(detection.pixelArea, detection.isStreak, detection.elongation);
    }

    static int computeSquareCropSize(double footprintRadius, int minimumSize, int padding) {
        return Math.max(minimumSize, (int) Math.round(footprintRadius * 2.0) + padding);
    }

    private static double[] resolveTrackCropExtents(TrackLinker.Track track) {
        double[] bounds = new double[]{Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE};
        if (track != null && track.points != null) {
            for (SourceExtractor.DetectedObject point : track.points) {
                includeDetectionExtents(bounds, point);
            }
        }
        return normalizeCropExtents(bounds);
    }

    private static double[] resolveDetectionCropExtents(SourceExtractor.DetectedObject detection) {
        double[] bounds = new double[]{Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE};
        includeDetectionExtents(bounds, detection);
        return normalizeCropExtents(bounds);
    }

    private static void includeDetectionExtents(double[] bounds, SourceExtractor.DetectedObject detection) {
        if (bounds == null || detection == null) {
            return;
        }
        double radius = computeBoundingFootprintRadius(detection);
        bounds[0] = Math.min(bounds[0], detection.x - radius);
        bounds[1] = Math.max(bounds[1], detection.x + radius);
        bounds[2] = Math.min(bounds[2], detection.y - radius);
        bounds[3] = Math.max(bounds[3], detection.y + radius);
    }

    private static double[] normalizeCropExtents(double[] bounds) {
        if (bounds[0] == Double.MAX_VALUE) bounds[0] = 0.0;
        if (bounds[1] == -Double.MAX_VALUE) bounds[1] = 0.0;
        if (bounds[2] == Double.MAX_VALUE) bounds[2] = 0.0;
        if (bounds[3] == -Double.MAX_VALUE) bounds[3] = 0.0;
        return bounds;
    }
}
