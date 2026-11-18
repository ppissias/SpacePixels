package eu.startales.spacepixels.tools;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Tests `--gpu` CLI flag parsing and error handling in {@link BatchDetectionCli}. */
public class BatchDetectionCliGpuTest {

    @Test
    public void parsesGpuModeFormsAndPreservesDefaultNullWhenOmitted() {
        assertNull(BatchDetectionCli.parseArguments(new String[]{"frames", "profile.json"}).gpuMode);
        assertEquals("AUTO", BatchDetectionCli.parseArguments(new String[]{"--gpu=auto", "frames", "profile.json"}).gpuMode);
        assertEquals("GPU", BatchDetectionCli.parseArguments(new String[]{"--gpu", "gpu", "frames", "profile.json"}).gpuMode);
        assertEquals("CPU", BatchDetectionCli.parseArguments(new String[]{"--gpu=CPU", "frames", "profile.json"}).gpuMode);
    }

    @Test
    public void invalidGpuModeReturnsUsageExitCode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = BatchDetectionCli.execute(
                new String[]{"--gpu=tpu", "frames", "profile.json"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(2, exit);
        String errText = err.toString(StandardCharsets.UTF_8);
        assertTrue(errText, errText.contains("Unknown GPU mode"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("--gpu"));
    }
}
