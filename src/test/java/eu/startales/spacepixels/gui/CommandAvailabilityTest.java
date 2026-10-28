package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.gui.CommandAvailability.Command;
import eu.startales.spacepixels.gui.CommandAvailability.Facts;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The rules the workflow commands are enabled by: the same as the buttons had, now in one place. */
public class CommandAvailabilityTest {

    private static final int ENOUGH = SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES;

    @Test
    public void importAndDirectoryBasedIterationAreAvailableBeforeASession() {
        Facts none = session().frames(0).build();
        for (Command command : Command.values()) {
            if (command == Command.PREPARE_FRAMES || command == Command.IMPORT || command == Command.DETECT_ITERATIVELY) {
                assertTrue(enabled(command, none));
            } else {
                assertEquals(command.name(), "import frames first (Import Aligned Frames…, or drop a folder on the window).",
                        CommandAvailability.of(command, none).reason());
            }
        }
    }

    @Test
    public void aMonochromeSessionCanBeDetectedPreviewedInspectedAndTuned() {
        Facts mono = session().build();
        for (Command command : new Command[]{Command.DETECT, Command.DETECT_ITERATIVELY, Command.PREVIEW,
                Command.INSPECTION, Command.AUTO_TUNE, Command.IMPORT}) {
            assertTrue(command.name(), enabled(command, mono));
        }
        assertEquals("all frames are already monochrome.", CommandAvailability.of(Command.CONVERT_TO_MONO, mono).reason());
        assertEquals("Auto-Tune needs at least " + ENOUGH + " frames.",
                CommandAvailability.of(Command.AUTO_TUNE, session().frames(ENOUGH - 1).build()).reason());
    }

    @Test
    public void colourFramesAreConvertedFirstButCanBeBlinkedAndSolved() {
        Facts colour = session().colour().selected(3).build();
        for (Command command : new Command[]{Command.DETECT, Command.PREVIEW,
                Command.INSPECTION, Command.AUTO_TUNE}) {
            assertEquals(command.name(), "convert the frames to monochrome first (1 Prepare).",
                    CommandAvailability.of(command, colour).reason());
        }
        assertTrue(enabled(Command.CONVERT_TO_MONO, colour));
        assertTrue(enabled(Command.DETECT_ITERATIVELY, colour));
        assertTrue(enabled(Command.BLINK, colour));
        assertTrue(enabled(Command.PLATE_SOLVE, colour));
    }

    @Test
    public void batchStretchFollowsItsSwitchOnTheImageStretchTab() {
        assertEquals("tick \"Enable batch stretch\" in the Image Stretch tab first.",
                CommandAvailability.of(Command.BATCH_STRETCH, session().build()).reason());
        assertTrue(enabled(Command.BATCH_STRETCH, session().stretch().build()));
        assertTrue(enabled(Command.BATCH_STRETCH, session().colour().stretch().build()));
    }

    @Test
    public void theSkyCatalogueNeedsASolvedFrameAndCanBeCancelledWhileItIsFetched() {
        assertEquals("plate solve at least one frame first, and write the solution to its FITS header.",
                CommandAvailability.of(Command.SKY_CATALOGUE, session().build()).reason());
        assertTrue(enabled(Command.SKY_CATALOGUE, session().solved(1).build()));
        assertTrue("cancel", enabled(Command.SKY_CATALOGUE, session().fetching().build()));
        // A job holds the window: no cancel either, as before.
        assertFalse(enabled(Command.SKY_CATALOGUE, session().solved(1).fetching().busy().build()));
    }

    @Test
    public void blinkNeedsThreeSelectedFramesAndStopsABlinkInProgress() {
        assertEquals("select at least 3 frames in the table.",
                CommandAvailability.of(Command.BLINK, session().selected(2).build()).reason());
        assertTrue(enabled(Command.BLINK, session().selected(3).build()));
        // While a blink loads or plays, the window is busy, and Blink (then Stop Blinking) stays available.
        Facts blinking = session().selected(3).busy().blinking().build();
        assertTrue(enabled(Command.BLINK, blinking));
        assertFalse(enabled(Command.DETECT, blinking));
        assertFalse(enabled(Command.IMPORT, blinking));
    }

    @Test
    public void plateSolveNeedsOneSelectedUnsolvedFrame() {
        assertEquals("select one frame in the table.",
                CommandAvailability.of(Command.PLATE_SOLVE, session().build()).reason());
        assertEquals("the selected frame is already solved.",
                CommandAvailability.of(Command.PLATE_SOLVE, session().selected(1).selectedSolved().build()).reason());
        assertTrue(enabled(Command.PLATE_SOLVE, session().selected(1).build()));
    }

    @Test
    public void nothingStartsWhileAJobHoldsTheWindow() {
        Facts busy = session().selected(3).solved(1).stretch().busy().build();
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

    /** A monochrome session of enough frames, nothing selected or solved; each call changes one fact. */
    private static FactsBuilder session() {
        return new FactsBuilder();
    }

    private static final class FactsBuilder {
        private int frames = ENOUGH;
        private boolean monochrome = true;
        private int selected;
        private boolean selectedSolved;
        private int solved;
        private boolean stretch;
        private boolean busy;
        private boolean blinking;
        private boolean fetching;

        FactsBuilder frames(int frames) {
            this.frames = frames;
            return this;
        }

        FactsBuilder colour() {
            monochrome = false;
            return this;
        }

        FactsBuilder selected(int selected) {
            this.selected = selected;
            return this;
        }

        FactsBuilder selectedSolved() {
            selectedSolved = true;
            return this;
        }

        FactsBuilder solved(int solved) {
            this.solved = solved;
            return this;
        }

        FactsBuilder stretch() {
            stretch = true;
            return this;
        }

        FactsBuilder busy() {
            busy = true;
            return this;
        }

        FactsBuilder blinking() {
            blinking = true;
            return this;
        }

        FactsBuilder fetching() {
            fetching = true;
            return this;
        }

        Facts build() {
            return new Facts(frames, monochrome, selected, selectedSolved, solved, stretch, busy, blinking, fetching);
        }
    }
}
