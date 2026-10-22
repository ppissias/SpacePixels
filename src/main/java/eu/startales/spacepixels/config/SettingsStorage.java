package eu.startales.spacepixels.config;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class SettingsStorage implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action ->
            new Thread(action, "SpacePixels-settings-io"));
    private final Map<String, Throwable> failures = new ConcurrentHashMap<>();

    public <Value> CompletableFuture<Value> submit(String target, Callable<Value> action) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(action);
        CompletableFuture<Value> result = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                try {
                    Value value = action.call();
                    failures.remove(target);
                    result.complete(value);
                } catch (Throwable failure) {
                    failures.put(target, failure);
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException rejected) {
            failures.put(target, rejected);
            result.completeExceptionally(rejected);
        }
        return result;
    }

    public Map<String, Throwable> getFailures() { return Map.copyOf(failures); }

    @Override public void close() { worker.shutdown(); }

    public void finish() throws IOException, InterruptedException {
        close();
        if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
            throw new IOException("Settings writes have not finished.");
        }
    }
}
