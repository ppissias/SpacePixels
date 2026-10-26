package eu.startales.spacepixels.detection;

import eu.startales.spacepixels.fits.FrameCalibrationInspector;

/**
 * The caller's decision whether to proceed with detection before heavy frame loading starts.
 */
@FunctionalInterface
public interface PreFlightSafetyPrompt {
    /** Called on the run's worker before detection or tuning; false declines the run without producing reports. */
    boolean shouldProceed(FrameCalibrationInspector.InspectionResult inspection);
}
