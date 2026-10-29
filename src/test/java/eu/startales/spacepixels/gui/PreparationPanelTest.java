package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.preparation.PreparationRequest;
import eu.startales.spacepixels.preparation.PreparationInputs;
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
            edt(() -> reference.set(new PreparationPanel(settings, storage, () -> false, admitted::set,
                    path -> new PreparationPanel.Installation(executable, "siril 1.4.4"))));
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
                assertEquals(lights, admitted.get().request().outputParent());
                var originalRequest = admitted.get().request();
                assertFalse(originalRequest.keepWorkingFiles());
                checkbox(panel, "Keep working files").doClick();
                assertFalse(button(panel, "Prepare with Siril").isEnabled());
                checkbox(panel, "I confirm this recipe").doClick();
                button(panel, "Prepare with Siril").doClick();
                assertTrue(admitted.get().request().keepWorkingFiles());
                assertFalse("Earlier requests remain immutable", originalRequest.keepWorkingFiles());
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
    @Test public void cameraModeIsInitiallyUnknownWithOnlySirilInstallation() throws Exception {
        ApplicationStorage storage = new ApplicationStorage();
        AtomicReference<PreparationPanel> reference = new AtomicReference<>();
        var preferences = temporary.getRoot().toPath().resolve("unset-preferences.json").toFile();
        try {
            edt(() -> {
                var panel = new PreparationPanel(ApplicationSettings.load(preferences), storage, () -> false,
                        ignored -> { }, path -> { throw new java.io.IOException("not installed"); });
                reference.set(panel);
                assertNull(combo(panel, PreparationRequest.Sensor.class).getSelectedItem());
                assertFalse(checkbox(panel, "Keep working files").isSelected());
                assertNotNull(field(panel, "Siril installation path"));
                assertEquals(1, all(panel).stream().filter(c -> c instanceof JTextField f
                        && f.getAccessibleContext().getAccessibleName() != null
                        && f.getAccessibleContext().getAccessibleName().endsWith("installation path")).count());
                assertFalse(all(panel).stream().anyMatch(c -> c instanceof JButton b && "Import Aligned Frames…".equals(b.getText())));
                assertFalse(all(panel).stream().anyMatch(c -> c instanceof JCheckBox b && b.getText().contains("Auto-Tune")));
            });
        } finally { if (reference.get() != null) edt(reference.get()::close); storage.finish(); }
    }

    /** Presets capture existing recipe fields, keep file row order explicit, and never discard hidden custom choices. */
    @Test public void cameraPresetsAndAdvancedOptionsCaptureOneConsistentRecipe() throws Exception {
        Path lights = images(temporary.getRoot().toPath(), "cfa-lights", 3);
        Path executable = temporary.newFile("camera-siril.exe").toPath();
        var settings = ApplicationSettings.inMemory(new AppConfig());
        var storage = new ApplicationStorage();
        var owner = new AtomicReference<PreparationPanel>();
        try {
            edt(() -> {
                var panel = new PreparationPanel(settings, storage, () -> false, ignored -> { },
                        ignored -> new PreparationPanel.Installation(executable, "siril 1.4.4"));
                owner.set(panel);
                field(panel, "Siril installation path").setText(executable.toString());
                button(panel, "Check").doClick();
                field(panel, "Lights (required)").setText(lights.toString());
            });
            await(() -> settings.getConfig().sirilExecutablePath.equals(executable.toString()));
            edt(() -> {
                var panel = owner.get();
                var preset = combo(panel, CameraBayerPreset.class);
                var pattern = combo(panel, PreparationRequest.Bayer.class);
                var rows = combo(panel, PreparationRequest.RowOrder.class);
                var advanced = checkbox(panel, "Show advanced de-bayer options");
                assertEquals(CameraBayerPreset.METADATA, preset.getSelectedItem());
                assertFalse("No CFA controls before choosing the sensor", visible(preset, panel));
                combo(panel, PreparationRequest.Sensor.class).setSelectedItem(PreparationRequest.Sensor.CFA);
                assertTrue(visible(preset, panel));
                assertFalse(visible(rows, panel));
                preset.setSelectedItem(CameraBayerPreset.ZWO_ASI1600MC);
                assertEquals(PreparationRequest.Bayer.GRBG, request(panel).bayer());
                assertEquals("Camera model cannot determine stored row order", PreparationRequest.RowOrder.METADATA,
                        request(panel).rowOrder());
                checkbox(panel, "I confirm this recipe").doClick();
                advanced.doClick();
                assertTrue("Disclosure is not a recipe change", checkbox(panel, "I confirm this recipe").isSelected());
                assertTrue(visible(rows, panel));
                rows.setSelectedItem(PreparationRequest.RowOrder.TOP_DOWN);
                assertFalse(checkbox(panel, "I confirm this recipe").isSelected());
                assertEquals(CameraBayerPreset.CUSTOM, preset.getSelectedItem());
                var x = labelledCombo(panel, "X offset (px)");
                var y = labelledCombo(panel, "Y offset (px)");
                x.setSelectedItem(1); y.setSelectedItem(1);
                var captured = request(panel);
                advanced.doClick();
                assertFalse(visible(rows, panel));
                assertEquals(captured, request(panel));
                assertTrue(text(panel).contains("Bayer offsets: 1, 1"));
                preset.setSelectedItem(CameraBayerPreset.QHY533C);
                assertEquals(PreparationRequest.Bayer.GBRG, request(panel).bayer());
                assertEquals(PreparationRequest.RowOrder.METADATA, request(panel).rowOrder());
                assertEquals(0, request(panel).offsetX());
                assertEquals(0, request(panel).offsetY());
                assertEquals("Earlier requests retain custom phase", 1, captured.offsetX());
                preset.setSelectedItem(CameraBayerPreset.QHY12);
                assertEquals(PreparationRequest.Bayer.BGGR, request(panel).bayer());
                pattern.setSelectedItem(PreparationRequest.Bayer.RGGB);
                assertEquals(CameraBayerPreset.CUSTOM, preset.getSelectedItem());
                preset.setSelectedItem(CameraBayerPreset.CANON_RAW);
                assertEquals(PreparationRequest.Bayer.METADATA, request(panel).bayer());
                assertFalse(x.isEnabled());
                panel.setBusy(true);
                assertFalse(preset.isEnabled()); assertFalse(advanced.isEnabled());
                panel.setBusy(false);
                assertTrue(preset.isEnabled()); assertTrue(advanced.isEnabled());
                combo(panel, PreparationRequest.Sensor.class).setSelectedItem(PreparationRequest.Sensor.RGB);
                assertFalse(visible(preset, panel));
                assertEquals(PreparationRequest.Bayer.METADATA, request(panel).bayer());
                assertEquals(PreparationRequest.RowOrder.METADATA, request(panel).rowOrder());
            });
        } finally { if (owner.get() != null) edt(owner.get()::close); storage.finish(); }
    }

    /** Captures a valid form on the EDT, reporting an unexpected validation failure as a test failure. */
    private static PreparationRequest request(PreparationPanel panel) {
        try { return panel.selection().request(); }
        catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    /** Finds a CFA phase editor by its accessible field label. */
    private static JComboBox<?> labelledCombo(Container panel, String label) {
        return all(panel).stream().filter(c -> c instanceof JComboBox<?> box
                && label.equals(box.getAccessibleContext().getAccessibleName()))
                .map(JComboBox.class::cast).findFirst().orElseThrow();
    }

    /** Offscreen visibility includes ancestor disclosures, without requiring a native window. */
    private static boolean visible(Component component, Container root) {
        for (Component current = component; current != root; current = current.getParent()) {
            if (current == null || !current.isVisible()) return false;
        }
        return true;
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
            edt(() -> reference.set(new PreparationPanel(ApplicationSettings.inMemory(new AppConfig()), storage, () -> false, ignored -> { })));
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
                var panel = new PreparationPanel(ApplicationSettings.inMemory(new AppConfig()), storage, () -> false, ignored -> { });
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
