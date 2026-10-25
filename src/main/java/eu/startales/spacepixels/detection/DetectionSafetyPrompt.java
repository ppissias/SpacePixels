/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.detection;

/**
 * The caller's decision whether to write the report of a finished detection. A detection run asks it on its worker,
 * after the detection and before the export; the window answers with a dialog on the EDT, headless callers with a
 * rule. It may leave optional groups out ({@link DetectionSummary#includeInReport}).
 */
@FunctionalInterface
public interface DetectionSafetyPrompt {
    /**
     * Whether the report may be written for these counts; false declines it. The run has already handed over its
     * overlays.
     */
    boolean shouldProceed(DetectionSummary summary);
}
