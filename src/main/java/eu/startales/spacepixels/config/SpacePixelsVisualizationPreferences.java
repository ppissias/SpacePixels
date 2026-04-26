/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.config;

import eu.startales.spacepixels.util.DisplayImageRenderer;
import eu.startales.spacepixels.util.RawImageAnnotator;
import eu.startales.spacepixels.util.reporting.DetectionReportGenerator;

/**
 * User-level rendering and export preferences that do not affect detection results.
 */
public class SpacePixelsVisualizationPreferences {

    public double streakLineScaleFactor = RawImageAnnotator.streakLineScaleFactor;
    public int streakCentroidBoxRadius = RawImageAnnotator.streakCentroidBoxRadius;
    public int pointSourceMinBoxRadius = RawImageAnnotator.pointSourceMinBoxRadius;
    public int dynamicBoxPadding = RawImageAnnotator.dynamicBoxPadding;
    public double autoStretchBlackSigma = DisplayImageRenderer.autoStretchBlackSigma;
    public double autoStretchWhiteSigma = DisplayImageRenderer.autoStretchWhiteSigma;
    public int gifBlinkSpeedMs = DetectionReportGenerator.gifBlinkSpeedMs;
    public int trackCropPadding = DetectionReportGenerator.trackCropPadding;
    public boolean includeAiCreativeReportSections = DetectionReportGenerator.includeAiCreativeReportSections;

    public static SpacePixelsVisualizationPreferences captureCurrent() {
        return new SpacePixelsVisualizationPreferences();
    }

    public void applyToRuntime() {
        RawImageAnnotator.streakLineScaleFactor = streakLineScaleFactor;
        RawImageAnnotator.streakCentroidBoxRadius = streakCentroidBoxRadius;
        RawImageAnnotator.pointSourceMinBoxRadius = pointSourceMinBoxRadius;
        RawImageAnnotator.dynamicBoxPadding = dynamicBoxPadding;
        DisplayImageRenderer.autoStretchBlackSigma = autoStretchBlackSigma;
        DisplayImageRenderer.autoStretchWhiteSigma = autoStretchWhiteSigma;
        DetectionReportGenerator.gifBlinkSpeedMs = gifBlinkSpeedMs;
        DetectionReportGenerator.trackCropPadding = trackCropPadding;
        DetectionReportGenerator.includeAiCreativeReportSections = includeAiCreativeReportSections;
    }
}
