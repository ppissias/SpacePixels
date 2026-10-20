package eu.startales.spacepixels.util;

import eu.startales.spacepixels.util.reporting.DetectionReportGenerator;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The folders of report runs, the newest report beside a session, and telling a cancel from a failure. */
public class OutputFoldersTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void runsStartedInTheSameSecondGetFoldersOfTheirOwn() {
        File first = OutputFolders.createNew(folder.getRoot(), "detections_");
        File second = OutputFolders.createNew(folder.getRoot(), "detections_");
        File third = OutputFolders.createNew(folder.getRoot(), "detections_");
        assertTrue(first.isDirectory() && second.isDirectory() && third.isDirectory());
        assertNotEquals(first, second);
        assertNotEquals(second, third);
        assertTrue(first.getName().startsWith("detections_"));
    }

    @Test
    public void anUnfinishedFolderIsRemovedWithEverythingInIt() throws IOException {
        File run = OutputFolders.createNew(folder.getRoot(), "detections_");
        File frames = new File(run, "frames");
        assertTrue(frames.mkdirs());
        Files.writeString(new File(frames, "U1.png").toPath(), "png");
        Files.writeString(new File(run, "report-data.json").toPath(), "{}");
        File other = folder.newFolder("detections_other");

        OutputFolders.deleteUnfinished(run);

        assertFalse(run.exists());
        assertTrue("another run's folder stays", other.isDirectory());
    }

    @Test
    public void theLatestReportIsTheNewestFinishedPage() throws IOException {
        assertNull(SessionReports.latest(folder.getRoot()));
        File older = page(folder.newFolder("detections_20261019_100000"), DetectionReportGenerator.detectionReportName, 1_000_000L);
        File iterative = page(folder.newFolder("iterative_detections_20261019_110000"), "index.html", 3_000_000L);
        page(folder.newFolder("detections_20261019_120000"), DetectionReportGenerator.detectionReportName, 2_000_000L);
        // Cancelled or failed: no page, so not a report.
        folder.newFolder("detections_20261020_090000");
        page(folder.newFolder("other"), "index.html", 9_000_000L);

        assertEquals(iterative, SessionReports.latest(folder.getRoot()));
        assertTrue(SessionReports.isIterative(iterative));
        assertFalse(SessionReports.isIterative(older));
    }

    @Test
    public void aCancelIsRecognisedAlsoWhenAnotherExceptionWrapsIt() {
        assertTrue(Cancellation.isCancellation(new CancellationException()));
        assertTrue(Cancellation.isCancellation(new RuntimeException(new java.util.concurrent.ExecutionException(new CancellationException()))));
        assertFalse(Cancellation.isCancellation(new IOException("disk full")));
        assertFalse(Cancellation.isCancellation(null));
    }

    private static File page(File parent, String name, long modified) throws IOException {
        File page = new File(parent, name);
        Files.writeString(page.toPath(), "<html></html>");
        assertTrue(page.setLastModified(modified));
        return page;
    }
}
