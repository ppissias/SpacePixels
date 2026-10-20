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

/** The kinds of long operation that run under the {@link JobCoordinator}. */
public enum JobKind {
    DETECTION("Detect Moving Targets"),
    ITERATIVE_DETECTION("Detect Iteratively"),
    TRANSIENT_INSPECTION("Manual Transient Inspection"),
    STAR_MASK_STACK("Test Star Mask"),
    CONVERT_TO_MONO("Convert to Mono"),
    BATCH_STRETCH("Batch Stretch"),
    PLATE_SOLVE("Plate Solve"),
    AUTO_TUNE("Auto-Tune"),
    BLINK("Blink"),
    IMPORT("Import"),
    WRITE_WCS("Write WCS header");

    private final String title;

    JobKind(String title) {
        this.title = title;
    }

    /** The name of the job as the window shows it. */
    public String title() {
        return title;
    }
}
