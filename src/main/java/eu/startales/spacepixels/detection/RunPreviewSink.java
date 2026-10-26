/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.detection;

/**
 * Where a run's pictures go, called on the run's own thread. It decides before any picture is made whether one would
 * be shown, so a hidden view or a picture too soon after the last costs nothing.
 */
public interface RunPreviewSink {

    /**
     * Whether a picture would be shown now. At most one picture is accepted every few seconds, including when the
     * phase changes. The flag remains so existing callers compile; it does not bypass the clock.
     */
    boolean wantsPreview(boolean stageChange);

    /** Drops a picture that was handed over and not yet shown, for example when the next phase starts. */
    default void dropPending() {
    }

    /** Hands over a picture; it must return at once and never fail the run. */
    void preview(RunPreview preview);
}
