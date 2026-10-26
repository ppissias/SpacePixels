package eu.startales.spacepixels.gui;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Checks argument preservation and the real helper-process exit gate without ever exiting the test JVM. */
public class ApplicationRestartTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    /** Paths and values containing spaces remain individual arguments; the isolated home wins over old options. */
    @Test public void preservesRuntimeOptionsAndApplicationArguments() {
        assertEquals(List.of("runtime path/java", "-Xmx4g", "-Dsample=some value", "-Duser.home=new home",
                        "-cp", "library path/app.jar;other.jar", ApplicationWindow.class.getName(), "argument with spaces"),
                ApplicationRestart.command("runtime path/java", "library path/app.jar;other.jar",
                        List.of("-Xmx4g", "-Duser.home=old home", "-Dsample=some value"), "new home",
                        List.of("argument with spaces")));
    }

    /** The new JVM starts only after the old JVM exits and keeps its home, arguments and working directory. */
    @Test(timeout = 40_000) public void committedRestartWaitsForParentExit() throws Exception {
        checkHelper(true);
    }

    /** Abandoning preparation cannot turn a later ordinary application close into a restart. */
    @Test(timeout = 40_000) public void abandonedRestartDoesNotRelaunch() throws Exception {
        checkHelper(false);
    }

    /** Runs a tiny isolated host, exercising the same readiness and commit protocol as the actual window. */
    private void checkHelper(boolean commit) throws Exception {
        Path root = folder.newFolder("restart with spaces").toPath();
        Path home = Files.createDirectory(root.resolve("isolated home"));
        String classpath = System.getProperty("spacepixels.test.classpath");
        assertNotNull("The test task supplies the application's runtime classpath", classpath);
        Process parent = new ProcessBuilder(ApplicationRestart.executable(System.getProperty("java.home")),
                "-Xmx64m", "-Duser.home=" + home, "-cp", classpath, Host.class.getName(),
                "parent", root.toString(), Boolean.toString(commit)).redirectErrorStream(true)
                .redirectOutput(root.resolve("host.log").toFile()).start();
        List<ProcessHandle> helpers = List.of();
        try {
            await("Parent prepared its helper", () -> Files.exists(root.resolve("ready")));
            helpers = parent.descendants().toList();
            assertTrue("The original application is still running", parent.isAlive());
            assertFalse("No replacement may start while its parent is alive", Files.exists(root.resolve("child")));
            if (!commit) await("Uncommitted helper terminates", () -> parent.descendants().noneMatch(ProcessHandle::isAlive));
            Files.writeString(root.resolve("release"), "exit");
            assertTrue("The host exits normally", parent.waitFor(10, TimeUnit.SECONDS));
            assertEquals(Files.readString(root.resolve("host.log")), 0, parent.exitValue());
            if (commit) {
                await("Replacement starts after parent exit", () -> Files.exists(root.resolve("child")));
                assertEquals(List.of("false", home.toString(), root.toString(), "argument with spaces"),
                        Files.readAllLines(root.resolve("child")));
            } else {
                assertFalse("Abandoned helper never launches a replacement", Files.exists(root.resolve("child")));
            }
        } finally {
            parent.destroyForcibly();
            helpers.forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
        }
    }

    /** Waits briefly for file-based process handshakes without touching Swing or the user's settings. */
    private static void await(String message, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(message, condition.getAsBoolean());
    }

    /** Forked regression fixture: acts first as the old application, then as its observable replacement. */
    public static final class Host {
        /** A process entry point only. */
        private Host() { }

        /** Records the child's inherited launch context, or holds the parent alive until the test releases it. */
        public static void main(String[] arguments) throws Exception {
            Path root = Path.of(arguments[1]);
            if ("child".equals(arguments[0])) {
                String content = String.join("\n", Boolean.toString(ProcessHandle.of(Long.parseLong(arguments[2]))
                                .map(ProcessHandle::isAlive).orElse(false)), System.getProperty("user.home"),
                        System.getProperty("user.dir"), arguments[3]);
                Path complete = root.resolve("complete");
                Files.writeString(complete, content);
                Files.move(complete, root.resolve("child"));
                return;
            }
            List<String> child = List.of(ApplicationRestart.executable(System.getProperty("java.home")), "-Xmx64m",
                    "-Duser.home=" + System.getProperty("user.home"), "-cp", System.getProperty("java.class.path"),
                    Host.class.getName(), "child", root.toString(), Long.toString(ProcessHandle.current().pid()),
                    "argument with spaces");
            try (ApplicationRestart.Pending pending = ApplicationRestart.prepare(child, root)) {
                if (Boolean.parseBoolean(arguments[2])) pending.commit();
                else pending.close();
                Files.writeString(root.resolve("ready"), "prepared");
                await("Parent release", () -> Files.exists(root.resolve("release")));
            }
        }
    }
}
