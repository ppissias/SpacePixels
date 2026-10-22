package eu.startales.spacepixels.gui.mechanics;

import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.Test;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;

import javax.swing.*;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import static org.junit.Assert.*;

/** Drives real focus-owner key events to distinguish image navigation from viewer control editing. */
public class ViewerKeyboardMechanicsTest extends MechanicsTestCase {
    /** Tab reaches viewer controls, numeric/combo keys stay local, and Escape deliberately restores image focus. */
    @Test(timeout = 180_000) public void imageKeysAndControlKeysHaveSeparateOwners() throws Exception {
        importFrames();
        GuiActionRunner.execute(() -> {
            window().table().target().setRowSelectionInterval(0, 0);
            window().button(VisibleText.button("Preview Frame")).target().doClick();
        });
        FrameFixture preview = WindowFinder.findFrame(new GenericTypeMatcher<>(JFrame.class) {
            /** Finds the showing preview by its user-visible title. */
            @Override protected boolean isMatching(JFrame candidate) {
                return candidate.isShowing() && candidate.getTitle().startsWith("Preview Frame");
            }
        }).withTimeout(20_000).using(robot());
        waitFor("first preview frame", () -> caption(preview).contains("Frame 1 of"));
        JComponent image = robot().finder().find(preview.target(), new GenericTypeMatcher<>(JComponent.class) {
            /** Finds the image by its public accessibility role and name. */
            @Override protected boolean isMatching(JComponent candidate) {
                return candidate.isShowing() && candidate.getAccessibleContext() != null
                        && candidate.getAccessibleContext().getAccessibleName() != null
                        && candidate.getAccessibleContext().getAccessibleName().startsWith("Preview Frame image");
            }
        });
        focus(image);
        robot().pressAndReleaseKeys(KeyEvent.VK_RIGHT);
        waitFor("image arrow steps", () -> caption(preview).contains("Frame 2 of"));
        robot().pressAndReleaseKeys(KeyEvent.VK_TAB);
        waitFor("Tab leaves image for controls", () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() != image);
        String before = caption(preview);
        robot().pressAndReleaseKeys(KeyEvent.VK_RIGHT);
        robot().waitForIdle();
        assertEquals(before, caption(preview));
        robot().pressAndReleaseKeys(KeyEvent.VK_ESCAPE);
        waitFor("Escape from tabbed control returns to image",
                () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == image);
        var combo = preview.comboBox();
        focus(combo.target());
        robot().pressAndReleaseKeys(KeyEvent.VK_DOWN);
        robot().pressAndReleaseKeys(KeyEvent.VK_RIGHT);
        robot().waitForIdle();
        assertEquals(before, caption(preview));
        robot().pressAndReleaseKeys(KeyEvent.VK_ESCAPE);
        focus(image);
        JSpinner spinner = preview.spinner(new GenericTypeMatcher<>(JSpinner.class, true) {
            /** Matches the numeric control by its associated visible title. */
            @Override protected boolean isMatching(JSpinner candidate) {
                return "Detection Sigma".equals(candidate.getAccessibleContext().getAccessibleName());
            }
        }).target();
        JFormattedTextField field = GuiActionRunner.execute(() -> ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField());
        focus(field);
        robot().pressAndReleaseKeys(KeyEvent.VK_RIGHT);
        robot().pressAndReleaseKeys(KeyEvent.VK_SPACE);
        robot().waitForIdle();
        assertEquals(before, caption(preview));
        robot().pressAndReleaseKeys(KeyEvent.VK_ESCAPE);
        waitFor("Escape returns to image", () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == image);
        robot().pressAndReleaseKeys(KeyEvent.VK_RIGHT);
        waitFor("navigation resumes", () -> caption(preview).contains("Frame 3 of"));
        robot().pressAndReleaseKeys(KeyEvent.VK_ESCAPE);
        waitFor("Escape on image closes", () -> !preview.target().isShowing());
    }

    /** Overview links and changed-row resets activate from actual Space events on focused buttons. */
    @Test(timeout = 180_000) public void settingsLinksAndResetWorkFromTheKeyboard() throws Exception {
        importFrames();
        GuiActionRunner.execute(() -> application().getTabbedPane()
                .setSelectedComponent(application().getDetectionConfigurationPanel()));
        JButton settings = window().button(new GenericTypeMatcher<>(JButton.class, true) {
            /** Locates the overview link by its accessible destination. */
            @Override protected boolean isMatching(JButton candidate) {
                return "Open Track Linking settings".equals(candidate.getAccessibleContext().getAccessibleName());
            }
        }).target();
        focus(settings);
        robot().pressAndReleaseKeys(KeyEvent.VK_SPACE);
        JCheckBox rejection = window().checkBox(new GenericTypeMatcher<>(JCheckBox.class, true) {
            /** Locates the detailed setting by its associated visible title. */
            @Override protected boolean isMatching(JCheckBox candidate) {
                return "Reject Camera-Fixed Sources".equals(candidate.getAccessibleContext().getAccessibleName());
            }
        }).target();
        boolean saved = GuiActionRunner.execute(rejection::isSelected);
        GuiActionRunner.execute(() -> rejection.setSelected(!saved));
        JButton reset = window().button(new GenericTypeMatcher<>(JButton.class, true) {
            /** Distinguishes the reset action for this setting from other dirty rows. */
            @Override protected boolean isMatching(JButton candidate) {
                return "Reset Reject Camera-Fixed Sources to saved value".equals(candidate.getAccessibleContext().getAccessibleName());
            }
        }).target();
        GuiActionRunner.execute(() -> reset.scrollRectToVisible(reset.getBounds()));
        focus(reset);
        robot().pressAndReleaseKeys(KeyEvent.VK_SPACE);
        waitFor("keyboard reset restores saved value", () -> saved == GuiActionRunner.execute(rejection::isSelected));
        assertFalse(GuiActionRunner.execute(reset::isVisible));
    }

    /** Installs disposable input through the public import action; these tests do not claim chooser coverage. */
    private void importFrames() throws Exception {
        var directory = SyntheticDatasetFactory.createMono16FitsSequence(folder.newFolder("frames").toPath(),
                "session", SyntheticDatasetFactory.DEFAULT_FRAME_COUNT).toFile();
        GuiActionRunner.execute(() -> application().importFolder(directory));
        waitFor("synthetic session installed", () -> GuiActionRunner.execute(() -> application().getSession() != null));
    }

    /** Requests deliberate keyboard focus and waits until the real focus owner accepts it. */
    private void focus(JComponent control) {
        GuiActionRunner.execute(() -> control.requestFocusInWindow());
        waitFor("focus reaches control", () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == control);
    }

    /** Reads the current visible frame status rather than inspecting viewer implementation state. */
    private String caption(FrameFixture preview) {
        return preview.label(new GenericTypeMatcher<>(JLabel.class, true) {
            /** Matches the frame readout by its user-visible prefix. */
            @Override protected boolean isMatching(JLabel label) {
                return label.getText() != null && label.getText().startsWith("Frame ");
            }
        }).target().getText();
    }
}
