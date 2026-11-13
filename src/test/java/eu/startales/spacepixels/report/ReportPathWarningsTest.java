package eu.startales.spacepixels.report;

import java.io.File;
import org.junit.Test;
import static org.junit.Assert.*;

/** Checks Windows path estimates, actual file counts and atomic report notices without changing JVM properties. */
public class ReportPathWarningsTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();

    /** The real iterative index scaffold accepts the same top notice as an ordinary report. */
    @Test public void iterativeIndexIncludesTopNotice() throws Exception {
        File root = temporary.newFolder();
        IterativeDetectionIndex.write(root, new IterativeDetectionIndex.Run(1, "input", "prepared",
                null, null, 5, null, null, "COMPLETED", java.util.List.of()));
        File index = new File(root, "index.html");
        ReportPathWarnings.addCompletedReportNote(index, "Windows path notice: move the entire report folder.");
        String html = java.nio.file.Files.readString(index.toPath());
        assertTrue(html.indexOf("Windows path notice:") > html.indexOf("<body>"));
        assertTrue(html.indexOf("Windows path notice:") < html.indexOf("<h1>"));
        assertTrue(html.contains("Outcome: Completed"));
    }

    /** Completed notices count real files rather than hypothetical animation assets. */
    @Test public void completedNoticeCountsActualLongFilesAndWritesTopNote() throws Exception {
        java.nio.file.Path root = temporary.newFolder().toPath();
        java.nio.file.Path nested = root;
        while (nested.toAbsolutePath().toString().length() < 245) {
            nested = java.nio.file.Files.createDirectory(nested.resolve("nested_folder"));
        }
        java.nio.file.Files.writeString(nested.resolve("animation_long_name.gif.sheet-123.png"), "asset");
        java.nio.file.Files.writeString(nested.resolve("animation_long_name.gif.poster.png"), "asset");
        java.nio.file.Files.writeString(nested.resolve("linked_measurements_long_name.csv"), "data");
        File report = root.resolve("detection_report.html").toFile();
        java.nio.file.Files.writeString(report.toPath(), "<html><body class='report'><h1>Results</h1></body></html>");
        String warning = ReportPathWarnings.afterDetection(report, "Windows 11");
        assertNotNull(warning);
        assertTrue(warning.contains("2 pictures and 1 other report file"));
        assertNull(ReportPathWarnings.afterDetection(report, "Linux"));
        ReportPathWarnings.addCompletedReportNote(report, warning);
        String html = java.nio.file.Files.readString(report.toPath());
        assertTrue(html.indexOf("Windows path notice") < html.indexOf("<h1>"));
        assertTrue(html.contains("<h1>Results</h1>"));
    }

    /** A short completed report has no warning even when the planning bound would be larger. */
    @Test public void completedShortOutputIsSilent() throws Exception {
        File report = temporary.newFile("detection_report.html");
        assertNull(ReportPathWarnings.afterDetection(report, "Windows 11"));
    }

    /** Preflight describes a future report rather than claiming that it already exists. */
    @Test public void preflightUsesFutureTense() {
        String warning = ReportPathWarnings.beforeDetection(new File("x".repeat(240)), false, "Windows 11");
        assertNotNull(warning);
        assertFalse(warning.contains("this completed report"));
        assertTrue(warning.contains("after completion"));
    }
    /** Non-Windows platforms do not receive Windows shell advice, regardless of path length. */
    @Test public void nonWindowsIsSilent() {
        assertNull(ReportPathWarnings.beforeDetection(new File("x".repeat(300)), true, "Linux"));
        assertNull(ReportPathWarnings.beforeDetection(new File("x".repeat(300)), false, "Mac OS X"));
    }

    /** Short Windows output paths remain below the advisory threshold. */
    @Test public void shortOutputIsSilent() {
        assertNull(ReportPathWarnings.outputDirectoryWarning(new File("short"), false, "Windows 11"));
    }

    /** The shell limit counts the complete asset path and includes the terminating NUL. */
    @Test public void exactThresholdIncludesAssetSuffix() {
        int overhead = new File("x", ReportPathWarnings.MAX_RELATIVE_ASSET).getAbsolutePath().length() - 1;
        File below = new File("x".repeat(259 - overhead));
        File at = new File("x".repeat(260 - overhead));
        assertNull(ReportPathWarnings.outputDirectoryWarning(below, false, "Windows 11"));
        String warning = ReportPathWarnings.outputDirectoryWarning(at, false, "Windows 11");
        assertNotNull(warning);
        assertTrue(warning.contains("260 characters"));
        assertTrue(warning.contains("entire report folder"));
        assertTrue(warning.contains("does not indicate a detection failure"));
    }

    /** Iterative estimates reserve the extra pass directory even before any frames or output exist. */
    @Test public void iterativeReservesPassAndLongerRoot() {
        int overhead = new File("x", ReportPathWarnings.MAX_RELATIVE_ASSET).getAbsolutePath().length() - 1;
        File dataset = new File("x".repeat(200 - overhead));
        assertNull(ReportPathWarnings.beforeDetection(dataset, false, "Windows 11"));
        assertNotNull(ReportPathWarnings.beforeDetection(dataset, true, "Windows 11"));
    }

    /** The bound covers the actual longest animation name including large IDs and paginated sheets. */
    @Test public void reservesCompleteAnimationSheetName() {
        String asset = "moving_targets/moving_track_2147483647_object_centric.gif.sheet-2147483647.png";
        assertTrue(ReportPathWarnings.MAX_RELATIVE_ASSET.length() >= asset.length());
        assertTrue(ReportPathWarnings.MAX_RELATIVE_ASSET.length()
                >= "local_activity/local_activity_2147483647_context.gif.sheet-2147483647.png".length());
    }
}
