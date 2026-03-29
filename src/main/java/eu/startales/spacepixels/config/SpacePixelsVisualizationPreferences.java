/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.config;

import eu.startales.spacepixels.util.ImageDisplayUtils;
import eu.startales.spacepixels.util.RawImageAnnotator;

/**
 * User-level rendering and export preferences that do not affect detection results.
 */
public class SpacePixelsVisualizationPreferences {

    public double streakLineScaleFactor = RawImageAnnotator.streakLineScaleFactor;
    public int streakCentroidBoxRadius = RawImageAnnotator.streakCentroidBoxRadius;
    public int pointSourceMinBoxRadius = RawImageAnnotator.pointSourceMinBoxRadius;
    public int dynamicBoxPadding = RawImageAnnotator.dynamicBoxPadding;
    public double autoStretchBlackSigma = ImageDisplayUtils.autoStretchBlackSigma;
    public double autoStretchWhiteSigma = ImageDisplayUtils.autoStretchWhiteSigma;
    public int gifBlinkSpeedMs = ImageDisplayUtils.gifBlinkSpeedMs;
    public int trackCropPadding = ImageDisplayUtils.trackCropPadding;
    public boolean includeAiCreativeReportSections = ImageDisplayUtils.includeAiCreativeReportSections;

    public static SpacePixelsVisualizationPreferences captureCurrent() {
        return new SpacePixelsVisualizationPreferences();
    }

    public void applyToRuntime() {
        RawImageAnnotator.streakLineScaleFactor = streakLineScaleFactor;
        RawImageAnnotator.streakCentroidBoxRadius = streakCentroidBoxRadius;
        RawImageAnnotator.pointSourceMinBoxRadius = pointSourceMinBoxRadius;
        RawImageAnnotator.dynamicBoxPadding = dynamicBoxPadding;
        ImageDisplayUtils.autoStretchBlackSigma = autoStretchBlackSigma;
        ImageDisplayUtils.autoStretchWhiteSigma = autoStretchWhiteSigma;
        ImageDisplayUtils.gifBlinkSpeedMs = gifBlinkSpeedMs;
        ImageDisplayUtils.trackCropPadding = trackCropPadding;
        ImageDisplayUtils.includeAiCreativeReportSections = includeAiCreativeReportSections;
    }
}
