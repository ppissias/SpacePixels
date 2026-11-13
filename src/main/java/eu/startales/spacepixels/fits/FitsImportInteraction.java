package eu.startales.spacepixels.fits;

import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

/** Interactive decisions only; import services own file access and conversions on their caller's worker. */
public interface FitsImportInteraction {
    int YES = 0;

    int confirm(String message, String title);
    int choose(String message, String title, String[] options);
    void error(String message, String title);
    Progress progress(String title);

    /** Worker-side capability check; ordinary/headless import adapters do not offer external alignment. */
    default boolean canAlignFrames() { return false; }

    /** Aligns admitted monochrome frames on the import worker and returns the new folder; never edits source frames. */
    default java.io.File alignFrames(FitsFileInformation[] frames) throws Exception {
        throw new UnsupportedOperationException("Siril alignment is unavailable.");
    }

    interface Progress extends TransientEngineProgressListener, AutoCloseable {
        @Override void close();
    }
}
