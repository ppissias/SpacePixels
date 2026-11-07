package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.JFileChooserFinder;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.FrameFixture;
import org.assertj.swing.fixture.JFileChooserFixture;
import org.assertj.swing.fixture.JSpinnerFixture;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import java.awt.Toolkit;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The first mechanics of the main window. See DEVELOPER.md, "Testing".
 * Run with {@code ./gradlew guiMechanicsTest}.
 */
public class WindowMechanicsTest extends MechanicsTestCase {

    @Test(timeout = 120_000)
    public void workflowButtonsArePresentAndIdleBeforeImport() {
        requireButton("Prepare Frames…", true);
        requireButton("Import Aligned Frames…", true);
        requireButton("Convert to Mono", false);
        requireButton("Blink Selected", false);
        requireButton("Plate Solve Selected", false);
        requireButton("Fetch Sky Catalogue", false);
        requireButton("Fetch Asteroids and Comets", false);
        requireButton("Preview Frame", false);
        requireButton("Manual Transient Inspection", false);
        requireButton("Auto-Tune Settings", false);
        requireButton("Detect Moving Targets", false);
        requireButton("Detect Iteratively (large datasets)", true);
        assertTrue(showingLabel("No frames imported").contains("No frames imported"));
        requireButton("Choose Folder…", true);
        requireButton("Prepare Raw Frames…", true);
        assertTrue(showingLabel("Drop a folder").contains("aligned frames"));
        assertTrue(showingLabel("calibrated and aligned").contains("XISF"));
        assertTrue(tabEnabled("Astrometry Config"));
        requireButton("Image Stretch…", false);
        assertEquals(-1, GuiActionRunner.execute(() -> application().getTabbedPane().indexOfTab("Image Stretch")).intValue());
        assertFalse(tabEnabled("Detection Settings"));
    }

    @Test(timeout = 120_000)
    public void importShowsTheSyntheticSequenceAndEnablesTheOtherTabs() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(
                folder.newFolder("data").toPath(), "session", SyntheticDatasetFactory.DEFAULT_FRAME_COUNT);

        window().button(VisibleText.button("Import Aligned Frames…")).click();
        JFileChooserFixture chooser = JFileChooserFinder.findFileChooser().withTimeout(15_000).using(robot());
        chooser.selectFile(sequence.toFile());
        chooser.approve();

