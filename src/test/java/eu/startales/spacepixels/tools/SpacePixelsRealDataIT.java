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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SpacePixelsRealDataIT {

    private static final String TESTDATA_ROOT_PROPERTY = "spacepixels.testdata.root";
    private static final String[] GENERATED_DIRECTORY_PREFIXES = {
            "_spacepixels_prepared_mono16_",
            "_spacepixels_xisf_mono16",
            "detections_"
    };

    @Test
    public void runsPreparationApiAndCliAgainstEachDatasetDirectory() throws Exception {
        String rootValue = System.getProperty(TESTDATA_ROOT_PROPERTY);
        Assume.assumeTrue("No real-data root configured.", rootValue != null && !rootValue.isBlank());

        Path rootDirectory = Path.of(rootValue);
        Assume.assumeTrue("Configured real-data root does not exist: " + rootDirectory, Files.isDirectory(rootDirectory));

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
                runSingleDataset(datasetDirectory);
            } catch (Throwable throwable) {
                failures.add(datasetDirectory.getFileName() + ": " + throwable.getMessage());
            }
        }

        if (!failures.isEmpty()) {
            fail("Real-data integration failures:\n" + String.join("\n", failures));
        }
    }

    private static void runSingleDataset(Path datasetDirectory) throws Exception {
        Set<Path> originalChildren = listImmediateDirectories(datasetDirectory);
        Path tempUserHome = Files.createTempDirectory("spacepixels-realdata-home");
        Path tempConfigFile = Files.createTempFile("spacepixels-realdata-profile", ".json");
        String originalUserHome = System.getProperty("user.home");

        try {
            System.setProperty("user.home", tempUserHome.toString());
            try (java.io.Writer writer = Files.newBufferedWriter(tempConfigFile)) {
                SpacePixelsDetectionProfileIO.write(
                        writer,
                        new DetectionConfig(),
                        SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES);
            }

            DetectionInputPreparation.PreparedDirectory preparedDirectory = DetectionInputPreparation.prepareInputDirectory(
                    datasetDirectory.toFile(),
                    true,
                    null);
            assertTrue(preparedDirectory.getPreparedInputDirectory().isDirectory());

            SpacePixelsPipelineApi api = new DefaultSpacePixelsPipelineApi();
            SpacePixelsPipelineResult apiResult = api.run(
                    SpacePixelsPipelineRequest.builder(preparedDirectory.getPreparedInputDirectory())
                            .detectionConfig(new DetectionConfig())
                            .inputPreparationMode(InputPreparationMode.FAIL_IF_NOT_READY)
                            .generateReport(false)
                            .build());

            assertNotNull(apiResult.getPipelineResult());
            assertTrue(apiResult.getFilesInformation().length > 0);

            ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
            int exitCode = BatchDetectionCli.execute(
                    new String[]{preparedDirectory.getPreparedInputDirectory().getAbsolutePath(), tempConfigFile.toString()},
                    new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8.name()),
                    new PrintStream(stderrBytes, true, StandardCharsets.UTF_8.name()));

            assertEquals(stderrBytes.toString(StandardCharsets.UTF_8.name()), 0, exitCode);
        } finally {
            if (originalUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalUserHome);
            }
            cleanupGeneratedDirectories(datasetDirectory, originalChildren);
            Files.deleteIfExists(tempConfigFile);
            deleteRecursively(tempUserHome);
        }
    }

    private static Set<Path> listImmediateDirectories(Path directory) throws Exception {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isDirectory)
                    .map(Path::toAbsolutePath)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private static void cleanupGeneratedDirectories(Path datasetDirectory, Set<Path> originalChildren) throws Exception {
        Set<Path> currentChildren = listImmediateDirectories(datasetDirectory);
        for (Path child : currentChildren) {
            if (originalChildren.contains(child.toAbsolutePath())) {
                continue;
            }
            String directoryName = child.getFileName().toString();
            if (matchesGeneratedDirectory(directoryName)) {
                deleteRecursively(child);
            }
        }
    }

    private static boolean matchesGeneratedDirectory(String directoryName) {
        for (String prefix : GENERATED_DIRECTORY_PREFIXES) {
            if (directoryName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
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
