package eu.startales.spacepixels.gui.mechanics;

import com.formdev.flatlaf.FlatDarkLaf;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.reporting.ReportLookupProxyServer;
import org.assertj.swing.finder.JFileChooserFinder;
import org.assertj.swing.fixture.JFileChooserFixture;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.fixture.FrameFixture;
import org.assertj.swing.junit.testcase.AssertJSwingJUnitTestCase;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * Opens one SpacePixels window against a scratch home and restores the Java preferences afterwards.
 * The Gradle task {@code guiMechanicsTest} runs these tests. {@code test} does not.
 */
abstract class MechanicsTestCase extends AssertJSwingJUnitTestCase {

    private static final long WAIT_NANOS = 60L * 1_000_000_000L;

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Rule
    public final TestName testName = new TestName();

    private String originalHome;
    private PreferenceSnapshot preferences;
    private ApplicationWindow application;
    private FrameFixture window;
    private int sequenceCount;

    @Override
    protected void onSetUp() throws Exception {
        preferences = PreferenceSnapshot.capture();
        originalHome = System.getProperty("user.home");
        Path home = folder.newFolder("home").toPath();
        System.setProperty("user.home", home.toString());
        application = GuiActionRunner.execute(() -> {
            FlatDarkLaf.setup();
            ApplicationWindow created = new ApplicationWindow();
            ((JFrame) created.getFrame()).setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            return created;
        });
        window = new FrameFixture(robot(), application.getFrame());
        window.show();
        fitOnScreen();
        System.out.println("GUI mechanics: " + testName.getMethodName());
        System.out.flush();
        pauseForTheEye();
    }

    @Override
    protected void onTearDown() throws Exception {
        try {
            if (window != null) {
                window.cleanUp();
                window = null;
            }
        } finally {
            try {
                ReportLookupProxyServer.getInstance().stop();
            } finally {
                if (originalHome == null) {
                    System.clearProperty("user.home");
                } else {
                    System.setProperty("user.home", originalHome);
                }
                if (preferences != null) {
                    preferences.restore();
                }
            }
        }
    }

    final FrameFixture window() {
        return window;
    }

    final ApplicationWindow application() {
        return application;
    }

    /** Closes this window and opens another against the same scratch home, so a saved profile can be read back. */
    final void reopenWindow() {
        window.cleanUp();
        application = GuiActionRunner.execute(() -> {
            ApplicationWindow created = new ApplicationWindow();
            ((JFrame) created.getFrame()).setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            return created;
        });
        window = new FrameFixture(robot(), application.getFrame());
        window.show();
        fitOnScreen();
        pauseForTheEye();
    }

    /** Keep the window on the main display, in front of the terminal, and inside the screen so Save can be clicked. */
    private void fitOnScreen() {
        GuiActionRunner.execute(() -> {
            JFrame frame = (JFrame) application.getFrame();
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
            int width = Math.min(1400, screen.width);
            int height = Math.min(Math.max(600, screen.height - 80), screen.height - 40);
            frame.setExtendedState(JFrame.NORMAL);
            frame.setBounds(screen.x + Math.max(0, (screen.width - width) / 2), screen.y + 20, width, height);
            frame.toFront();
            frame.requestFocus();
        });
        robot().waitForIdle();
    }

    /** Leave the window up long enough to see which case is running. */
    private static void pauseForTheEye() {
        try {
            Thread.sleep(1200);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    final void waitFor(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT_NANOS;
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            robot().waitForIdle();
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException failure) {
                last = failure;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(description, last);
    }

    final String showingLabel(String text) {
        return window.label(VisibleText.labelContaining(text)).target().getText();
    }

    /** Imports a fresh synthetic monochrome sequence through the folder chooser and returns its folder. */
    final Path importSyntheticSequence() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(
                folder.newFolder("data-" + sequenceCount++).toPath(), "session", SyntheticDatasetFactory.DEFAULT_FRAME_COUNT);
        importFolder(sequence);
        return sequence;
    }

    /** Imports this folder through the folder chooser and waits until the prepare line names the frames. */
    final void importFolder(Path sequence) {
        window.button(VisibleText.button("Import Aligned Frames…")).click();
        JFileChooserFixture chooser = JFileChooserFinder.findFileChooser().withTimeout(15_000).using(robot());
        chooser.selectFile(sequence.toFile());
        chooser.approve();
        String frames = SyntheticDatasetFactory.DEFAULT_FRAME_COUNT + " frames";
        waitFor("the prepare line names the imported frames",
                () -> showingLabel("Monochrome").contains(frames));
    }
}
