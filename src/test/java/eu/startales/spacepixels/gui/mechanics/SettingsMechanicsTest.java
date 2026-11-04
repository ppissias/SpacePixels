package eu.startales.spacepixels.gui.mechanics;

import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.fixture.JListFixture;
import org.assertj.swing.fixture.JSpinnerFixture;
import org.assertj.swing.fixture.JTextComponentFixture;
import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.Component;
import java.awt.Rectangle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Detection Settings: every page, a rejected value, Revert, and Save, on the test's own home.
 * Run with {@code ./gradlew guiMechanicsTest}.
 */
public class SettingsMechanicsTest extends MechanicsTestCase {

    private static final String DECIMAL = "Detection Sigma";
    private static final String WHOLE = "Streak Min Pixels";

    @Test(timeout = 180_000)
    public void pagesOpenAndInvalidTextRevertsAndSaves() throws Exception {
        importSyntheticSequence();
        window().tabbedPane().selectTab("Detection Settings");
        window().checkBox(VisibleText.checkBox("Show expert settings")).check();

        requireVisible("Overview", "Detection Sigma");
        requireVisible("Streak Detection", "Single Streak Min Peak Sigma");
        requireVisible("Streak Detection", "Streak Shape And Linking");
        requireVisible("Quality Control", "Strictness");
        requireVisible("Track Linking", "Reject Camera-Fixed Sources");
        requireVisible("Anomaly Detection", "Enable Anomaly Rescue");
        requireVisible("Slow Movers", "Enable Slow-Mover Detection");
        requireVisible("Faint Movers (experimental)", "Search for faint movers");
        requireVisible("Residual Analysis", "Enable Residual Transient Analysis");
        requireVisible("Variable Stars", "Enable Variable-Star Detection");
        requireVisible("Report Visualization", "Auto Stretch Black Sigma");

        showPage("Overview");
        requireLettersRejected(DECIMAL, "enter a number");
        showPage("Streak Detection");
        requireLettersRejected(WHOLE, "enter a whole number");

        showPage("Overview");
        JSpinner sigma = spinner(DECIMAL);
        double savedSigma = number(sigma);
        double changedSigma = nudge(sigma);
        showPage("Streak Detection");
        JSpinner pixels = spinner(WHOLE);
        double savedPixels = number(pixels);
        double changedPixels = nudge(pixels);
        waitFor("the footer says the settings are unsaved",
                () -> showingLabel("Unsaved changes").contains("Unsaved changes"));

        window().button(VisibleText.button("Revert")).click();
        waitFor("Revert names itself", () -> showingLabel("Reverted to the saved settings").contains("Reverted to the saved settings"));
        showPage("Overview");
        assertEquals(savedSigma, number(spinner(DECIMAL)), 0.02);
        showPage("Streak Detection");
        assertEquals(savedPixels, number(spinner(WHOLE)), 0.02);

        showPage("Overview");
        changedSigma = nudge(spinner(DECIMAL));
        showPage("Streak Detection");
        changedPixels = nudge(spinner(WHOLE));
        window().button(VisibleText.button("Save")).click();
        waitFor("the footer says the settings were saved",
                () -> showingLabel("Saved settings").contains("Saved settings"));

        reopenWindow();
        importSyntheticSequence();
        window().tabbedPane().selectTab("Detection Settings");
        window().checkBox(VisibleText.checkBox("Show expert settings")).check();
        showPage("Overview");
        assertEquals(changedSigma, number(spinner(DECIMAL)), 0.02);
        showPage("Streak Detection");
        assertEquals(changedPixels, number(spinner(WHOLE)), 0.02);
    }

