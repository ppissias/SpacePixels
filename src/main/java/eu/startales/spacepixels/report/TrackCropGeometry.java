/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.report;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.model.Track;

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

        /**
         * The box around a track: its detections and their extent, plus {@code padding}, kept inside a frame of
         * {@code frameDimensions} (width, height; zero when unknown).
         */
        CropBounds(Track track, int padding, int[] frameDimensions) {
            this(resolveTrackCropExtents(track), padding, frameDimensions);
        }

        CropBounds(DetectedObject detection, int padding, int[] frameDimensions) {
            this(resolveDetectionCropExtents(detection), padding, frameDimensions);
        }

        /**
         * A box no larger than the frame, moved inside it where it would run over an edge, so that no part of a
         * cutout lies outside the frame. Pictures place the detections relative to {@code startX} and
         * {@code startY}, so a moved box only puts them off centre.
         */
        private CropBounds(double[] bounds, int padding, int[] frameDimensions) {
            double minX = bounds[0];
            double maxX = bounds[1];
            double minY = bounds[2];
            double maxY = bounds[3];
            int frameWidth = frameDimensions[0];
            int frameHeight = frameDimensions[1];
            int width = (int) Math.round(maxX - minX) + padding;
            int height = (int) Math.round(maxY - minY) + padding;
            trackBoxWidth = frameWidth > 0 ? Math.min(width, frameWidth) : width;
            trackBoxHeight = frameHeight > 0 ? Math.min(height, frameHeight) : height;
            startX = insideFrame((int) Math.round((minX + maxX) / 2.0) - trackBoxWidth / 2, trackBoxWidth, frameWidth);
            startY = insideFrame((int) Math.round((minY + maxY) / 2.0) - trackBoxHeight / 2, trackBoxHeight, frameHeight);
            fixedCenterX = startX + trackBoxWidth / 2;
            fixedCenterY = startY + trackBoxHeight / 2;
        }
    }

    /**
     * The start of a box of {@code size} moved inside a frame of {@code frameSize} where it would run over an edge;
     * unchanged when the frame size is unknown (zero) or smaller than the box.
     */
    static int insideFrame(int start, int size, int frameSize) {
        if (frameSize <= 0 || size > frameSize) {
            return start;
        }
        return Math.max(0, Math.min(frameSize - size, start));
    }

    static double computeFootprintRadius(double pixelArea, boolean useElongation, double elongation) {
        if (pixelArea <= 0.0) {
            return 0.0;
        }
        double effectiveArea = useElongation ? pixelArea * elongation : pixelArea;
        return Math.sqrt(effectiveArea / Math.PI);
    }

    static double computeBoundingFootprintRadius(DetectedObject detection) {
        if (detection == null) {
            return 0.0;
        }
        return computeFootprintRadius(detection.pixelArea, detection.isStreak, detection.elongation);
    }

    static int computeSquareCropSize(double footprintRadius, int minimumSize, int padding) {
        return Math.max(minimumSize, (int) Math.round(footprintRadius * 2.0) + padding);
    }

    private static double[] resolveTrackCropExtents(Track track) {
        double[] bounds = new double[]{Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE};
        if (track != null && track.points != null) {
            for (DetectedObject point : track.points) {
                includeDetectionExtents(bounds, point);
            }
        }
        return normalizeCropExtents(bounds);
    }

    private static double[] resolveDetectionCropExtents(DetectedObject detection) {
        double[] bounds = new double[]{Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE};
        includeDetectionExtents(bounds, detection);
        return normalizeCropExtents(bounds);
    }

    /**
     * Widens {@code bounds} to a detection: a streak by the pixels it covers (or its oriented extent), so that a long
     * streak gets a box of its own shape; anything else by a circle of its footprint radius.
     */
    private static void includeDetectionExtents(double[] bounds, DetectedObject detection) {
        if (bounds == null || detection == null) {
            return;
        }
        if (detection.isStreak && detection.rawPixels != null && !detection.rawPixels.isEmpty()) {
            for (Pixel pixel : detection.rawPixels) {
                bounds[0] = Math.min(bounds[0], pixel.x);
                bounds[1] = Math.max(bounds[1], pixel.x);
                bounds[2] = Math.min(bounds[2], pixel.y);
                bounds[3] = Math.max(bounds[3], pixel.y);
            }
            return;
        }
        double halfWidth;
        double halfHeight;
        if (detection.isStreak && detection.majorExtent > 0 && Double.isFinite(detection.angle)) {
            double cos = Math.abs(Math.cos(detection.angle));
            double sin = Math.abs(Math.sin(detection.angle));
            halfWidth = 0.5 * (detection.majorExtent * cos + detection.minorExtent * sin);
            halfHeight = 0.5 * (detection.majorExtent * sin + detection.minorExtent * cos);
        } else {
            halfWidth = halfHeight = computeBoundingFootprintRadius(detection);
        }
        bounds[0] = Math.min(bounds[0], detection.x - halfWidth);
        bounds[1] = Math.max(bounds[1], detection.x + halfWidth);
        bounds[2] = Math.min(bounds[2], detection.y - halfHeight);
        bounds[3] = Math.max(bounds[3], detection.y + halfHeight);
    }

    private static double[] normalizeCropExtents(double[] bounds) {
        if (bounds[0] == Double.MAX_VALUE) bounds[0] = 0.0;
        if (bounds[1] == -Double.MAX_VALUE) bounds[1] = 0.0;
        if (bounds[2] == Double.MAX_VALUE) bounds[2] = 0.0;
        if (bounds[3] == -Double.MAX_VALUE) bounds[3] = 0.0;
        return bounds;
    }
}
