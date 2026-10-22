package eu.startales.spacepixels.session;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A session's pending writes and UI acknowledgements, separate from the application-owned I/O queue. */
public final class SessionPersistence {
    @FunctionalInterface public interface Write { void run() throws IOException; }

    private final SessionStorage storage;
    private final Executor ui;
    private final Set<CompletableFuture<Void>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean active = true;

    public SessionPersistence(SessionStorage storage, Executor ui) {
        this.storage = storage;
        this.ui = ui;
    }

    public void save(Write write, Consumer<Throwable> finished) {
        if (!active) throw new IllegalStateException("Session is closed.");
        CompletableFuture<Void> acknowledged = new CompletableFuture<>();
        pending.add(acknowledged);
        storage.submit(() -> { write.run(); return null; }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                storage.recordWriteFailure(failure);
                java.util.logging.Logger.getLogger(SessionPersistence.class.getName()).log(
                        java.util.logging.Level.WARNING, "Could not save session metadata", failure);
            }
            try {
                ui.execute(() -> {
                    Throwable problem = failure;
                    try {
                        if (active) finished.accept(problem);
                    } catch (Throwable callbackFailure) {
                        problem = callbackFailure;
                    } finally {
                        pending.remove(acknowledged);
                        if (problem == null) acknowledged.complete(null); else acknowledged.completeExceptionally(problem);
                    }
                });
            } catch (RuntimeException dispatchFailure) {
                pending.remove(acknowledged);
                acknowledged.completeExceptionally(dispatchFailure);
            }
        });
    }

    /** Worker-side barrier: a run sees the saved state, or fails rather than silently reading an older correction. */
    public void awaitWrites() throws IOException, InterruptedException {
        awaitWrites(() -> false);
    }

    public void awaitWrites(BooleanSupplier cancelled) throws IOException, InterruptedException {
        CompletableFuture<Void> barrier = CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
        try {
            while (true) {
                if (cancelled.getAsBoolean()) throw new CancellationException("Cancelled while waiting for session saves.");
                try {
                    barrier.get(100, TimeUnit.MILLISECONDS);
                    return;
                } catch (TimeoutException waiting) {
                }
            }
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error error) throw error;
            throw new IOException("Could not save session metadata.", cause);
        }
    }

    public SessionStorage storage() { return storage; }
    public void close() { active = false; }
}
