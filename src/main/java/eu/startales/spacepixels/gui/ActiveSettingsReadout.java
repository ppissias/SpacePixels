package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.jobs.JobOutcome;
import io.github.ppissias.jtransient.config.DetectionConfig;

/**
 * The main panel's settings readout. Calls are on the EDT. {@link #showActiveDetectionSettings} reads {@code config}
 * during the call and does not retain it.
 */
interface ActiveSettingsReadout {
    /** Copies the eight Overview values from the session configuration just applied. */
    void showActiveDetectionSettings(DetectionConfig config);

    /** Shows which analyses the current settings will run. */
    void showEnabledAnalyses(String enabled);

    /** The main status line. */
    void showStatus(String text);

    /** Refreshes command availability and the astrometry status. */
    void refreshWorkflowStatus();

    /** Shows why a detection-shaped job failed. */
    void showDetectionProblem(JobOutcome<?> outcome);
}
