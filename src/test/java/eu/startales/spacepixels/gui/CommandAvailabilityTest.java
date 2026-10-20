package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.gui.CommandAvailability.Command;
import eu.startales.spacepixels.gui.CommandAvailability.Facts;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The rules the shared commands are enabled by: the same as the buttons had, now in one place. */
public class CommandAvailabilityTest {

    private static final int ENOUGH = SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES;

    @Test
    public void nothingButImportBeforeASession() {
        Facts none = new Facts(0, true, 0, false, false, false);
        for (Command command : Command.values()) {
            assertEquals(command.name(), "import frames first (Import Aligned Frames…, or drop a folder on the window).",
                    CommandAvailability.of(command, none).reason());
        }
    }

    @Test
    public void aMonochromeSessionCanBeDetectedPreviewedAndTuned() {
        Facts mono = new Facts(ENOUGH, true, 0, false, false, false);
        assertTrue(enabled(Command.DETECT, mono));
        assertTrue(enabled(Command.PREVIEW, mono));
        assertTrue(enabled(Command.AUTO_TUNE, mono));
        assertEquals("Auto-Tune needs at least " + ENOUGH + " frames.",
                CommandAvailability.of(Command.AUTO_TUNE, new Facts(ENOUGH - 1, true, 0, false, false, false)).reason());
    }

    @Test
    public void colourFramesMustBeConvertedFirstButCanBeBlinkedAndSolved() {
        Facts colour = new Facts(ENOUGH, false, 3, false, false, false);
        for (Command command : new Command[]{Command.DETECT, Command.PREVIEW, Command.AUTO_TUNE}) {
            assertEquals(command.name(), "convert the frames to monochrome first (1 Prepare).",
                    CommandAvailability.of(command, colour).reason());
        }
        assertTrue(enabled(Command.BLINK, colour));
        assertTrue(enabled(Command.PLATE_SOLVE, colour));
    }

    @Test
    public void blinkNeedsThreeSelectedFramesAndStopsABlinkInProgress() {
        assertEquals("select at least 3 frames in the table.",
                CommandAvailability.of(Command.BLINK, new Facts(10, true, 2, false, false, false)).reason());
        assertTrue(enabled(Command.BLINK, new Facts(10, true, 3, false, false, false)));
        // While a blink loads or plays, the window is busy, and Blink (then Stop Blinking) stays available.
        Facts blinking = new Facts(10, true, 3, false, true, true);
        assertTrue(enabled(Command.BLINK, blinking));
        assertFalse(enabled(Command.DETECT, blinking));
    }

    @Test
    public void plateSolveNeedsOneSelectedUnsolvedFrame() {
        assertEquals("select one frame in the table.",
                CommandAvailability.of(Command.PLATE_SOLVE, new Facts(10, true, 0, false, false, false)).reason());
        assertEquals("the selected frame is already solved.",
                CommandAvailability.of(Command.PLATE_SOLVE, new Facts(10, true, 1, true, false, false)).reason());
        assertTrue(enabled(Command.PLATE_SOLVE, new Facts(10, true, 1, false, false, false)));
    }

    @Test
    public void nothingStartsWhileAJobHoldsTheWindow() {
        Facts busy = new Facts(ENOUGH, true, 3, false, true, false);
        for (Command command : Command.values()) {
            assertEquals(command.name(), "wait until the current task has finished.",
                    CommandAvailability.of(command, busy).reason());
        }
    }

    private static boolean enabled(Command command, Facts facts) {
        CommandAvailability.Availability availability = CommandAvailability.of(command, facts);
        assertEquals(availability.enabled(), availability.reason() == null);
        return availability.enabled();
    }
}
