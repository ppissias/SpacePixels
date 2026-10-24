package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.session.ApplicationStorage;
import eu.startales.spacepixels.config.SpacePixelsAppConfigIO;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import eu.startales.spacepixels.config.SpacePixelsVisualizationPreferencesIO;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import org.assertj.swing.edt.GuiActionRunner;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class SettingsIoMechanicsTest extends MechanicsTestCase {
    private ApplicationStorage storage;
    private CountDownLatch release;

    @Override protected ApplicationWindow createApplicationWindow() {
        storage = new ApplicationStorage();
        return new ApplicationWindow(storage);
    }

    @Override protected void onTearDown() throws Exception {
        if (release != null) release.countDown();
        try {
            storage.finish();
        } finally {
            super.onTearDown();
        }
    }

    @Test(timeout = 120_000)
    public void aDelayedDetectionSaveKeepsNewerEditsUnsavedAndRevertUsesTheWrittenSnapshot() throws Exception {
        importFrames();
        var panel = application().getDetectionConfigurationPanel();
        holdWrites();
        GuiActionRunner.execute(() -> {
            spinner(panel, "spinDetectionSigma").setValue(3.3);
            spinner(panel, "spinCropPadding").setValue(120);
            field(panel, "saveButton", JButton.class).doClick();
            assertFalse(field(panel, "saveButton", JButton.class).isEnabled());
            spinner(panel, "spinDetectionSigma").setValue(4.4);
            spinner(panel, "spinCropPadding").setValue(180);
        });
        heartbeat();
        assertFalse(homeFile(SpacePixelsDetectionProfileIO.DEFAULT_FILENAME).exists());
        release.countDown();
        waitFor("the completed save preserves newer unsaved edits", () -> GuiActionRunner.execute(() ->
                field(panel, "saveButton", JButton.class).isEnabled()
                        && field(panel, "footerStateLabel", JLabel.class).getText().contains("Unsaved changes")
                        && field(panel, "footerMessageLabel", JLabel.class).getText().contains("newer edits remain unsaved")));
        try (FileReader reader = new FileReader(homeFile(SpacePixelsDetectionProfileIO.DEFAULT_FILENAME))) {
            assertEquals(3.3, SpacePixelsDetectionProfileIO.load(reader).getDetectionConfig().detectionSigmaMultiplier, 0.0);
        }
        try (FileReader reader = new FileReader(homeFile(SpacePixelsVisualizationPreferencesIO.DEFAULT_FILENAME))) {
            assertEquals(120, SpacePixelsVisualizationPreferencesIO.load(reader).trackCropPadding);
        }
        GuiActionRunner.execute(() -> {
            assertEquals(4.4, ((Number) spinner(panel, "spinDetectionSigma").getValue()).doubleValue(), 0.0);
            button(panel, "Revert").doClick();
            assertEquals(3.3, ((Number) spinner(panel, "spinDetectionSigma").getValue()).doubleValue(), 0.0);
            assertEquals(120, spinner(panel, "spinCropPadding").getValue());
            assertTrue(field(panel, "footerStateLabel", JLabel.class).getText().contains("Saved settings"));
        });
    }

    @Test(timeout = 120_000)
    public void orderedApplicationAutosavesKeepLaterSiteEditsUnsaved() throws Exception {
        var panel = application().getConfigurationApplicationPanel();
        holdWrites();
        GuiActionRunner.execute(() -> {
            field(panel, "latTextField", JTextField.class).setText("10");
            field(panel, "longTextField", JTextField.class).setText("20");
            spinner(panel, "starDepthSpinner").setValue(16.0);
            field(panel, "latTextField", JTextField.class).setText("11");
            spinner(panel, "starDepthSpinner").setValue(17.0);
            field(panel, "latTextField", JTextField.class).setText("12");
        });
        heartbeat();
        assertFalse(homeFile(SpacePixelsAppConfigIO.DEFAULT_FILENAME).exists());
        release.countDown();
        waitFor("the latest autosave finishes without saving newer edits", () -> GuiActionRunner.execute(() ->
                field(panel, "saveButton", JButton.class).isEnabled()
                        && field(panel, "footerStateLabel", JLabel.class).getText().contains("Unsaved application defaults")));
        var saved = SpacePixelsAppConfigIO.load(homeFile(SpacePixelsAppConfigIO.DEFAULT_FILENAME));
        assertEquals("11", saved.siteLat);
        assertEquals("20", saved.siteLong);
        assertEquals(17.0, saved.skyCatalogueStarMagnitude, 0.0);
        GuiActionRunner.execute(() -> {
            assertEquals("12", field(panel, "latTextField", JTextField.class).getText());
            button(panel, "Revert").doClick();
            assertEquals("11", field(panel, "latTextField", JTextField.class).getText());
        });
    }

    @Test(timeout = 120_000)
    public void anApplicationAutosaveFailureKeepsDefaultsInUseAndCanBeRetried() throws Exception {
        var panel = application().getConfigurationApplicationPanel();
        File applicationFile = homeFile(SpacePixelsAppConfigIO.DEFAULT_FILENAME);
        Files.createDirectory(applicationFile.toPath());
        File child = new File(applicationFile, "keep.txt");
        Files.writeString(child.toPath(), "prevent replacement");
        GuiActionRunner.execute(() -> {
            field(panel, "latTextField", JTextField.class).setText("10");
            spinner(panel, "starDepthSpinner").setValue(16.0);
        });
        waitFor("the autosave failure remains visibly unsaved", () -> GuiActionRunner.execute(() ->
                field(panel, "saveButton", JButton.class).isEnabled()
                        && field(panel, "footerMessageLabel", JLabel.class).getText().contains("Not saved")
                        && field(panel, "footerStateLabel", JLabel.class).getText().contains("Unsaved application defaults")));
        GuiActionRunner.execute(() -> {
            assertEquals("10", application().getApplicationSettings().getConfig().siteLat);
            assertEquals(16.0, application().getApplicationSettings().getConfig().skyCatalogueStarMagnitude, 0.0);
        });
        assertTrue(storage.getSettingsFailures().containsKey("Application defaults"));
        Files.delete(child.toPath());
        Files.delete(applicationFile.toPath());
        GuiActionRunner.execute(() -> field(panel, "saveButton", JButton.class).doClick());
        waitFor("the retry saves the current defaults", () -> GuiActionRunner.execute(() ->
                field(panel, "saveButton", JButton.class).isEnabled()
                        && field(panel, "footerStateLabel", JLabel.class).getText().contains("Saved settings")));
        assertEquals("10", SpacePixelsAppConfigIO.load(applicationFile).siteLat);
        assertTrue(storage.getSettingsFailures().isEmpty());
    }

    @Test(timeout = 120_000)
    public void aPartialSettingsFailureRemainsUnsavedAndASuccessfulRetryClearsTheFailure() throws Exception {
        importFrames();
        var panel = application().getDetectionConfigurationPanel();
        File preferences = homeFile(SpacePixelsVisualizationPreferencesIO.DEFAULT_FILENAME);
        Files.createDirectory(preferences.toPath());
        File child = new File(preferences, "keep.txt");
        Files.writeString(child.toPath(), "prevent replacement");
        AtomicReference<String> error = new AtomicReference<>();
        Timer dismiss = new Timer(25, event -> {
            for (Window shown : Window.getWindows()) {
                if (shown instanceof JDialog dialog && dialog.isShowing() && "Error".equals(dialog.getTitle())) {
                    JOptionPane pane = optionPane(dialog);
                    if (pane != null) {
                        error.set(String.valueOf(pane.getMessage()));
                        pane.setValue(JOptionPane.OK_OPTION);
                    }
                }
            }
        });
        GuiActionRunner.execute(dismiss::start);
        try {
            GuiActionRunner.execute(() -> {
                spinner(panel, "spinDetectionSigma").setValue(3.8);
                field(panel, "saveButton", JButton.class).doClick();
            });
            waitFor("the partial failure explains which file was saved", () -> error.get() != null
                    && error.get().contains("detection profile was saved, but the visualization preferences were not"));
            GuiActionRunner.execute(() -> {
                assertTrue(field(panel, "footerStateLabel", JLabel.class).getText().contains("Unsaved changes"));
                assertEquals(3.8, ((Number) spinner(panel, "spinDetectionSigma").getValue()).doubleValue(), 0.0);
            });
        assertTrue(storage.getSettingsFailures().containsKey("Detection settings"));
            Files.delete(child.toPath());
            Files.delete(preferences.toPath());
            GuiActionRunner.execute(() -> field(panel, "saveButton", JButton.class).doClick());
            waitFor("a retry marks the settings saved", () -> GuiActionRunner.execute(() ->
                    field(panel, "saveButton", JButton.class).isEnabled()
                            && field(panel, "footerStateLabel", JLabel.class).getText().contains("Saved settings")));
        assertTrue(storage.getSettingsFailures().isEmpty());
        } finally {
            GuiActionRunner.execute(dismiss::stop);
        }
    }

    @Test(timeout = 120_000)
    public void closingWaitsForAcceptedSettingsWritesWithoutFreezingTheWindow() throws Exception {
        var panel = application().getConfigurationApplicationPanel();
        holdWrites();
        GuiActionRunner.execute(() -> {
            field(panel, "latTextField", JTextField.class).setText("10");
            field(panel, "saveButton", JButton.class).doClick();
            application().getFrame().dispatchEvent(new WindowEvent(application().getFrame(), WindowEvent.WINDOW_CLOSING));
            assertTrue(application().getFrame().isDisplayable());
        });
        heartbeat();
        release.countDown();
        waitFor("the window closes after the pending defaults are saved", () ->
                !GuiActionRunner.execute(() -> application().getFrame().isDisplayable()));
        assertEquals("10", SpacePixelsAppConfigIO.load(homeFile(SpacePixelsAppConfigIO.DEFAULT_FILENAME)).siteLat);
    }

    private void importFrames() throws Exception {
        File directory = SyntheticDatasetFactory.createMono16FitsSequence(folder.newFolder("frames").toPath(),
                "session", SyntheticDatasetFactory.DEFAULT_FRAME_COUNT).toFile();
        GuiActionRunner.execute(() -> application().importFolder(directory));
        waitFor("the synthetic session is installed", () -> GuiActionRunner.execute(() -> application().getSession() != null));
    }

    private void holdWrites() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        storage.submit("Test gate", () -> {
            assertFalse(EventQueue.isDispatchThread());
            entered.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            return null;
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
    }

    private static void heartbeat() throws Exception {
        FutureTask<Boolean> heartbeat = new FutureTask<>(EventQueue::isDispatchThread);
        EventQueue.invokeLater(heartbeat);
        assertTrue(heartbeat.get(3, TimeUnit.SECONDS));
    }

    private static File homeFile(String name) { return new File(System.getProperty("user.home"), name); }

    private static JSpinner spinner(Object panel, String name) { return field(panel, name, JSpinner.class); }

    private static <Value> Value field(Object owner, String name, Class<Value> type) {
        try {
            var field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(owner));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static JButton button(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) return button;
            if (component instanceof Container nested) {
                JButton found = button(nested, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JOptionPane optionPane(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JOptionPane pane) return pane;
            if (component instanceof Container nested) {
                JOptionPane found = optionPane(nested);
                if (found != null) return found;
            }
        }
        return null;
    }
}
