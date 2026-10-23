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
 * The package boundaries described in DEVELOPER.md ("Principles" and "The packages"): the jobs and the
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

    @Test
    public void sessionStateDoesNotDependOnSwingOrTheWindow() throws IOException {
        assertNoReferences("session", "eu.startales.spacepixels.gui.", "javax.swing.", "java.awt.");
    }

    @Test
    public void settingsStorageDoesNotDependOnSwingOrTheWindow() throws IOException {
        for (String file : List.of("SettingsStorage", "ApplicationSettings")) {
            assertNoReferences("config/" + file + ".java", "eu.startales.spacepixels.gui.", "javax.swing.", "java.awt.");
        }
    }

    @Test
    public void fitsImportAndBatchServicesDoNotDependOnSwingOrTheWindow() throws IOException {
        for (String file : List.of("FitsImportService", "FitsBatchService", "FitsImportInteraction", "FitsFiles", "FitsImages",
                "FitsPreparationService", "DetectionInputPreparation", "FitsSequenceValidator", "FitsFormatChecker",
                "IterativeDetectionPipelineService", "IterativeDetectionRequest", "IterativeDetectionPlan")) {
            assertNoReferences("util/" + file + ".java", "eu.startales.spacepixels.gui.", "javax.swing.", "java.awt.");
        }
    }

    /** Keeps all processing and reporting utilities window-free while allowing headless AWT rendering. */
    @Test
    public void processingUtilitiesDoNotDependOnSwingOrTheWindow() throws IOException {
        assertNoReferences("util", "eu.startales.spacepixels.gui.", "javax.swing.");
    }

    /** Keeps every published headless package independent of desktop workflows as well as Swing. */
    @Test
    public void headlessPackagesDoNotDependOnDesktopCode() throws IOException {
        for (String packageName : List.of("api", "config", "session", "util", "tools")) {
            assertNoReferences(packageName, "eu.startales.spacepixels.gui.", "eu.startales.spacepixels.tasks.",
                    "eu.startales.spacepixels.jobs.", "javax.swing.");
        }
    }

    /** Scans Java sources recursively for imports and qualified references across the selected boundary. */
    private static void assertNoReferences(String packageName, String... forbidden) throws IOException {
        Path directory = SOURCES.resolve(packageName);
        assertTrue("Run from the project folder: " + directory.toAbsolutePath(), Files.exists(directory));
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.isDirectory(directory) ? Files.walk(directory) : Stream.of(directory)) {
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
