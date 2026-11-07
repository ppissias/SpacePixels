package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.FitsFileInformation;
import javax.swing.Action;
import eu.startales.spacepixels.detection.RunPreviewSink;

/** The imported frames and the Overview commands that share the main panel's actions. */
interface FrameList {
    /** The session's frames, or null before an import. */
    FitsFileInformation[] getImportedFiles();

    /** The table selection, which may be empty. */
    FitsFileInformation[] getSelectedFilesInformation();

    /** The single selected frame, or null. */
    FitsFileInformation getSelectedFileInformation();

    /** Re-evaluates every command from the current facts. */
    void refreshCommands();

    /** Detect Moving Targets, shared with the Overview. */
    Action detectAction();

    /** Preview Frame, shared with the Overview. */
    Action previewAction();

    /** The Overview's Auto-Tune action, installed by the settings panel. */
    void setAutoTuneCommand(Runnable openOverviewAndRunAutoTune);

    /** A guarded sink for the next admitted job's live pictures; obtained on the EDT before starting the job. */
    RunPreviewSink previewSink();
}
