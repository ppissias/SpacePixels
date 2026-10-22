package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.junit.Test;
import javax.swing.*;
import java.io.File;
import java.io.FileReader;
import static org.junit.Assert.*;

/** Checks the migrated settings page through real search/filter, editor, Save and Revert controls. */
public class TypedSettingsMechanicsTest extends MechanicsTestCase {
    /** Stable IDs reveal expert rows and typed apply/save/revert keeps the existing profile and control contracts. */
    @Test(timeout = 180_000) public void typedResidualFieldSearchSaveAndRevert() throws Exception {
        var directory = SyntheticDatasetFactory.createMono16FitsSequence(folder.newFolder("frames").toPath(),
                "session", SyntheticDatasetFactory.DEFAULT_FRAME_COUNT).toFile();
        GuiActionRunner.execute(() -> application().importFolder(directory));
        waitFor("session imported", () -> GuiActionRunner.execute(() -> application().getSession() != null));
        GuiActionRunner.execute(() -> application().getTabbedPane()
                .setSelectedComponent(application().getDetectionConfigurationPanel()));
        var search = window().textBox(new GenericTypeMatcher<>(JTextField.class, true) {
            /** Locates the user-facing settings search by its accessible name. */
            @Override protected boolean isMatching(JTextField candidate) {
                return "Search settings".equals(candidate.getAccessibleContext().getAccessibleName());
            }
        }).target();
        GuiActionRunner.execute(() -> search.setText("localActivityClusterRadiusPixels"));
        JSpinner radius = window().spinner(new GenericTypeMatcher<>(JSpinner.class, true) {
            /** Locates the expert radius revealed by stable field-ID search. */
            @Override protected boolean isMatching(JSpinner candidate) {
                return "Local Activity Cluster Radius (Pixels)".equals(candidate.getAccessibleContext().getAccessibleName());
            }
        }).target();
        assertTrue(radius.isShowing());
        GuiActionRunner.execute(() -> radius.setValue(11.5));
        GuiActionRunner.execute(() -> window().button(VisibleText.button("Save")).target().doClick());
        waitFor("typed field save completed", () -> showingLabel("Saved settings").contains("Saved settings"));
        File profileFile = new File(System.getProperty("user.home"), SpacePixelsDetectionProfileIO.DEFAULT_FILENAME);
        try (FileReader reader = new FileReader(profileFile)) {
            assertEquals(11.5, SpacePixelsDetectionProfileIO.load(reader).getDetectionConfig().localActivityClusterRadiusPixels, 0);
        }
        GuiActionRunner.execute(() -> radius.setValue(12.5));
        GuiActionRunner.execute(() -> window().button(VisibleText.button("Revert")).target().doClick());
        waitFor("typed binding reloads the saved value", () -> ((Number) GuiActionRunner.execute(radius::getValue)).doubleValue() == 11.5);
        assertTrue(radius.getAccessibleContext().getAccessibleDescription().contains("pixels"));
    }
}
