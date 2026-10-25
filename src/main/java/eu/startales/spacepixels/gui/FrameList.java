package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.FitsFileInformation;
import javax.swing.Action;

/** The imported frames and the Overview commands that share the main panel's actions. */
interface FrameList {
    /** The session's frames, or null before an import. */
    FitsFileInformation[] getImportedFiles();

    /** The table selection, which may be empty. */
    FitsFileInformation[] getSelectedFilesInformation();

    /** The single selected frame, or null. */
    FitsFileInformation getSelectedFileInformation();

    /** Selection index and row count, or {@code {-1, 0}} when nothing is selected. */
    int[] frameSelection();

    /** Moves the selection by {@code delta} rows. */
    void selectFrameOffset(int delta);

    /** Selects the first frame when the table has rows and none is selected. */
    void selectFirstFileIfNoneSelected();

    /** Re-evaluates every command from the current facts. */
    void refreshCommands();

    /** Detect Moving Targets, shared with the Overview. */
    Action detectAction();

    /** Preview Frame, shared with the Overview. */
    Action previewAction();

    /** The Overview's Auto-Tune action, installed by the settings panel. */
    void setAutoTuneCommand(Runnable openOverviewAndRunAutoTune);
}
