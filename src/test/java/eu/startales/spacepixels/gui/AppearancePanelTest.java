package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.session.ApplicationStorage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Checks that an agreed custom-style restart follows a successful save and respects closing or failed writes. */
public class AppearancePanelTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** A queued settings write must finish before the selected custom style can trigger a restart. */
    @Test public void savesCustomStyleBeforeRequestingRestart() throws Exception {
        var style = temporary.newFile("custom.properties").toPath();
        Files.writeString(style, "@background=#202020\n");
        var preferences = temporary.newFile("appearance.json");
        AppearanceSettings.defaults().save(preferences);
        var storage = new ApplicationStorage();
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var restarts = new AtomicInteger();
        storage.submit("Pending settings", () -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Pending write was not released");
            return null;
        });
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> new AppearancePanel(
                    new AppearanceSettings(AppearanceSettings.Theme.LIGHT, 125, style.toString()), null,
                    preferences, storage, () -> false, restarts::incrementAndGet).save(true));
            assertEquals(0, restarts.get());
            assertEquals(AppearanceSettings.defaults(), AppearanceSettings.load(preferences));
        } finally {
            release.countDown();
            storage.finish();
            SwingUtilities.invokeAndWait(() -> { });
        }
        assertEquals(1, restarts.get());
        assertEquals(new AppearanceSettings(AppearanceSettings.Theme.LIGHT, 125, style.toString()),
                AppearanceSettings.load(preferences));
    }

    /** An unreadable selected file preserves saved preferences and never requests a restart. */
    @Test public void invalidCustomStyleDoesNotSaveOrRestart() throws Exception {
        var preferences = temporary.newFile("appearance.json");
        AppearanceSettings.defaults().save(preferences);
        var restarts = new AtomicInteger();
        var storage = new ApplicationStorage();
        try {
            SwingUtilities.invokeAndWait(() -> new AppearancePanel(
                    new AppearanceSettings(AppearanceSettings.Theme.DARK, 100,
                            temporary.getRoot().toPath().resolve("missing.properties").toString()), null,
                    preferences, storage, () -> false, restarts::incrementAndGet).save(true));
        } finally {
            storage.finish();
            SwingUtilities.invokeAndWait(() -> { });
        }
        assertEquals(0, restarts.get());
        assertEquals(AppearanceSettings.defaults(), AppearanceSettings.load(preferences));
        assertTrue(storage.getSettingsFailures().containsKey("Appearance"));
    }

    /** Closing drains an admitted save without allowing its later completion to restart the application. */
    @Test public void closingSuppressesTheRestartAfterAnAdmittedSave() throws Exception {
        var preferences = temporary.getRoot().toPath().resolve("appearance.json").toFile();
        var restarts = new AtomicInteger();
        var closing = new AtomicBoolean();
        var storage = new ApplicationStorage();
        try {
            SwingUtilities.invokeAndWait(() -> {
                new AppearancePanel(AppearanceSettings.defaults(), null, preferences,
                        storage, closing::get, restarts::incrementAndGet).save(true);
                closing.set(true);
            });
        } finally {
            storage.finish();
            SwingUtilities.invokeAndWait(() -> { });
        }
        assertEquals(0, restarts.get());
        assertEquals(AppearanceSettings.defaults(), AppearanceSettings.load(preferences));
    }
}
