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
 * The first mechanics of the main window. See DESIGN-IMPROVEMENTS.md, "GUI mechanics tests".
 * Run with {@code ./gradlew guiMechanicsTest}.
 */
public class WindowMechanicsTest extends MechanicsTestCase {

    @Test(timeout = 120_000)
    public void workflowButtonsArePresentAndIdleBeforeImport() {
        requireButton("Import Aligned Frames…", true);
        requireButton("Convert to Mono", false);
        requireButton("Batch Stretch", false);
        requireButton("Blink Selected", false);
        requireButton("Plate Solve Selected", false);
        requireButton("Fetch Sky Catalogue", false);
        requireButton("Preview Frame", false);
        requireButton("Manual Transient Inspection", false);
        requireButton("Auto-Tune Settings", false);
        requireButton("Detect Moving Targets", false);
        requireButton("Detect Iteratively (large datasets)", true);
        assertTrue(showingLabel("No frames imported").contains("No frames imported"));
        assertTrue(tabEnabled("Astrometry Config"));
        assertFalse(tabEnabled("Image Stretch"));
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
        assertTrue(tabEnabled("Image Stretch"));
        assertTrue(tabEnabled("Detection Settings"));
        requireButton("Detect Moving Targets", true);
        requireButton("Convert to Mono", false);
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
