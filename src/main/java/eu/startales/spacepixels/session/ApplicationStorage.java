package eu.startales.spacepixels.session;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The application's single queue for settings and session files. Metadata reads share the queue with writes, so a
 * reimport sees earlier accepted edits. Settings failures are cleared by a successful retry of the same target;
 * a session write failure remains recorded for the existing shutdown warning, even after a successful retry.
 * Closing rejects new work and drains accepted work; only a non-UI shutdown worker calls {@link #finish()}.
 */
public final class ApplicationStorage implements AutoCloseable {
    private final SessionMetadataStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action ->
            new Thread(action, "SpacePixels-file-io"));
    private final Map<String, Throwable> settingsFailures = new ConcurrentHashMap<>();
    private volatile Throwable sessionWriteFailure;

    /** Uses the filesystem metadata store. */
    public ApplicationStorage() { this(SessionMetadataStore.filesystem()); }

    /** Uses the supplied metadata store, including the delayed/failing stores of the I/O contract tests. */
    public ApplicationStorage(SessionMetadataStore store) { this.store = Objects.requireNonNull(store); }

    /** The metadata store; callers access it only from queued work. */
    public SessionMetadataStore store() { return store; }

    /** Records a session write failure without clearing an earlier failure after later successful work. */
    void recordSessionWriteFailure(Throwable failure) { sessionWriteFailure = Objects.requireNonNull(failure); }

    /** The most recent session write failure, retained until the application closes, or null. */
    public Throwable getSessionWriteFailure() { return sessionWriteFailure; }

    /** A detached view of settings failures by target; successful same-target retries remove their entry. */
    public Map<String, Throwable> getSettingsFailures() { return Map.copyOf(settingsFailures); }

    /**
     * Queues a metadata read or session write behind accepted work. Failure is returned to the caller; a session
     * owner also records write failures with {@link #recordSessionWriteFailure(Throwable)}. Rejection completes the future
     * exceptionally without running the action.
     */
    public <Value> CompletableFuture<Value> submit(Callable<Value> action) {
        return enqueue(null, action);
    }

    /**
     * Queues a settings write, recording failure for this target before completing its future. Only success of this
     * target clears its failure, including when another settings or session operation succeeds in between.
     */
    public <Value> CompletableFuture<Value> submit(String target, Callable<Value> action) {
        return enqueue(Objects.requireNonNull(target), action);
    }

    /** Runs one action on the worker; a null target means its caller owns failure reporting. */
    private <Value> CompletableFuture<Value> enqueue(String target, Callable<Value> action) {
        Objects.requireNonNull(action);
        CompletableFuture<Value> result = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                try {
                    Value value = action.call();
                    if (target != null) settingsFailures.remove(target);
                    result.complete(value);
                } catch (Throwable failure) {
                    if (target != null) settingsFailures.put(target, failure);
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException rejected) {
            if (target != null) settingsFailures.put(target, rejected);
            result.completeExceptionally(rejected);
        }
        return result;
    }

    /** Stops accepting work without blocking the caller, which may be the UI thread. */
    @Override public void close() { worker.shutdown(); }

    /** Waits up to thirty seconds for accepted work on a non-UI shutdown thread; a timeout allows a later retry. */
    public void finish() throws IOException, InterruptedException {
        close();
        if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
            throw new IOException("Session metadata or settings writes have not finished.");
        }
    }
}
