package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.config.DetectionConfigRules;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.DialogFixture;
import org.junit.Test;

import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.FileReader;
import java.util.Map;

import static org.junit.Assert.*;

/** Drives rule-review choices, hidden-value persistence and typed pair bindings in an isolated desktop session. */
public class SharedSettingsRulesMechanicsTest extends MechanicsTestCase {
    /** Exercises Keep/Align in the real dialog and verifies only explicit alignment changes the saved hidden policy. */
    @Test(timeout = 180_000)
    public void reviewKeepsHiddenValuesUntilExplicitAlignmentAndSavePreservesThem() throws Exception {
        importSyntheticSequence();
        window().tabbedPane().selectTab("Detection Settings");
        var panel = application().getDetectionConfigurationPanel();
        GuiActionRunner.execute(() -> {
            field(panel, "jTransientConfig", DetectionConfig.class).qualityGrowSigmaMultiplier = 8;
            field(panel, "chkEnableVariableStarDetection", JCheckBox.class).setSelected(true);
            field(panel, "spinPhotometryMaxStars", JSpinner.class).setValue(9);
            field(panel, "spinVariableMinAmplitudeMag", JSpinner.class).setValue(0.1);
            field(panel, "spinVariableLimitedMinAmplitudeMag", JSpinner.class).setValue(0.02);
        });
        window().button(VisibleText.button("Review rules…")).click();
        DialogFixture review = reviewDialog();
        String diagnostics = GuiActionRunner.execute(() -> text(review.target()));
        assertTrue(diagnostics, diagnostics.contains("qualityGrowSigmaMultiplier"));
        assertTrue(diagnostics, diagnostics.contains("photometryMaxStars"));
        assertTrue(diagnostics, diagnostics.contains("variableLimitedMinAmplitudeMag"));
        review.button(VisibleText.button("Keep settings")).click();
        GuiActionRunner.execute(() -> {
            DetectionConfig config = field(panel, "jTransientConfig", DetectionConfig.class);
            assertEquals(8, config.qualityGrowSigmaMultiplier, 0);
            assertEquals(0.02, config.variableLimitedMinAmplitudeMag, 0);
            assertEquals(9, config.photometryMaxStars);
        });
        window().button(VisibleText.button("Review rules…")).click();
        reviewDialog().button(VisibleText.button("Align listed changes")).click();
        GuiActionRunner.execute(() -> {
            DetectionConfig config = field(panel, "jTransientConfig", DetectionConfig.class);
            assertEquals(config.qualitySigmaMultiplier, config.qualityGrowSigmaMultiplier, 0);
            assertEquals(0.1, config.variableLimitedMinAmplitudeMag, 0);
            assertEquals(9, config.photometryMaxStars);
            assertTrue(field(panel, "footerStateLabel", JLabel.class).getText().contains("Unsaved changes"));
        });
        window().button(VisibleText.button("Save")).click();
        waitFor("the aligned profile is saved", () -> GuiActionRunner.execute(() ->
                field(panel, "footerStateLabel", JLabel.class).getText().contains("Saved settings")));
        try (FileReader reader = new FileReader(new File(System.getProperty("user.home"), SpacePixelsDetectionProfileIO.DEFAULT_FILENAME))) {
            DetectionConfig saved = SpacePixelsDetectionProfileIO.load(reader).getDetectionConfig();
            assertEquals(saved.qualitySigmaMultiplier, saved.qualityGrowSigmaMultiplier, 0);
            assertEquals(0.1, saved.variableLimitedMinAmplitudeMag, 0);
            assertEquals(9, saved.photometryMaxStars);
        }
    }

    /** Crosses all six bound pairs to verify engine-selected directions and the automatic master-grow sentinel. */
    @Test(timeout = 120_000)
    public void everyTypedWidgetPairUsesTheEngineAdjustmentDirection() throws Exception {
        importSyntheticSequence();
        var panel = application().getDetectionConfigurationPanel();
        GuiActionRunner.execute(() -> {
            Map<?, ?> bindings = field(panel, "detectionInputs", Map.class);
            for (DetectionConfigRules.Pair pair : DetectionConfigRules.pairs()) {
                JSpinner first = (JSpinner) bindings.get(pair.firstField());
                JSpinner second = (JSpinner) bindings.get(pair.secondField());
                if (first == null || second == null) continue;
                first.setValue(((Number) second.getValue()).doubleValue() + 0.1);
                DetectionConfig config = field(panel, "jTransientConfig", DetectionConfig.class);
                assertTrue(pair.ruleId().name(), pair.holds(config));
                assertEquals(((Number) first.getValue()).doubleValue(),
                        ((Number) second.getValue()).doubleValue(), 0);
            }
            field(panel, "spinMasterGrowSigma", JSpinner.class).setValue(0.0);
            assertEquals(0, field(panel, "jTransientConfig", DetectionConfig.class).masterGrowSigmaMultiplier, 0);
        });
    }

    /** Finds the currently showing rules dialog without depending on generated component names. */
    private DialogFixture reviewDialog() {
        return WindowFinder.findDialog(new GenericTypeMatcher<>(JDialog.class) {
            /** Matches only the visible shared-rules dialog, excluding unrelated application dialogs. */
            @Override protected boolean isMatching(JDialog dialog) {
                return dialog.isShowing() && "Shared settings rules".equals(dialog.getTitle());
            }
        }).using(robot());
    }

    /** Retrieves the diagnostic text area from a dialog subtree, or null when none is present. */
    private static String text(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JTextArea area) return area.getText();
            if (component instanceof Container nested) {
                String found = text(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Reads a private test binding without exposing widget/configuration internals in the production API. */
    private static <Value> Value field(Object owner, String name, Class<Value> type) {
        try {
            var field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(owner));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
