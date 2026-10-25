package eu.startales.spacepixels.gui.mechanics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import eu.startales.spacepixels.config.DetectionSetting;
import eu.startales.spacepixels.config.DetectionSettings;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import eu.startales.spacepixels.config.SpacePixelsVisualizationPreferencesIO;
import eu.startales.spacepixels.config.VariableStarReportOptions;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.assertj.swing.edt.GuiActionRunner;
import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/** Checks every registered control against saved profiles, hidden values, defaults, Revert and startup reload. */
public class AllTypedSettingsMechanicsTest extends MechanicsTestCase {
    /** All visible fields save their displayed values while hidden fields survive edits and restore correctly. */
    @Test(timeout = 180_000) public void allPagesSaveRevertLoadDefaultsAndReloadWithoutLosingHiddenFields() throws Exception {
        DetectionConfig seed = new DetectionConfig();
        seed.qualityGrowSigmaMultiplier = 1.234;
        try (FileWriter writer = new FileWriter(profileFile())) {
            SpacePixelsDetectionProfileIO.write(writer, new SpacePixelsDetectionProfile(seed, 30));
        }
        reopenWindow();
        var directory = SyntheticDatasetFactory.createMono16FitsSequence(folder.newFolder("frames").toPath(),
                "session", SyntheticDatasetFactory.DEFAULT_FRAME_COUNT).toFile();
        GuiActionRunner.execute(() -> application().importFolder(directory));
        waitFor("session imported", () -> GuiActionRunner.execute(() -> application().getSession() != null));
        GuiActionRunner.execute(() -> application().getTabbedPane()
                .setSelectedComponent(application().getDetectionConfigurationPanel()));
        Map<String, ControlMetadata> controls = GuiActionRunner.execute(() -> controls(application().getDetectionConfigurationPanel()));
        Map<String, JComponent> inputs = controls.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().input(), (first, second) -> first, LinkedHashMap::new));
        assertEquals("78 engine fields, 11 application fields and the rejection preset", 90, inputs.size());
        for (DetectionSetting<?> setting : DetectionSettings.all()) {
            assertNotNull(setting.id(), controls.get(setting.id()));
            assertEquals(setting.expert(), controls.get(setting.id()).expert());
        }
        Map<String, Boolean> expectedApplicationExpert = Map.ofEntries(
                Map.entry("variableStarMaxCandidateCards", false),
                Map.entry("variableStarSortOrder", false),
                Map.entry("frameRejectionPreset", false),
                Map.entry("autoTuneMaxCandidateFrames", true),
                Map.entry("autoStretchBlackSigma", false),
                Map.entry("autoStretchWhiteSigma", false),
                Map.entry("gifBlinkSpeedMs", false),
                Map.entry("streakLineScaleFactor", true),
                Map.entry("streakCentroidBoxRadius", true),
                Map.entry("pointSourceMinBoxRadius", true),
                Map.entry("dynamicBoxPadding", true),
                Map.entry("trackCropPadding", true));
        expectedApplicationExpert.forEach((id, expert) -> {
            assertNotNull(id, controls.get(id));
            assertEquals(id, expert, controls.get(id).expert());
        });
        GuiActionRunner.execute(() -> inputs.forEach((id, input) -> {
            if (!id.equals("frameRejectionPreset")) change(input);
        }));
        Map<String, Object> expected = GuiActionRunner.execute(() -> values(inputs));
        GuiActionRunner.execute(() -> window().button(VisibleText.button("Save")).target().doClick());
        waitFor("all typed settings saved", () -> showingLabel("Saved settings").contains("Saved settings"));
        SpacePixelsDetectionProfile saved;
        try (FileReader reader = new FileReader(profileFile())) {
            saved = SpacePixelsDetectionProfileIO.load(reader);
        }
        for (DetectionSetting<?> setting : DetectionSettings.all()) {
            sameValue(setting.id(), expected.get(setting.id()), setting.read(saved.getDetectionConfig()));
        }
        assertEquals(expected.get("autoTuneMaxCandidateFrames"), saved.getAutoTuneMaxCandidateFrames());
        assertEquals(expected.get("variableStarMaxCandidateCards"), saved.getVariableStarReportOptions().maxCandidateCards());
        assertEquals(expected.get("variableStarSortOrder"), saved.getVariableStarReportOptions().sortOrder());
        JsonObject before = new Gson().toJsonTree(seed).getAsJsonObject();
        JsonObject after = new Gson().toJsonTree(saved.getDetectionConfig()).getAsJsonObject();
        Set<String> visible = DetectionSettings.all().stream().map(DetectionSetting::id).collect(Collectors.toSet());
        for (String field : before.keySet()) {
            if (!visible.contains(field)) assertEquals("Hidden field " + field, before.get(field), after.get(field));
        }
        try (FileReader reader = new FileReader(new File(System.getProperty("user.home"), SpacePixelsVisualizationPreferencesIO.DEFAULT_FILENAME))) {
            JsonObject visualization = new Gson().toJsonTree(SpacePixelsVisualizationPreferencesIO.load(reader)).getAsJsonObject();
            for (String field : visualization.keySet()) {
                assertEquals(field, new Gson().toJsonTree(expected.get(field)), visualization.get(field));
            }
        }
        GuiActionRunner.execute(() -> inputs.forEach((id, input) -> {
            if (!id.equals("frameRejectionPreset")) change(input);
        }));
        GuiActionRunner.execute(() -> window().button(VisibleText.button("Revert")).target().doClick());
        assertValues(expected, GuiActionRunner.execute(() -> values(inputs)));
        GuiActionRunner.execute(() -> window().button(VisibleText.button("Load Defaults")).target().doClick());
        Map<String, Object> defaults = GuiActionRunner.execute(() -> values(inputs));
        DetectionConfig defaultConfig = new DetectionConfig();
        for (DetectionSetting<?> setting : DetectionSettings.all()) {
            sameValue(setting.id(), setting.read(defaultConfig), defaults.get(setting.id()));
        }
        sameValue("default report card limit", VariableStarReportOptions.DEFAULT.maxCandidateCards(), defaults.get("variableStarMaxCandidateCards"));
        assertEquals(VariableStarReportOptions.DEFAULT.sortOrder(), defaults.get("variableStarSortOrder"));
        sameValue("tuning limit is independent of detection defaults", expected.get("autoTuneMaxCandidateFrames"), defaults.get("autoTuneMaxCandidateFrames"));
        GuiActionRunner.execute(() -> window().button(VisibleText.button("Revert")).target().doClick());
        assertValues(expected, GuiActionRunner.execute(() -> values(inputs)));
        reopenWindow();
        Map<String, Object> reloaded = GuiActionRunner.execute(() -> values(inputs(application().getDetectionConfigurationPanel())));
        assertValues(expected, reloaded);
    }

    /** Carries a mounted control and its direct expert-disclosure metadata from the settings panel. */
    private record ControlMetadata(JComponent input, boolean expert) {
    }

    /** Locates controls by their stable field identity, including expert and temporarily inapplicable rows. */
    private static Map<String, JComponent> inputs(Container root) {
        return controls(root).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().input(), (first, second) -> first, LinkedHashMap::new));
    }

    /** Reads direct row and Overview metadata and verifies every registered input is mounted in the panel tree. */
    private static Map<String, ControlMetadata> controls(Container root) {
        Set<JComponent> mounted = new java.util.HashSet<>();
        collectMountedComponents(root, mounted);
        Map<String, ControlMetadata> result = new LinkedHashMap<>();
        try {
            var coreField = root.getClass().getDeclaredField("coreDefinitions");
            coreField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<JSpinner, DetectionSetting<?>> coreDefinitions = (Map<JSpinner, DetectionSetting<?>>) coreField.get(root);
            coreDefinitions.forEach((spinner, setting) -> {
                assertTrue("Core control not mounted: " + setting.id(), mounted.contains(spinner));
                assertNull("Duplicate input identity " + setting.id(),
                        result.put(setting.id(), new ControlMetadata(spinner, setting.expert())));
            });
            var rowsField = root.getClass().getDeclaredField("settingRows");
            rowsField.setAccessible(true);
            java.util.List<?> rows = (java.util.List<?>) rowsField.get(root);
            for (Object row : rows) {
                var idField = row.getClass().getDeclaredField("id");
                idField.setAccessible(true);
                String id = (String) idField.get(row);
                var inputField = row.getClass().getDeclaredField("input");
                inputField.setAccessible(true);
                JComponent input = (JComponent) inputField.get(row);
                var isExpertMethod = row.getClass().getDeclaredMethod("isExpert");
                isExpertMethod.setAccessible(true);
                boolean expert = (Boolean) isExpertMethod.invoke(row);
                assertTrue("Row control not mounted: " + id, mounted.contains(input));
                assertNull("Duplicate input identity " + id,
                        result.put(id, new ControlMetadata(input, expert)));
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        return result;
    }

    /** Visits the component tree to verify every registered control is present in the container hierarchy. */
    private static void collectMountedComponents(Container root, Set<JComponent> mounted) {
        for (Component component : root.getComponents()) {
            if (component instanceof JComponent input) {
                mounted.add(input);
            }
            if (component instanceof Container nested) collectMountedComponents(nested, mounted);
        }
    }

    /** Edits through each existing model, leaving dependent alignment to the application. */
    private static void change(JComponent input) {
        if (input instanceof JSpinner spinner) {
            Object next = spinner.getNextValue();
            spinner.setValue(next == null ? spinner.getPreviousValue() : next);
        } else if (input instanceof AbstractButton toggle) {
            toggle.setSelected(!toggle.isSelected());
        } else if (input instanceof JComboBox<?> choice) {
            choice.setSelectedIndex((choice.getSelectedIndex() + 1) % choice.getItemCount());
        } else {
            fail("Unsupported settings input " + input.getClass());
        }
    }

    /** Captures displayed values on the EDT, including derived preset state. */
    private static Map<String, Object> values(Map<String, JComponent> inputs) {
        Map<String, Object> result = new LinkedHashMap<>();
        inputs.forEach((id, input) -> {
            if (input instanceof JSpinner spinner) result.put(id, spinner.getValue());
            else if (input instanceof AbstractButton toggle) result.put(id, toggle.isSelected());
            else if (input instanceof JComboBox<?> choice) result.put(id, choice.getSelectedItem());
        });
        return result;
    }

    /** Compares all controls while allowing integer/double wrapper differences in Swing number models. */
    private static void assertValues(Map<String, Object> expected, Map<String, Object> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((id, value) -> sameValue(id, value, actual.get(id)));
    }

    /** Compares numeric values in their existing units and other settings by their typed identity. */
    private static void sameValue(String id, Object expected, Object actual) {
        if (expected instanceof Number first && actual instanceof Number second) {
            assertEquals(id, first.doubleValue(), second.doubleValue(), 1e-9);
        } else {
            assertEquals(id, expected, actual);
        }
    }

    /** Resolves the detection profile only inside the fixture's temporary home. */
    private static File profileFile() {
        return new File(System.getProperty("user.home"), SpacePixelsDetectionProfileIO.DEFAULT_FILENAME);
    }
}
