package eu.startales.spacepixels.report;
import eu.startales.spacepixels.util.StagedFile;
import java.io.*;
import java.nio.file.*;

/** Publishes a text artifact only after its writer has completed without errors. */
final class AtomicReportFile implements AutoCloseable {
    private final String name;
    private final StagedFile staged;
    private final PrintWriter writer;

    AtomicReportFile(File file) throws IOException {
        name = file.getName();
        Files.createDirectories(file.toPath().toAbsolutePath().getParent());
        staged = new StagedFile(file);
        try {
            writer = new PrintWriter(staged.newWriter());
        } catch (IOException e) {
            staged.close();
            throw e;
        }
    }

    PrintWriter writer() { return writer; }

    void commit() throws IOException {
        writer.flush();
        boolean failed = writer.checkError();
        writer.close();
        if (failed || writer.checkError()) throw new IOException("Could not finish writing " + name);
        staged.publish();
    }

    @Override public void close() throws IOException {
        writer.close();
        staged.close();
    }
}
