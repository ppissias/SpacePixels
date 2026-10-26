package eu.startales.spacepixels.gui.mechanics;

import com.formdev.flatlaf.FlatLaf;
import com.google.gson.JsonParser;
import eu.startales.spacepixels.gui.AppearanceTestSupport;
import eu.startales.spacepixels.gui.ApplicationWindow;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.fixture.FrameFixture;
import org.assertj.swing.finder.WindowFinder;
import org.junit.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

/** Exercises saved appearance, failed saves, startup themes, large text and real image viewers through the GUI. */
public class AppearanceMechanicsTest extends MechanicsTestCase {
    /** Appearance works before import, saves independently, and applies to newly constructed windows after restart. */
    @Test(timeout = 180_000)
    public void savesAppearanceAndRestoresDefaultsWithoutChangingOtherSettings() throws Exception {
        window().tabbedPane().selectTab("Appearance");
        var appFile = Path.of(System.getProperty("user.home"), "spacepixels_app.json");
        String before = Files.exists(appFile) ? Files.readString(appFile) : null;
        window().comboBox(named(JComboBox.class, "Theme")).selectItem("High contrast dark");
        GuiActionRunner.execute(() -> spinner(window()).setValue(150));
        saveForLater(window());
        waitFor("appearance saved", () -> status(window()).contains("Appearance saved"));
        var saved = JsonParser.parseString(Files.readString(AppearanceTestSupport.preferencesFile().toPath())).getAsJsonObject();
        assertEquals("HIGH_CONTRAST_DARK", saved.get("theme").getAsString());
        assertEquals(150, saved.get("scalePercent").getAsInt());
        assertEquals(before, Files.exists(appFile) ? Files.readString(appFile) : null);

        AppearanceTestSupport.Startup startup = AppearanceTestSupport.readSaved();
        ApplicationWindow restarted = GuiActionRunner.execute(startup::open);
        FrameFixture next = new FrameFixture(robot(), restarted.getFrame());
        try {
            next.show();
            fit(restarted);
            next.tabbedPane().selectTab("Appearance");
            assertEquals(Color.WHITE, GuiActionRunner.execute(() -> UIManager.getColor("Label.foreground")));
            assertEquals(150, ((Number) GuiActionRunner.execute(() -> spinner(next).getValue())).intValue());
            capture(restarted, "saved-high-contrast-150");
            next.button(VisibleText.button("Restore defaults")).click();
            assertEquals(100, ((Number) GuiActionRunner.execute(() -> spinner(next).getValue())).intValue());
            assertEquals("Dark", GuiActionRunner.execute(() -> next.comboBox(named(JComboBox.class, "Theme"))
                    .target().getSelectedItem().toString()));
            assertEquals(150, JsonParser.parseString(Files.readString(AppearanceTestSupport.preferencesFile().toPath()))
                    .getAsJsonObject().get("scalePercent").getAsInt());
            saveForLater(next);
            waitFor("defaults saved", () -> status(next).contains("Appearance saved"));
            var defaults = JsonParser.parseString(Files.readString(AppearanceTestSupport.preferencesFile().toPath())).getAsJsonObject();
            assertEquals("DARK", defaults.get("theme").getAsString());
            assertEquals(100, defaults.get("scalePercent").getAsInt());
        } finally {
            close(restarted);
            GuiActionRunner.execute(AppearanceTestSupport::resetTheme);
        }
    }

    /** Unreadable custom files report a failed save and keep the last successfully saved preferences. */
    @Test(timeout = 180_000)
    public void anUnavailableCustomFileDoesNotReplaceSavedPreferences() throws Exception {
        window().tabbedPane().selectTab("Appearance");
        saveForLater(window());
        waitFor("defaults saved", () -> status(window()).contains("Appearance saved"));
        String previous = Files.readString(AppearanceTestSupport.preferencesFile().toPath());
        window().textBox(named(JTextField.class, "Custom style file (optional)"))
                .setText(new File(folder.getRoot(), "missing.properties").toString());
        window().button(VisibleText.button("Save appearance")).click();
        waitFor("failed save explained", () -> status(window()).contains("Appearance was not saved"));
        assertEquals(previous, Files.readString(AppearanceTestSupport.preferencesFile().toPath()));
        window().button(VisibleText.button("Save appearance")).requireEnabled();
    }

