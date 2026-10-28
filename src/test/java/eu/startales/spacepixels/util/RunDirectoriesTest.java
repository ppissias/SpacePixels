package eu.startales.spacepixels.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import static org.junit.Assert.*;

/** Run names contain the actual local date/time and never reuse another run's directory. */
public class RunDirectoriesTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** Preparation and reports share timestamp syntax and collision handling, including existing same-second names. */
    @Test public void createsDatedUniqueDirectoriesWithoutChangingEarlierRuns() throws Exception {
        String before = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        var paths = new HashSet<Path>();
        for (int i = 0; i < 5; i++) {
            Path path = RunDirectories.create(temporary.getRoot().toPath(), "spacepixels_prepared_");
            assertTrue(paths.add(path));
            assertTrue(path.getFileName().toString().matches("spacepixels_prepared_\\d{8}_\\d{6}(?:_\\d+)?"));
            java.nio.file.Files.writeString(path.resolve("owned.txt"), "run " + i);
        }
        String after = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        for (Path path : paths) {
            String name = path.getFileName().toString();
            assertTrue(name.startsWith("spacepixels_prepared_" + before) || name.startsWith("spacepixels_prepared_" + after));
            assertTrue(java.nio.file.Files.readString(path.resolve("owned.txt")).startsWith("run "));
        }
    }
}
