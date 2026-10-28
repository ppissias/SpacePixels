package eu.startales.spacepixels.util;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Creates owned run directories with a local timestamp and atomic, numbered collision handling. */
public final class RunDirectories {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** Stateless naming shared by reports and frame preparation. */
    private RunDirectories() { }

    /** Creates prefix + current local date/time; an existing name receives _2, _3, and so on, without reuse. */
    public static Path create(Path parent, String prefix) throws IOException {
        String name = prefix + LocalDateTime.now().format(TIMESTAMP);
        for (int number = 1; ; number++) {
            Path candidate = parent.resolve(name + (number == 1 ? "" : "_" + number));
            try { return Files.createDirectory(candidate); }
            catch (FileAlreadyExistsException collision) { /* Only name collisions are retried. */ }
        }
    }
}
