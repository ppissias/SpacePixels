package eu.startales.spacepixels.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.startales.spacepixels.util.StagedFile;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.util.Objects;

/** Immutable desktop preferences, stored separately from application, visualization and detection settings. */
record AppearanceSettings(Theme theme, int scalePercent, String customProperties) {
    static final String FILE_NAME = "spacepixels_appearance.json";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    /** Available built-in themes; the optional properties file overrides the selected theme. */
    enum Theme {
        DARK("Dark"), HIGH_CONTRAST_DARK("High contrast dark"), LIGHT("Light");
        private final String title;

        /** Keeps stable serialized identifiers separate from their user-visible labels. */
        Theme(String title) { this.title = title; }

        /** The label shown by the theme selector. */
        @Override public String toString() { return title; }
    }

    /** Rejects invalid saved values before they can make the application unreadable. Scale is relative to the OS. */
    AppearanceSettings {
        Objects.requireNonNull(theme, "Choose an appearance theme.");
        if (scalePercent < 100 || scalePercent > 300) {
            throw new IllegalArgumentException("Interface size must be between 100% and 300%.");
        }
        customProperties = customProperties == null ? "" : customProperties.trim();
    }

    /** The default preserves the standard dark theme and the operating system's normal size. */
    static AppearanceSettings defaults() { return new AppearanceSettings(Theme.DARK, 100, ""); }

    /** The independent preferences file in the current user's home, which tests isolate. */
    static File defaultFile() { return new File(System.getProperty("user.home"), FILE_NAME); }

    /** Reads on a non-UI thread; a missing file uses defaults, while a malformed file is reported to its caller. */
    static AppearanceSettings load(File file) throws IOException {
        if (!file.exists()) return defaults();
        try (Reader reader = Files.newBufferedReader(file.toPath())) {
            AppearanceSettings settings = JSON.fromJson(reader, AppearanceSettings.class);
            if (settings == null) throw new IOException("The appearance preferences are empty.");
            return settings;
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read appearance preferences: " + invalid.getMessage(), invalid);
        }
    }

    /** Replaces the preferences only after the complete UTF-8 document is written, on the settings worker. */
    void save(File file) throws IOException {
        try (StagedFile staged = new StagedFile(file)) {
            staged.writeString(JSON.toJson(this));
            staged.publish();
        }
    }
}
