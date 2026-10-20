package eu.startales.spacepixels;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The boundaries the architecture review set (DESIGN-IMPROVEMENTS, "Architecture adoption review"): the jobs and the
 * tasks run without the window, so they refer to no Swing and nothing of the {@code gui} package, in imports or in
 * qualified names. A rule is added here as each boundary is reached.
 */
public class DependencyRulesTest {

    private static final Path SOURCES = Paths.get("src", "main", "java", "eu", "startales", "spacepixels");

    @Test
    public void jobsDependOnNeitherTheWindowNorTheTasks() throws IOException {
        assertNoReferences("jobs", "eu.startales.spacepixels.gui.", "eu.startales.spacepixels.tasks.", "javax.swing.", "java.awt.");
    }

    @Test
    public void tasksDoNotDependOnTheWindow() throws IOException {
        assertNoReferences("tasks", "eu.startales.spacepixels.gui.", "javax.swing.");
    }

    private static void assertNoReferences(String packageName, String... forbidden) throws IOException {
        Path directory = SOURCES.resolve(packageName);
        assertTrue("Run from the project folder: " + directory.toAbsolutePath(), Files.isDirectory(directory));
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (line.startsWith("*") || line.startsWith("/*") || line.startsWith("//")) {
                        continue;
                    }
                    for (String reference : forbidden) {
                        if (line.contains(reference)) {
                            found.add(file.getFileName() + ":" + (i + 1) + " " + line);
                        }
                    }
                }
            }
        }
        assertEquals("Forbidden references in " + packageName, List.of(), found);
    }
}
