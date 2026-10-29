package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.platesolve.PlateSolveService;
import eu.startales.spacepixels.tasks.WriteWcsTask;
import io.github.ppissias.jplatesolve.PlateSolveResult;
import nom.tam.fits.Fits;
import org.assertj.swing.edt.GuiActionRunner;
import org.junit.Test;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Real window closure must await native cleanup and noncancellable header writes without blocking the EDT. */
public class ShutdownMechanicsTest extends MechanicsTestCase {
    /** Shutdown's completion snapshot remains valid because no later job can enter while pending writes drain. */
    @Test(timeout = 120_000) public void closingRejectsNewWorkWhileStorageDrains() throws Exception {
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        application().getStorage().submit(() -> {
            writing.countDown(); release.await(); return null;
        });
        try {
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            CountDownLatch closed = closeWindow();
            assertFalse(GuiActionRunner.execute(() -> application().getJobCoordinator().start(JobKind.PLATE_SOLVE, null,
                    context -> JobOutcome.completed(null), (handle, outcome) -> { }).admitted()));
            release.countDown();
            assertTrue(closed.await(10, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    /** A separate child holds solver sidecars until the cancelled job terminates it and finishes cleanup. */
    @Test(timeout = 120_000) public void closingWaitsForNativeProcessAndSidecarCleanup() throws Exception {
        Path root = folder.newFolder("solver").toPath();
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m", "-Duser.home=" + root, "-cp", System.getProperty("spacepixels.test.classpath"),
                HoldingSolver.class.getName(), root.toString()).redirectErrorStream(true)
                .redirectOutput(root.resolve("solver.log").toFile()).start();
        CountDownLatch started = new CountDownLatch(1), cleaning = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            waitFor("the child is holding its sidecars", () -> Files.exists(root.resolve("ready")));
            assertTrue(GuiActionRunner.execute(() -> application().getJobCoordinator().start(JobKind.PLATE_SOLVE, null, context -> {
                try {
                    started.countDown();
                    while (!context.isCancelled()) Thread.sleep(10);
                    return JobOutcome.cancelled();
                } finally {
                    cleaning.countDown(); release.await();
                    process.destroyForcibly();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("Child did not stop");
                    Files.delete(root.resolve("frame.ini")); Files.delete(root.resolve("frame.wcs"));
                }
            }, (handle, outcome) -> { }).admitted()));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            CountDownLatch closed = closeWindow();
            assertTrue(cleaning.await(5, TimeUnit.SECONDS));
            assertFalse("Window cannot finish shutdown before native cleanup", closed.await(500, TimeUnit.MILLISECONDS));
            assertTrue(process.isAlive());
            release.countDown();
            assertTrue(closed.await(10, TimeUnit.SECONDS));
            assertFalse(process.isAlive());
            assertFalse(Files.exists(root.resolve("frame.ini")));
            assertFalse(Files.exists(root.resolve("frame.wcs")));
        } finally { release.countDown(); process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
    }

    /** Closing during an accepted WCS write lets staged validation and publication finish before disposal. */
    @Test(timeout = 120_000) public void closingWaitsForTheAcceptedWcsWrite() throws Exception {
        Path image = folder.getRoot().toPath().resolve("frame.fit");
        try (Fits fits = new Fits()) { fits.addHDU(Fits.makeHDU(new short[][]{{1, 2}, {3, 4}})); fits.write(image.toFile()); }
        byte[] before = Files.readAllBytes(image);
        var result = new PlateSolveResult(true, null, null, Map.of("source", "astap", "CTYPE1", "RA---TAN", "CTYPE2", "DEC--TAN",
                "CRVAL1", "123.25", "CRVAL2", "-12.5", "CRPIX1", "1.5", "CRPIX2", "1.5", "CD1_1", "-0.001", "CD2_2", "0.001"));
        var write = new WriteWcsTask(new PlateSolveService(new AppConfig()), image.toString(), result);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            assertTrue(GuiActionRunner.execute(() -> application().getJobCoordinator().start(JobKind.WRITE_WCS, null, context -> {
                started.countDown(); release.await(); return write.run(context);
            }, (handle, outcome) -> { }).admitted()));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            CountDownLatch closed = closeWindow();
            assertFalse("A noncancellable write still owns shutdown", closed.await(500, TimeUnit.MILLISECONDS));
            assertArrayEquals(before, Files.readAllBytes(image));
            release.countDown();
            assertTrue(closed.await(10, TimeUnit.SECONDS));
            try (Fits fits = new Fits(image.toFile())) { assertEquals(123.25, fits.getHDU(0).getHeader().getDoubleValue("CRVAL1"), 0); }
        } finally { release.countDown(); }
    }

    /** Dispatches the real close event; the base fixture uses DISPOSE_ON_CLOSE so the test JVM never exits. */
    private CountDownLatch closeWindow() {
        CountDownLatch closed = new CountDownLatch(1);
        GuiActionRunner.execute(() -> {
            application().getFrame().addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) { closed.countDown(); }
            });
            application().getFrame().dispatchEvent(new WindowEvent(application().getFrame(), WindowEvent.WINDOW_CLOSING));
        });
        return closed;
    }

    /** Native-process fixture holding actual open files; it owns no descendants and reads no application settings. */
    public static final class HoldingSolver {
        /** Holds the same kind of sidecar handles a local plate solver can keep open while it is running. */
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            Files.writeString(root.resolve("frame.ini"), "solver state"); Files.writeString(root.resolve("frame.wcs"), "solution");
            try (var ini = Files.newInputStream(root.resolve("frame.ini")); var wcs = Files.newInputStream(root.resolve("frame.wcs"))) {
                Files.writeString(root.resolve("ready"), "holding files"); Thread.sleep(120_000);
            }
        }
    }
}