        String frames = SyntheticDatasetFactory.DEFAULT_FRAME_COUNT + " frames";
        waitFor("the prepare line names the imported frames",
                () -> showingLabel("Monochrome").contains(frames));
        waitFor("the status line reports the imported files",
                () -> showingLabel("Imported 5 file").contains("Imported 5 file"));
        assertTrue(tabEnabled("Astrometry Config"));
        requireButton("Image Stretch…", true);
        assertTrue(tabEnabled("Detection Settings"));
        requireButton("Detect Moving Targets", true);
        requireButton("Convert to Mono", false);
        assertFalse("the drop zone gives way to the table", GuiActionRunner.execute(() ->
                window().robot().finder().findAll(window().target(), VisibleText.button("Choose Folder…")).size() > 0));
        assertTrue(GuiActionRunner.execute(() -> window().table().target().isShowing()));
        assertEquals("1", GuiActionRunner.execute(() -> window().table().target().getValueAt(0, 0)));
    }

    /** The captions name what a command will act on, and the panels stay put while they do. */
    @Test(timeout = 120_000)
    public void captionsAndToolbarFollowTheSelection() throws Exception {
        importSyntheticSequence();
        JButton preview = window().button(VisibleText.button("Preview Frame")).target();
        JButton blink = window().button(VisibleText.button("Blink Selected")).target();
        JButton solve = window().button(VisibleText.button("Plate Solve Selected")).target();
        assertEquals("Preview Frame", GuiActionRunner.execute(preview::getText));
        assertEquals("Blink Selected", GuiActionRunner.execute(blink::getText));
        assertEquals("Plate Solve Selected", GuiActionRunner.execute(solve::getText));
        java.awt.Rectangle previewAt = GuiActionRunner.execute(() -> preview.getBounds());

        window().table().selectRows(1, 2, 3);
        waitFor("the captions follow the selection", () -> "Blink Selected (3)".equals(GuiActionRunner.execute(blink::getText)));
        assertEquals("Preview Frame 2", GuiActionRunner.execute(preview::getText));
        assertEquals("Plate Solve Frame 2", GuiActionRunner.execute(solve::getText));
        assertEquals("the buttons do not move with their captions", previewAt, GuiActionRunner.execute(() -> preview.getBounds()));
        window().label(VisibleText.labelExactly("3 selected")).requireVisible();
        assertTrue(showingLabel("Blink: the 3 selected").contains("Preview: sequence from frame 2"));

        window().button(VisibleText.button("Clear selection")).click();
        waitFor("the selection is cleared", () -> GuiActionRunner.execute(() -> window().table().target().getSelectedRowCount()) == 0);
        waitFor("the captions return", () -> "Preview Frame".equals(GuiActionRunner.execute(preview::getText)));
        assertTrue(showingLabel("Blink: select 3").contains("Preview: sequence from the first frame"));
    }

    /** The highlight is on the next step: frames first, and Detect once they are in. */
    @Test(timeout = 120_000)
    public void theHighlightMovesWithTheSession() throws Exception {
        JButton detect = window().button(VisibleText.button("Detect Moving Targets")).target();
        assertFalse("nothing to detect yet", isHighlighted(window().button(VisibleText.button("Detect Moving Targets")).target()));
        assertTrue("an empty window highlights its way in", isHighlighted(window().button(VisibleText.button("Choose Folder…")).target()));
        assertFalse(isHighlighted(window().button(VisibleText.button("Import Aligned Frames…")).target()));
        java.awt.Dimension before = GuiActionRunner.execute(detect::getPreferredSize);
        importSyntheticSequence();
        waitFor("Detect is the next step", () -> isHighlighted(detect));
        assertEquals("the button keeps its size", before.height, GuiActionRunner.execute(detect::getPreferredSize).height);
    }

    @Test(timeout = 180_000)
    public void savedDetectionSigmaIsThereWhenTheWindowOpensAgain() throws Exception {
        importSyntheticSequence();
        window().tabbedPane().selectTab("Detection Settings");

        JSpinner spinner = VisibleText.spinnerByLabel(window().target(), "Detection Sigma");
        double original = number(spinner);
        new JSpinnerFixture(robot(), spinner).increment(5);
        double changed = number(spinner);
        assertEquals(original + 0.5, changed, 0.02);
        waitFor("the footer says the settings are unsaved",
                () -> showingLabel("Unsaved changes").contains("Unsaved changes"));

        window().button(VisibleText.button("Save")).click();
        waitFor("the footer says the settings were saved",
                () -> showingLabel("Saved to").contains("Saved to")
                        && showingLabel("Saved settings").contains("Saved settings"));

        reopenWindow();
        importSyntheticSequence();
        window().tabbedPane().selectTab("Detection Settings");
        JSpinner reloaded = VisibleText.spinnerByLabel(window().target(), "Detection Sigma");
        assertEquals(changed, number(reloaded), 0.001);
        assertTrue(showingLabel("Saved settings").contains("Saved settings"));
    }

    @Test(timeout = 180_000)
    public void blinkCanBeStopped() throws Exception {
        importSyntheticSequence();
        window().table().selectRows(0, 1, 2);
        waitFor("Blink Selected becomes available for three frames",
                () -> window().button(VisibleText.button("Blink Selected")).isEnabled());

        window().button(VisibleText.button("Blink Selected")).click();
        stopBlink();
        waitFor("the status line says the blink stopped",
                () -> showingLabel("Blinking stopped").contains("Blinking stopped"));
        requireButton("Blink Selected", true);
    }

    /**
     * Stops the blink from the control that is on screen. A fast load is already playing, and its window covers
     * Stop Blinking, so that window is moved down first.
     */
    private void stopBlink() {
        FrameFixture blink = WindowFinder.findFrame(new org.assertj.swing.core.GenericTypeMatcher<>(JFrame.class) {
            @Override
            protected boolean isMatching(JFrame frame) {
                return frame.getTitle() != null && frame.getTitle().startsWith("Blink");
            }
        }).withTimeout(15_000).using(robot());
        try {
            blink.button(VisibleText.button("Cancel")).click();
            return;
        } catch (RuntimeException alreadyPlaying) {
            GuiActionRunner.execute(() -> blink.target().setLocation(0,
                    Toolkit.getDefaultToolkit().getScreenSize().height - 40));
            window().button(VisibleText.button("Stop Blinking")).click();
        }
    }

    private void requireButton(String caption, boolean enabled) {
        JButton button = window().button(VisibleText.button(caption)).target();
        assertEquals(caption, enabled, button.isEnabled());
    }

    private boolean tabEnabled(String title) {
        JTabbedPane tabs = window().tabbedPane().target();
        int index = tabs.indexOfTab(title);
        assertTrue("No tab named " + title, index >= 0);
        return tabs.isEnabledAt(index);
    }

    private static double number(JSpinner spinner) {
        Object value = GuiActionRunner.execute(spinner::getValue);
        return ((Number) value).doubleValue();
    }
}
