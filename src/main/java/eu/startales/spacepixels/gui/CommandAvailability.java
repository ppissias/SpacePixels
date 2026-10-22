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

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;

/**
 * Which workflow commands of the Main tab can run now, and why the others cannot. The rules read facts about the
 * session, the selection and the work in progress, never the state of other controls, so every entry point of a
 * command (its button, shortcut, table menu item, Overview button or a dropped folder) agrees. Nothing here touches
 * Swing.
 */
final class CommandAvailability {

    /** The workflow commands of the Main tab. */
    enum Command {
        IMPORT, CONVERT_TO_MONO, BATCH_STRETCH, PLATE_SOLVE, SKY_CATALOGUE, BLINK, PREVIEW, INSPECTION,
        AUTO_TUNE, DETECT, DETECT_ITERATIVELY
    }

    /**
     * What the rules read.
     *
     * @param frames         the frames of the session, 0 without one
     * @param monochrome     whether every frame is monochrome
     * @param selectedFrames how many frames are selected in the table
     * @param selectedSolved whether the selected frame (the first of a selection) has a plate solution
     * @param solvedFrames   how many frames have a plate solution in their header
     * @param stretchEnabled whether "Enable batch stretch" is ticked on the Image Stretch tab
     * @param busy           whether a job holds the window, or a blink plays
     * @param blinking       whether a blink is loading or playing (its command then stops it)
     * @param skyFetching    whether the sky catalogue is being fetched (its command then cancels it)
     */
    record Facts(int frames, boolean monochrome, int selectedFrames, boolean selectedSolved, int solvedFrames,
                 boolean stretchEnabled, boolean busy, boolean blinking, boolean skyFetching) {
    }

    /** Whether a command can run; when it cannot, why, worded to follow "Not available: ". */
    record Availability(boolean enabled, String reason) {
        static final Availability AVAILABLE = new Availability(true, null);

        static Availability unavailable(String reason) {
            return new Availability(false, reason);
        }
    }

    private CommandAvailability() {
    }

    static Availability of(Command command, Facts facts) {
        if (command == Command.BLINK && facts.blinking()) {
            return Availability.AVAILABLE;
        }
        if (facts.busy()) {
            return Availability.unavailable("wait until the current task has finished.");
        }
        if (command == Command.IMPORT || command == Command.DETECT_ITERATIVELY
                || (command == Command.SKY_CATALOGUE && facts.skyFetching())) {
            return Availability.AVAILABLE;
        }
        if (facts.frames() == 0) {
            return Availability.unavailable("import frames first (Import Aligned Frames…, or drop a folder on the window).");
        }
        switch (command) {
            case CONVERT_TO_MONO:
                return facts.monochrome() ? Availability.unavailable("all frames are already monochrome.")
                        : Availability.AVAILABLE;
            case BATCH_STRETCH:
                return facts.stretchEnabled() ? Availability.AVAILABLE
                        : Availability.unavailable("tick \"Enable batch stretch\" in the Image Stretch tab first.");
            case SKY_CATALOGUE:
                return facts.solvedFrames() > 0 ? Availability.AVAILABLE
                        : Availability.unavailable("plate solve at least one frame first, and write the solution to its FITS header.");
            case BLINK:
                return facts.selectedFrames() >= 3 ? Availability.AVAILABLE
                        : Availability.unavailable("select at least 3 frames in the table.");
            case PLATE_SOLVE:
                if (facts.selectedFrames() == 0) {
                    return Availability.unavailable("select one frame in the table.");
                }
                return facts.selectedSolved() ? Availability.unavailable("the selected frame is already solved.")
                        : Availability.AVAILABLE;
            default:
                break;
        }
        if (!facts.monochrome()) {
            return Availability.unavailable("convert the frames to monochrome first (1 Prepare).");
        }
        if (command == Command.AUTO_TUNE && facts.frames() < SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES) {
            return Availability.unavailable("Auto-Tune needs at least " + SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES + " frames.");
        }
        return Availability.AVAILABLE;
    }
}
