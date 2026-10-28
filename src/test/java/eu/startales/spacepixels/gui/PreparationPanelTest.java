package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.preparation.PreparationRequest;
import eu.startales.spacepixels.preparation.PreparationInputs;
import eu.startales.spacepixels.preparation.PreparationTool;
import eu.startales.spacepixels.session.ApplicationStorage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import eu.startales.spacepixels.config.AppConfig;
import static org.junit.Assert.*;

/** Panel-only UI contracts validate evidence, confirmation invalidation and immutable requests without native dialogs. */
public class PreparationPanelTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** A confirmed request defaults to the lights folder, respects explicit output and is invalidated by later edits. */
    @Test public void preparationNeedsEvidenceAndReconfirmsAfterEdits() throws Exception {
        Path lights = temporary.newFolder("lights").toPath();
        for (int i = 0; i < 3; i++) Files.createFile(lights.resolve(i + ".fit"));
        Path executable = temporary.newFile("siril-cli.exe").toPath();
        ApplicationSettings settings = ApplicationSettings.load(temporary.getRoot().toPath().resolve("preferences.json").toFile());
        ApplicationStorage storage = new ApplicationStorage();
        AtomicReference<PreparationPanel.Selection> admitted = new AtomicReference<>();
        AtomicReference<PreparationPanel> reference = new AtomicReference<>();
        try {
            edt(() -> reference.set(new PreparationPanel(settings, storage, () -> false, admitted::set, () -> { },
                    (tool, path) -> new PreparationPanel.Installation(executable, "siril 1.4.4"))));
            PreparationPanel panel = reference.get();
            edt(() -> {
                assertFalse(button(panel, "Prepare with Siril").isEnabled());
                JTextField cli = field(panel, "Siril installation path");
                cli.setText(executable.toString());
                button(cli.getParent(), "Check").doClick();
                field(panel, "Lights (required)").setText(lights.toString());
                combo(panel, PreparationRequest.Sensor.class).setSelectedItem(PreparationRequest.Sensor.MONO);
                button(panel, "Inspect inputs").doClick();
            });
            await(() -> settings.getConfig().sirilExecutablePath.equals(executable.toString()));
            await(() -> text(panel).contains("Inputs counted"));
            edt(() -> {
                checkbox(panel, "These folders contain").doClick();
                checkbox(panel, "I confirm this recipe").doClick();
                assertTrue(button(panel, "Prepare with Siril").isEnabled());
                button(panel, "Prepare with Siril").doClick();
                assertNotNull(admitted.get());
                assertEquals(PreparationTool.SIRIL, admitted.get().request().tool());
                assertEquals(lights, admitted.get().request().outputParent());
                assertTrue(text(panel).contains("Output parent (lights source folder): " + lights));
                JTextField output = field(panel, "Output parent folder (optional)");
                output.setText(temporary.getRoot().toString());
                assertFalse(button(panel, "Prepare with Siril").isEnabled());
                checkbox(panel, "I confirm this recipe").doClick();
                button(panel, "Prepare with Siril").doClick();
                assertEquals(temporary.getRoot().toPath(), admitted.get().request().outputParent());
                output.setText("\0");
                checkbox(panel, "I confirm this recipe").doClick();
                assertFalse(button(panel, "Prepare with Siril").isEnabled());
                assertTrue(text(panel).contains("Invalid output parent folder"));
                assertThrows(java.io.IOException.class, panel::selection);
                output.setText("  ");
                checkbox(panel, "I confirm this recipe").doClick();
                button(panel, "Prepare with Siril").doClick();
                assertEquals(lights, admitted.get().request().outputParent());
                field(panel, "Lights (required)").setText(lights.resolve("changed").toString());
                assertFalse(button(panel, "Prepare with Siril").isEnabled());
                assertEquals(lights, admitted.get().request().inputs().get(eu.startales.spacepixels.preparation.PreparationInputs.Role.LIGHTS));
                assertTrue(text(panel).contains("Output parent (lights source folder): " + lights.resolve("changed")));
            });
        } finally {
            if (reference.get() != null) edt(reference.get()::close);
            storage.finish();
        }
    }

    /** A camera mode is intentionally unset rather than guessed from a two-dimensional image or extension. */
    @Test public void cameraModeIsInitiallyUnknownAndBothToolRowsArePresent() throws Exception {
        ApplicationStorage storage = new ApplicationStorage();
        AtomicReference<PreparationPanel> reference = new AtomicReference<>();
        var preferences = temporary.getRoot().toPath().resolve("unset-preferences.json").toFile();
        try {
            edt(() -> {
                var panel = new PreparationPanel(ApplicationSettings.load(preferences), storage, () -> false,
                        ignored -> { }, () -> { }, (tool, path) -> { throw new java.io.IOException("not installed"); });
                reference.set(panel);
                assertNull(combo(panel, PreparationRequest.Sensor.class).getSelectedItem());
                assertNotNull(field(panel, "Siril installation path"));
                assertNotNull(field(panel, "DeepSkyStacker installation path"));
                assertNotNull(button(panel, "Import Aligned Frames…"));
            });
        } finally { if (reference.get() != null) edt(reference.get()::close); storage.finish(); }
    }

    /** Scanning a second session replaces every role, offers ambiguous groups and preserves an explicit output destination. */
    @Test public void sessionScansReplaceAllRolesAndExposeSeparateChoices() throws Exception {
        Path first = temporary.newFolder("first").toPath();
        Path second = temporary.newFolder("second").toPath();
        images(first, "lights", 3);
        images(first, "flat", 2);
        images(first, "bias", 2);
        Path m13 = images(second, "light m13", 3);
        images(second, "light m57", 3);
        Path flats = images(second, "flat", 2);
        ApplicationStorage storage = new ApplicationStorage();
        AtomicReference<PreparationPanel> reference = new AtomicReference<>();
        try {
            edt(() -> reference.set(new PreparationPanel(ApplicationSettings.inMemory(new AppConfig()), storage, () -> false, ignored -> { }, () -> { })));
            PreparationPanel panel = reference.get();
            edt(() -> {
                field(panel, "Output parent folder (optional)").setText(temporary.getRoot().toString());
                field(panel, "Observation session (optional)").setText(first.toString());
                button(panel, "Scan and suggest folders").doClick();
            });
            await(() -> scanFinished(panel));
            edt(() -> {
                assertEquals(first.resolve("lights").toString(), field(panel, "Lights (required)").getText());
                button(panel, "Inspect inputs").doClick();
            });
            await(() -> text(panel).contains("Inputs counted"));
            edt(() -> {
                checkbox(panel, "These folders contain").doClick();
                checkbox(panel, "I confirm this recipe").doClick();
                field(panel, "Observation session (optional)").setText(second.toString());
                assertFalse(checkbox(panel, "These folders contain").isSelected());
                button(panel, "Scan and suggest folders").doClick();
                assertTrue(field(panel, "Lights (required)").getText().isBlank());
                assertTrue(field(panel, "Biases (optional; folder or master FITS)").getText().isBlank());
            });
            await(() -> scanFinished(panel));
            edt(() -> {
                assertTrue(field(panel, "Lights (required)").getText().isBlank());
                assertEquals(flats.toString(), field(panel, "Flats (optional; folder or master FITS)").getText());
                assertTrue(field(panel, "Biases (optional; folder or master FITS)").getText().isBlank());
                JComboBox<?> choices = foundInputs(panel, "Lights");
                assertEquals(2, choices.getItemCount());
                assertEquals(-1, choices.getSelectedIndex());
                choices.setSelectedItem(choices.getItemAt(0));
                assertEquals(m13.toString(), field(panel, "Lights (required)").getText());
                assertEquals(temporary.getRoot().toString(), field(panel, "Output parent folder (optional)").getText());
                field(panel, "Observation session (optional)").setText(second.resolve("missing").toString());
                button(panel, "Scan and suggest folders").doClick();
                assertTrue(field(panel, "Lights (required)").getText().isBlank());
                assertEquals(0, foundInputs(panel, "Lights").getItemCount());
            });
            await(() -> text(panel).contains("Choose an existing session folder"));
            edt(() -> assertTrue(field(panel, "Flats (optional; folder or master FITS)").getText().isBlank()));
        } finally { if (reference.get() != null) edt(reference.get()::close); storage.finish(); }
    }

    /** A discovered master is visible as an optional explicit file choice, never silently combined with nearby exposures. */
    @Test public void scannedMasterRequiresExplicitFileSelection() throws Exception {
        Path root = temporary.newFolder("masters").toPath();
        images(root, "lights", 3);
        Path bias = images(root, "bias", 2);
        Path master = Files.createFile(bias.resolve("master_bias.fit"));
        ApplicationStorage storage = new ApplicationStorage();
        AtomicReference<PreparationPanel> reference = new AtomicReference<>();
        try {
            edt(() -> {
                var panel = new PreparationPanel(ApplicationSettings.inMemory(new AppConfig()), storage, () -> false, ignored -> { }, () -> { });
                reference.set(panel);
                field(panel, "Observation session (optional)").setText(root.toString());
                button(panel, "Scan and suggest folders").doClick();
            });
            PreparationPanel panel = reference.get();
            await(() -> scanFinished(panel));
            edt(() -> {
                assertTrue(field(panel, "Biases (optional; folder or master FITS)").getText().isBlank());
                JComboBox<?> choices = foundInputs(panel, "Biases");
                assertEquals(1, choices.getItemCount());
                assertTrue(((PreparationInputs.Candidate) choices.getItemAt(0)).master());
                assertEquals(-1, choices.getSelectedIndex());
                choices.setSelectedItem(choices.getItemAt(0));
                assertEquals(master.toString(), field(panel, "Biases (optional; folder or master FITS)").getText());
                button(panel, "Inspect inputs").doClick();
            });
            await(() -> text(panel).contains("Inputs counted"));
            edt(() -> assertTrue(all(panel).stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                    .anyMatch(label -> "Existing master FITS".equals(label.getText()))));
        } finally { if (reference.get() != null) edt(reference.get()::close); storage.finish(); }
    }

    /** Creates candidate filenames without claiming the files are decoded image data. */
    private static Path images(Path root, String relative, int count) throws Exception {
        Path folder = Files.createDirectories(root.resolve(relative));
        for (int i = 0; i < count; i++) Files.createFile(folder.resolve(i + ".fit"));
        return folder;
    }

    /** Waits for an actual completed scan message rather than transient empty fields. */
    private static boolean scanFinished(Container panel) {
        return all(panel).stream().filter(JLabel.class::isInstance).map(JLabel.class::cast).map(JLabel::getToolTipText)
                .anyMatch(text -> text != null && (text.startsWith("Suggested folders;") || text.startsWith("No unique lights folder")));
    }

    /** Finds the role-specific discovered-input selector through its accessible label. */
    private static JComboBox<?> foundInputs(Container panel, String role) {
        return all(panel).stream().filter(component -> component instanceof JComboBox<?> combo
                        && (role + " found inputs").equals(combo.getAccessibleContext().getAccessibleName()))
                .map(JComboBox.class::cast).findFirst().orElseThrow();
    }

    /** Runs one assertion/edit on Swing's owning thread. */
    private static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }

    /** Gives real SwingWorker completion a bounded chance to deliver on the EDT. */
    private static void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            boolean[] value = {false}; edt(() -> value[0] = ready.getAsBoolean());
            if (value[0]) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for form evidence");
    }

    /** Finds a labelled editor through its accessibility association rather than private field names. */
    private static JTextField field(Container root, String label) {
        return all(root).stream().filter(component -> component instanceof JTextField field
                && label.equals(field.getAccessibleContext().getAccessibleName())).map(JTextField.class::cast).findFirst().orElseThrow();
    }

    /** Locates a real command button by visible text. */
    private static JButton button(Container root, String text) {
        return all(root).stream().filter(component -> component instanceof JButton button && text.equals(button.getText()))
                .map(JButton.class::cast).findFirst().orElseThrow();
    }

    /** Locates the actual explicit confirmation control. */
    private static JCheckBox checkbox(Container root, String start) {
        return all(root).stream().filter(component -> component instanceof JCheckBox checkbox && AccessibleControls.plainText(checkbox.getText()).startsWith(start))
                .map(JCheckBox.class::cast).findFirst().orElseThrow();
    }

    /** Selects the typed combo without depending on its index in the layout. */
    private static JComboBox<?> combo(Container root, Class<?> type) {
        return all(root).stream().filter(component -> component instanceof JComboBox<?> combo && combo.getItemCount() > 0 && type.isInstance(combo.getItemAt(0)))
                .map(JComboBox.class::cast).findFirst().orElseThrow();
    }

    /** Reads readiness text while on the EDT. */
    private static String text(Container root) {
        return all(root).stream().filter(JTextArea.class::isInstance).map(JTextArea.class::cast).map(JTextArea::getText).reduce("", String::concat);
    }

    /** Enumerates this panel's controls without constructing or displaying a native window. */
    private static java.util.List<Component> all(Container root) {
        java.util.List<Component> values = new java.util.ArrayList<>();
        for (Component component : root.getComponents()) {
            values.add(component);
            if (component instanceof Container container) values.addAll(all(container));
        }
        return values;
    }
}
