package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.session.ProcessingSession;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.FrameFixture;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import org.junit.Test;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import static org.junit.Assert.*;

/** Session lifecycle checks use public application operations on the EDT, without native mouse input. */
public class SessionMechanicsTest extends MechanicsTestCase {
    @Test(timeout = 180_000)
    public void defaultsAreAvailableBeforeImportAndUnsavedEditsSurviveTwoImports() throws Exception {
        GuiActionRunner.execute(() -> application().getTabbedPane().setSelectedComponent(application().getConfigurationApplicationPanel()));
        var settings = GuiActionRunner.execute(() -> application().getApplicationSettings());
        JLabel latitudeLabel = window().label(VisibleText.labelContaining("Site Latitude (N)")).target();
        JTextField latitude = GuiActionRunner.execute(() -> {
            Container row = latitudeLabel.getParent();
            while (row != null) {
                JTextField field = firstField(row);
                if (field != null) return field;
                row = row.getParent();
            }
            throw new AssertionError("Latitude field was not found");
        });
        GuiActionRunner.execute(() -> latitude.setText("37.9838"));
        window().label(VisibleText.labelContaining("Unsaved")).requireVisible();
        GuiActionRunner.execute(() -> application().getTabbedPane().setSelectedComponent(application().getMainApplicationPanel()));
        importFreshSession();
        ProcessingSession first = GuiActionRunner.execute(() -> application().getSession());
        assertSame(settings.getConfig(), first.getProcessing().getAppConfig());
        assertEquals("37.9838", first.getProcessing().getAppConfig().siteLat);
        importFreshSession();
        ProcessingSession second = GuiActionRunner.execute(() -> application().getSession());
        assertNotSame(first, second);
        assertTrue(first.isClosed());
        assertEquals("37.9838", second.getProcessing().getAppConfig().siteLat);
        assertFalse(new java.io.File(System.getProperty("user.home"), "spacepixels_app.json").exists());
        GuiActionRunner.execute(() -> application().getTabbedPane().setSelectedComponent(application().getConfigurationApplicationPanel()));
        window().label(VisibleText.labelContaining("Unsaved")).requireVisible();
    }

    @Test(timeout = 180_000)
    public void aNewImportClosesReusablePreviewAndDoesNotAdoptThePreviousCatalogue() throws Exception {
        Path solved = SolvedSession.create(folder.newFolder("solved").toPath());
        importSession(solved);
        ProcessingSession first = GuiActionRunner.execute(() -> application().getSession());
        assertNotNull(GuiActionRunner.execute(() -> first.getSkyCatalogue().get()));
        int listeners = GuiActionRunner.execute(() -> first.getSkyCatalogue().listenerCount());
        GuiActionRunner.execute(() -> window().table().target().setRowSelectionInterval(0, 0));
        GuiActionRunner.execute(() -> window().button(VisibleText.button("Preview Frame")).target().doClick());
        FrameFixture preview = WindowFinder.findFrame(new org.assertj.swing.core.GenericTypeMatcher<JFrame>(JFrame.class) {
            @Override protected boolean isMatching(JFrame frame) {
                return frame.isShowing() && frame.getTitle().startsWith("Preview Frame");
            }
        }).using(robot());
        assertTrue(GuiActionRunner.execute(() -> first.getSkyCatalogue().listenerCount()) > listeners);
        GuiActionRunner.execute(() -> application().getFrame().toFront());
        importFreshSession();
        ProcessingSession second = GuiActionRunner.execute(() -> application().getSession());
        assertTrue(first.isClosed());
        assertNotSame(first.getSkyCatalogue(), second.getSkyCatalogue());
        assertNull(GuiActionRunner.execute(() -> second.getSkyCatalogue().get()));
        assertFalse(GuiActionRunner.execute(() -> preview.target().isDisplayable()));
        assertEquals(0, (int) GuiActionRunner.execute(() -> first.getSkyCatalogue().listenerCount()));
    }

    private static JTextField firstField(Component component) {
        if (component instanceof JTextField field) return field;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JTextField field = firstField(child);
                if (field != null) return field;
            }
        }
        return null;
    }

    private void importFreshSession() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(
                folder.newFolder().toPath(), "session", SyntheticDatasetFactory.DEFAULT_FRAME_COUNT);
        importSession(sequence);
    }

    private void importSession(Path sequence) {
        ProcessingSession previous = GuiActionRunner.execute(() -> application().getSession());
        GuiActionRunner.execute(() -> application().importFolder(sequence.toFile()));
        waitFor("a new session is installed", () -> GuiActionRunner.execute(() ->
                application().getSession() != null && application().getSession() != previous
                        && application().getJobCoordinator().current() == null));
    }
}
