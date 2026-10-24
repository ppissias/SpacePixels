/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.config;

/**
 * The saved form of the visualization preferences: the values of {@link VisualizationSettings} that the settings
 * page edits, as written to the visualization file. A value missing from a file keeps its built-in default.
 */
public class SpacePixelsVisualizationPreferences {
    private static final VisualizationSettings DEFAULTS = VisualizationSettings.defaults();

    public double streakLineScaleFactor = DEFAULTS.getStreakLineScaleFactor();
    public int streakCentroidBoxRadius = DEFAULTS.getStreakCentroidBoxRadius();
    public int pointSourceMinBoxRadius = DEFAULTS.getPointSourceMinBoxRadius();
    public int dynamicBoxPadding = DEFAULTS.getDynamicBoxPadding();
    public double autoStretchBlackSigma = DEFAULTS.getAutoStretchBlackSigma();
    public double autoStretchWhiteSigma = DEFAULTS.getAutoStretchWhiteSigma();
    public int gifBlinkSpeedMs = DEFAULTS.getGifBlinkSpeedMs();
    public int trackCropPadding = DEFAULTS.getTrackCropPadding();

    /** The saved form of {@code settings}. */
    public static SpacePixelsVisualizationPreferences of(VisualizationSettings settings) {
        SpacePixelsVisualizationPreferences preferences = new SpacePixelsVisualizationPreferences();
        preferences.streakLineScaleFactor = settings.getStreakLineScaleFactor();
        preferences.streakCentroidBoxRadius = settings.getStreakCentroidBoxRadius();
        preferences.pointSourceMinBoxRadius = settings.getPointSourceMinBoxRadius();
        preferences.dynamicBoxPadding = settings.getDynamicBoxPadding();
        preferences.autoStretchBlackSigma = settings.getAutoStretchBlackSigma();
        preferences.autoStretchWhiteSigma = settings.getAutoStretchWhiteSigma();
        preferences.gifBlinkSpeedMs = settings.getGifBlinkSpeedMs();
        preferences.trackCropPadding = settings.getTrackCropPadding();
        return preferences;
    }

    /** {@code base} with these saved values; the values the file does not hold stay as in {@code base}. */
    public VisualizationSettings applyTo(VisualizationSettings base) {
        return base.withStreakLineScaleFactor(streakLineScaleFactor)
                .withStreakCentroidBoxRadius(streakCentroidBoxRadius)
                .withPointSourceMinBoxRadius(pointSourceMinBoxRadius)
                .withDynamicBoxPadding(dynamicBoxPadding)
                .withAutoStretchBlackSigma(autoStretchBlackSigma)
                .withAutoStretchWhiteSigma(autoStretchWhiteSigma)
                .withGifBlinkSpeedMs(gifBlinkSpeedMs)
                .withTrackCropPadding(trackCropPadding);
    }
}
