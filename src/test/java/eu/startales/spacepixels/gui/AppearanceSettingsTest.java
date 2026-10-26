package eu.startales.spacepixels.gui;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** Desktop appearance persistence is independent, checked, atomic and recoverable without changing the saved file. */
public class AppearanceSettingsTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** Existing installations with no appearance file keep the standard dark theme and OS size. */
    @Test public void missingFileUsesDefaults() throws Exception {
        assertEquals(AppearanceSettings.defaults(), AppearanceSettings.load(new File(temporary.getRoot(), "missing.json")));
    }

    /** Every appearance field survives a save, while the existing application settings remain untouched. */
    @Test public void savesAndRestoresOnlyAppearancePreferences() throws Exception {
        File app = temporary.newFile("spacepixels_app.json");
        Files.writeString(app.toPath(), "existing application preferences");
        File target = new File(temporary.getRoot(), AppearanceSettings.FILE_NAME);
        var settings = new AppearanceSettings(AppearanceSettings.Theme.HIGH_CONTRAST_DARK, 175, "my colours.properties");
        settings.save(target);
        assertEquals(settings, AppearanceSettings.load(target));
        assertEquals("existing application preferences", Files.readString(app.toPath()));
        assertEquals(2, temporary.getRoot().listFiles().length);
    }

    /** A damaged or out-of-range file is reported and never silently replaced with defaults. */
    @Test public void invalidPreferencesRecoverWithoutOverwritingTheFile() throws Exception {
        File file = temporary.newFile("appearance.json");
        for (String invalid : new String[]{"not JSON", "null", "{\"theme\":\"LIGHT\",\"scalePercent\":900}",
                "{\"theme\":\"UNKNOWN\",\"scalePercent\":100}"}) {
            Files.writeString(file.toPath(), invalid);
            assertThrows(java.io.IOException.class, () -> AppearanceSettings.load(file));
            AppearanceTheme.Prepared loaded = AppearanceTheme.load(file);
            assertEquals(AppearanceSettings.defaults(), loaded.settings());
            assertNotNull(loaded.warning());
            assertEquals(invalid, Files.readString(file.toPath()));
        }
    }

    /** Overrides support UTF-8 and references, and resolve relative to the appearance file rather than the process. */
    @Test public void relativeCustomFileIsLoadedWithoutChangingIt() throws Exception {
        File custom = temporary.newFile("custom.properties");
        String source = "# Couleurs éclaircies\n@foreground = #ffffff\nSpacePixels.helpForeground = @foreground\n";
        Files.writeString(custom.toPath(), source);
        var settings = new AppearanceSettings(AppearanceSettings.Theme.LIGHT, 150, custom.getName());
        var prepared = AppearanceTheme.prepare(settings, new File(temporary.getRoot(), "appearance.json"));
        assertEquals("#ffffff", prepared.overrides().get("@foreground"));
        assertEquals("@foreground", prepared.overrides().get("SpacePixels.helpForeground"));
        assertThrows(UnsupportedOperationException.class, () -> prepared.overrides().clear());
        assertEquals(source, Files.readString(custom.toPath()));
    }

    /** A removed custom file keeps the selected built-in theme and readable size available on the next start. */
    @Test public void unavailableCustomFileFallsBackToTheChosenBuiltInTheme() throws Exception {
        File file = new File(temporary.getRoot(), "appearance.json");
        var settings = new AppearanceSettings(AppearanceSettings.Theme.LIGHT, 200, "missing.properties");
        settings.save(file);
        var prepared = AppearanceTheme.load(file);
        assertEquals(settings, prepared.settings());
        assertTrue(prepared.overrides().isEmpty());
        assertNotNull(prepared.warning());
        assertEquals(settings, AppearanceSettings.load(file));
    }
}
