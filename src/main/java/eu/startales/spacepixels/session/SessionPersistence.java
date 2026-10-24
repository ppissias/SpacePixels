package eu.startales.spacepixels.session;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A session's writes: runs them on the application-owned session writer and tells the window when each is done,
 * unless the session has closed by then.
 */
public final class SessionPersistence {
    @FunctionalInterface public interface Write { void run() throws IOException; }

    private final SessionStorage storage;
    private final Executor ui;
    private volatile boolean active = true;

    public SessionPersistence(SessionStorage storage, Executor ui) {
        this.storage = storage;
        this.ui = ui;
    }

    /**
     * Runs {@code write} on the session writer, then tells {@code finished} on the UI executor, with the failure or
     * null, unless the session has closed meanwhile. A failure is also logged and recorded on the storage.
     */
    public void save(Write write, Consumer<Throwable> finished) {
        if (!active) throw new IllegalStateException("Session is closed.");
        storage.submit(() -> { write.run(); return null; }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                storage.recordWriteFailure(failure);
                java.util.logging.Logger.getLogger(SessionPersistence.class.getName()).log(
                        java.util.logging.Level.WARNING, "Could not save session metadata", failure);
            }
            try {
                ui.execute(() -> {
                    if (active) finished.accept(failure);
                });
            } catch (RuntimeException windowGone) {
                // The UI executor no longer runs work (the application is closing): nobody is left to tell.
            }
        });
    }

    public SessionStorage storage() { return storage; }
    public void close() { active = false; }
}
