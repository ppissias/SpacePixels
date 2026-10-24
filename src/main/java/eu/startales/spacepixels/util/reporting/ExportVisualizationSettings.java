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

import eu.startales.spacepixels.config.VariableStarReportOptions;

/**
 * Immutable snapshot of the visualization settings used while rendering report images and animations.
 */
public final class ExportVisualizationSettings {
    private static final ExportVisualizationSettings DEFAULTS = new ExportVisualizationSettings(0.5, 5.0, 300, 200, 200, 15, 2.0f);

    /** Returns built-in rendering defaults independently of mutable desktop preferences. */
    public static ExportVisualizationSettings defaults() {
        return DEFAULTS;
    }

    private final double autoStretchBlackSigma;
    private final double autoStretchWhiteSigma;
    private final int gifBlinkSpeedMs;
    private final int trackCropPadding;
    private final int trackObjectCentricCropSize;
    private final int targetCircleRadius;
    private final float targetCircleStrokeWidth;
    private final VariableStarReportOptions variableStarReportOptions;

    public ExportVisualizationSettings(double autoStretchBlackSigma,
                                       double autoStretchWhiteSigma,
                                       int gifBlinkSpeedMs,
                                       int trackCropPadding,
                                       int trackObjectCentricCropSize,
                                       int targetCircleRadius,
                                       float targetCircleStrokeWidth) {
        this(autoStretchBlackSigma, autoStretchWhiteSigma, gifBlinkSpeedMs, trackCropPadding,
                trackObjectCentricCropSize, targetCircleRadius, targetCircleStrokeWidth, VariableStarReportOptions.DEFAULT);
    }

    public ExportVisualizationSettings(double autoStretchBlackSigma, double autoStretchWhiteSigma, int gifBlinkSpeedMs,
                                       int trackCropPadding, int trackObjectCentricCropSize,
                                       int targetCircleRadius, float targetCircleStrokeWidth, VariableStarReportOptions options) {
        this.autoStretchBlackSigma = autoStretchBlackSigma;
        this.autoStretchWhiteSigma = autoStretchWhiteSigma;
        this.gifBlinkSpeedMs = gifBlinkSpeedMs;
        this.trackCropPadding = trackCropPadding;
        this.trackObjectCentricCropSize = trackObjectCentricCropSize;
        this.targetCircleRadius = targetCircleRadius;
        this.targetCircleStrokeWidth = targetCircleStrokeWidth;
        this.variableStarReportOptions = java.util.Objects.requireNonNull(options, "options");
    }

    public VariableStarReportOptions getVariableStarReportOptions() { return variableStarReportOptions; }

    /** Returns an independent immutable snapshot with the supplied variable-star presentation choices. */
    public ExportVisualizationSettings withVariableStarReportOptions(VariableStarReportOptions options) {
        return new ExportVisualizationSettings(autoStretchBlackSigma, autoStretchWhiteSigma, gifBlinkSpeedMs,
                trackCropPadding, trackObjectCentricCropSize, targetCircleRadius, targetCircleStrokeWidth, options);
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

    public int getTargetCircleRadius() {
        return targetCircleRadius;
    }

    public float getTargetCircleStrokeWidth() {
        return targetCircleStrokeWidth;
    }
}
