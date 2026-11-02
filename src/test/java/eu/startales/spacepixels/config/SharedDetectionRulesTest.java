package eu.startales.spacepixels.config;

import com.google.gson.Gson;
import eu.startales.spacepixels.api.SpacePixelsPipelineRequest;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.config.DetectionConfigRules;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.Assert.*;

/** Verifies that profiles and API requests share engine diagnostics without rewriting accepted legacy settings. */
public class SharedDetectionRulesTest {
    /** Compares structured rejection for every double field using NaN and both infinities in complete profiles. */
    @Test
    public void profileAndApiUseTheSameStructuredNumericRules() throws Exception {
        for (var field : DetectionConfig.class.getFields()) {
            if (field.getType() != double.class) continue;
            for (double value : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
                DetectionConfig config = new DetectionConfig();
                field.setDouble(config, value);
                var api = assertThrows(DetectionConfigRules.InvalidConfigurationException.class, () ->
                        SpacePixelsPipelineRequest.builder(new File("unused")).detectionConfig(config).build());
                var json = new Gson().toJsonTree(new DetectionConfig()).getAsJsonObject();
                json.addProperty(field.getName(), Double.toString(value));
                IOException profile = assertThrows(IOException.class, () -> SpacePixelsDetectionProfileIO.load(
                        new StringReader(json.toString())));
                var cause = (DetectionConfigRules.InvalidConfigurationException) profile.getCause();
                assertEquals(field.getName(), api.issues(), cause.issues());
                assertTrue(DetectionSettingsPresentation.issue(api.issues().getFirst()).contains(field.getName()));
            }
        }
    }

    /** The star-cap warning explains, with the engine's own numbers, the references the star-shape check counts. */
    @Test
    public void starCapWarningExplainsTheReferencesTheShapeCheckCounts() {
        DetectionConfig config = new DetectionConfig();
        config.enableVariableStarDetection = true;
        config.photometryMaxStars = 52;
        var issue = DetectionConfigRules.validate(config, DetectionConfigRules.Operation.CONFIGURATION).stream()
                .filter(i -> i.ruleId() == DetectionConfigRules.RuleId.PHOTOMETRY_STAR_CAP).findFirst().orElseThrow();
        String text = DetectionSettingsPresentation.issue(issue);
        assertTrue(text, text.contains("star cap (52) is below 55"));
        assertTrue(text, text.contains("its 50 reference stars"));
        assertTrue(text, text.contains("comparison-star fit its 10"));
        assertTrue(text, text.contains("Zero or a negative cap measures every usable star"));
    }

    /** Round-trips hidden values and sentinels, then checks API snapshot independence and advisory diagnostics. */
    @Test
    public void auditedLegacyProfilesRoundTripWithoutSilentScientificChanges() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.qualityGrowSigmaMultiplier = 8;
        config.masterGrowSigmaMultiplier = 0;
        config.photometryMaxStars = -1;
        config.maxFwhmRatio = 0;
        config.trackMinFrameRatio = 0;
        config.variableMinPersistenceFrames = 30;
        config.variableMinFrames = 20;
        StringWriter json = new StringWriter();
        SpacePixelsDetectionProfileIO.write(json, config, 30);
        var loaded = SpacePixelsDetectionProfileIO.load(new StringReader(json.toString()));
        var request = SpacePixelsPipelineRequest.builder(new File("unused")).detectionConfig(loaded.getDetectionConfig()).build();
        DetectionConfig captured = request.getDetectionConfig();
        assertEquals(8, captured.qualityGrowSigmaMultiplier, 0);
        assertEquals(0, captured.masterGrowSigmaMultiplier, 0);
        assertEquals(-1, captured.photometryMaxStars);
        assertEquals(0, captured.trackMinFrameRatio, 0);
        assertEquals(30, captured.variableMinPersistenceFrames);
        assertTrue(DetectionConfigRules.validate(captured, DetectionConfigRules.Operation.CONFIGURATION).stream()
                .anyMatch(issue -> issue.ruleId() == DetectionConfigRules.RuleId.QUALITY_GROW_SIGMA));
        captured.qualityGrowSigmaMultiplier = 11;
        assertEquals(8, request.getDetectionConfig().qualityGrowSigmaMultiplier, 0);
    }
}
