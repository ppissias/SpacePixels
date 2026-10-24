package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.session.*;
import eu.startales.spacepixels.util.*;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import org.assertj.swing.edt.GuiActionRunner;
import org.junit.Test;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Direct EDT checks deliberately hold session I/O, without taking over native mouse input. */
public class SessionIoMechanicsTest extends MechanicsTestCase {
    private final CountDownLatch loadStarted = new CountDownLatch(1);
    private final CountDownLatch loadRelease = new CountDownLatch(1);
    private final CountDownLatch saveStarted = new CountDownLatch(1);
    private final CountDownLatch saveRelease = new CountDownLatch(1);
    private final SessionMetadataStore files = SessionMetadataStore.filesystem();
    private final ApplicationStorage storage = new ApplicationStorage(new SessionMetadataStore() {
        @Override public SessionMetadata load(File directory, FitsFileInformation[] frames) throws IOException {
            loadStarted.countDown();
            await(loadRelease);
            return files.load(directory, frames);
        }
        @Override public void saveCatalogue(File directory, SkyCatalogue catalogue) throws IOException {
            files.saveCatalogue(directory, catalogue);
        }
        @Override public void saveCorrection(File directory, PlateSolutionCorrection correction) throws IOException {
            saveStarted.countDown();
            await(saveRelease);
            files.saveCorrection(directory, correction);
        }
        @Override public void saveAutoTune(File directory, AutoTuneSessionStore.Document document) throws IOException {
            files.saveAutoTune(directory, document);
        }
    });

    @Override protected ApplicationWindow createApplicationWindow() { return new ApplicationWindow(storage); }

    @Test(timeout = 180000)
    public void slowRestoreAndCorrectionSaveLeavePaintingAndSettingsNavigationResponsive() throws Exception {
        File input = SolvedSession.create(folder.newFolder("slow-store").toPath()).toFile();
        try {
            GuiActionRunner.execute(() -> application().importFolder(input));
            assertTrue(loadStarted.await(10, TimeUnit.SECONDS));
            assertNull(GuiActionRunner.execute(() -> application().getSession()));
            paintWindow();
            loadRelease.countDown();
            waitFor("prepared session", () -> GuiActionRunner.execute(() ->
                    application().getSession() != null && application().getJobCoordinator().current() == null));
            ProcessingSession session = GuiActionRunner.execute(() -> application().getSession());
            assertNotNull(GuiActionRunner.execute(() -> session.getSkyCatalogue().get()));
            GuiActionRunner.execute(() -> {
                try { session.getSkyCatalogue().setCorrectionEnabled(true); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });
            assertTrue(saveStarted.await(10, TimeUnit.SECONDS));
            assertTrue(GuiActionRunner.execute(() -> session.getSkyCatalogue().isCorrectionEnabled()));
            assertFalse(PlateSolutionCorrection.load(input).enabled);
            FutureTask<Void> navigation = new FutureTask<>(() -> {
                var tabs = application().getTabbedPane();
                tabs.setSelectedComponent(application().getConfigurationApplicationPanel());
                assertSame(application().getConfigurationApplicationPanel(), tabs.getSelectedComponent());
                tabs.setSelectedComponent(application().getMainApplicationPanel());
                return null;
            });
            EventQueue.invokeLater(navigation);
            navigation.get(3, TimeUnit.SECONDS);
            paintWindow();
            saveRelease.countDown();
            waitFor("saved correction", () -> PlateSolutionCorrection.load(input).enabled);
            assertTrue(PlateSolutionCorrection.load(input).enabled);
            GuiActionRunner.execute(() -> application().importFolder(input));
            waitFor("reimported saved setting", () -> GuiActionRunner.execute(() ->
                    application().getSession() != session && application().getJobCoordinator().current() == null));
            assertTrue(GuiActionRunner.execute(() -> application().getSession().getSkyCatalogue().isCorrectionEnabled()));
            assertTrue(session.isClosed());
        } finally {
            loadRelease.countDown();
            saveRelease.countDown();
        }
    }

    private void paintWindow() throws Exception {
        FutureTask<Void> repaint = new FutureTask<>(() -> {
            var frame = application().getFrame();
            BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try { frame.paint(graphics); } finally { graphics.dispose(); }
            return null;
        });
        EventQueue.invokeLater(repaint);
        repaint.get(3, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new IOException("Test gate timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }
}