    /** Both colour schemes and large sizes keep the form's actions reachable by real Tab traversal. */
    @Test(timeout = 180_000)
    public void themesAndLargeFontsKeepAppearanceControlsReachable() throws Exception {
        for (String theme : new String[]{"DARK", "HIGH_CONTRAST_DARK", "LIGHT"}) {
            int percent = theme.equals("DARK") ? 100 : theme.equals("LIGHT") ? 300 : 200;
            ApplicationWindow opened = GuiActionRunner.execute(() -> AppearanceTestSupport.builtIn(theme, percent).open());
            FrameFixture fixture = new FrameFixture(robot(), opened.getFrame());
            try {
                fixture.show();
                fit(opened);
                fixture.tabbedPane().selectTab("Appearance");
                assertEquals(theme.equals("LIGHT"), GuiActionRunner.execute(() -> !FlatLaf.isLafDark()).booleanValue());
                JComponent first = fixture.comboBox(named(JComboBox.class, "Theme")).target();
                GuiActionRunner.execute(() -> {
                    assertEquals("Theme uses the width of its options", first.getPreferredSize().width, first.getWidth());
                    JSpinner size = spinner(fixture);
                    assertEquals("Size editor stays compact", size.getPreferredSize().width, size.getWidth());
                    var path = fixture.textBox(named(JTextField.class, "Custom style file (optional)")).target();
                    assertEquals("Path uses its column width", path.getPreferredSize().width, path.getWidth());
                });
                JButton save = fixture.button(VisibleText.button("Save appearance")).target();
                GuiActionRunner.execute(() -> { first.requestFocusInWindow(); });
                waitFor("first appearance control focused", () -> GuiActionRunner.execute(first::isFocusOwner));
                for (int step = 0; step < 20 && !GuiActionRunner.execute(save::isFocusOwner); step++) {
                    robot().pressAndReleaseKeys(java.awt.event.KeyEvent.VK_TAB);
                    robot().waitForIdle();
                }
                assertTrue("Tab reaches Save at " + percent + "%", GuiActionRunner.execute(save::isFocusOwner));
                assertTrue("Save is scrolled fully into view", GuiActionRunner.execute(() ->
                        save.getVisibleRect().height >= save.getHeight() - 1));
                capture(opened, theme.toLowerCase() + "-" + percent);
            } finally {
                close(opened);
                GuiActionRunner.execute(AppearanceTestSupport::resetTheme);
            }
        }
    }

    /** The high-contrast large-text application still imports real FITS frames and presents its normal controls. */
    @Test(timeout = 180_000)
    public void highContrastLargeTextImportsARealDataset() throws Exception {
        String realRoot = System.getProperty("spacepixels.testdata.root");
        assumeNotNull(realRoot);
        ApplicationWindow opened = GuiActionRunner.execute(() -> AppearanceTestSupport.builtIn("HIGH_CONTRAST_DARK", 150).open());
        FrameFixture fixture = new FrameFixture(robot(), opened.getFrame());
        try {
            fixture.show();
            fit(opened);
            GuiActionRunner.execute(() -> opened.importFolder(new File(realRoot, "m5-reg")));
            waitFor("real FITS dataset imported", () -> GuiActionRunner.execute(() ->
                    opened.getSession() != null && opened.getJobCoordinator().current() == null));
            assertTrue(GuiActionRunner.execute(() -> opened.getSession().getFiles().length > 3));
            capture(opened, "real-data-high-contrast-150");
        } finally {
            close(opened);
            GuiActionRunner.execute(AppearanceTestSupport::resetTheme);
        }
    }

