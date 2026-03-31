package eu.startales.spacepixels.config;

import com.google.gson.Gson;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that SpacePixels detection-profile persistence keeps the extra auto-tune candidate-pool
 * setting and legacy field-name migration compatible with older profile JSON.
 */
public class SpacePixelsDetectionProfileIOTest {

    @Test
    public void writeAndLoadRoundTripPreservesAutoTuneMaxCandidateFrames() throws Exception {
        DetectionConfig config = new DetectionConfig();
        config.detectionSigmaMultiplier = 6.5d;
        config.qualityGrowSigmaMultiplier = 2.7d;
        config.qualityMaxElongationForFwhm = 1.9d;

        SpacePixelsDetectionProfile detectionProfile = new SpacePixelsDetectionProfile(config, 37);
        StringWriter writer = new StringWriter();
        SpacePixelsDetectionProfileIO.write(writer, detectionProfile);

        String json = writer.toString();
        SpacePixelsDetectionProfile loadedProfile = SpacePixelsDetectionProfileIO.load(new StringReader(json));

        assertTrue(json.contains("\"autoTuneMaxCandidateFrames\": 37"));
        assertEquals(37, loadedProfile.getAutoTuneMaxCandidateFrames());
        assertEquals(6.5d, loadedProfile.getDetectionConfig().detectionSigmaMultiplier, 0.0d);
        assertEquals(2.7d, loadedProfile.getDetectionConfig().qualityGrowSigmaMultiplier, 0.0d);
        assertEquals(1.9d, loadedProfile.getDetectionConfig().qualityMaxElongationForFwhm, 0.0d);
    }

    @Test
    public void loadDefaultsMissingFieldAndClampsInvalidValues() throws Exception {
        String legacyJson = new Gson().toJson(new DetectionConfig());
        SpacePixelsDetectionProfile legacyProfile = SpacePixelsDetectionProfileIO.load(new StringReader(legacyJson));
        assertEquals(SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES, legacyProfile.getAutoTuneMaxCandidateFrames());

        String invalidJson = "{\"detectionSigmaMultiplier\":5.0,\"autoTuneMaxCandidateFrames\":3}";
        SpacePixelsDetectionProfile invalidProfile = SpacePixelsDetectionProfileIO.load(new StringReader(invalidJson));
        assertEquals(SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES, invalidProfile.getAutoTuneMaxCandidateFrames());
    }

    @Test
    public void normalizeAutoTuneMaxCandidateFramesAllowsFourFrameRuns() {
        assertEquals(4, SpacePixelsDetectionProfile.normalizeAutoTuneMaxCandidateFrames(4));
        assertEquals(4, SpacePixelsDetectionProfile.normalizeAutoTuneMaxCandidateFrames(3));
    }

    @Test
    public void loadMigratesLegacyQualityFields() throws Exception {
        String legacyJson = "{\"growSigmaMultiplier\":2.4,\"maxElongationForFwhm\":1.8}";

        SpacePixelsDetectionProfile loadedProfile = SpacePixelsDetectionProfileIO.load(new StringReader(legacyJson));

        assertEquals(2.4d, loadedProfile.getDetectionConfig().qualityGrowSigmaMultiplier, 0.0d);
        assertEquals(1.8d, loadedProfile.getDetectionConfig().qualityMaxElongationForFwhm, 0.0d);
    }
}
