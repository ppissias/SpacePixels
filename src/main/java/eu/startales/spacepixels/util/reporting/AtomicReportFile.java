package eu.startales.spacepixels.util.reporting;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Publishes a text artifact only after its writer has completed without errors. */
final class AtomicReportFile implements AutoCloseable {
    private final Path target;
    private final Path temporary;
    private final PrintWriter writer;

    AtomicReportFile(File file) throws IOException {
        target = file.toPath().toAbsolutePath();
        Files.createDirectories(target.getParent());
        temporary = Files.createTempFile(target.getParent(), ".spacepixels-export-", ".tmp");
        writer = new PrintWriter(Files.newBufferedWriter(temporary, StandardCharsets.UTF_8));
    }

    PrintWriter writer() { return writer; }

    void commit() throws IOException {
        writer.flush();
        boolean failed = writer.checkError();
        writer.close();
        if (failed || writer.checkError()) throw new IOException("Could not finish writing " + target.getFileName());
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override public void close() throws IOException {
        writer.close();
        Files.deleteIfExists(temporary);
    }
}
