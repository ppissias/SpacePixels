package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.gui.AppearanceTestSupport;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.DialogFixture;
import org.junit.Test;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Checks the restart choice, safe preparation failure and running-job handover in the real application. */
public class AppearanceRestartMechanicsTest extends MechanicsTestCase {
    private final AtomicInteger attempts = new AtomicInteger();

    /** Injects a real failure seam, preventing this suite from ever relaunching the test worker. */
    @Override protected ApplicationWindow createApplicationWindow() {
        return AppearanceTestSupport.failingRestart(attempts::incrementAndGet);
    }

    /** A successful save offers both choices, and an unavailable runtime leaves the saved, usable window open. */
    @Test(timeout = 120_000) public void restartNowIsOfferedAndPreparationFailureKeepsWindowOpen() throws Exception {
        window().tabbedPane().selectTab("Appearance");
        window().button(VisibleText.button("Save appearance")).click();
        DialogFixture choice = dialog("Restart SpacePixels");
        choice.button(VisibleText.button("Later")).requireEnabled();
        assertTrue(Files.exists(AppearanceTestSupport.preferencesFile().toPath()));
        choice.button(VisibleText.button("Restart now")).click();
        dialog("Restart unavailable").button(VisibleText.button("OK")).click();
        assertEquals(1, attempts.get());
        assertTrue(GuiActionRunner.execute(() -> application().getFrame().isShowing()));
        window().button(VisibleText.button("Save appearance")).requireEnabled();
        window().tabbedPane().selectTab("Main");
        window().button(VisibleText.button("Import Aligned Frames…")).requireEnabled();
    }

    /** Restart waits for the existing job's outcome and blocks another admission without cancelling the work. */
    @Test(timeout = 120_000) public void restartWaitsForTheRunningJob() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        try {
            assertTrue(GuiActionRunner.execute(() -> application().getJobCoordinator().start(JobKind.AUTO_TUNE,
                    null, context -> {
                        started.countDown();
                        if (!finish.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Job was not released");
                        return JobOutcome.completed("finished");
                    }, (handle, outcome) -> {
                        assertTrue("The existing job finishes successfully", outcome instanceof JobOutcome.Completed<?>);
                        delivered.countDown();
                    }).admitted()));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            GuiActionRunner.execute(() -> AppearanceTestSupport.requestRestart(application()));
            assertEquals("No helper is prepared before the job ends", 0, attempts.get());
            assertFalse(GuiActionRunner.execute(() -> application().getJobCoordinator().start(JobKind.AUTO_TUNE,
                    null, context -> JobOutcome.completed(null), (handle, outcome) -> {}).admitted()));
        } finally { finish.countDown(); }
        dialog("Restart unavailable").button(VisibleText.button("OK")).click();
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertEquals(1, attempts.get());
        assertNull(GuiActionRunner.execute(() -> application().getJobCoordinator().current()));
    }

    /** A separate application exits only after pending writes, saves its placement, then launches the replacement. */
    @Test(timeout = 120_000) public void successfulRestartDrainsWritesAndPreservesWindowPlacement() throws Exception {
        Path root = folder.newFolder("successful-restart").toPath();
        Path home = Files.createDirectory(root.resolve("home"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(java, "-Xmx256m", "-Duser.home=" + home, "-cp",
                System.getProperty("spacepixels.test.classpath"), AppearanceTestSupport.RestartHost.class.getName(),
                "parent", root.toString()).redirectErrorStream(true).redirectOutput(root.resolve("host.log").toFile()).start();
        List<ProcessHandle> helpers = List.of();
        try {
            waitFor("restart is prepared", () -> Files.exists(root.resolve("ready")));
            helpers = host.descendants().toList();
            assertTrue("Old application stays alive until its save drains", host.isAlive());
            assertFalse(Files.exists(root.resolve("saved")));
            assertFalse(Files.exists(root.resolve("replacement")));
            Files.writeString(root.resolve("release-save"), "finish");
            assertTrue(host.waitFor(15, TimeUnit.SECONDS));
            assertEquals(Files.readString(root.resolve("host.log")), 0, host.exitValue());
            waitFor("replacement records the completed save", () -> Files.exists(root.resolve("replacement")));
            assertEquals(List.of("finished", "false", "1111", "777"), Files.readAllLines(root.resolve("replacement")));
        } finally {
            host.destroyForcibly();
            helpers.forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
        }
    }

    /** Finds just this workflow's modal dialog by the title exposed to the user. */
    private DialogFixture dialog(String title) {
        return WindowFinder.findDialog(new GenericTypeMatcher<>(JDialog.class, true) {
            /** Rejects progress and other concurrently owned dialogs. */
            @Override protected boolean isMatching(JDialog candidate) { return title.equals(candidate.getTitle()); }
        }).withTimeout(15_000).using(robot());
    }
}
