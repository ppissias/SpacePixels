package eu.startales.spacepixels.session;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Application-owned, ordered session I/O. Reads are queued behind earlier writes, including across reimports. */
public final class SessionStorage implements AutoCloseable {
    private final SessionMetadataStore store;
    private volatile Throwable writeFailure;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> new Thread(action, "SpacePixels-session-io"));

    public SessionStorage() { this(SessionMetadataStore.filesystem()); }

    public SessionStorage(SessionMetadataStore store) { this.store = Objects.requireNonNull(store); }

    public SessionMetadataStore store() { return store; }

    void recordWriteFailure(Throwable failure) { writeFailure = failure; }
    public Throwable getWriteFailure() { return writeFailure; }

    public <Value> CompletableFuture<Value> submit(Callable<Value> action) {
        CompletableFuture<Value> result = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                try {
                    result.complete(action.call());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException rejected) {
            result.completeExceptionally(rejected);
        }
        return result;
    }

    /** Stops accepting work without waiting on the caller (which can be the EDT). */
    @Override public void close() { worker.shutdown(); }

    /** Joins pending writes on a non-UI thread during application shutdown. */
    public void finish() throws IOException, InterruptedException {
        close();
        if (!worker.awaitTermination(30, TimeUnit.SECONDS)) throw new IOException("Session metadata writes have not finished.");
    }
}
