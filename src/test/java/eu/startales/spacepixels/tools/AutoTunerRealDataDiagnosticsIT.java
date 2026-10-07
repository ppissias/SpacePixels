package eu.startales.spacepixels.tools;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import eu.startales.spacepixels.util.AutoTuneCandidatePoolBuilder;
import eu.startales.spacepixels.util.DetectionInputPreparation;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.Reader;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Opt-in diagnostics for the calibrated auto-tuner on a real dataset, without running the detection pipeline.
 *
 * <p>Set {@code SPACEPIXELS_TUNER_DATASET} to an aligned 16-bit FITS directory, and optionally
 * {@code SPACEPIXELS_TUNER_PROFILE} to a detection-profile JSON used as the base configuration.</p>
 */
public class AutoTunerRealDataDiagnosticsIT {

    @Test
    public void printsCalibrationForRealDataset() throws Exception {
        String dataset = System.getenv("SPACEPIXELS_TUNER_DATASET");
        Assume.assumeTrue("SPACEPIXELS_TUNER_DATASET not set", dataset != null && !dataset.isBlank());
        File directory = new File(dataset);
        Assume.assumeTrue("Dataset directory missing: " + directory, directory.isDirectory());

        DetectionConfig base = new DetectionConfig();
        int poolSize = SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES;
        String profilePath = System.getenv("SPACEPIXELS_TUNER_PROFILE");
        if (profilePath != null && !profilePath.isBlank()) {
            try (Reader reader = Files.newBufferedReader(new File(profilePath).toPath())) {
                SpacePixelsDetectionProfile profile = SpacePixelsDetectionProfileIO.load(reader);
                base = profile.getDetectionConfig();
                poolSize = profile.getAutoTuneMaxCandidateFrames();
            }
        }

        if ("1".equals(System.getenv("SPACEPIXELS_TUNER_PREPARE"))) {
            // Convert colour / float input to 16-bit mono first (written into a subfolder of the dataset).
            DetectionInputPreparation.PreparedDirectory prepared = DetectionInputPreparation.prepareInputDirectory(directory, true, null);
            directory = prepared.getPreparedInputDirectory();
            System.out.println("Prepared input directory: " + directory.getAbsolutePath());
        }

        FitsFileInformation[] files = ImageProcessing.getInstance(directory).getFitsfileInformationHeadless();
        List<ImageFrame> pool = AutoTuneCandidatePoolBuilder.buildCandidatePool(files, base, poolSize, (p, m) -> { });

        long start = System.currentTimeMillis();
        JTransientAutoTuner.AutoTunerResult result = CalibratedAutoTuner.tune(pool, base,
                JTransientAutoTuner.AutoTuneProfile.BALANCED, null);
        System.out.println(result.telemetryReport);
        System.out.printf("Calibration wall time: %.1f s%n", (System.currentTimeMillis() - start) / 1000.0);
        assertTrue(result.telemetryReport, result.success);
    }
}
