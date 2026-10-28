package eu.startales.spacepixels.preparation;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Exercises real owned child JVMs, rather than mocking timeout, log pressure or process-tree cancellation. */
public class PreparationProcessTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** Tool output reaches the caller before exit, excludes earlier commands and preserves split UTF-8 and final partial lines. */
    @Test public void streamsNewOutputWhileChildIsStillRunning() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path log = root.resolve("live.log");
        Files.writeString(log, "EARLIER COMMAND\n");
        var executor = Executors.newSingleThreadExecutor();
        var received = new StringBuffer();
        var live = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean();
        try {
            var future = executor.submit(() -> PreparationProcess.run(command("stream", root), root, Map.of(), log,
                    Duration.ofSeconds(15), stop::get, text -> { received.append(text); if (text.contains("LIVE π")) live.countDown(); }));
            assertTrue("Output must arrive during execution", live.await(10, TimeUnit.SECONDS));
            assertFalse("The child is still waiting for release", future.isDone());
            assertFalse(received.toString().contains("EARLIER COMMAND"));
            assertFalse(received.toString().contains("\u001b"));
            Files.writeString(root.resolve("release"), "go");
            var completed = future.get(10, TimeUnit.SECONDS);
            assertEquals(0, completed.exitCode());
            assertFalse(completed.output().contains("EARLIER COMMAND"));
            assertTrue(received.toString().contains("FINAL WITHOUT NEWLINE"));
        } finally { stop.set(true); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    /** A large log remains on disk while the result delivered to Swing stays bounded. */
    @Test public void keepsLargeLogsOnDiskAndReturnsExitStatus() throws Exception {
        Path root = temporary.getRoot().toPath();
        var result = PreparationProcess.run(command("log", root), root, Map.of(), root.resolve("log.txt"), Duration.ofSeconds(15), () -> false);
        assertEquals(7, result.exitCode());
        assertTrue(Files.size(root.resolve("log.txt")) > 30_000);
        assertTrue(result.output().length() <= 12_288);
        assertTrue(result.output().contains("LOG-END"));
    }

    /** Cancellation is not complete until a spawned descendant has stopped as well. */
    @Test public void cancellationJoinsOwnedDescendants() throws Exception {
        Path root = temporary.getRoot().toPath();
        var executor = Executors.newSingleThreadExecutor();
        AtomicBoolean stop = new AtomicBoolean();
        try {
            var result = executor.submit(() -> PreparationProcess.run(command("parent", root), root, Map.of(), root.resolve("process.log"), Duration.ofSeconds(15), stop::get));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(root.resolve("child.pid")) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue("child started", Files.exists(root.resolve("child.pid")));
            long pid = Long.parseLong(Files.readString(root.resolve("child.pid")));
            stop.set(true);
            try { result.get(10, TimeUnit.SECONDS); fail("Cancellation must propagate"); }
            catch (java.util.concurrent.ExecutionException expected) { assertTrue(expected.getCause() instanceof CancellationException); }
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally { stop.set(true); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    /** A timed-out child is stopped before the operation reports failure. */
    @Test public void timeoutStopsTheChild() throws Exception {
        Path root = temporary.getRoot().toPath();
        assertThrows(java.io.IOException.class, () -> PreparationProcess.run(command("sleep", root), root, Map.of(), root.resolve("timeout.log"), Duration.ofMillis(600), () -> false));
        if (Files.exists(root.resolve("child.pid"))) {
            long pid = Long.parseLong(Files.readString(root.resolve("child.pid")));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    /** Uses the test JVM's runtime and classpath without depending on a platform shell or installed astronomy tools. */
    private static List<String> command(String mode, Path root) {
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        return List.of(executable, "-Xmx32m", "-XX:ActiveProcessorCount=1", "-Duser.home=" + root,
                "-cp", System.getProperty("spacepixels.test.classpath", System.getProperty("java.class.path")),
                Child.class.getName(), mode, root.toString());
    }

    /** Disposable subprocess fixture with deliberate long-lived descendants and a large log. */
    public static final class Child {
        /** Runs only in the spawned fixture JVM, with no application settings or Java preferences. */
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[1]);
            if (args[0].equals("log")) {
                System.out.println("x".repeat(40_000)); System.out.println("LOG-END"); System.exit(7);
            } else if (args[0].equals("stream")) {
                byte[] text = "\u001b[32mLIVE π\u001b[0m\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (int i = 0; i < text.length; i++) {
                    System.out.write(text[i]); System.out.flush();
                    if ((text[i] & 255) == 0xcf) Thread.sleep(150);
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
                while (!Files.exists(root.resolve("release")) && System.nanoTime() < deadline) Thread.sleep(20);
                System.out.print("FINAL WITHOUT NEWLINE"); System.out.flush();
            } else if (args[0].equals("parent")) {
                Process child = new ProcessBuilder(command("sleep", root)).inheritIO().start();
                child.waitFor();
            } else {
                Path pid = root.resolve("child.pid.pending");
                Files.writeString(pid, Long.toString(ProcessHandle.current().pid()));
                Files.move(pid, root.resolve("child.pid"), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                Thread.sleep(60_000);
            }
        }
    }
}
