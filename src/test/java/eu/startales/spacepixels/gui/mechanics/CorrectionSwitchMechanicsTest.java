package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.WindowFinder;
import org.junit.Test;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import java.awt.event.WindowEvent;
import static org.junit.Assert.*;

/** Exercises the main correction switch, its saved state and synchronization with an open viewer. */
public class CorrectionSwitchMechanicsTest extends MechanicsTestCase {
    /** Main and viewer controls share one session value and a saved choice survives reopening the application. */
    @Test(timeout = 180_000) public void mainSwitchPersistsAndFollowsViewerChanges() throws Exception {
        var directory = SolvedSession.create(folder.newFolder("solved").toPath());
        importFolder(directory);
        var apply = window().checkBox(new GenericTypeMatcher<>(JCheckBox.class, true) {
            /** Finds the compact control by its full accessible meaning. */
            @Override protected boolean isMatching(JCheckBox candidate) {
                return "Apply plate-solution correction".equals(candidate.getAccessibleContext().getAccessibleName());
            }
        });
        assertFalse(apply.target().isSelected());
        apply.check();
        waitFor("main correction saved", () -> {
            var correction = PlateSolutionCorrection.load(directory.toFile());
            return correction != null && correction.enabled && Boolean.TRUE.equals(correction.userEnabled);
        });
        reopenWindow();
        importFolder(directory);
        assertTrue(GuiActionRunner.execute(() -> application().getSession().getSkyCatalogue().isCorrectionEnabled()));
        window().table().selectRows(0);
        window().button(VisibleText.button("Preview Frame")).click();
        var viewer = WindowFinder.findFrame(new GenericTypeMatcher<>(JFrame.class) {
            /** Finds the preview opened by the real workflow. */
            @Override protected boolean isMatching(JFrame candidate) {
                return candidate.isShowing() && candidate.getTitle().startsWith("Preview Frame");
            }
        }).withTimeout(20_000).using(robot());
        var correction = viewer.checkBox(VisibleText.checkBox("Align markers to corrected plate solution"));
        waitFor("viewer correction available", () -> correction.target().isEnabled());
        assertTrue(correction.target().isSelected());
        correction.uncheck();
        waitFor("viewer change reaches main status", () -> !GuiActionRunner.execute(() ->
                application().getSession().getSkyCatalogue().isCorrectionEnabled()));
        GuiActionRunner.execute(() -> viewer.target().dispatchEvent(new WindowEvent(viewer.target(), WindowEvent.WINDOW_CLOSING)));
        waitFor("viewer closed", () -> !viewer.target().isShowing());
        assertFalse(window().checkBox(VisibleText.checkBox("Apply")).target().isSelected());
        waitFor("viewer correction saved", () -> !PlateSolutionCorrection.load(directory.toFile()).enabled);
        GuiActionRunner.execute(() -> application().getSession().getSkyCatalogue().setCorrection(null));
        assertTrue(robot().finder().findAll(window().target(), new GenericTypeMatcher<>(JCheckBox.class, true) {
            /** No switch is offered after the useful correction disappears. */
            @Override protected boolean isMatching(JCheckBox candidate) { return "Apply".equals(candidate.getText()); }
        }).isEmpty());
    }
}
