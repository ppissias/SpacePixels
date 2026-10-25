package eu.startales.spacepixels.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import eu.startales.spacepixels.config.DetectionSetting;
import eu.startales.spacepixels.config.DetectionSettings;
import eu.startales.spacepixels.config.DetectionSettingsPresentation;
import eu.startales.spacepixels.config.ResidualAnalysisSettings;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.config.DetectionConfigRules;
import org.junit.Test;
import javax.swing.*;
import java.awt.EventQueue;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

/** Exercises typed field ownership, shared engine validation, stable identity and existing shared button models. */
public class TypedDetectionSettingsTest {
    /** Every definition changes exactly its declared field, preserving hidden configuration and primitive types. */
    @Test public void definitionsMutateOnlyTheirDeclaredField() {
        Set<String> identities = new HashSet<>();
        for (DetectionSetting<?> setting : DetectionSettings.all()) {
            assertTrue(identities.add(setting.id()));
            assertEquals(setting.title(), DetectionSettingsPresentation.title(setting.id()));
            checkOwnership(setting);
        }
        assertEquals(78, identities.size());
    }

    /** Invalid numeric input is rejected before mutation and the engine remains the source of issue identities. */
    @Test public void nonFiniteValuesAndUnsupportedContractsAreExplicit() {
        DetectionConfig config = new DetectionConfig();
        double before = config.localActivityClusterRadiusPixels;
        var setting = ResidualAnalysisSettings.CLUSTER_RADIUS;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> setting.write(config, Double.NaN));
        assertTrue(failure.getMessage().contains(setting.id()));
        assertEquals(before, config.localActivityClusterRadiusPixels, 0);
        config.localActivityClusterRadiusPixels = Double.NaN;
        assertTrue(DetectionConfigRules.validate(config, DetectionConfigRules.Operation.CONFIGURATION).stream()
                .anyMatch(issue -> issue.fields().contains(setting.id()) && issue.ruleId() == DetectionConfigRules.RuleId.NON_FINITE));
        assertThrows(IllegalArgumentException.class, () -> new DetectionSetting<>("enableLocalActivityClusters",
                "Wrong type", "", "Invalid application binding", false, Integer.class,
                source -> source.localActivityClusterMinFrames, (target, value) -> target.localActivityClusterMinFrames = value));
    }

    /** Load/apply uses the original control models and a field ID can be searched independently of its page. */
    @Test public void bindingsPreserveModelsAndDefinitionOwnedVisibility() throws Exception {
        EventQueue.invokeAndWait(() -> {
            DetectionConfig config = new DetectionConfig();
            config.qualityGrowSigmaMultiplier = 1.234;
            JCheckBox detail = new JCheckBox();
            JCheckBox overview = new JCheckBox();
            overview.setModel(detail.getModel());
            var binding = new DetectionSettingBinding<>(ResidualAnalysisSettings.ENABLE_RESIDUAL,
                    detail::isSelected, detail::setSelected);
            config.enableResidualTransientAnalysis = true;
            binding.loadFrom(config);
            assertTrue(overview.isSelected());
            overview.setSelected(false);
            binding.applyTo(config);
            assertFalse(config.enableResidualTransientAnalysis);
            assertEquals(1.234, config.qualityGrowSigmaMultiplier, 0);
            JSpinner input = new JSpinner(new SpinnerNumberModel(10.0, 0.0, 100.0, 0.5));
            SettingRow row = new SettingRow(new JPanel(), new JPanel(), new JLabel("Radius"),
                    ResidualAnalysisSettings.CLUSTER_RADIUS.id(), "Radius",
                    ResidualAnalysisSettings.CLUSTER_RADIUS.help(), true, input);
            row.section = new SettingsNavigator.Section(new JLabel("Common"), "Common", false);
            assertEquals(ResidualAnalysisSettings.CLUSTER_RADIUS.id(), row.id);
            assertTrue(row.matches("localactivityclusterradiuspixels"));
            assertTrue(row.matches("pixels"));
            assertTrue(row.isExpert());
            SettingRow nonExpertInExpertSection = new SettingRow(new JPanel(), new JPanel(), new JLabel("Preset"),
                    "frameRejectionPreset", "Strictness", "Outlier preset", false, new JComboBox<>(new String[]{"Normal"}));
            nonExpertInExpertSection.section = new SettingsNavigator.Section(new JLabel("Expert"), "Expert", true);
            assertFalse(nonExpertInExpertSection.isExpert());
        });
    }

    /** Application values use typed load/apply without changing the control model or accepting non-finite edits. */
    @Test public void applicationBindingsKeepTheirOwnerAndRejectNonFiniteValues() throws Exception {
        EventQueue.invokeAndWait(() -> {
            var preferences = new eu.startales.spacepixels.config.SpacePixelsVisualizationPreferences();
            JSpinner input = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 10.0, 0.1));
            SpinnerModel model = input.getModel();
            var binding = new ApplicationSettingBinding<>("autoStretchBlackSigma", "Auto Stretch Black Sigma", "sigma",
                    "Black point below the image mean.", false, Double.class,
                    () -> preferences.autoStretchBlackSigma, value -> preferences.autoStretchBlackSigma = value,
                    () -> ((Number) input.getValue()).doubleValue(), input::setValue);
            preferences.autoStretchBlackSigma = 2.5;
            binding.load();
            assertEquals(2.5, ((Number) input.getValue()).doubleValue(), 0);
            input.setValue(3.5);
            binding.apply();
            assertEquals(3.5, preferences.autoStretchBlackSigma, 0);
            assertSame(model, input.getModel());
            assertThrows(IllegalArgumentException.class, () -> binding.write(Double.NaN));
            var nonFiniteBinding = new ApplicationSettingBinding<>("autoStretchBlackSigma", "Auto Stretch Black Sigma", "sigma",
                    "Black point below the image mean.", false, Double.class,
                    () -> preferences.autoStretchBlackSigma, value -> preferences.autoStretchBlackSigma = value,
                    () -> Double.NaN, input::setValue);
            assertThrows(IllegalArgumentException.class, nonFiniteBinding::apply);
            assertEquals(3.5, preferences.autoStretchBlackSigma, 0);
            assertTrue(binding.help().contains("sigma"));
        });
    }

    /** Compares serialized field changes so accidental cross-field getters/setters cannot pass a round trip. */
    private static <Value> void checkOwnership(DetectionSetting<Value> setting) {
        DetectionConfig config = new DetectionConfig();
        Gson gson = new Gson();
        JsonObject before = gson.toJsonTree(config).getAsJsonObject();
        Object current = setting.read(config);
        Object replacement;
        if (current instanceof Boolean enabled) replacement = !enabled;
        else if (current instanceof Integer count) replacement = count + 1;
        else replacement = ((Number) current).doubleValue() + 1;
        Value value = setting.type().cast(replacement);
        setting.write(config, value);
        assertEquals(value, setting.read(config));
        JsonObject after = gson.toJsonTree(config).getAsJsonObject();
        Set<String> changed = new HashSet<>();
        for (String field : before.keySet()) {
            if (!before.get(field).equals(after.get(field))) changed.add(field);
        }
        assertEquals(Set.of(setting.id()), changed);
    }
}