    /** At the largest allowed size, native window limits fit the display and Tab reveals the scrolled trial actions. */
    @Test(timeout = 180_000) public void largestTextKeepsPreviewControlsReachableAtItsMinimumSize() throws Exception {
        var sequence = eu.startales.spacepixels.testsupport.SyntheticDatasetFactory.createMono16FitsSequence(
                folder.newFolder("large-viewer").toPath(), "session", 5);
        ApplicationWindow opened = GuiActionRunner.execute(() -> AppearanceTestSupport.builtIn("LIGHT", 300).open());
        FrameFixture fixture = new FrameFixture(robot(), opened.getFrame());
        FrameFixture preview = null;
        try {
            fixture.show();
            fit(opened);
            GuiActionRunner.execute(() -> opened.importFolder(sequence.toFile()));
            waitFor("large-text session imported", () -> GuiActionRunner.execute(() ->
                    opened.getSession() != null && opened.getJobCoordinator().current() == null));
            GuiActionRunner.execute(() -> {
                fixture.table().target().setRowSelectionInterval(0, 0);
                fixture.button(VisibleText.button("Preview Frame")).target().doClick();
            });
            preview = WindowFinder.findFrame(new GenericTypeMatcher<>(JFrame.class, true) {
                /** Matches this test's preview rather than the main application. */
                @Override protected boolean isMatching(JFrame frame) { return frame.getTitle().startsWith("Preview Frame"); }
            }).withTimeout(15_000).using(robot());
            JFrame frame = (JFrame) preview.target();
            GuiActionRunner.execute(() -> { frame.setSize(80, 60); frame.toFront(); });
            robot().waitForIdle();
            GuiActionRunner.execute(() -> {
                Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                assertTrue(frame.getMinimumSize().width <= usable.width);
                assertTrue(frame.getMinimumSize().height <= usable.height);
                assertTrue(frame.getWidth() >= frame.getMinimumSize().width);
                assertTrue(frame.getHeight() >= frame.getMinimumSize().height);
            });
            JSpinner trial = preview.spinner(named(JSpinner.class, "Detection Sigma")).target();
            JButton apply = preview.button(VisibleText.button("Apply to Settings")).target();
            GuiActionRunner.execute(() -> { ((JSpinner.DefaultEditor) trial.getEditor()).getTextField().requestFocusInWindow(); });
            for (int i = 0; i < 25 && !GuiActionRunner.execute(apply::isFocusOwner); i++) {
                robot().pressAndReleaseKeys(java.awt.event.KeyEvent.VK_TAB);
                robot().waitForIdle();
            }
            assertTrue("Tab reaches the trial action", GuiActionRunner.execute(apply::isFocusOwner));
            assertTrue("Focused action is fully revealed", GuiActionRunner.execute(() ->
                    apply.getVisibleRect().height >= apply.getHeight() - 1 && apply.getVisibleRect().width >= apply.getWidth() - 1));
            capture(frame, "preview-minimum-light-300");
        } finally {
            if (preview != null) {
                JFrame frame = (JFrame) preview.target();
                GuiActionRunner.execute(frame::dispose);
            }
            close(opened);
            GuiActionRunner.execute(AppearanceTestSupport::resetTheme);
        }
    }

