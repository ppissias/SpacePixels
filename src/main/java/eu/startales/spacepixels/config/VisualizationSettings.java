/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.config;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * How pictures are drawn: the display stretch, report animations and crops, detection markers and variable-star
 * presentation. None of these values changes a detection result. The value is immutable; {@link #defaults()} holds
 * the built-in values, independent of any window or saved preference, and each {@code with…} method returns a copy
 * with one value replaced. Numbers are taken as given, without a range check; the settings page bounds what users
 * enter.
 */
public final class VisualizationSettings {
    private static final VisualizationSettings DEFAULTS = new VisualizationSettings(new Values());

    private final double autoStretchBlackSigma;
    private final double autoStretchWhiteSigma;
    private final int gifBlinkSpeedMs;
    private final int trackCropPadding;
    private final int trackObjectCentricCropSize;
    private final int targetCircleRadius;
    private final float targetCircleStrokeWidth;
    private final double streakLineScaleFactor;
    private final int streakCentroidBoxRadius;
    private final int pointSourceMinBoxRadius;
    private final int dynamicBoxPadding;
    private final int maxAnimatedMarkers;
    private final VariableStarReportOptions variableStarReportOptions;

    private VisualizationSettings(Values values) {
        autoStretchBlackSigma = values.autoStretchBlackSigma;
        autoStretchWhiteSigma = values.autoStretchWhiteSigma;
        gifBlinkSpeedMs = values.gifBlinkSpeedMs;
        trackCropPadding = values.trackCropPadding;
        trackObjectCentricCropSize = values.trackObjectCentricCropSize;
        targetCircleRadius = values.targetCircleRadius;
        targetCircleStrokeWidth = values.targetCircleStrokeWidth;
        streakLineScaleFactor = values.streakLineScaleFactor;
        streakCentroidBoxRadius = values.streakCentroidBoxRadius;
        pointSourceMinBoxRadius = values.pointSourceMinBoxRadius;
        dynamicBoxPadding = values.dynamicBoxPadding;
        maxAnimatedMarkers = values.maxAnimatedMarkers;
        variableStarReportOptions = Objects.requireNonNull(values.variableStarReportOptions, "variableStarReportOptions");
    }

    /** The built-in values. */
    public static VisualizationSettings defaults() {
        return DEFAULTS;
    }

    /** How far below the image mean, in standard deviations, the automatic black point of the display stretch lies. */
    public double getAutoStretchBlackSigma() {
        return autoStretchBlackSigma;
    }

    /** How far above the image mean, in standard deviations, the automatic white point of the display stretch lies. */
    public double getAutoStretchWhiteSigma() {
        return autoStretchWhiteSigma;
    }

    /** The frame delay of exported animated GIFs, in milliseconds. */
    public int getGifBlinkSpeedMs() {
        return gifBlinkSpeedMs;
    }

    /** The border added around cropped track pictures, in pixels. */
    public int getTrackCropPadding() {
        return trackCropPadding;
    }

    /** The side of the object-centred crops of a track, in pixels. */
    public int getTrackObjectCentricCropSize() {
        return trackObjectCentricCropSize;
    }

    /** The radius of the circle around a target in report pictures, in pixels. */
    public int getTargetCircleRadius() {
        return targetCircleRadius;
    }

    /** The line width of the circle around a target in report pictures. */
    public float getTargetCircleStrokeWidth() {
        return targetCircleStrokeWidth;
    }

    /** Multiplies a streak's elongation to give the length of the line drawn along it. */
    public double getStreakLineScaleFactor() {
        return streakLineScaleFactor;
    }

    /** The radius of the small box drawn at a streak's centroid, in frame pixels. */
    public int getStreakCentroidBoxRadius() {
        return streakCentroidBoxRadius;
    }

    /** The smallest radius of the box drawn around a point source, in frame pixels. */
    public int getPointSourceMinBoxRadius() {
        return pointSourceMinBoxRadius;
    }

    /** The margin added to a point source's rough radius, so its box lies on the sky background, in frame pixels. */
    public int getDynamicBoxPadding() {
        return dynamicBoxPadding;
    }

    /**
     * The most detections the cutout animation of unclassified transients shows, so very busy sessions keep a
     * moderate report size; 0 or less makes no animation.
     */
    public int getMaxAnimatedMarkers() {
        return maxAnimatedMarkers;
    }

    /** How the report presents variable-star candidates. */
    public VariableStarReportOptions getVariableStarReportOptions() {
        return variableStarReportOptions;
    }

    /** A copy with the black point {@code sigma} standard deviations below the image mean. */
    public VisualizationSettings withAutoStretchBlackSigma(double sigma) {
        return with(values -> values.autoStretchBlackSigma = sigma);
    }

    /** A copy with the white point {@code sigma} standard deviations above the image mean. */
    public VisualizationSettings withAutoStretchWhiteSigma(double sigma) {
        return with(values -> values.autoStretchWhiteSigma = sigma);
    }

    /** A copy with this frame delay of exported animated GIFs, in milliseconds. */
    public VisualizationSettings withGifBlinkSpeedMs(int milliseconds) {
        return with(values -> values.gifBlinkSpeedMs = milliseconds);
    }

    /** A copy with this border around cropped track pictures, in pixels. */
    public VisualizationSettings withTrackCropPadding(int pixels) {
        return with(values -> values.trackCropPadding = pixels);
    }

    /** A copy with this side of the object-centred crops of a track, in pixels. */
    public VisualizationSettings withTrackObjectCentricCropSize(int pixels) {
        return with(values -> values.trackObjectCentricCropSize = pixels);
    }

    /** A copy with this radius of the circle around a target in report pictures, in pixels. */
    public VisualizationSettings withTargetCircleRadius(int pixels) {
        return with(values -> values.targetCircleRadius = pixels);
    }

    /** A copy with this line width of the circle around a target in report pictures, in pixels. */
    public VisualizationSettings withTargetCircleStrokeWidth(float width) {
        return with(values -> values.targetCircleStrokeWidth = width);
    }

    /** A copy whose streak lines are {@code factor} times the streak's elongation long. */
    public VisualizationSettings withStreakLineScaleFactor(double factor) {
        return with(values -> values.streakLineScaleFactor = factor);
    }

    /** A copy with this radius of the box at a streak's centroid, in frame pixels. */
    public VisualizationSettings withStreakCentroidBoxRadius(int pixels) {
        return with(values -> values.streakCentroidBoxRadius = pixels);
    }

    /** A copy with this smallest radius of the box around a point source, in frame pixels. */
    public VisualizationSettings withPointSourceMinBoxRadius(int pixels) {
        return with(values -> values.pointSourceMinBoxRadius = pixels);
    }

    /** A copy with this margin added to a point source's rough radius for its box, in frame pixels. */
    public VisualizationSettings withDynamicBoxPadding(int pixels) {
        return with(values -> values.dynamicBoxPadding = pixels);
    }

    /** A copy that animates at most {@code markers} unclassified transients; 0 or less makes no animation. */
    public VisualizationSettings withMaxAnimatedMarkers(int markers) {
        return with(values -> values.maxAnimatedMarkers = markers);
    }

    /**
     * A copy with this variable-star presentation.
     *
     * @throws NullPointerException if {@code options} is null
     */
    public VisualizationSettings withVariableStarReportOptions(VariableStarReportOptions options) {
        Objects.requireNonNull(options, "options");
        return with(values -> values.variableStarReportOptions = options);
    }

    /** A new value with this one's values, after {@code change} has replaced some of them. */
    private VisualizationSettings with(Consumer<Values> change) {
        Values values = new Values(this);
        change.accept(values);
        return new VisualizationSettings(values);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VisualizationSettings that)) {
            return false;
        }
        return Double.compare(autoStretchBlackSigma, that.autoStretchBlackSigma) == 0
                && Double.compare(autoStretchWhiteSigma, that.autoStretchWhiteSigma) == 0
                && gifBlinkSpeedMs == that.gifBlinkSpeedMs
                && trackCropPadding == that.trackCropPadding
                && trackObjectCentricCropSize == that.trackObjectCentricCropSize
                && targetCircleRadius == that.targetCircleRadius
                && Float.compare(targetCircleStrokeWidth, that.targetCircleStrokeWidth) == 0
                && Double.compare(streakLineScaleFactor, that.streakLineScaleFactor) == 0
                && streakCentroidBoxRadius == that.streakCentroidBoxRadius
                && pointSourceMinBoxRadius == that.pointSourceMinBoxRadius
                && dynamicBoxPadding == that.dynamicBoxPadding
                && maxAnimatedMarkers == that.maxAnimatedMarkers
                && variableStarReportOptions.equals(that.variableStarReportOptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(autoStretchBlackSigma, autoStretchWhiteSigma, gifBlinkSpeedMs, trackCropPadding,
                trackObjectCentricCropSize, targetCircleRadius, targetCircleStrokeWidth, streakLineScaleFactor,
                streakCentroidBoxRadius, pointSourceMinBoxRadius, dynamicBoxPadding, maxAnimatedMarkers,
                variableStarReportOptions);
    }

    @Override
    public String toString() {
        return "VisualizationSettings[autoStretchBlackSigma=" + autoStretchBlackSigma
                + ", autoStretchWhiteSigma=" + autoStretchWhiteSigma
                + ", gifBlinkSpeedMs=" + gifBlinkSpeedMs
                + ", trackCropPadding=" + trackCropPadding
                + ", trackObjectCentricCropSize=" + trackObjectCentricCropSize
                + ", targetCircleRadius=" + targetCircleRadius
                + ", targetCircleStrokeWidth=" + targetCircleStrokeWidth
                + ", streakLineScaleFactor=" + streakLineScaleFactor
                + ", streakCentroidBoxRadius=" + streakCentroidBoxRadius
                + ", pointSourceMinBoxRadius=" + pointSourceMinBoxRadius
                + ", dynamicBoxPadding=" + dynamicBoxPadding
                + ", maxAnimatedMarkers=" + maxAnimatedMarkers
                + ", variableStarReportOptions=" + variableStarReportOptions + "]";
    }

    /** The values while a copy is made; it starts with the built-in values. */
    private static final class Values {
        double autoStretchBlackSigma = 0.5;
        double autoStretchWhiteSigma = 5.0;
        int gifBlinkSpeedMs = 300;
        int trackCropPadding = 200;
        int trackObjectCentricCropSize = 200;
        int targetCircleRadius = 15;
        float targetCircleStrokeWidth = 2.0f;
        double streakLineScaleFactor = 5.0;
        int streakCentroidBoxRadius = 3;
        int pointSourceMinBoxRadius = 10;
        int dynamicBoxPadding = 5;
        int maxAnimatedMarkers = 500;
        VariableStarReportOptions variableStarReportOptions = VariableStarReportOptions.DEFAULT;

        /** The built-in values. */
        Values() {
        }

        /** The values of {@code settings}. */
        Values(VisualizationSettings settings) {
            autoStretchBlackSigma = settings.autoStretchBlackSigma;
            autoStretchWhiteSigma = settings.autoStretchWhiteSigma;
            gifBlinkSpeedMs = settings.gifBlinkSpeedMs;
            trackCropPadding = settings.trackCropPadding;
            trackObjectCentricCropSize = settings.trackObjectCentricCropSize;
            targetCircleRadius = settings.targetCircleRadius;
            targetCircleStrokeWidth = settings.targetCircleStrokeWidth;
            streakLineScaleFactor = settings.streakLineScaleFactor;
            streakCentroidBoxRadius = settings.streakCentroidBoxRadius;
            pointSourceMinBoxRadius = settings.pointSourceMinBoxRadius;
            dynamicBoxPadding = settings.dynamicBoxPadding;
            maxAnimatedMarkers = settings.maxAnimatedMarkers;
            variableStarReportOptions = settings.variableStarReportOptions;
        }
    }
}
