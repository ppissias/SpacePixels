/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.jobs;

/**
 * The kinds of long operation that run under the {@link JobCoordinator}. A cancellable job stops at its next progress
 * report once cancelled. Plate solving waits on an external solver, writing the WCS header replaces a file that must
 * be written completely, and Blink has its own Stop, so those three cannot be cancelled.
 */
public enum JobKind {
    DETECTION("Detect Moving Targets", true),
    ITERATIVE_DETECTION("Detect Iteratively", true),
    TRANSIENT_INSPECTION("Manual Transient Inspection", true),
    STAR_MASK_STACK("Test Star Mask", true),
    CONVERT_TO_MONO("Convert to Mono", true),
    BATCH_STRETCH("Batch Stretch", true),
    PLATE_SOLVE("Plate Solve", false),
    AUTO_TUNE("Auto-Tune", true),
    BLINK("Blink", false),
    IMPORT("Import", true),
    WRITE_WCS("Write WCS header", false);

    private final String title;
    private final boolean cancellable;

    JobKind(String title, boolean cancellable) {
        this.title = title;
        this.cancellable = cancellable;
    }

    /** The name of the job as the window shows it. */
    public String title() {
        return title;
    }

    /** Whether the user can cancel a job of this kind while it runs. */
    public boolean cancellable() {
        return cancellable;
    }
}
