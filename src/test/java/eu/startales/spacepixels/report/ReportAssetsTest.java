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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The files beside a report page: complete, in running order, with one script's failure kept from the others. */
public class ReportAssetsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void reportFilesAreWrittenBesideThePage() throws Exception {
        File folder = temporary.newFolder("report");
        ReportAssets.writeReportFiles(folder);
        assertEquals(ReportAssets.reportStyle(), Files.readString(new File(folder, ReportAssets.STYLE_FILE).toPath()));
        assertEquals(ReportAssets.reportScript(), Files.readString(new File(folder, ReportAssets.SCRIPT_FILE).toPath()));
    }

    /** The lookups use the catalogue comparison, and the faint-mover review the animations, when they start. */
    @Test
    public void scriptsRunInTheirOrder() {
        String script = ReportAssets.reportScript();
        int previous = -1;
        for (String part : new String[]{"session-pictures.js", "unclassified-inspector.js", "catalogue-comparison.js",
                "report-lookups.js", "report-interactions.js", "faint-mover-review.js"}) {
            int at = script.indexOf("/* " + part + " */\ntry {");
            assertTrue(part, at > previous);
            previous = at;
        }
        assertEquals("every part guards the others", 6, script.split("console\\.error\\('SpacePixels report: ", -1).length - 1);
        assertFalse("the lookup addresses are filled in", script.contains("__SPACEPIXELS_"));
    }

    /** The per-report values of the inspector are in the page, so its script and styles are the same for every report. */
    @Test
    public void inspectorFilesHoldNoPerReportValues() {
        assertTrue(ReportAssets.reportScript().contains("Number(panel.dataset.frameDelay)"));
        assertFalse(ReportAssets.reportStyle().matches("(?s).*\\.unclassified-map\\{[^}]*[^-]width:\\d+px.*"));
    }

    @Test
    public void iterativeResultsGetTheirOwnStyle() throws Exception {
        File folder = temporary.newFolder("iterative");
        ReportAssets.writeIterativeResultsStyle(folder);
        String style = Files.readString(new File(folder, ReportAssets.STYLE_FILE).toPath());
        assertTrue(style.contains("--sp-page:"));
        assertTrue(style.contains("font-family:system-ui"));
    }

    /** The results page of an iterative run loads its styles from the file written beside it. */
    @Test
    public void iterativeResultsPageLinksItsStyle() throws Exception {
        File folder = temporary.newFolder("run");
        IterativeDetectionIndex.write(folder, new IterativeDetectionIndex.Run(1, "source", "prepared", null, null, 0,
                null, null, "COMPLETED", java.util.List.of()));
        String page = Files.readString(new File(folder, "index.html").toPath());
        assertTrue(page.contains(ReportAssets.STYLE_LINK));
        assertFalse(page.contains("<style"));
        assertTrue(new File(folder, ReportAssets.STYLE_FILE).isFile());
    }

    /** A syntax error would stop every script of every new report, so the assembled file is parsed when Node is there. */
    @Test
    public void assembledScriptParses() throws Exception {
        File folder = temporary.newFolder("syntax");
        ReportAssets.writeReportFiles(folder);
        String node = System.getenv("NODE_EXECUTABLE");
        if (node == null || node.isBlank()) node = "node";
        File log = temporary.newFile("node-check.log");
        Process process;
        try {
            process = new ProcessBuilder(node, "--check", new File(folder, ReportAssets.SCRIPT_FILE).getPath())
                    .redirectErrorStream(true).redirectOutput(log).start();
        } catch (IOException unavailable) {
            Assume.assumeNoException("Node is not installed", unavailable);
            return;
        }
        try {
            assertTrue("The syntax check timed out", process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(Files.readString(log.toPath()), 0, process.exitValue());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
