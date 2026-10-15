package eu.startales.spacepixels.util;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner.AutoTuneProfile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AutoTuneSessionStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void savesBothTunersAndReloadsTheSettingsTheyChose() throws Exception {
        CalibratedAutoTuner.Candidate candidate = new CalibratedAutoTuner.Candidate();
        candidate.sigma = 1.7;
        candidate.growSigma = 1.4;
        candidate.minPixels = 4;
        candidate.masterSigma = 3.2;
        candidate.masterGrowSigma = 2.1;
        candidate.masterMinPixels = 6;
        candidate.maskOverlap = 0.35;
        candidate.falsePositivesPerMpixFrame = 0.02;
        candidate.falsePositiveUpperPerMpixFrame = 0.03;
        candidate.recoveredFraction = 0.8;
        candidate.snr50 = Double.NaN;
        candidate.maskCoverage = 0.12;
        candidate.completeness = new double[]{0.1, 0.9};

        CalibratedAutoTuner.Calibration calibration = new CalibratedAutoTuner.Calibration();
        calibration.success = true;
        calibration.jitter = 1.25;
        calibration.elapsedSeconds = 127;
        calibration.withinBudget[AutoTuneProfile.HIGH.ordinal()] = true;
        calibration.chosen[AutoTuneProfile.HIGH.ordinal()] = candidate;
        calibration.measuredMpixFrames = 10;
        for (int i = 0; i < 200; i++) {
            CalibratedAutoTuner.Candidate extra = new CalibratedAutoTuner.Candidate();
            extra.sigma = 3.0;
            extra.falsePositivesPerMpixFrame = i;
            extra.falsePositiveUpperPerMpixFrame = i;
            extra.recoveredFraction = 0.1;
            extra.completeness = new double[]{0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8};
            calibration.candidates.add(extra);
        }

        JTransientAutoTuner.AutoTunerResult calibrated = new JTransientAutoTuner.AutoTunerResult();
        calibrated.success = true;
        calibrated.calibration = calibration;
        calibrated.telemetryReport = "report <line>";
        calibrated.optimizedConfig = new DetectionConfig();
        calibrated.summary = "High profile";

        DetectionConfig legacyConfig = new DetectionConfig();
        legacyConfig.detectionSigmaMultiplier = 2.2;
        legacyConfig.growSigmaMultiplier = 1.8;
        legacyConfig.minDetectionPixels = 5;
        legacyConfig.maxMaskOverlapFraction = 0.4;
        legacyConfig.maxStarJitter = 2.5;
        JTransientAutoTuner.AutoTunerResult legacy = new JTransientAutoTuner.AutoTunerResult();
        legacy.success = true;
        legacy.optimizedConfig = legacyConfig;
        legacy.telemetryReport = "legacy report";

        AutoTuneSessionStore.Document document = new AutoTuneSessionStore.Document();
        document.frameCount = 10;
        document.width = 100;
        document.height = 80;
        document.fileNames = Arrays.asList("b.fits", "a.fits");
        document.calibrated = run("2026-10-15T16:42:00Z", 127, "HIGH", calibrated);
        document.legacy = run("2026-10-14T07:10:00Z", 45, "MEDIUM", legacy);

        File session = folder.newFolder("session");
        AutoTuneSessionStore.save(session, document);

        AutoTuneSessionStore.Document loaded = AutoTuneSessionStore.loadMatching(
                session, 10, 100, 80, Arrays.asList("a.fits", "b.fits"));
        assertNotNull(loaded);
        assertEquals(Arrays.asList("a.fits", "b.fits"), loaded.fileNames);
        assertEquals("report <line>", loaded.calibrated.result.telemetryReport);
        assertEquals(0.9, loaded.calibrated.result.calibration.chosen[AutoTuneProfile.HIGH.ordinal()].completeness[1], 0);
        assertTrue(Double.isNaN(loaded.calibrated.result.calibration.chosen[AutoTuneProfile.HIGH.ordinal()].snr50));
        assertNull(loaded.calibrated.result.calibration.chosen[AutoTuneProfile.LOW.ordinal()]);
        assertTrue(loaded.calibrated.result.calibration.candidates == null
                || loaded.calibrated.result.calibration.candidates.isEmpty());
        assertNotNull(loaded.calibrated.result.calibration.tradeoff);
        assertEquals(0.1, loaded.calibrated.result.calibration.tradeoff.profiles[AutoTuneProfile.HIGH.ordinal()]
                .bySigma[indexOfSigma(loaded.calibrated.result.calibration.tradeoff.sigmas, 3.0)].recoveredFraction, 1e-9);
        assertTrue(new File(session, AutoTuneSessionStore.FILE_NAME).length() < 200_000);

        DetectionConfig applied = CalibratedAutoTuner.configFor(
                loaded.calibrated.result.calibration, new DetectionConfig(), AutoTuneProfile.HIGH);
        assertEquals(1.7, applied.detectionSigmaMultiplier, 0);
        assertEquals(3.2, applied.masterSigmaMultiplier, 0);
        assertEquals(1.25, applied.maxStarJitter, 0);
        assertEquals(2.2, loaded.legacy.result.optimizedConfig.detectionSigmaMultiplier, 0);
        assertEquals(2.5, loaded.legacy.result.optimizedConfig.maxStarJitter, 0);

        String described = AutoTuneSessionStore.describe(loaded);
        assertTrue(described, described.contains("calibrated, run at " + AutoTuneSessionStore.formatRanAt("2026-10-15T16:42:00Z")));
        assertTrue(described, described.contains("2 min 7 s"));
        assertTrue(described, described.contains("legacy, run at " + AutoTuneSessionStore.formatRanAt("2026-10-14T07:10:00Z")));
        assertTrue(described, described.contains("45 s"));

        assertNull(AutoTuneSessionStore.loadMatching(session, 11, 100, 80, Arrays.asList("a.fits", "b.fits")));
        assertNull(AutoTuneSessionStore.loadMatching(session, 10, 100, 80, Collections.singletonList("a.fits")));
    }

    @Test
    public void ignoresANewerVersion() throws Exception {
        File session = folder.newFolder("newer");
        Files.write(new File(session, AutoTuneSessionStore.FILE_NAME).toPath(),
                "{\"version\":99,\"frameCount\":1}".getBytes(StandardCharsets.UTF_8));
        assertNull(AutoTuneSessionStore.load(session));
    }

    private static int indexOfSigma(double[] sigmas, double sigma) {
        for (int i = 0; i < sigmas.length; i++) {
            if (sigmas[i] == sigma) {
                return i;
            }
        }
        throw new AssertionError("sigma " + sigma);
    }

    private static AutoTuneSessionStore.Run run(String ranAt, long seconds, String profile, JTransientAutoTuner.AutoTunerResult result) {
        AutoTuneSessionStore.Run run = new AutoTuneSessionStore.Run();
        run.ranAt = ranAt;
        run.elapsedSeconds = seconds;
        run.profile = profile;
        run.result = result;
        return run;
    }
}
