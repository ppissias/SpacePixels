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
            } else if (args[0].equals("parent")) {
                Process child = new ProcessBuilder(command("sleep", root)).inheritIO().start();
                child.waitFor();
            } else {
                Files.writeString(root.resolve("child.pid"), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(60_000);
            }
        }
    }
}
