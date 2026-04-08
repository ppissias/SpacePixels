package eu.startales.spacepixels.tools;

import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BatchDetectionCliTest {

    private static final int FRAME_COUNT = 5;

    @Test
    public void executeReturnsZeroForReadyMono16Sequence() throws Exception {
        Path tempRoot = Files.createTempDirectory("spacepixels-cli-mono16");
        Path sequenceDirectory = SyntheticDatasetFactory.createMono16FitsSequence(tempRoot, "mono16", FRAME_COUNT);
        Path configFile = SyntheticDatasetFactory.writeDetectionProfile(
                tempRoot,
                "profile.json",
                SyntheticDatasetFactory.createSyntheticDetectionConfig());

        ExecutionResult result = executeCli(sequenceDirectory, configFile);

        assertEquals(result.stderr, 0, result.exitCode);
        assertTrue(result.stdout.contains("Batch detection completed successfully."));
        assertTrue(result.stdout.contains("Report file:"));
    }

    @Test
    public void executeReturnsOneForUnsupportedFloatFitsInStrictMode() throws Exception {
        Path tempRoot = Files.createTempDirectory("spacepixels-cli-float32");
        Path sequenceDirectory = SyntheticDatasetFactory.createMono32FloatFitsSequence(tempRoot, "float32mono", FRAME_COUNT);
        Path configFile = SyntheticDatasetFactory.writeDetectionProfile(
                tempRoot,
                "profile.json",
                new DetectionConfig());

        ExecutionResult result = executeCli(sequenceDirectory, configFile);

        assertEquals(1, result.exitCode);
        assertTrue(result.stderr.contains("Expected uncompressed 16-bit monochrome FITS files."));
    }

    private static ExecutionResult executeCli(Path inputDirectory, Path configFile) throws Exception {
        ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
        PrintStream stdout = new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8.name());
        PrintStream stderr = new PrintStream(stderrBytes, true, StandardCharsets.UTF_8.name());

        Path tempUserHome = Files.createTempDirectory("spacepixels-cli-home");
        String originalUserHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempUserHome.toString());
            int exitCode = BatchDetectionCli.execute(
                    new String[]{inputDirectory.toString(), configFile.toString()},
                    stdout,
                    stderr);
            return new ExecutionResult(
                    exitCode,
                    stdoutBytes.toString(StandardCharsets.UTF_8.name()),
                    stderrBytes.toString(StandardCharsets.UTF_8.name()));
        } finally {
            if (originalUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalUserHome);
            }
        }
    }

    private static final class ExecutionResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private ExecutionResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
