package eu.startales.spacepixels.detection;

import eu.startales.spacepixels.fits.FrameCalibrationInspector;

/**
 * The caller's decision whether to proceed with detection before heavy frame loading starts.
 */
@FunctionalInterface
public interface PreFlightSafetyPrompt {
    boolean shouldProceed(FrameCalibrationInspector.InspectionResult inspection);
}
