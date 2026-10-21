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
