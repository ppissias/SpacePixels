package eu.startales.spacepixels.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import static org.junit.Assert.*;

/** Checks completed-artifact output resolution without launching a browser or file manager. */
public class SessionReportsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** Only an existing completed report resolves to a real output directory. */
    @Test public void outputDirectoryRequiresAnExistingReport() throws Exception {
        File report = folder.newFile("report.html");
        assertEquals(folder.getRoot().getAbsoluteFile(), SessionReports.outputDirectory(report));
        assertNull(SessionReports.outputDirectory(null));
        assertNull(SessionReports.outputDirectory(folder.getRoot()));
        assertTrue(report.delete());
        assertNull(SessionReports.outputDirectory(report));
    }
}
