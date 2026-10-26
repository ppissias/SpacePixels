package eu.startales.spacepixels.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.util.UIScale;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Loads desktop appearance off the EDT and installs one instance-owned FlatLaf definition before any window exists. */
final class AppearanceTheme {
    private static final Logger LOG = Logger.getLogger(AppearanceTheme.class.getName());

    /** Prepared settings and detached properties; no later UI operation reads the user's files. */
    record Prepared(AppearanceSettings settings, Map<String, String> overrides, String warning) {
        /** Owns an immutable copy of the optional overrides. */
        Prepared { overrides = Map.copyOf(overrides); }
    }

    /** Stateless startup and theme-construction helpers. */
    private AppearanceTheme() { }

    /** Reads saved appearance before the EDT starts; broken preferences never prevent the application opening. */
    static Prepared load(File file) {
        AppearanceSettings settings;
        try {
            settings = AppearanceSettings.load(file);
        } catch (IOException failure) {
            LOG.log(Level.WARNING, "Using default appearance", failure);
            return new Prepared(AppearanceSettings.defaults(), Map.of(), failure.getMessage());
        }
        try {
            return prepare(settings, file);
        } catch (IOException failure) {
            LOG.log(Level.WARNING, "Using the built-in appearance without custom overrides", failure);
            return new Prepared(settings, Map.of(), failure.getMessage());
        }
    }

    /** Reads a UTF-8 override file on a worker; relative paths are relative to the appearance preferences folder. */
    static Prepared prepare(AppearanceSettings settings, File preferences) throws IOException {
        Map<String, String> overrides = new LinkedHashMap<>();
        if (!settings.customProperties().isEmpty()) {
            try {
                Path path = Path.of(settings.customProperties());
                if (!path.isAbsolute()) path = preferences.toPath().toAbsolutePath().getParent().resolve(path);
                Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(path)) { properties.load(reader); }
                for (String key : properties.stringPropertyNames()) overrides.put(key, properties.getProperty(key));
            } catch (IOException | RuntimeException failure) {
                throw new IOException("Cannot load the custom appearance file: " + failure.getMessage(), failure);
            }
        }
        return new Prepared(settings, overrides, null);
    }

    /** Creates a theme without installing it or modifying global defaults; callers use it on the EDT. */
    static FlatLaf create(AppearanceSettings.Theme theme, Map<String, String> overrides) {
        FlatLaf laf = theme == AppearanceSettings.Theme.LIGHT ? new FlatLightLaf() : new FlatDarkLaf();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("Component.arc", "10");
        values.put("Button.arc", "10");
        values.put("SpacePixels.helpForeground", "@foreground");
        values.put("SpacePixels.imageForeground", "#ffffff");
        values.put("SpacePixels.linkForeground", theme == AppearanceSettings.Theme.LIGHT ? "#005ea6" : "#75baff");
        if (theme == AppearanceSettings.Theme.HIGH_CONTRAST_DARK) {
            values.put("@background", "#101010");
            values.put("@foreground", "#ffffff");
            values.put("@disabledForeground", "#aaaaaa");
            values.put("@selectionBackground", "#005ea6");
            values.put("@selectionForeground", "#ffffff");
            values.put("@accentColor", "#8ecbff");
            values.put("Component.borderColor", "#aaaaaa");
            values.put("Button.borderColor", "#aaaaaa");
            values.put("Table.gridColor", "#777777");
            values.put("Separator.foreground", "#777777");
            values.put("Component.focusColor", "#8ecbff");
            values.put("TextComponent.arc", "6");
        }
        values.putAll(overrides);
        laf.setExtraDefaults(Map.copyOf(values));
        return laf;
    }

    /** Installs the saved size and theme on the EDT before constructing controls, with a recoverable custom-file fallback. */
    static String install(Prepared prepared) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Install appearance on the EDT.");
        UIScale.setZoomFactor(prepared.settings().scalePercent() / 100f);
        FlatLaf laf = create(prepared.settings().theme(), prepared.overrides());
        if (FlatLaf.setup(laf) && readableDefaults()) return prepared.warning();
        LOG.warning("The custom appearance could not be installed; using the built-in theme.");
        if (!FlatLaf.setup(create(prepared.settings().theme(), Map.of())) || !readableDefaults()) {
            throw new IllegalStateException("Cannot initialize the application appearance.");
        }
        return "The custom appearance could not be applied. The built-in theme is in use.";
    }

    /** Guards the essential defaults before the application creates components with a custom theme. */
    private static boolean readableDefaults() {
        return UIManager.getFont("Label.font") != null && UIManager.getFont("Button.font") != null
                && UIManager.getColor("Label.foreground") != null && UIManager.getColor("Panel.background") != null
                && UIManager.getColor("TextField.foreground") != null && UIManager.getColor("TextField.background") != null
                && UIManager.getColor("Table.foreground") != null && UIManager.getColor("Table.background") != null;
    }
}