    /** Standard file popups enforce their font-aware limits while retaining their approve and cancel actions. */
    @Test(timeout = 120_000) public void fileChooserRetainsItsActionsAtNormalAndLargestTextSizes() throws Exception {
        for (int percent : new int[]{100, 300}) {
            ApplicationWindow opened = GuiActionRunner.execute(() -> AppearanceTestSupport.builtIn("DARK", percent).open());
            FrameFixture fixture = new FrameFixture(robot(), opened.getFrame());
            try {
                fixture.show();
                fit(opened);
                fixture.tabbedPane().selectTab("Appearance");
                JButton browse = fixture.button(VisibleText.button("Browse…")).target();
                EventQueue.invokeLater(browse::doClick);
                var popup = WindowFinder.findDialog(new GenericTypeMatcher<>(JDialog.class, true) {
                    /** Matches only the custom-properties file chooser. */
                    @Override protected boolean isMatching(JDialog dialog) { return "Choose appearance properties".equals(dialog.getTitle()); }
                }).withTimeout(10_000).using(robot());
                GuiActionRunner.execute(() -> popup.target().setSize(80, 60));
                robot().waitForIdle();
                JButton approve = popup.button(VisibleText.button("Open")).target();
                JButton cancel = popup.button(VisibleText.button("Cancel")).target();
                GuiActionRunner.execute(() -> {
                    Dialog dialog = popup.target();
                    Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                    assertTrue(dialog.getWidth() >= dialog.getMinimumSize().width);
                    assertTrue(dialog.getHeight() >= dialog.getMinimumSize().height);
                    assertTrue(dialog.getMinimumSize().width <= screen.width);
                    assertTrue(dialog.getMinimumSize().height <= screen.height);
                    for (JButton button : new JButton[]{approve, cancel}) {
                        assertEquals(button.getWidth(), button.getVisibleRect().width);
                        assertEquals(button.getHeight(), button.getVisibleRect().height);
                    }
                });
                capture(popup.target(), "file-chooser-minimum-" + percent);
                GuiActionRunner.execute(() -> { cancel.doClick(); });
            } finally {
                close(opened);
                GuiActionRunner.execute(AppearanceTestSupport::resetTheme);
            }
        }
    }

    /** Saves and chooses the explicit Later option, never relaunching the test JVM. */
    private void saveForLater(FrameFixture fixture) {
        fixture.button(VisibleText.button("Save appearance")).click();
        WindowFinder.findDialog(new GenericTypeMatcher<>(JDialog.class, true) {
            /** Finds only the save's restart choice, not other application dialogs. */
            @Override protected boolean isMatching(JDialog dialog) { return "Restart SpacePixels".equals(dialog.getTitle()); }
        }).withTimeout(10_000).using(robot()).button(VisibleText.button("Later")).click();
    }

    /** Matches an input by the accessible name supplied by its visible label. */
    private static <T extends JComponent> GenericTypeMatcher<T> named(Class<T> type, String name) {
        return new GenericTypeMatcher<>(type, true) {
            /** Uses the same names that keyboard and assistive-technology users encounter. */
            @Override protected boolean isMatching(T component) {
                return name.equals(component.getAccessibleContext().getAccessibleName());
            }
        };
    }

    /** The single interface-size spinner in the selected Appearance page. */
    private static JSpinner spinner(FrameFixture fixture) {
        return fixture.spinner(named(JSpinner.class, "Interface size (%)")).target();
    }

    /** Reads the current appearance message on the EDT, including worker completion. */
    private static String status(FrameFixture fixture) {
        return GuiActionRunner.execute(() -> fixture.label(named(JLabel.class, "Appearance status")).target().getText());
    }

    /** Keeps test windows within the desktop while exercising a narrow, scrollable form. */
    private void fit(ApplicationWindow application) {
        GuiActionRunner.execute(() -> {
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            application.getFrame().setBounds(screen.x + 10, screen.y + 10, Math.min(1150, screen.width - 20), Math.min(800, screen.height - 30));
            application.getFrame().toFront();
        });
        robot().waitForIdle();
    }

    /** Disposes this window and drains its writes without shutting down the robot shared by the test. */
    private static void close(ApplicationWindow application) throws Exception {
        GuiActionRunner.execute(() -> application.getFrame().dispose());
        application.getStorage().finish();
    }

    /** Writes optional review images outside the repository after capturing the application on the EDT. */
    private void capture(ApplicationWindow application, String name) throws Exception {
        capture(application.getFrame(), name);
    }

    /** Captures viewer layout evidence with the same scratch-only output as the main appearance screenshots. */
    private void capture(Window frame, String name) throws Exception {
        String root = System.getProperty("spacepixels.appearance.screenshots");
        if (root == null) return;
        var image = GuiActionRunner.execute(() -> AppearanceTestSupport.snapshot(frame));
        Path directory = Files.createDirectories(Path.of(root));
        ImageIO.write(image, "png", directory.resolve(name + ".png").toFile());
    }
}
