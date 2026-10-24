package eu.startales.spacepixels.gui.mechanics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import eu.startales.spacepixels.config.DetectionSetting;
import eu.startales.spacepixels.config.DetectionSettings;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import eu.startales.spacepixels.config.SpacePixelsVisualizationPreferences;
import eu.startales.spacepixels.config.SpacePixelsVisualizationPreferencesIO;
import eu.startales.spacepixels.config.VariableStarReportOptions;
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
    private final SpacePixelsVisualizationPreferences originalVisualization = SpacePixelsVisualizationPreferences.captureCurrent();

    /** Restores process-wide presentation defaults after the fixture has released its window. */
    @Override protected void onTearDown() throws Exception {
        try {
            super.onTearDown();
        } finally {
            originalVisualization.applyToRuntime();
        }
    }

    /** All visible fields save their displayed values while hidden fields survive edits and restore correctly. */
    @Test(timeout = 180_000) public void allPagesSaveRevertLoadDefaultsAndReloadWithoutLosingHiddenFields() throws Exception {
        DetectionConfig seed = new DetectionConfig();
        seed.qualityGrowSigmaMultiplier = 1.234;
        try (FileWriter writer = new FileWriter(profileFile())) {
            SpacePixelsDetectionProfileIO.write(writer, new SpacePixelsDetectionProfile(seed, 30));
        }
        reopenWindow();
        importSyntheticSequence();
        window().tabbedPane().selectTab("Detection Settings");
        Map<String, JComponent> inputs = GuiActionRunner.execute(() -> inputs(application().getDetectionConfigurationPanel()));
        assertEquals("78 engine fields, 11 application fields and the rejection preset", 90, inputs.size());
        for (DetectionSetting<?> setting : DetectionSettings.all()) {
            assertNotNull(setting.id(), inputs.get(setting.id()));
            assertEquals(setting.expert(), inputs.get(setting.id()).getClientProperty("spacepixels.expertSetting"));
        }
        GuiActionRunner.execute(() -> inputs.forEach((id, input) -> {
            if (!id.equals("frameRejectionPreset")) change(input);
        }));
        Map<String, Object> expected = GuiActionRunner.execute(() -> values(inputs));
        window().button(VisibleText.button("Save")).click();
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
        window().button(VisibleText.button("Revert")).click();
        assertValues(expected, GuiActionRunner.execute(() -> values(inputs)));
        window().button(VisibleText.button("Load Defaults")).click();
        Map<String, Object> defaults = GuiActionRunner.execute(() -> values(inputs));
        DetectionConfig defaultConfig = new DetectionConfig();
        for (DetectionSetting<?> setting : DetectionSettings.all()) {
            sameValue(setting.id(), setting.read(defaultConfig), defaults.get(setting.id()));
        }
        sameValue("default report card limit", VariableStarReportOptions.DEFAULT.maxCandidateCards(), defaults.get("variableStarMaxCandidateCards"));
        assertEquals(VariableStarReportOptions.DEFAULT.sortOrder(), defaults.get("variableStarSortOrder"));
        sameValue("tuning limit is independent of detection defaults", expected.get("autoTuneMaxCandidateFrames"), defaults.get("autoTuneMaxCandidateFrames"));
        window().button(VisibleText.button("Revert")).click();
        assertValues(expected, GuiActionRunner.execute(() -> values(inputs)));
        reopenWindow();
        Map<String, Object> reloaded = GuiActionRunner.execute(() -> values(inputs(application().getDetectionConfigurationPanel())));
        assertValues(expected, reloaded);
    }

    /** Locates controls by their stable field identity, including expert and temporarily inapplicable rows. */
    private static Map<String, JComponent> inputs(Container root) {
        Map<String, JComponent> result = new LinkedHashMap<>();
        collectInputs(root, result);
        return result;
    }

    /** Visits the component tree without relying on private spinner names or page layout. */
    private static void collectInputs(Container root, Map<String, JComponent> result) {
        for (Component component : root.getComponents()) {
            if (component instanceof JComponent input && input.getClientProperty("spacepixels.settingId") instanceof String id) {
                assertNull("Duplicate input identity " + id, result.put(id, input));
            }
            if (component instanceof Container nested) collectInputs(nested, result);
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
