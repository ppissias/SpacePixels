package eu.startales.spacepixels.report;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Exercises the actual browser evaluator; Node is a development-only, optional test runner. */
public class CatalogueComparisonJavascriptTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void browserComparisonRulesAndOfflineHydration() throws Exception {
        String node = System.getenv("NODE_EXECUTABLE");
        if (node == null || node.isBlank()) node = "node";
        File log = temporary.newFile("javascript-tests.log");
        Process process;
        try {
            process = new ProcessBuilder(node, "--test", "src/test/javascript/catalogue-comparison.test.js",
                    "src/test/javascript/report-lookups.test.js",
                    "src/test/javascript/report-interactions.test.js")
                    .redirectErrorStream(true).redirectOutput(log).start();
        } catch (IOException unavailable) {
            Assume.assumeNoException("Node is not installed; run the report JavaScript tests with NODE_EXECUTABLE set", unavailable);
            return;
        }
        try {
            assertTrue("JavaScript tests timed out", process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(Files.readString(log.toPath()), 0, process.exitValue());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
