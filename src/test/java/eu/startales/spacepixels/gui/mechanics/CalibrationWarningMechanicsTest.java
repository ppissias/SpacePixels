package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.fixture.DialogFixture;
import org.junit.Test;
import javax.swing.JDialog;

import java.awt.event.KeyEvent;

import static org.junit.Assert.*;

/** Drives real warning dialogs and their recommended keyboard default against an owned scratch session. */
public class CalibrationWarningMechanicsTest extends MechanicsTestCase {
    /** Cancel preserves the open dataset, override imports, and Enter declines detection before it creates output. */
    @Test(timeout = 180_000) public void importOverrideAndDetectionCancelRespectTheCurrentSession() throws Exception {
        importSyntheticSequence();
        var original = GuiActionRunner.execute(() -> application().getSession());
        var suspect = SyntheticDatasetFactory.createUncalibratedMono16FitsSequence(
                folder.newFolder("suspect").toPath(), "spikes", 5, 50);
        GuiActionRunner.execute(() -> application().importFolder(suspect.toFile()));
        var declined = warning("Dataset Quality Warning");
        declined.button(VisibleText.button("Cancel Import (Recommended)")).requireVisible();
        robot().pressAndReleaseKeys(KeyEvent.VK_ENTER);
        waitFor("the import is declined", () -> GuiActionRunner.execute(() -> application().getJobCoordinator().current() == null));
        assertSame(original, GuiActionRunner.execute(() -> application().getSession()));

        GuiActionRunner.execute(() -> application().importFolder(suspect.toFile()));
        var accepted = warning("Dataset Quality Warning");
        accepted.button(VisibleText.button("Proceed Anyway")).click();
        waitFor("override imports the suspect dataset", () -> GuiActionRunner.execute(() ->
                application().getJobCoordinator().current() == null && application().getSession() != original));
        var imported = GuiActionRunner.execute(() -> application().getSession());
        window().button(VisibleText.button("Detect Moving Targets")).click();
        var preflight = warning("Pre-Detection Quality Warning");
        assertSame("The quality question belongs to the initiating window", application().getFrame(),
                GuiActionRunner.execute(() -> preflight.target().getOwner()));
        preflight.button(VisibleText.button("Cancel Detection (Recommended)")).requireVisible();
        robot().pressAndReleaseKeys(KeyEvent.VK_ENTER);
        waitFor("declining preflight unlocks the window", () -> GuiActionRunner.execute(() ->
                application().getJobCoordinator().current() == null));
        assertSame(imported, GuiActionRunner.execute(() -> application().getSession()));
        assertEquals(0, suspect.toFile().listFiles(file -> file.isDirectory()
                && file.getName().startsWith("detections_")).length);
    }

    /** Finds the showing warning by its actual window title, independently of component names. */
    private DialogFixture warning(String title) {
        return WindowFinder.findDialog(new GenericTypeMatcher<>(JDialog.class) {
            /** Matches only this workflow's visible question. */
            @Override protected boolean isMatching(JDialog dialog) {
                return dialog.isShowing() && title.equals(dialog.getTitle());
            }
        }).withTimeout(20_000).using(robot());
    }
}
