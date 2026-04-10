package eu.startales.spacepixels.tools;

import eu.startales.spacepixels.api.DefaultSpacePixelsPipelineApi;
import eu.startales.spacepixels.api.InputPreparationMode;
import eu.startales.spacepixels.api.SpacePixelsPipelineApi;
import eu.startales.spacepixels.api.SpacePixelsPipelineRequest;
import eu.startales.spacepixels.api.SpacePixelsPipelineResult;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import eu.startales.spacepixels.util.DetectionInputPreparation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SpacePixelsRealDataIT {

    private static final String TESTDATA_ROOT_PROPERTY = "spacepixels.testdata.root";

    @Test
    public void runsPreparationApiAndCliAgainstEachDatasetDirectory() throws Exception {
        String rootValue = System.getProperty(TESTDATA_ROOT_PROPERTY);
        Assume.assumeTrue("No real-data root configured.", rootValue != null && !rootValue.isBlank());

        Path rootDirectory = Path.of(rootValue);
        Assume.assumeTrue("Configured real-data root does not exist: " + rootDirectory, Files.isDirectory(rootDirectory));
        RealDataDetectionProfile detectionProfile = resolveDetectionProfile(rootDirectory);

        List<Path> datasetDirectories;
        try (Stream<Path> stream = Files.list(rootDirectory)) {
            datasetDirectories = stream.filter(Files::isDirectory)
                    .sorted()
                    .collect(Collectors.toList());
        }
        Assume.assumeFalse("No dataset directories found under " + rootDirectory, datasetDirectories.isEmpty());

        List<String> failures = new ArrayList<>();
        for (Path datasetDirectory : datasetDirectories) {
            try {
                runSingleDataset(datasetDirectory, detectionProfile);
            } catch (Throwable throwable) {
                failures.add(datasetDirectory.getFileName() + ": " + throwable.getMessage());
            }
        }

        if (!failures.isEmpty()) {
            fail("Real-data integration failures:\n" + String.join("\n", failures));
        }
    }

    private static void runSingleDataset(Path datasetDirectory, RealDataDetectionProfile detectionProfile) throws Exception {
        Path tempUserHome = Files.createTempDirectory("spacepixels-realdata-home");
        Path tempConfigFile = detectionProfile.rootConfigPath != null
                ? null
                : Files.createTempFile("spacepixels-realdata-profile", ".json");
        String originalUserHome = System.getProperty("user.home");

        try {
            System.setProperty("user.home", tempUserHome.toString());
            if (tempConfigFile != null) {
                try (java.io.Writer writer = Files.newBufferedWriter(tempConfigFile)) {
                    SpacePixelsDetectionProfileIO.write(
                            writer,
                            detectionProfile.profile.getDetectionConfig(),
                            detectionProfile.profile.getAutoTuneMaxCandidateFrames());
                }
            }

            DetectionInputPreparation.PreparedDirectory preparedDirectory = DetectionInputPreparation.prepareInputDirectory(
                    datasetDirectory.toFile(),
                    true,
                    null);
            assertTrue(preparedDirectory.getPreparedInputDirectory().isDirectory());

            SpacePixelsPipelineApi api = new DefaultSpacePixelsPipelineApi();
            SpacePixelsPipelineResult apiResult = api.run(
                    SpacePixelsPipelineRequest.builder(preparedDirectory.getPreparedInputDirectory())
                            .detectionConfig(detectionProfile.profile.getDetectionConfig())
                            .autoTuneMaxCandidateFrames(detectionProfile.profile.getAutoTuneMaxCandidateFrames())
                            .inputPreparationMode(InputPreparationMode.FAIL_IF_NOT_READY)
                            .generateReport(false)
                            .build());

            assertNotNull(apiResult.getPipelineResult());
            assertTrue(apiResult.getFilesInformation().length > 0);

            ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
            int exitCode = BatchDetectionCli.execute(
                    new String[]{preparedDirectory.getPreparedInputDirectory().getAbsolutePath(), detectionProfile.resolveCliConfigPath(tempConfigFile).toString()},
                    new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8.name()),
                    new PrintStream(stderrBytes, true, StandardCharsets.UTF_8.name()));

            assertEquals(stderrBytes.toString(StandardCharsets.UTF_8.name()), 0, exitCode);
            String cliOutput = stdoutBytes.toString(StandardCharsets.UTF_8.name());
            String reportPath = extractCliValue(cliOutput, "Report file:");
            assertTrue("CLI did not report a generated report path for " + datasetDirectory, reportPath != null && !reportPath.isBlank());
            System.out.println("Real-data detection profile for " + datasetDirectory.getFileName() + ": " + detectionProfile.describeSource());
            System.out.println("Real-data report for " + datasetDirectory.getFileName() + ": " + reportPath);
        } finally {
            if (originalUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalUserHome);
            }
            if (tempConfigFile != null) {
                Files.deleteIfExists(tempConfigFile);
            }
            deleteRecursively(tempUserHome);
        }
    }

    private static RealDataDetectionProfile resolveDetectionProfile(Path rootDirectory) throws Exception {
        Path rootConfigPath = rootDirectory.resolve(SpacePixelsDetectionProfileIO.DEFAULT_FILENAME);
        if (Files.isRegularFile(rootConfigPath)) {
            try (java.io.Reader reader = Files.newBufferedReader(rootConfigPath)) {
                return new RealDataDetectionProfile(SpacePixelsDetectionProfileIO.load(reader), rootConfigPath);
            }
        }

        return new RealDataDetectionProfile(
                new SpacePixelsDetectionProfile(
                        new DetectionConfig(),
                        SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES),
                null);
    }

    private static String extractCliValue(String cliOutput, String prefix) {
        if (cliOutput == null || prefix == null) {
            return null;
        }
        for (String line : cliOutput.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static final class RealDataDetectionProfile {
        private final SpacePixelsDetectionProfile profile;
        private final Path rootConfigPath;

        private RealDataDetectionProfile(SpacePixelsDetectionProfile profile, Path rootConfigPath) {
            this.profile = profile;
            this.rootConfigPath = rootConfigPath;
        }

        private Path resolveCliConfigPath(Path temporaryConfigPath) {
            return rootConfigPath != null ? rootConfigPath : temporaryConfigPath;
        }

        private String describeSource() {
            return rootConfigPath != null
                    ? rootConfigPath.toAbsolutePath().toString()
                    : "generated default DetectionConfig";
        }
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
