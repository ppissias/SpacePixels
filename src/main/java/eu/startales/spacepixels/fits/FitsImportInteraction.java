package eu.startales.spacepixels.fits;

import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

/** Interactive decisions only; import services own file access and conversions on their caller's worker. */
public interface FitsImportInteraction {
    int YES = 0;

    int confirm(String message, String title);
    int choose(String message, String title, String[] options);
    void error(String message, String title);
    Progress progress(String title);

    /** What the observer chose for raw colour exposures: Bayer mosaics stored in one image plane. */
    enum RawColourChoice {
        /** Import nothing; the window opens its preparation on the folder. */
        PREPARE,
        /** Import the mosaics as monochrome frames, as before. */
        IMPORT_AS_MONOCHROME,
        /** Import nothing. */
        CANCEL
    }

    /**
     * Asks what to do with raw colour exposures in {@code folder}, on the import worker. Adapters without a
     * preparation window cancel the import.
     */
    default RawColourChoice rawColour(BayerMosaic mosaic, java.io.File folder) { return RawColourChoice.CANCEL; }

    /**
     * Worker-side capability check for the admitted frames; ordinary/headless import adapters do not offer external
     * alignment.
     */
    default boolean canAlignFrames(FitsFileInformation[] frames) { return false; }

    /**
     * Aligns admitted single-plane frames on the import worker and returns the new folder; raw colour frames are
     * debayered first. Never edits source frames.
     */
    default java.io.File alignFrames(FitsFileInformation[] frames) throws Exception {
        throw new UnsupportedOperationException("Siril alignment is unavailable.");
    }

    interface Progress extends TransientEngineProgressListener, AutoCloseable {
        @Override void close();
    }
}
