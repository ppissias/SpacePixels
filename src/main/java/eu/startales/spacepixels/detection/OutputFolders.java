/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.detection;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import eu.startales.spacepixels.util.DebugOutput;

/**
 * The folders a run writes its report into. Every run gets a folder of its own, so a run that is stopped can remove
 * the folder it was writing without touching another run's output.
 */
public final class OutputFolders {

    private static final Logger LOGGER = Logger.getLogger(OutputFolders.class.getName());


    private OutputFolders() {
    }

    /**
     * Creates {@code prefix} plus the current time in {@code parent}, for example {@code detections_20261020_204815}.
     * When a folder of that name exists (two runs in one second), a number is added: {@code ..._204815_2}.
     */
    static File createNew(File parent, String prefix) {
        try { return eu.startales.spacepixels.util.RunDirectories.create(parent.toPath(), prefix).toFile(); }
        catch (IOException failure) { throw new UncheckedIOException("Could not create the output folder.", failure); }
    }

    /**
     * Creates a new timestamped {@code detections_} folder beside {@code anyFitsFile} (in the working directory when
     * the file has no parent) and returns it. It is never the folder of another run, even when two runs start in the
     * same second.
     */
    public static File createDetectionsDirectory(File anyFitsFile) {
        File parentDir = anyFitsFile.getParentFile();
        if (parentDir == null) {
            parentDir = new File(System.getProperty("user.dir"));
        }
        File detectionsDir = createNew(parentDir, "detections_");
        DebugOutput.println("Created new export directory at: " + detectionsDir.getAbsolutePath());
        return detectionsDir;
    }

    /** Removes a folder a stopped run created and did not finish, with everything in it. A failure is only reported. */
    public static void deleteUnfinished(File folder) {
        if (folder == null || !folder.isDirectory()) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder.toPath())) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | UncheckedIOException e) {
            LOGGER.log(Level.WARNING, "Could not remove the unfinished folder " + folder.getAbsolutePath(), e);
        }
    }
}
