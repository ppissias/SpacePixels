package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.reporting.DetectionReportGenerator;
import org.assertj.swing.edt.GuiActionRunner;
import org.junit.Test;
import javax.swing.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Checks visible frame scope and completed-output actions without running detection or opening external programs. */
public class WorkspaceScopeMechanicsTest extends MechanicsTestCase {
    /** Selection changes describe the tuning fallback and completed-output controls belong to the imported session. */
    @Test(timeout = 180_000) public void scopeAndOutputFollowTheCurrentSession() throws Exception {
        var directory = SyntheticDatasetFactory.createMono16FitsSequence(folder.newFolder("frames").toPath(),
                "session", 5);
        var output = Files.createDirectory(directory.resolve("detections_saved"));
        var report = Files.writeString(output.resolve(DetectionReportGenerator.detectionReportName), "<html>Saved report</html>");
        GuiActionRunner.execute(() -> application().importFolder(directory.toFile()));
        waitFor("session imported", () -> GuiActionRunner.execute(() -> application().getSession() != null));
        GuiActionRunner.execute(() -> window().table().target().setRowSelectionInterval(0, 0));
        assertTrue(showingLabel("Auto-Tune: pool from all 5").contains("selection below 4"));
        GuiActionRunner.execute(() -> window().table().target().setRowSelectionInterval(0, 4));
        assertTrue(showingLabel("Auto-Tune: pool from 5 selected").contains("Run:</b> all 5 imported frames"));
        JButton open = window().button(VisibleText.button("Open output folder")).target();
        assertEquals(output.toFile().getAbsolutePath(), open.getToolTipText());
        assertTrue(open.isFocusable());
        assertNull(GuiActionRunner.execute(() -> application().getJobCoordinator().current()));
        Files.delete(report);
        GuiActionRunner.execute(() -> open.doClick());
        waitFor("missing report clears output action", () -> !GuiActionRunner.execute(open::isVisible));
        assertTrue(showingLabel("moved or deleted").contains("moved or deleted"));
        assertNull(GuiActionRunner.execute(() -> application().getJobCoordinator().current()));
    }
}
