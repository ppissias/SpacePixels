package eu.startales.spacepixels.config;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DetectionSettingsRulesTest {

    @Test
    public void theDefaultsMeetEveryRule() {
        assertTrue(DetectionSettingsRules.problems(new DetectionConfig()).isEmpty());
    }

    @Test
    public void aNumberThatIsNotFiniteIsNamed() {
        DetectionConfig config = new DetectionConfig();
        config.minBackgroundDeviationADU = Double.NaN;

        List<String> problems = DetectionSettingsRules.problems(config);

        assertEquals(1, problems.size());
        assertTrue(problems.get(0), problems.get(0).contains("minBackgroundDeviationADU"));
    }

    @Test
    public void crossedPairsAreProblems() {
        DetectionConfig config = new DetectionConfig();
        config.growSigmaMultiplier = config.detectionSigmaMultiplier + 1;
        config.slowMoverMaxAxisRatio = config.slowMoverMinAxisRatio - 0.5;

        assertEquals(List.of("Grow Sigma is above Detection Sigma", "Min Axis Ratio is above Max Axis Ratio"),
                DetectionSettingsRules.problems(config));
    }

    @Test
    public void equalValuesAreInOrder() {
        DetectionConfig config = new DetectionConfig();
        config.growSigmaMultiplier = config.detectionSigmaMultiplier;
        config.rhythmStationaryThreshold = config.maxJumpPixels;

        assertTrue(DetectionSettingsRules.problems(config).isEmpty());
    }

    @Test
    public void theAdjustmentNamesTheMovedSettingAndWhy() {
        assertEquals("Grow Sigma lowered to 4.0 (cannot be above Detection Sigma)",
                DetectionSettingsRules.Pair.GROW_SIGMA.adjustment("4.0"));
        assertEquals("Max Axis Ratio raised to 2.50 (cannot be below Min Axis Ratio)",
                DetectionSettingsRules.Pair.SLOW_MOVER_AXIS_RATIO.adjustment("2.50"));
    }
}
