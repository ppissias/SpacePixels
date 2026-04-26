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

/**
 * Immutable snapshot of the visualization settings used while rendering report images and animations.
 */
public final class ExportVisualizationSettings {

    private final double autoStretchBlackSigma;
    private final double autoStretchWhiteSigma;
    private final int gifBlinkSpeedMs;
    private final int trackCropPadding;
    private final int trackObjectCentricCropSize;
    private final boolean includeAiCreativeReportSections;
    private final int targetCircleRadius;
    private final float targetCircleStrokeWidth;

    public ExportVisualizationSettings(double autoStretchBlackSigma,
                                       double autoStretchWhiteSigma,
                                       int gifBlinkSpeedMs,
                                       int trackCropPadding,
                                       int trackObjectCentricCropSize,
                                       boolean includeAiCreativeReportSections,
                                       int targetCircleRadius,
                                       float targetCircleStrokeWidth) {
        this.autoStretchBlackSigma = autoStretchBlackSigma;
        this.autoStretchWhiteSigma = autoStretchWhiteSigma;
        this.gifBlinkSpeedMs = gifBlinkSpeedMs;
        this.trackCropPadding = trackCropPadding;
        this.trackObjectCentricCropSize = trackObjectCentricCropSize;
        this.includeAiCreativeReportSections = includeAiCreativeReportSections;
        this.targetCircleRadius = targetCircleRadius;
        this.targetCircleStrokeWidth = targetCircleStrokeWidth;
    }

    public double getAutoStretchBlackSigma() {
        return autoStretchBlackSigma;
    }

    public double getAutoStretchWhiteSigma() {
        return autoStretchWhiteSigma;
    }

    public int getGifBlinkSpeedMs() {
        return gifBlinkSpeedMs;
    }

    public int getTrackCropPadding() {
        return trackCropPadding;
    }

    public int getTrackObjectCentricCropSize() {
        return trackObjectCentricCropSize;
    }

    public boolean isIncludeAiCreativeReportSections() {
        return includeAiCreativeReportSections;
    }

    public int getTargetCircleRadius() {
        return targetCircleRadius;
    }

    public float getTargetCircleStrokeWidth() {
        return targetCircleStrokeWidth;
    }
}
