package eu.startales.spacepixels.preparation;

import java.io.IOException;
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

/** Runs one owned child process, redirecting logs to disk and joining descendants before releasing ownership. */
public final class PreparationProcess {
    /** Exit status and a bounded tail of the full disk log, suitable for a status or failure message. */
    public record Result(int exitCode, String output) { }

    /** Stateless process ownership; the caller owns the request and workspace. */
    private PreparationProcess() { }

    /** Runs without a shell; timeout/cancellation stop and join the owned process tree before throwing. */
    public static Result run(List<String> command, Path directory, Map<String, String> environment, Path log,
                             Duration timeout, BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) throw new CancellationException("Preparation cancelled.");
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().putAll(environment);
        Process process = builder.start();
        Set<ProcessHandle> owned = new LinkedHashSet<>();
        owned.add(process.toHandle());
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean complete = false;
        try {
            process.getOutputStream().close();
            while (true) {
                for (ProcessHandle handle : List.copyOf(owned)) owned.addAll(handle.descendants().toList());
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Preparation cancelled.");
                }
                if (System.nanoTime() >= deadline) throw new IOException("The external tool exceeded its time limit. See " + log.getFileName() + ".");
                if (!process.isAlive() && owned.stream().noneMatch(ProcessHandle::isAlive)) break;
                if (process.isAlive()) process.waitFor(100, TimeUnit.MILLISECONDS);
                else Thread.sleep(100);
            }
            complete = true;
            return new Result(process.exitValue(), tail(log));
        } finally {
            if (!complete) stop(owned);
        }
    }

    /** Keeps admission while termination is pending; interruption never abandons an owned running process. */
    private static void stop(Set<ProcessHandle> processes) {
        boolean interrupted = Thread.interrupted();
        for (ProcessHandle handle : List.copyOf(processes)) processes.addAll(handle.descendants().toList());
        List.copyOf(processes).reversed().forEach(handle -> { if (handle.isAlive()) handle.destroy(); });
        long gentle = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (processes.stream().anyMatch(ProcessHandle::isAlive)) {
            for (ProcessHandle handle : List.copyOf(processes)) {
                processes.addAll(handle.descendants().toList());
                if (handle.isAlive() && System.nanoTime() > gentle) handle.destroyForcibly();
            }
            try { Thread.sleep(50); } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** Reads only the last 12 KiB, even when an external application produces a very large log. */
    private static String tail(Path path) throws IOException {
        try (SeekableByteChannel input = Files.newByteChannel(path, StandardOpenOption.READ)) {
            input.position(Math.max(0, input.size() - 12_288));
            ByteBuffer bytes = ByteBuffer.allocate(12_288);
            while (input.read(bytes) > 0 && bytes.hasRemaining()) { }
            return new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8);
        }
    }
}
