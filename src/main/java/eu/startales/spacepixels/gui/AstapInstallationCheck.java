package eu.startales.spacepixels.gui;

import javax.swing.SwingWorker;
import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AstapInstallationCheck implements AutoCloseable {
    record Request(String path, boolean select) {}
    record Result(boolean available, String message) {}
    @FunctionalInterface interface Inspector { Result inspect(Request request) throws Exception; }

    private static final Pattern STAR_DATABASE_FILE = Pattern.compile("^([a-z]\\d\\d)_\\d{4}\\.\\w+$", Pattern.CASE_INSENSITIVE);
    private final Inspector inspector;
    private final BiConsumer<Request, Result> completed;
    private final BiConsumer<Request, Throwable> failed;
    private SwingWorker<Result, Void> active;
    private Request pending;
    private long version;
    private boolean closed;

    AstapInstallationCheck(BiConsumer<Request, Result> completed, BiConsumer<Request, Throwable> failed) {
        this(AstapInstallationCheck::inspect, completed, failed);
    }

    AstapInstallationCheck(Inspector inspector, BiConsumer<Request, Result> completed, BiConsumer<Request, Throwable> failed) {
        this.inspector = inspector;
        this.completed = completed;
        this.failed = failed;
    }

    void check(String path, boolean select) {
        if (closed) return;
        version++;
        pending = new Request(path, select);
        if (active == null) startNext();
    }

    private void startNext() {
        Request request = pending;
        pending = null;
        long expectedVersion = version;
        active = new SwingWorker<>() {
            @Override protected Result doInBackground() throws Exception { return inspector.inspect(request); }

            @Override protected void done() {
                if (closed || active != this) return;
                active = null;
                if (version == expectedVersion) {
                    try {
                        completed.accept(request, get());
                    } catch (CancellationException ignored) {
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        failed.accept(request, interruption);
                    } catch (ExecutionException failure) {
                        failed.accept(request, failure.getCause());
                    }
                }
                if (!closed && pending != null) startNext();
            }
        };
        active.execute();
    }

    @Override public void close() {
        closed = true;
        pending = null;
        if (active != null) active.cancel(true);
        active = null;
    }

    private static Result inspect(Request request) throws Exception {
        if (request.path() == null || request.path().isBlank()) {
            return new Result(false, "ASTAP is not set. Download ASTAP and a star database (D50), then choose astap.exe.");
        }
        File executable = new File(request.path());
        if (request.select()) {
            Process process = new ProcessBuilder(executable.getAbsolutePath(), "-h")
                    .directory(executable.getAbsoluteFile().getParentFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
        }
        if (!executable.isFile()) {
            return new Result(false, "ASTAP was not found at this path. Choose it again, or Download it.");
        }
        Map<String, Integer> databases = starDatabases(executable.getParentFile());
        if (databases.isEmpty()) {
            return new Result(false, "ASTAP found, but no star database next to it, so solving will fail. Download one (D50 is a good choice).");
        }
        return new Result(true, "✓ ASTAP found · star database " + String.join(", ", databases.keySet()));
    }

    static Map<String, Integer> starDatabases(File folder) {
        Map<String, Integer> databases = new TreeMap<>();
        File[] files = folder == null ? null : folder.listFiles();
        if (files == null) return databases;
        for (File file : files) {
            Matcher matcher = STAR_DATABASE_FILE.matcher(file.getName());
            if (matcher.matches() && file.isFile()) {
                databases.merge(matcher.group(1).toUpperCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        return databases;
    }
}
