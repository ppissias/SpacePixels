package eu.startales.spacepixels.util;

import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

/** Interactive decisions only; import services own file access and conversions on their caller's worker. */
public interface FitsImportInteraction {
    int YES = 0;

    int confirm(String message, String title);
    int choose(String message, String title, String[] options);
    void error(String message, String title);
    Progress progress(String title);

    interface Progress extends TransientEngineProgressListener, AutoCloseable {
        @Override void close();
    }
}
