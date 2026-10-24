package eu.startales.spacepixels.util;

import java.io.File;

/** An accepted preparation step redirects the import to its newly written directory. */
public final class RedirectImportException extends Exception {
    private final File newDirectory;

    /** Carries the replacement directory that the caller should import next. */
    public RedirectImportException(File newDirectory) {
        super("Redirecting import to new directory");
        this.newDirectory = newDirectory;
    }

    /** Returns the directory that replaces the current import target. */
    public File getNewDirectory() {
        return newDirectory;
    }
}
