package eu.startales.spacepixels.report;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class AtomicReportFileTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void failedRenderPreservesThePreviousReport() throws Exception {
        File report = temporary.newFile("report.html"); Files.writeString(report.toPath(), "complete old report");
        try (var output = new AtomicReportFile(report)) { output.writer().print("incomplete new report"); }
        assertEquals("complete old report", Files.readString(report.toPath()));
        assertEquals(1, temporary.getRoot().list().length);
    }

    @Test public void commitPublishesCompleteUtf8Output() throws Exception {
        File report = temporary.newFile("report.html");
        try (var output = new AtomicReportFile(report)) { output.writer().print("Δmag ≥ 0.3"); output.commit(); }
        assertEquals("Δmag ≥ 0.3", Files.readString(report.toPath()));
    }
}
