package eu.startales.spacepixels.preparation;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.Assert.*;

/** Cleanup removes only owned working trees and preserves final products, metadata and neighbouring data. */
public class PreparationCleanupTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** Final exposures, optional stack and root-level run records survive removal of the private working copies. */
    @Test public void removesOnlyOwnedWorkingTrees() throws Exception {
        Path root = temporary.newFolder("spacepixels_prepared_cleanup").toPath();
        for (String tree : List.of("input/lights", "converted/lights", "masters")) {
            Path folder = Files.createDirectories(root.resolve(tree));
            Files.writeString(folder.resolve("working.fit"), "temporary");
        }
        List<String> retained = List.of("aligned/frame_000001.fit", "reference/reference.fit", "convert.ssf", "prepare.ssf",
                "preparation.json", "preparation.log", "siril.cfg");
        for (String name : retained) {
            Path file = root.resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file, "keep " + name);
        }
        Path original = temporary.newFile("observer-original.fit").toPath();
        Files.writeString(original, "untouched original");
        FramePreparationService.removeWorkingFiles(root);
        for (String tree : List.of("input", "converted", "masters")) assertFalse(Files.exists(root.resolve(tree)));
        for (String name : retained) assertEquals("keep " + name, Files.readString(root.resolve(name)));
        assertEquals("untouched original", Files.readString(original));
        FramePreparationService.removeWorkingFiles(root); // An already-cleaned run remains a safe no-op.
    }

    /** An arbitrary user directory cannot become a cleanup root, even when its child has a working-directory name. */
    @Test public void refusesDirectoriesNotOwnedByPreparation() throws Exception {
        Path root = temporary.newFolder("observations").toPath();
        Path input = Files.createDirectories(root.resolve("input"));
        Path original = Files.writeString(input.resolve("source.fit"), "original");
        assertThrows(java.io.IOException.class, () -> FramePreparationService.removeWorkingFiles(root));
        assertEquals("original", Files.readString(original));
    }
}
