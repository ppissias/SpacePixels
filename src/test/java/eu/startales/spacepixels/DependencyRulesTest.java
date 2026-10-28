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
    public void applicationStorageAndSettingsDoNotDependOnSwingOrTheWindow() throws IOException {
        assertNoReferences("session/ApplicationStorage.java", "eu.startales.spacepixels.gui.", "javax.swing.", "java.awt.");
        assertNoReferences("config/ApplicationSettings.java", "eu.startales.spacepixels.gui.", "javax.swing.", "java.awt.");
    }

    @Test
    public void fitsImportAndBatchServicesDoNotDependOnSwingOrTheWindow() throws IOException {
        for (String file : List.of("fits/FitsImportService.java", "fits/FitsBatchService.java", "fits/FitsImportInteraction.java",
                "fits/FitsInput.java", "fits/FitsPreparation.java", "detection/DetectionInputPreparation.java",
                "detection/IterativeDetectionPipelineService.java", "detection/IterativeDetectionRequest.java",
                "detection/IterativeDetectionPlan.java")) {
            assertNoReferences(file, "eu.startales.spacepixels.gui.", "javax.swing.", "java.awt.");
        }
    }

    /** Settings are values and their files; the renderers take them as parameters, so settings need no report code. */
    @Test
    public void settingsDoNotDependOnReporting() throws IOException {
        assertNoReferences("config", "eu.startales.spacepixels.report.");
    }

    /** Keeps all processing and reporting utilities window-free while allowing headless AWT rendering. */
    @Test
    public void processingUtilitiesDoNotDependOnSwingOrTheWindow() throws IOException {
        for (String pkg : List.of("fits", "detection", "platesolve", "report", "skycatalog", "util", "preparation")) {
            assertNoReferences(pkg, "eu.startales.spacepixels.gui.", "javax.swing.");
        }
    }

    /** Processing returns values to the session instead of owning or mutating session state. */
    @Test
    public void processingUtilitiesDoNotDependOnSessionState() throws IOException {
        for (String pkg : List.of("fits", "detection", "platesolve", "report", "skycatalog", "util", "preparation")) {
            assertNoReferences(pkg, "eu.startales.spacepixels.session.");
        }
    }

    /** Keeps every published headless package independent of desktop workflows as well as Swing. */
    @Test
    public void headlessPackagesDoNotDependOnDesktopCode() throws IOException {
        for (String packageName : List.of("api", "config", "session", "util", "tools",
                "fits", "detection", "platesolve", "report", "skycatalog", "preparation")) {
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