    /** The new page shares its Overview switch and saves minutes in canonical engine seconds. */
    @Test(timeout = 180_000)
    public void faintMoversAreOptInAndSettingsSurviveSaveAndReopen() throws Exception {
        importSyntheticSequence();
        window().tabbedPane().selectTab("Detection Settings");
        showPage("Faint Movers (experimental)");
        window().checkBox(VisibleText.checkBox("Search for faint movers")).requireNotSelected().check();
        assertEquals(1.0, number(spinner("Fastest motion")), 0.001);
        assertEquals(10.0, number(spinner("Time limit (minutes)")), 0.001);
        field(spinner("Time limit (minutes)")).setText("0.5");
        field(spinner("Fastest motion")).setText("0.123");
        window().button(VisibleText.button("Save")).click();
        waitFor("faint mover settings saved", () -> showingLabel("Saved settings").contains("Saved settings"));
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(System.getProperty("user.home"),
                eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO.DEFAULT_FILENAME))) {
            var config = eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO.load(reader).getDetectionConfig();
            assertTrue(config.enableFaintMoverSearch);
            assertEquals(30, config.faintMoverMaxSeconds, 1e-9);
            assertEquals(0.123, config.faintMoverMaxSpeedPixelsPerMinute, 1e-9);
        }
        showPage("Overview");
        window().checkBox(VisibleText.checkBox("Faint movers (experimental)")).requireSelected().uncheck();
        showPage("Faint Movers (experimental)");
        window().checkBox(VisibleText.checkBox("Search for faint movers")).requireNotSelected();
        window().button(VisibleText.button("Revert")).click();
        window().checkBox(VisibleText.checkBox("Search for faint movers")).requireSelected();
        reopenWindow();
        importSyntheticSequence();
        window().tabbedPane().selectTab("Detection Settings");
        showPage("Faint Movers (experimental)");
        window().checkBox(VisibleText.checkBox("Search for faint movers")).requireSelected();
        assertEquals(0.5, number(spinner("Time limit (minutes)")), 1e-9);
        assertEquals(0.123, number(spinner("Fastest motion")), 1e-9);
        var solved = eu.startales.spacepixels.testsupport.SyntheticDatasetFactory.createSolvedMono16FitsSequence(
                folder.getRoot().toPath(), "solved", 8).toFile();
        GuiActionRunner.execute(() -> application().importFolder(solved));
        waitFor("solved session installed", () -> GuiActionRunner.execute(() -> application().getSession() != null
                && new java.io.File(application().getSession().getFiles()[0].getFilePath()).getParentFile().equals(solved)
                && application().getJobCoordinator().current() == null));
        window().tabbedPane().selectTab("Detection Settings");
        showPage("Faint Movers (experimental)");
        assertTrue(controlIsShowing("arcsec/min"));
        var file = GuiActionRunner.execute(() -> application().getSession().getFiles()[0]);
        var wcs = eu.startales.spacepixels.fits.WcsCoordinateTransformer.fromHeader(file.getFitsHeader());
        var a = wcs.pixelToSky(file.getSizeWidth() / 2.0, file.getSizeHeight() / 2.0);
        var b = wcs.pixelToSky(file.getSizeWidth() / 2.0 + 1, file.getSizeHeight() / 2.0);
        double scale = eu.startales.spacepixels.util.SkyMath.separationArcsec(a.getRaDegrees(), a.getDecDegrees(), b.getRaDegrees(), b.getDecDegrees());
        assertEquals(0.123 * scale, number(spinner("Fastest motion")), 1e-9);
        window().button(VisibleText.button("Save")).click();
        waitFor("converted units saved", () -> showingLabel("Saved settings").contains("Saved settings"));
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(System.getProperty("user.home"),
                eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO.DEFAULT_FILENAME))) {
            var config = eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO.load(reader).getDetectionConfig();
            assertEquals(0.123, config.faintMoverMaxSpeedPixelsPerMinute, 1e-9);
        }
    }

    /** Readable converted values do not quantize precise canonical settings during an unchanged save. */
    @Test(timeout = 180_000)
    public void unchangedConvertedControlsRetainPreciseCanonicalValues() throws Exception {
        var precise = new io.github.ppissias.jtransient.config.DetectionConfig();
        precise.enableFaintMoverSearch = true;
        precise.faintMoverMaxSpeedPixelsPerMinute = 0.1234567890123;
        precise.faintMoverMaxSeconds = 1;
        try (var writer = java.nio.file.Files.newBufferedWriter(java.nio.file.Path.of(System.getProperty("user.home"),
                eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO.DEFAULT_FILENAME))) {
            eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO.write(writer, precise, 30);
        }
        reopenWindow();
        importSyntheticSequence();
        window().tabbedPane().selectTab("Detection Settings");
        showPage("Faint Movers (experimental)");
        assertEquals(0.123457, number(spinner("Fastest motion")), 0);
        assertEquals(0.0166667, number(spinner("Time limit (minutes)")), 0);
        window().button(VisibleText.button("Save")).click();
        waitFor("precise canonical values saved", () -> showingLabel("Saved settings").contains("Saved settings"));
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(System.getProperty("user.home"),
                eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO.DEFAULT_FILENAME))) {
            var config = eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO.load(reader).getDetectionConfig();
            assertEquals(precise.faintMoverMaxSpeedPixelsPerMinute, config.faintMoverMaxSpeedPixelsPerMinute, 0);
            assertEquals(precise.faintMoverMaxSeconds, config.faintMoverMaxSeconds, 0);
        }
    }

    /** Opens the page and requires one of its controls to be on screen. */
    private void requireVisible(String page, String control) {
        showPage(page);
        assertTrue(page + " shows " + control, controlIsShowing(control));
    }

    private void showPage(String title) {
        JListFixture list = pageList();
        int index = GuiActionRunner.execute(() -> pageIndex(list.target(), title));
        assertTrue("No settings page named " + title, index >= 0);
        // Selecting the row directly: a click's point can land on a group heading once the list has scrolled.
        GuiActionRunner.execute(() -> {
            JList<?> pages = list.target();
            pages.ensureIndexIsVisible(index);
            pages.setSelectedIndex(index);
        });
        robot().waitForIdle();
    }

    private JListFixture pageList() {
        return window().list(new GenericTypeMatcher<>(JList.class, true) {
            @Override
            protected boolean isMatching(JList component) {
                return component.getModel().getSize() > 5;
            }
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int pageIndex(JList list, String title) {
        for (int i = 0; i < list.getModel().getSize(); i++) {
            Component rendered = list.getCellRenderer().getListCellRendererComponent(
                    list, list.getModel().getElementAt(i), i, false, false);
            if (rendered instanceof JLabel label && label.getText() != null && label.getText().startsWith(title)) {
                return i;
            }
        }
        return -1;
    }

    private boolean controlIsShowing(String text) {
        try {
            window().label(plainOrHtml(text));
            return true;
        } catch (RuntimeException notALabel) {
            try {
                window().checkBox(VisibleText.checkBox(text));
                return true;
            } catch (RuntimeException notACheckBox) {
                return false;
            }
        }
    }

    private static GenericTypeMatcher<JLabel> plainOrHtml(String text) {
        return new GenericTypeMatcher<>(JLabel.class, true) {
            @Override
            protected boolean isMatching(JLabel label) {
                String shown = label.getText();
                return shown != null && shown.contains(text);
            }
        };
    }

    /** Types letters, requires the red outline and the footer, and requires the stored value to stay. */
    private void requireLettersRejected(String title, String footer) {
        JSpinner spinner = spinner(title);
        scrollTo(spinner);
        double kept = number(spinner);
        JTextComponentFixture field = field(spinner);
        String original = GuiActionRunner.execute(() -> field.target().getText());
        field.setText("abc");
        waitFor(title + " rejects letters", () -> "error".equals(outline(spinner))
                && showingLabel(footer).contains(footer)
                && Math.abs(number(spinner) - kept) < 0.001);
        field.setText(original);
        waitFor(title + " accepts its number again", () -> outline(spinner).isEmpty());
    }

    /** Moves the spinner by one step, toward the end that has room, and returns the new value. */
    private double nudge(JSpinner spinner) {
        scrollTo(spinner);
        double value = number(spinner);
        SpinnerNumberModel model = GuiActionRunner.execute(() -> (SpinnerNumberModel) spinner.getModel());
        double step = model.getStepSize().doubleValue();
        double max = ((Number) model.getMaximum()).doubleValue();
        JSpinnerFixture fixture = new JSpinnerFixture(robot(), spinner);
        if (value + step <= max + 1e-9) {
            fixture.increment();
        } else {
            fixture.decrement();
        }
        return number(spinner);
    }

    private JSpinner spinner(String title) {
        return GuiActionRunner.execute(() -> VisibleText.spinnerByLabel(window().target(), title));
    }

    private JTextComponentFixture field(JSpinner spinner) {
        JTextField text = GuiActionRunner.execute(() -> ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField());
        return new JTextComponentFixture(robot(), text);
    }

    private static void scrollTo(JSpinner spinner) {
        GuiActionRunner.execute(() -> spinner.scrollRectToVisible(new Rectangle(spinner.getSize())));
    }

    private static double number(JSpinner spinner) {
        return ((Number) GuiActionRunner.execute(spinner::getValue)).doubleValue();
    }

    private static String outline(JSpinner spinner) {
        Object value = GuiActionRunner.execute(() -> spinner.getClientProperty("JComponent.outline"));
        return value == null ? "" : value.toString();
    }
}
