/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util;

/**
 * What a completed detection leaves for the viewers: its tracks and its variable-star candidates, numbered as in its
 * report. It holds no image buffers. A run hands it over as soon as its detection finishes, whatever then happens to
 * the report.
 *
 * @param tracks    the run's moving objects and streak tracks, possibly none
 * @param variables the run's variable-star candidates, possibly none
 */
public record DetectionOverlays(TrackOverlay tracks, VariableStarOverlay variables) {

    /** The overlays of a finished detection, built as its report builds its maps and numbers. */
    public static DetectionOverlays of(PipelineExecutionData execution) {
        return new DetectionOverlays(TrackOverlay.from(execution.getPipelineResult()),
                execution.getReportContext().variableStarOverlay(execution.getPipelineResult()));
    }
}
