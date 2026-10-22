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

public class SharedDetectionRulesTest {
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
