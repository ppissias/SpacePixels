/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util;

/**
 * Enumerates the preview stretch presets exposed by the UI together with their parameter labels,
 * valid ranges, and default values.
 */
public enum StretchAlgorithm {
    ASINH("Asinh", "Black Point (%)", 0, 50, 10, "Stretch Strength", 1, 50, 10),
    ENHANCE_LOW("Enhance Low", "Intensity Threshold", 0, 100, 50, "Application Iterations", 1, 20, 1),
    ENHANCE_HIGH("Enhance High", "Intensity Threshold", 0, 100, 50, "Application Iterations", 1, 20, 1),
    EXTREME("Extreme", "Noise Threshold", 0, 100, 50, "Intensity Factor", 1, 20, 15);

    private final String displayName;
    private final String primaryParameterLabel;
    private final int primaryMinimum;
    private final int primaryMaximum;
    private final int primaryDefault;
    private final String secondaryParameterLabel;
    private final int secondaryMinimum;
    private final int secondaryMaximum;
    private final int secondaryDefault;

    StretchAlgorithm(String displayName,
                     String primaryParameterLabel,
                     int primaryMinimum,
                     int primaryMaximum,
                     int primaryDefault,
                     String secondaryParameterLabel,
                     int secondaryMinimum,
                     int secondaryMaximum,
                     int secondaryDefault) {
        this.displayName = displayName;
        this.primaryParameterLabel = primaryParameterLabel;
        this.primaryMinimum = primaryMinimum;
        this.primaryMaximum = primaryMaximum;
        this.primaryDefault = primaryDefault;
        this.secondaryParameterLabel = secondaryParameterLabel;
        this.secondaryMinimum = secondaryMinimum;
        this.secondaryMaximum = secondaryMaximum;
        this.secondaryDefault = secondaryDefault;
    }

    public String getPrimaryParameterLabel() {
        return primaryParameterLabel;
    }

    public int getPrimaryMinimum() {
        return primaryMinimum;
    }

    public int getPrimaryMaximum() {
        return primaryMaximum;
    }

    public int getPrimaryDefault() {
        return primaryDefault;
    }

    public String getSecondaryParameterLabel() {
        return secondaryParameterLabel;
    }

    public int getSecondaryMinimum() {
        return secondaryMinimum;
    }

    public int getSecondaryMaximum() {
        return secondaryMaximum;
    }

    public int getSecondaryDefault() {
        return secondaryDefault;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
