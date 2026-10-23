package eu.startales.spacepixels.config;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Application preferences loaded independently of sessions, or detached headless settings with no persistence file. */
public final class ApplicationSettings {
    private final File file;
    private final AppConfig config;

    private ApplicationSettings(File file, AppConfig config) {
        this.file = file;
        this.config = Objects.requireNonNull(config);
    }

    public static ApplicationSettings loadDefault() {
        return load(new File(System.getProperty("user.home", ""), SpacePixelsAppConfigIO.DEFAULT_FILENAME));
    }

    /** Creates detached headless settings without reading or owning a user preference file. */
    public static ApplicationSettings inMemory(AppConfig config) {
        return new ApplicationSettings(null, Objects.requireNonNull(config).copy());
    }

    public static ApplicationSettings load(File file) {
        AppConfig config = new AppConfig();
        if (file.exists()) {
            try {
                config = SpacePixelsAppConfigIO.load(file);
            } catch (IOException | RuntimeException failure) {
                Logger.getLogger(ApplicationSettings.class.getName()).log(Level.WARNING,
                        "Failed to load application preferences; using defaults.", failure);
            }
        }
        return new ApplicationSettings(file, config);
    }

    public AppConfig getConfig() {
        return config;
    }

    public void save() throws IOException {
        save(config.copy());
    }

    /** Saves an explicit snapshot; detached headless settings reject persistence without creating a file. */
    public void save(AppConfig snapshot) throws IOException {
        if (file == null) throw new IOException("In-memory application settings have no persistence file.");
        SpacePixelsAppConfigIO.write(file, snapshot);
    }
}
