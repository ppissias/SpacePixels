package eu.startales.spacepixels.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.util.UIScale;
import eu.startales.spacepixels.session.ApplicationStorage;

import javax.swing.*;
import java.awt.EventQueue;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

/** Test access to the real startup boundary without exposing desktop implementation types as public API. */
public final class AppearanceTestSupport {
    /** No instances; each prepared startup owns its own immutable appearance snapshot. */
    private AppearanceTestSupport() { }

    /** The current test-isolated preferences file. */
    public static File preferencesFile() { return AppearanceSettings.defaultFile(); }

    /** Reads saved settings off the EDT just as main does before constructing any windows. */
    public static Startup readSaved() { return new Startup(AppearanceTheme.load(preferencesFile())); }

    /** Prepares a built-in appearance without reading or writing a preferences file. */
    public static Startup builtIn(String theme, int percent) {
        return new Startup(new AppearanceTheme.Prepared(
                new AppearanceSettings(AppearanceSettings.Theme.valueOf(theme), percent, ""), Map.of(), null));
    }

    /** Builds the real window with a failing launcher so GUI tests can choose Restart now without exiting. */
    public static ApplicationWindow failingRestart(Runnable attempted) {
        return new ApplicationWindow(new ApplicationStorage(), AppearanceSettings.defaults(), null, () -> {
            attempted.run();
            throw new IOException("Simulated unavailable Java runtime");
        });
    }

    /** Exercises the same EDT request used by the save prompt while a test-owned job is still running. */
    public static void requestRestart(ApplicationWindow application) { application.requestRestart(); }

    /** Forked GUI fixture whose successful restart exercises production shutdown without exiting the test worker. */
    public static final class RestartHost {
        /** Only started as a separate process. */
        private RestartHost() { }

        /** Holds a settings write pending while restarting; the replacement records the fully drained state. */
        public static void main(String[] arguments) throws Exception {
            Path root = Path.of(arguments[1]);
            if ("child".equals(arguments[0])) {
                Preferences placement = Preferences.userNodeForPackage(ApplicationWindow.class);
                String result = String.join("\n", Files.readString(root.resolve("saved")),
                        Boolean.toString(ProcessHandle.of(Long.parseLong(arguments[2])).map(ProcessHandle::isAlive).orElse(false)),
                        Integer.toString(placement.getInt("mainWindow.width", 0)),
                        Integer.toString(placement.getInt("mainWindow.height", 0)));
                Files.writeString(root.resolve("complete"), result);
                Files.move(root.resolve("complete"), root.resolve("replacement"));
                return;
            }
            ApplicationStorage storage = new ApplicationStorage();
            storage.submit("Restart regression", () -> {
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
                while (!Files.exists(root.resolve("release-save")) && System.nanoTime() < deadline) Thread.sleep(10);
                if (!Files.exists(root.resolve("release-save"))) throw new IOException("Test did not release its save");
                Files.writeString(root.resolve("saved"), "finished");
                return null;
            });
            SwingUtilities.invokeLater(() -> {
                AppearanceTheme.install(new AppearanceTheme.Prepared(AppearanceSettings.defaults(), Map.of(), null));
                ApplicationWindow application = new ApplicationWindow(storage, AppearanceSettings.defaults(), null, () -> {
                    var command = List.of(ApplicationRestart.executable(System.getProperty("java.home")), "-Xmx64m",
                            "-Duser.home=" + System.getProperty("user.home"), "-cp", System.getProperty("java.class.path"),
                            RestartHost.class.getName(), "child", root.toString(), Long.toString(ProcessHandle.current().pid()));
                    ApplicationRestart.Pending pending = ApplicationRestart.prepare(command, root);
                    try { Files.writeString(root.resolve("ready"), "prepared"); }
                    catch (IOException failure) { pending.close(); throw failure; }
                    return pending;
                });
                application.getFrame().setBounds(20, 20, 1111, 777);
                application.getFrame().setVisible(true);
                EventQueue.invokeLater(application::requestRestart);
            });
        }
    }

    /** An appearance already loaded on the test thread, ready for installation on the EDT. */
    public static final class Startup {
        private final AppearanceTheme.Prepared prepared;

        /** Captures exactly the startup data used by main. */
        private Startup(AppearanceTheme.Prepared prepared) { this.prepared = prepared; }

        /** Installs appearance before constructing the same application components as a normal start. */
        public ApplicationWindow open() {
            if (!EventQueue.isDispatchThread()) throw new IllegalStateException("Open the window on the EDT.");
            String warning = AppearanceTheme.install(prepared);
            ApplicationWindow window = new ApplicationWindow(new ApplicationStorage(), prepared.settings(), warning);
            ((JFrame) window.getFrame()).setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            return window;
        }
    }

    /** Restores the standard zoom/theme on the EDT so later GUI cases do not inherit this test's appearance. */
    public static void resetTheme() {
        UIScale.setZoomFactor(1);
        FlatDarkLaf.setup();
    }

    /** Paints only the application window on the EDT; the test writes the returned image off the EDT. */
    public static BufferedImage snapshot(Window frame) {
        BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { frame.paintAll(graphics); } finally { graphics.dispose(); }
        return image;
    }
}
