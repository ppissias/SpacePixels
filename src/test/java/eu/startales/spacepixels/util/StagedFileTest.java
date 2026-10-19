package eu.startales.spacepixels.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A file is replaced only by complete new contents, and a failure leaves the previous file and no leftovers. */
public class StagedFileTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void publishReplacesTheTarget() throws Exception {
        File target = file("settings.json", "old");
        StagedFile.write(target, staged -> Files.writeString(staged, "new", StandardCharsets.UTF_8));
        assertEquals("new", read(target));
        assertOnlyFiles("settings.json");
    }

    @Test
    public void publishCreatesAMissingTarget() throws Exception {
        File target = new File(folder.getRoot(), "catalogue.json");
        try (StagedFile staged = new StagedFile(target)) {
            staged.writeString("Δmag ≥ 0.3");
            staged.publish();
        }
        assertEquals("Δmag ≥ 0.3", read(target));
        assertOnlyFiles("catalogue.json");
    }

    @Test
    public void aFailedWriteLeavesThePreviousFile() throws Exception {
        File target = file("frame.fit", "complete old frame");
        IOException failure = assertThrows(IOException.class, () -> StagedFile.write(target, staged -> {
            Files.writeString(staged, "half a frame", StandardCharsets.UTF_8);
            throw new IOException("disk full");
        }));
        assertEquals("disk full", failure.getMessage());
        assertEquals("complete old frame", read(target));
        assertOnlyFiles("frame.fit");
    }

    @Test
    public void aFailedReplacementLeavesThePreviousContents() throws Exception {
        // A folder in the target's place cannot be replaced by a file.
        File target = folder.newFolder("frame.fit");
        Files.writeString(new File(target, "inside.txt").toPath(), "kept");
        try (StagedFile staged = new StagedFile(target)) {
            staged.writeString("new");
            IOException failure = assertThrows(IOException.class, staged::publish);
            assertTrue(failure.getMessage(), failure.getMessage().startsWith("Cannot replace frame.fit: "));
        }
        assertEquals("kept", read(new File(target, "inside.txt")));
        assertOnlyFiles("frame.fit");
    }

    @Test
    public void withoutAtomicMovesThePreviousFileIsReplacedAndItsBackupRemoved() throws Exception {
        File target = file("frame.fit", "old");
        File replacement = file("replacement.tmp", "new");
        StagedFile.replaceKeepingBackup(replacement.toPath(), target.toPath());
        assertEquals("new", read(target));
        assertOnlyFiles("frame.fit");
    }

    @Test
    public void withoutAtomicMovesThePreviousFileIsPutBackWhenTheNewOneCannotTakeItsPlace() throws Exception {
        File target = file("frame.fit", "old");
        File missing = new File(folder.getRoot(), "missing.tmp");
        assertThrows(IOException.class, () -> StagedFile.replaceKeepingBackup(missing.toPath(), target.toPath()));
        assertEquals("old", read(target));
        assertOnlyFiles("frame.fit");
    }

    private File file(String name, String contents) throws IOException {
        File file = folder.newFile(name);
        Files.writeString(file.toPath(), contents, StandardCharsets.UTF_8);
        return file;
    }

    private static String read(File file) throws IOException {
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    private void assertOnlyFiles(String... names) {
        String[] present = folder.getRoot().list();
        Arrays.sort(present);
        assertArrayEquals(names, present);
    }
}
