/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The folders a run writes its report into. Every run gets a folder of its own, so a run that is stopped can remove
 * the folder it was writing without touching another run's output.
 */
final class OutputFolders {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private OutputFolders() {
    }

    /**
     * Creates {@code prefix} plus the current time in {@code parent}, for example {@code detections_20261020_204815}.
     * When a folder of that name exists (two runs in one second), a number is added: {@code ..._204815_2}.
     */
    static File createNew(File parent, String prefix) {
        String name = prefix + LocalDateTime.now().format(TIMESTAMP);
        File folder = new File(parent, name);
        for (int number = 2; folder.exists(); number++) {
            folder = new File(parent, name + "_" + number);
        }
        if (!folder.mkdirs()) {
            System.err.println("Could not create the output folder " + folder.getAbsolutePath() + ". Check the folder permissions.");
        }
        return folder;
    }

    /**
     * Creates a new timestamped {@code detections_} folder beside {@code anyFitsFile} (in the working directory when
     * the file has no parent) and returns it. It is never the folder of another run, even when two runs start in the
     * same second.
     */
    static File createDetectionsDirectory(File anyFitsFile) {
        File parentDir = anyFitsFile.getParentFile();
        if (parentDir == null) {
            parentDir = new File(System.getProperty("user.dir"));
        }
        File detectionsDir = createNew(parentDir, "detections_");
        DebugOutput.println("Created new export directory at: " + detectionsDir.getAbsolutePath());
        return detectionsDir;
    }

    /** Removes a folder a stopped run created and did not finish, with everything in it. A failure is only reported. */
    static void deleteUnfinished(File folder) {
        if (folder == null || !folder.isDirectory()) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder.toPath())) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | UncheckedIOException e) {
            System.err.println("Could not remove the unfinished folder " + folder.getAbsolutePath() + ": " + e.getMessage());
        }
    }
}
