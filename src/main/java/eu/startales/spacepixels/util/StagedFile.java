/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Replaces a file only once its new contents are complete. The contents are written to a temporary file beside the
 * target; {@link #publish()} then moves it over the target in one step, and {@link #close()} removes it when it was
 * not published. A failed write or replacement therefore leaves the previous file as it was.
 *
 * <pre>
 * try (StagedFile staged = new StagedFile(target)) {
 *     ... write the new contents to staged.path() ...
 *     staged.publish();
 * }
 * </pre>
 */
public final class StagedFile implements AutoCloseable {

    /** Writes the complete new contents to the temporary file. */
    @FunctionalInterface
    public interface Content<E extends Exception> {
        void writeTo(Path staged) throws IOException, E;
    }

    private final Path target;
    private final Path temporary;
    private boolean published;

    /** Creates the temporary file in the target's folder, which must exist. */
    public StagedFile(File target) throws IOException {
        this.target = target.toPath().toAbsolutePath().normalize();
        // Named after the target and ending in .tmp, so a leftover is recognisable and never imported as an image.
        temporary = Files.createTempFile(this.target.getParent(), "." + this.target.getFileName() + ".", ".tmp");
    }

    /** Writes a file in one call: stages the contents, then replaces the target. */
    public static <E extends Exception> void write(File target, Content<E> content) throws IOException, E {
        try (StagedFile staged = new StagedFile(target)) {
            content.writeTo(staged.path());
            staged.publish();
        }
    }

    /** Where the new contents are written. */
    public Path path() {
        return temporary;
    }

    /** A UTF-8 writer for the new contents; close it before {@link #publish()}. */
    public BufferedWriter newWriter() throws IOException {
        return Files.newBufferedWriter(temporary, StandardCharsets.UTF_8);
    }

    /** Writes the complete new contents as UTF-8 text. */
    public void writeString(String contents) throws IOException {
        Files.writeString(temporary, contents, StandardCharsets.UTF_8);
    }

    /** Moves the new contents over the target. When this fails, the target keeps its previous contents. */
    public void publish() throws IOException {
        try {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                replaceKeepingBackup(temporary, target);
            }
        } catch (IOException e) {
            throw new IOException("Cannot replace " + target.getFileName() + ": " + reason(e), e);
        }
        published = true;
    }

    @Override
    public void close() throws IOException {
        if (!published) {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * The replacement for a file system that cannot move atomically: the previous file is renamed aside and put back
     * when the new one cannot take its place, so the only copy is never deleted first.
     */
    static void replaceKeepingBackup(Path replacement, Path target) throws IOException {
        if (!Files.exists(target)) {
            Files.move(replacement, target);
            return;
        }
        Path backup = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".previous");
        try {
            Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(backup);
            throw e;
        }
        try {
            Files.move(replacement, target);
        } catch (IOException e) {
            try {
                Files.move(backup, target);
            } catch (IOException restore) {
                throw new IOException(reason(e) + "; the previous file is kept as " + backup.getFileName(), e);
            }
            throw e;
        }
        Files.deleteIfExists(backup);
    }

    private static String reason(IOException e) {
        if (e instanceof AccessDeniedException) {
            return "access denied";
        }
        if (e instanceof FileSystemException fileSystemException && fileSystemException.getReason() != null) {
            return fileSystemException.getReason();
        }
        return e.getMessage();
    }
}
