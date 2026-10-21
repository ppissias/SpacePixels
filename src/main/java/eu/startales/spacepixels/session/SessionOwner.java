package eu.startales.spacepixels.session;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/** UI-thread session handover and result admission, independent of Swing and the main window. */
public final class SessionOwner implements AutoCloseable {
    private volatile ProcessingSession current;
    private boolean closed;

    public ProcessingSession current() { return current; }

    public boolean isCurrent(Object session) {
        return !closed && current == session && (current == null || !current.isClosed());
    }

    public boolean applyIfCurrent(Object session, Runnable update) {
        if (!isCurrent(session)) return false;
        update.run();
        return true;
    }

    /** Transfers ownership only if the import still belongs to the expected previous session. */
    public boolean replace(Object expected, ProcessingSession replacement) {
        Objects.requireNonNull(replacement);
        if (replacement.isClosed()) throw new IllegalArgumentException("Cannot install a closed session.");
        if (!isCurrent(expected)) {
            replacement.close();
            return false;
        }
        ProcessingSession previous = current;
        current = replacement;
        if (previous != null && previous != replacement) {
            try {
                previous.close();
            } catch (RuntimeException failure) {
                Logger.getLogger(SessionOwner.class.getName()).log(Level.WARNING, "Previous session cleanup failed.", failure);
            }
        }
        return true;
    }

    @Override
    public void close() {
        closed = true;
        ProcessingSession previous = current;
        current = null;
        if (previous != null) previous.close();
    }
}
