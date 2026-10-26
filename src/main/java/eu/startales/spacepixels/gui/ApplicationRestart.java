package eu.startales.spacepixels.gui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Restarts the desktop with its current runtime/options after the old JVM exits, avoiding overlapping large heaps. */
public final class ApplicationRestart {
    /** Prepares a waiting helper on a worker; the application commits it only after its saves have drained. */
    @FunctionalInterface
    interface Launcher {
        /** Returns an owned, ready helper, or fails while the current application is still usable. */
        Pending prepare() throws IOException;
    }

    /** No instances; command snapshots and waiting helpers have explicit owners. */
    private ApplicationRestart() { }

    /** Captures startup arguments without shell quoting or file I/O; preparation itself runs off the EDT. */
    static Launcher currentProcess(String[] arguments) {
        String java = executable(System.getProperty("java.home"));
        String classpath = System.getProperty("java.class.path");
        List<String> command = command(java, classpath, ManagementFactory.getRuntimeMXBean().getInputArguments(),
                System.getProperty("user.home"), List.of(arguments.clone()));
        Path directory = Path.of(System.getProperty("user.dir"));
        return () -> prepare(command, directory);
    }

    /** Uses the GUI launcher on Windows so an appearance restart does not open a console window. */
    static String executable(String javaHome) {
        return Path.of(javaHome, "bin", java.io.File.separatorChar == '\\' ? "javaw.exe" : "java").toString();
    }

    /** Preserves application JVM options and arguments, with the current home directory authoritative. */
    static List<String> command(String java, String classpath, List<String> options, String home, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(java);
        for (String option : options) if (!option.startsWith("-Duser.home=")) command.add(option);
        command.add("-Duser.home=" + home);
        command.add("-cp");
        command.add(classpath);
        command.add(ApplicationWindow.class.getName());
        command.addAll(arguments);
        return List.copyOf(command);
    }

    /** Starts a small waiting JVM and verifies readiness before the application begins shutting down. */
    static Pending prepare(List<String> command, Path directory) throws IOException {
        List<String> helper = new ArrayList<>(List.of(executable(System.getProperty("java.home")), "-Xmx32m", "-cp",
                System.getProperty("java.class.path"), ApplicationRestart.class.getName(),
                Long.toString(ProcessHandle.current().pid())));
        helper.addAll(command);
        ProcessBuilder builder = new ProcessBuilder(helper).directory(directory.toFile());
        // These options are already captured in the application command and must not enlarge the helper's heap.
        for (String name : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) builder.environment().remove(name);
        Process process = builder.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (process.isAlive() && process.getInputStream().available() < 5 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            if (process.getInputStream().available() < 5
                    || !Arrays.equals("READY".getBytes(StandardCharsets.US_ASCII), process.getInputStream().readNBytes(5))) {
                throw new IOException("The restart helper could not start. Please restart SpacePixels manually.");
            }
            return new Pending(process);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IOException("Restart preparation was interrupted.", interrupted);
        } catch (IOException failure) {
            process.destroy();
            throw failure;
        }
    }

    /** A helper that can be abandoned without restarting, or committed on the shutdown worker after saving. */
    static final class Pending implements AutoCloseable {
        private final Process helper;
        private boolean committed;

        /** Owns the already-ready helper process. */
        private Pending(Process helper) { this.helper = helper; }

        /** Authorizes relaunch; the helper still waits for this JVM's exit before starting the application. */
        synchronized void commit() throws IOException {
            if (committed) return;
            helper.getOutputStream().write("RESTART\n".getBytes(StandardCharsets.US_ASCII));
            helper.getOutputStream().flush();
            helper.getOutputStream().close();
            committed = true;
        }

        /** Abandons an uncommitted helper if preparation is cancelled or normal application closure wins the race. */
        @Override public synchronized void close() {
            if (!committed) helper.destroy();
        }
    }

    /** Helper entry point: EOF without a commit means ordinary closure, not permission to launch another JVM. */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2) throw new IllegalArgumentException("Missing restart parent and command.");
        long parent = Long.parseLong(arguments[0]);
        System.out.print("READY");
        System.out.flush();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII))) {
            if (!"RESTART".equals(input.readLine())) return;
        }
        ProcessHandle.of(parent).ifPresent(handle -> handle.onExit().join());
        new ProcessBuilder(Arrays.asList(arguments).subList(1, arguments.length)).inheritIO().start();
    }
}
