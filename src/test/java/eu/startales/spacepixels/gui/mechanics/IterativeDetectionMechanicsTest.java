package eu.startales.spacepixels.gui.mechanics;

import com.google.gson.JsonParser;
import eu.startales.spacepixels.session.ProcessingSession;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import org.assertj.swing.edt.GuiActionRunner;
import org.junit.Test;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;

public class IterativeDetectionMechanicsTest extends MechanicsTestCase {
    @Test(timeout = 120_000)
    public void configurationBeforeImportPreviewsPassesAndExplainsManualSettings() throws Exception {
        File source = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "source", 40).toFile();
        JDialog dialog = openConfiguration();
        GuiActionRunner.execute(() -> {
            field(dialog, "Source directory").setText(source.getAbsolutePath());
            VisibleText.spinnerByLabel(dialog, "Increase by").setValue(10);
            VisibleText.spinnerByLabel(dialog, "Maximum frames").setValue(32);
        });
        waitFor("the planned pass sizes", () -> GuiActionRunner.execute(() -> text(dialog, "[5, 15, 25, 32]") != null));
        GuiActionRunner.execute(() -> {
            assertNotNull(text(dialog, "Uses current Detection Settings"));
            JLabel note = text(dialog, "For manual tuning");
            assertNotNull(note);
            assertTrue(note.getFont().isItalic());
            VisibleText.spinnerByLabel(dialog, "Maximum frames").setValue(4);
            assertFalse(button(dialog, "Run iterations").isEnabled());
            VisibleText.spinnerByLabel(dialog, "Maximum frames").setValue(32);
            assertTrue(button(dialog, "Run iterations").isEnabled());
            checkbox(dialog, "Auto-Tune once before the passes (calibrated)").doClick();
            assertNotNull(text(dialog, "Auto-Tune adjusts settings once"));
            assertTrue(VisibleText.spinnerByLabel(dialog, "Auto-Tune sample limit").isEnabled());
            button(dialog, "Cancel").doClick();
        });
        assertNull(GuiActionRunner.execute(() -> application().getSession()));
        assertNull(GuiActionRunner.execute(() -> application().getJobCoordinator().current()));
        assertEquals(40, source.listFiles().length);
    }

    @Test(timeout = 120_000)
    public void runningAnotherDirectoryPreservesTheOpenSessionAndUsesUnsavedSettings() throws Exception {
        File current = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "current", 5).toFile();
        GuiActionRunner.execute(() -> application().importFolder(current));
        waitFor("the current session", () -> GuiActionRunner.execute(() -> application().getSession() != null
                && application().getJobCoordinator().current() == null));
        ProcessingSession session = GuiActionRunner.execute(() -> application().getSession());
        eu.startales.spacepixels.skycatalog.SkyCatalogue catalogue = new eu.startales.spacepixels.skycatalog.SkyCatalogue();
        File report = new File(current, "previous-report.html");
        GuiActionRunner.execute(() -> {
            session.setLastReport(report);
            session.getSkyCatalogue().set(catalogue);
            window().table().target().setRowSelectionInterval(0, 0);
            application().getTabbedPane().setSelectedComponent(application().getDetectionConfigurationPanel());
            VisibleText.spinnerByLabel(application().getDetectionConfigurationPanel(), "Detection Sigma").setValue(3.3);
            application().getTabbedPane().setSelectedComponent(application().getMainApplicationPanel());
        });
        File source = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "other", 7).toFile();
        JDialog dialog = openConfiguration();
        GuiActionRunner.execute(() -> field(dialog, "Source directory").setText(source.getAbsolutePath()));
        waitFor("seven source frames", () -> GuiActionRunner.execute(() -> text(dialog, "7 available; passes: [5, 7]") != null));
        GuiActionRunner.execute(() -> button(dialog, "Run iterations").doClick());
        waitFor("the iterative completion dialog", () -> GuiActionRunner.execute(() -> showingDialog("Pipeline Complete") != null));
        GuiActionRunner.execute(() -> {
            JDialog completed = showingDialog("Pipeline Complete");
            JOptionPane option = find(completed, JOptionPane.class, component -> true);
            assertNotNull(option);
            option.setValue(JOptionPane.NO_OPTION);
        });
        assertSame(session, GuiActionRunner.execute(() -> application().getSession()));
        assertFalse(session.isClosed());
        assertEquals(report, session.getLastReport());
        assertEquals(5, session.getFiles().length);
        assertSame(catalogue, session.getSkyCatalogue().get());
        assertEquals(0, GuiActionRunner.execute(() -> window().table().target().getSelectedRow()).intValue());
        assertEquals(5, GuiActionRunner.execute(() -> window().table().target().getRowCount()).intValue());
        assertNull(session.getTrackOverlay());
        assertNull(session.getVariableStarOverlay());
        File output = Arrays.stream(source.listFiles()).filter(file -> file.getName().startsWith("iterative_detections_")).findFirst().orElseThrow();
        var data = JsonParser.parseString(Files.readString(new File(output, "run-data.json").toPath())).getAsJsonObject();
        assertEquals(3.3, data.getAsJsonObject("config").get("detectionSigmaMultiplier").getAsDouble(), 0);
        assertTrue(new File(output, "5_frames/detection_report.html").isFile());
        assertTrue(new File(output, "7_frames/detection_report.html").isFile());
    }

    private JDialog openConfiguration() {
        EventQueue.invokeLater(() -> window().button(VisibleText.button("Detect Iteratively (large datasets)")).target().doClick());
        waitFor("the iterative configuration", () -> GuiActionRunner.execute(() -> showingDialog("Iterative detection configuration") != null));
        return GuiActionRunner.execute(() -> showingDialog("Iterative detection configuration"));
    }

    private static JDialog showingDialog(String title) {
        for (Window window : Window.getWindows()) if (window instanceof JDialog dialog && dialog.isShowing() && title.equals(dialog.getTitle())) return dialog;
        return null;
    }

    private static JLabel text(Component root, String text) { return find(root, JLabel.class, label -> label.getText() != null && label.getText().contains(text)); }
    private static JButton button(Component root, String text) { return find(root, JButton.class, button -> text.equals(button.getText())); }
    private static JCheckBox checkbox(Component root, String text) { return find(root, JCheckBox.class, box -> text.equals(box.getText())); }
    private static JTextField field(Component root, String title) { return (JTextField) text(root, title).getLabelFor(); }

    private static <ComponentType extends Component> ComponentType find(Component root, Class<ComponentType> type,
                                                                        java.util.function.Predicate<ComponentType> matches) {
        if (type.isInstance(root) && matches.test(type.cast(root))) return type.cast(root);
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            ComponentType found = find(child, type, matches);
            if (found != null) return found;
        }
        return null;
    }
}
