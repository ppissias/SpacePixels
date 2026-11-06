/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.report.FindingCategory;

/**
 * The overlay layers of the frame viewers, as listed in their side panel. Two layers are groups: Other findings holds
 * the report's remaining categories, and Catalog annotations the known objects; a child is drawn only while its
 * group is on too. Raw detections, the groups and the known stars have no label switch of their own where noted.
 */
enum ViewerLayer {
    RAW_DETECTIONS("Raw detections", null, true, false, false),
    VARIABLE_STARS("Variable stars", null, true, true, true),
    FAINT_MOVERS("Faint movers", null, true, true, true),
    TRACKS("Tracks", null, true, true, true),
    OTHER_FINDINGS("Other findings", null, true, false, false),
    SINGLE_FRAME("Single streaks & anomalies", OTHER_FINDINGS, true, false, true),
    SLOW_MOVERS("Slow-mover candidates", OTHER_FINDINGS, true, false, true),
    LOCAL_RESIDUALS("Local rescue & activity", OTHER_FINDINGS, true, false, true),
    GROUPS("Camera-fixed & common motion", OTHER_FINDINGS, true, false, true),
    CATALOGUE("Catalog annotations", null, false, false, false),
    KNOWN_STARS("Known stars", CATALOGUE, true, false, true),
    KNOWN_VARIABLES("Known variables", CATALOGUE, true, true, true),
    DEEP_SKY("Deep-sky objects", CATALOGUE, true, true, true),
    SOLAR_SYSTEM("Known asteroids & comets", CATALOGUE, true, true, true);

    final String title;
    /** The group this layer belongs to, or null. */
    final ViewerLayer parent;
    final boolean visibleByDefault;
    final boolean labelsByDefault;
    /** Whether the layer has its own label switch (Aa). */
    final boolean hasLabels;

    ViewerLayer(String title, ViewerLayer parent, boolean visibleByDefault, boolean labelsByDefault, boolean hasLabels) {
        this.title = title;
        this.parent = parent;
        this.visibleByDefault = visibleByDefault;
        this.labelsByDefault = labelsByDefault;
        this.hasLabels = hasLabels;
    }

    /** The Other findings layer of a report category, or null for the categories with layers of their own. */
    static ViewerLayer of(FindingCategory category) {
        return switch (category) {
            case SINGLE_STREAK, ANOMALY -> SINGLE_FRAME;
            case SLOW_MOVER -> SLOW_MOVERS;
            case LOCAL_RESCUE, LOCAL_ACTIVITY -> LOCAL_RESIDUALS;
            case CAMERA_FIXED, COMMON_MOTION -> GROUPS;
            case MOVING_TARGET, STREAK_TRACK -> TRACKS;
            case VARIABLE_STAR -> VARIABLE_STARS;
            case FAINT_MOVER -> FAINT_MOVERS;
        };
    }

    /** The catalogue layer of a sky mark; named stars are known stars. */
    static ViewerLayer of(SkyMark.Kind kind) {
        return switch (kind) {
            case STAR, NAMED_STAR -> KNOWN_STARS;
            case VARIABLE -> KNOWN_VARIABLES;
            case DEEP_SKY -> DEEP_SKY;
            case SOLAR_SYSTEM -> SOLAR_SYSTEM;
        };
    }
}
