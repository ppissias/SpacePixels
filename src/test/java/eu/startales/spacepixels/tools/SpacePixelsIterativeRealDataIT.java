package eu.startales.spacepixels.tools;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import eu.startales.spacepixels.util.DetectionInputPreparation;
import eu.startales.spacepixels.util.ImageProcessing;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SpacePixelsIterativeRealDataIT {

    private static final String TESTDATA_ROOT_PROPERTY = "spacepixels.testdata.root";
    private static final String ITERATIVE_DATASET_PROPERTY = "spacepixels.iterative.dataset";
    private static final String ITERATIVE_MAX_FRAMES_PROPERTY = "spacepixels.iterative.maxFrames";
    private static final String TESTDATA_ROOT_ENV = "SPACEPIXELS_TESTDATA_ROOT";
    private static final String ITERATIVE_DATASET_ENV = "SPACEPIXELS_ITERATIVE_DATASET";
    private static final String ITERATIVE_MAX_FRAMES_ENV = "SPACEPIXELS_ITERATIVE_MAX_FRAMES";
    private static final String DEFAULT_ITERATIVE_DATASET = "16_bit_mono_files";

    @Test
    public void runsIterativeDetectionAgainstPreparedRealData() throws Exception {
        String rootValue = firstNonBlank(
                System.getProperty(TESTDATA_ROOT_PROPERTY),
                System.getenv(TESTDATA_ROOT_ENV));
        Assume.assumeTrue("No real-data root configured.", rootValue != null && !rootValue.isBlank());

        Path rootDirectory = Path.of(rootValue);
        Assume.assumeTrue("Configured real-data root does not exist: " + rootDirectory, Files.isDirectory(rootDirectory));

        Path datasetDirectory = rootDirectory.resolve(firstNonBlank(
                System.getProperty(ITERATIVE_DATASET_PROPERTY),
                System.getenv(ITERATIVE_DATASET_ENV),
                DEFAULT_ITERATIVE_DATASET));
        Assume.assumeTrue("Configured iterative dataset does not exist: " + datasetDirectory, Files.isDirectory(datasetDirectory));

        DetectionConfig detectionConfig = resolveDetectionConfig(rootDirectory);
        Path tempUserHome = Files.createTempDirectory("spacepixels-iterative-realdata-home");
        String originalUserHome = System.getProperty("user.home");

        try {
            System.setProperty("user.home", tempUserHome.toString());

            DetectionInputPreparation.PreparedDirectory preparedDirectory = DetectionInputPreparation.prepareInputDirectory(
                    datasetDirectory.toFile(),
                    true,
                    null);
            assertTrue(preparedDirectory.getPreparedInputDirectory().isDirectory());

            ImageProcessing imageProcessing = ImageProcessing.getInstance(preparedDirectory.getPreparedInputDirectory());
            int frameCount = imageProcessing.getFitsfileInformationHeadless().length;
            assertTrue(frameCount >= 5);
            int maxFrames = resolveConfiguredMaxFrames();
            int effectiveMaxFrames = resolveEffectiveMaxFrames(maxFrames, frameCount);

            File iterativeIndex = imageProcessing.detectSlowObjectsIterative(
                    detectionConfig.clone(),
                    null,
                    null,
                    maxFrames);

            assertNotNull(iterativeIndex);
            assertTrue("Iterative index path was not created: " + iterativeIndex, iterativeIndex.isFile());

            File masterDir = iterativeIndex.getParentFile();
            assertNotNull(masterDir);
            for (int k = 5; k <= effectiveMaxFrames; k += 5) {
                assertTrue(new File(masterDir, k + "_frames/detection_report.html").isFile());
            }

            System.out.println("Real-data iterative dataset: " + datasetDirectory.getFileName());
            System.out.println("Real-data iterative index: " + iterativeIndex.getAbsolutePath());
        } finally {
            if (originalUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalUserHome);
            }
            deleteRecursively(tempUserHome);
        }
    }

    private static DetectionConfig resolveDetectionConfig(Path rootDirectory) throws Exception {
        Path rootConfigPath = rootDirectory.resolve(SpacePixelsDetectionProfileIO.DEFAULT_FILENAME);
        if (Files.isRegularFile(rootConfigPath)) {
            try (java.io.Reader reader = Files.newBufferedReader(rootConfigPath)) {
                SpacePixelsDetectionProfile profile = SpacePixelsDetectionProfileIO.load(reader);
                return profile.getDetectionConfig();
            }
        }
        return new DetectionConfig();
    }

    private static int resolveConfiguredMaxFrames() {
        String value = firstNonBlank(
                System.getProperty(ITERATIVE_MAX_FRAMES_PROPERTY),
                System.getenv(ITERATIVE_MAX_FRAMES_ENV));
        if (value == null) {
            return 10;
        }
        return Integer.parseInt(value.trim());
    }

    private static int resolveEffectiveMaxFrames(int configuredMaxFrames, int frameCount) {
        int effectiveMaxFrames = (configuredMaxFrames > 0 && configuredMaxFrames < frameCount)
                ? configuredMaxFrames
                : frameCount;
        if (effectiveMaxFrames < 5) {
            effectiveMaxFrames = 5;
        }
        return effectiveMaxFrames;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void deleteRecursively(Path directory) throws Exception {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }
}
