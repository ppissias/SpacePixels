package eu.startales.spacepixels.gui;

import java.io.File;

/** Folder import owned by the window, which installs the resulting session. */
interface FolderImport {
    /** Asks for a folder and imports it. */
    void choose();

    /** Imports {@code folder} when it is a directory. */
    void importFolder(File folder);
}
