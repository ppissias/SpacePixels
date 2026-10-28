package eu.startales.spacepixels.preparation;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Runs one owned child process, redirecting logs to disk and joining descendants before releasing ownership. */
public final class PreparationProcess {
    /** Exit status and a bounded tail of the full disk log, suitable for a status or failure message. */
    public record Result(int exitCode, String output) { }

    /** Stateless process ownership; the caller owns the request and workspace. */
    private PreparationProcess() { }

    /** Runs without a shell; timeout/cancellation stop and join the owned process tree before throwing. */
    public static Result run(List<String> command, Path directory, Map<String, String> environment, Path log,
                             Duration timeout, BooleanSupplier cancelled) throws IOException, InterruptedException {
        return run(command, directory, environment, log, timeout, cancelled, ignored -> { });
    }

    /** Streams bounded batches of new log text while running, retaining full output on disk and joining owned children. */
    public static Result run(List<String> command, Path directory, Map<String, String> environment, Path log,
                             Duration timeout, BooleanSupplier cancelled, Consumer<String> output) throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) throw new CancellationException("Preparation cancelled.");
        long start = Files.exists(log) ? Files.size(log) : 0;
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().putAll(environment);
        Process process = builder.start();
        Set<ProcessHandle> owned = new LinkedHashSet<>();
        owned.add(process.toHandle());
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean complete = false;
        try {
            try (LiveLog live = new LiveLog(log, start, output)) {
                process.getOutputStream().close();
                while (true) {
                    discoverChildren(owned);
                    live.read(false);
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        throw new CancellationException("Preparation cancelled.");
                    }
                    if (System.nanoTime() >= deadline) throw new IOException("The external tool exceeded its time limit. See " + log.getFileName() + ".");
                    if (!process.isAlive() && owned.stream().noneMatch(ProcessHandle::isAlive)) break;
                    if (process.isAlive()) process.waitFor(100, TimeUnit.MILLISECONDS);
                    else Thread.sleep(100);
                }
                live.read(true);
                complete = true;
                return new Result(process.exitValue(), tail(log, start));
            }
        } finally {
            if (!complete) stop(owned);
        }
    }

    /** Incremental UTF-8 line reader; partial lines and delivered batches are bounded while the complete disk log remains intact. */
    private static final class LiveLog implements AutoCloseable {
        private final SeekableByteChannel input;
        private final Consumer<String> output;
        private final ByteBuffer buffer = ByteBuffer.allocate(8192);
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        /** Starts after earlier commands' output in the same run log, avoiding duplicate delivery. */
        LiveLog(Path log, long offset, Consumer<String> output) throws IOException {
            input = Files.newByteChannel(log, StandardOpenOption.READ);
            input.position(offset);
            this.output = output;
        }

        /** Polls without waiting for a newline; the final drain also delivers an unterminated last line. */
        void read(boolean finished) throws IOException {
            StringBuilder batch = new StringBuilder();
            int bytes = 0;
            while (finished || bytes < 65_536) {
                buffer.clear();
                int count = input.read(buffer);
                if (count <= 0) break;
                bytes += count;
                buffer.flip();
                while (buffer.hasRemaining()) {
                    int value = buffer.get() & 255;
                    if (value == '\n' || value == '\r') appendLine(batch);
                    else if (line.size() < 16_384) line.write(value);
                }
                if (batch.length() >= 65_536) { output.accept(batch.toString()); batch.setLength(0); }
            }
            if (finished) appendLine(batch);
            if (!batch.isEmpty()) output.accept(batch.toString());
        }

        /** Removes terminal-control sequences; UTF-8 characters split across reads stay buffered until their line ends. */
        private void appendLine(StringBuilder batch) {
            String text = line.toString(StandardCharsets.UTF_8).replaceAll("\\x1B\\[[0-9;?]*[ -/]*[@-~]", "").strip();
            line.reset();
            if (!text.isBlank()) batch.append(text).append('\n');
        }

        /** Closes only this read handle; the owned process and its complete log have independent lifetimes. */
        @Override public void close() throws IOException { input.close(); }
    }

    /** Keeps admission while termination is pending; interruption never abandons an owned running process. */
    private static void stop(Set<ProcessHandle> processes) {
        boolean interrupted = Thread.interrupted();
        discoverChildren(processes);
        List.copyOf(processes).reversed().forEach(handle -> { if (handle.isAlive()) handle.destroy(); });
        long gentle = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (processes.stream().anyMatch(ProcessHandle::isAlive)) {
            discoverChildren(processes);
            for (ProcessHandle handle : List.copyOf(processes)) {
                if (handle.isAlive() && System.nanoTime() > gentle) handle.destroyForcibly();
            }
            try { Thread.sleep(50); } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /**
     * Follows only verified direct edges. Windows can retain an old parent PID after that parent exits; when its PID
     * is reused, descendants() can include an unrelated older process and its newer children. Do not traverse that
     * branch or adopt children of an expired handle, including while cancelling an owned process tree.
     */
    static void discoverChildren(Set<ProcessHandle> owned) {
        List<ProcessHandle> pending = new java.util.ArrayList<>(owned);
        for (int i = 0; i < pending.size(); i++) {
            ProcessHandle parent = pending.get(i);
            if (!parent.isAlive()) continue;
            var birth = parent.info().startInstant();
            if (birth.isEmpty()) continue;
            List<ProcessHandle> children = parent.children().toList();
            if (!parent.isAlive()) continue;
            for (ProcessHandle child : children) {
                var childBirth = child.info().startInstant();
                if (childBirth.isPresent() && !childBirth.get().isBefore(birth.get()) && owned.add(child)) pending.add(child);
            }
        }
    }

    /** Reads only the last 12 KiB, even when an external application produces a very large log. */
    private static String tail(Path path, long commandStart) throws IOException {
        try (SeekableByteChannel input = Files.newByteChannel(path, StandardOpenOption.READ)) {
            input.position(Math.max(commandStart, input.size() - 12_288));
            ByteBuffer bytes = ByteBuffer.allocate(12_288);
            while (input.read(bytes) > 0 && bytes.hasRemaining()) { }
            return new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8);
        }
    }
}
