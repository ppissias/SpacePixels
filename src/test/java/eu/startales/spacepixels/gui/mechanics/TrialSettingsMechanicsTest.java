package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.Test;

import javax.swing.*;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Exercises the previews' real controls, independent trials, selective Apply and shared application job lock. */
public class TrialSettingsMechanicsTest extends MechanicsTestCase {
    /** Preview trials do not change live settings, and Apply cannot restore untouched values from an old snapshot. */
    @Test(timeout = 180_000)
    public void previewAppliesOnlyEditedValuesAndClosingDiscardsUnappliedTrials() throws Exception {
        importSyntheticSequence();
        JSpinner sigma = mainSpinner("Detection Sigma");
        JSpinner grow = mainSpinner("Grow Sigma (Hysteresis)");
        JSpinner minimum = mainSpinner("Min Detection Pixels");
        JSpinner jitter = mainSpinner("Base Star Jitter Radius");
        GuiActionRunner.execute(() -> {
            sigma.setValue(5.0);
            grow.setValue(3.0);
            minimum.setValue(8);
        });
        FrameFixture preview = openPreview();
        JSpinner trial = spinner(preview, "Detection Sigma");
        assertNotSame(sigma.getModel(), trial.getModel());
        GuiActionRunner.execute(() -> {
            JSpinner trialGrow = spinner(preview, "Grow Sigma (Hysteresis)");
            trialGrow.setValue(2.0);
            trialGrow.setValue(3.0);
            trial.setValue(2.0);
        });
        assertNumber(sigma, 5);
        GuiActionRunner.execute(() -> {
            grow.setValue(1.0);
            minimum.setValue(17);
            jitter.setValue(6.0);
            preview.button(VisibleText.button("Apply to Settings")).target().doClick();
        });
        assertNumber(sigma, 2);
        assertNumber(grow, 1);
        assertNumber(minimum, 17);
        assertNumber(jitter, 6);

        GuiActionRunner.execute(() -> {
            sigma.setValue(7.0);
            preview.button(VisibleText.button("Apply to Settings")).target().doClick();
        });
        assertNumber(sigma, 7);
        GuiActionRunner.execute(() -> {
            trial.setValue(4.0);
            preview.target().dispatchEvent(new WindowEvent(preview.target(), WindowEvent.WINDOW_CLOSING));
        });
        assertFalse(GuiActionRunner.execute(preview.target()::isShowing));
        FrameFixture reopened = openPreview();
        assertNumber(spinner(reopened, "Detection Sigma"), 7);
        assertNumber(sigma, 7);
    }

    /** Real Enter/Escape events commit or discard trial text while arrows resume image navigation afterward. */
    @Test(timeout = 180_000)
    public void typedPreviewValuesStayLocalAndInvalidTextDoesNotApply() throws Exception {
        importSyntheticSequence();
        JSpinner live = mainSpinner("Detection Sigma");
        double original = GuiActionRunner.execute(() -> ((Number) live.getValue()).doubleValue());
        FrameFixture preview = openPreview();
        JSpinner trial = spinner(preview, "Detection Sigma");
        JFormattedTextField field = GuiActionRunner.execute(() -> ((JSpinner.DefaultEditor) trial.getEditor()).getTextField());
        JComponent image = image(preview);
        focus(field);
        GuiActionRunner.execute(() -> field.setText("6.5"));
        robot().pressAndReleaseKeys(KeyEvent.VK_ENTER);
        waitFor("Enter returns to the image", () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == image);
        assertNumber(trial, 6.5);
        assertNumber(live, original);
        focus(field);
        GuiActionRunner.execute(() -> field.setText("99999"));
        robot().pressAndReleaseKeys(KeyEvent.VK_ENTER);
        robot().waitForIdle();
        assertSame(field, KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
        preview.button(VisibleText.button("Apply to Settings")).requireDisabled();
        assertNumber(live, original);
        robot().pressAndReleaseKeys(KeyEvent.VK_ESCAPE);
        waitFor("Escape discards text and returns to the image", () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == image);
        assertNumber(trial, 6.5);
        preview.button(VisibleText.button("Apply to Settings")).requireEnabled();
        robot().pressAndReleaseKeys(KeyEvent.VK_RIGHT);
        waitFor("image navigation resumes", () -> preview.label(VisibleText.labelContaining("Frame 2 of")).target().isShowing());
    }

    /** Two mask windows merge their own edited fields, and both mask and frame controls pause during a real job slot. */
    @Test(timeout = 240_000)
    public void maskWindowsMergeSeparateEditsAndAllTrialWindowsFollowTheJobLock() throws Exception {
        importSyntheticSequence();
        JSpinner sigma = mainSpinner("Master Sigma");
        JSpinner grow = mainSpinner("Master Grow Sigma");
        JSpinner jitter = mainSpinner("Base Star Jitter Radius");
        GuiActionRunner.execute(() -> {
            sigma.setValue(5.0);
            grow.setValue(3.0);
            jitter.setValue(2.0);
        });
        FrameFixture preview = openPreview();
        FrameFixture first = openMask(null);
        FrameFixture second = openMask(first.target());
        GuiActionRunner.execute(() -> {
            spinner(first, "Master Sigma").setValue(2.0);
            spinner(second, "Base Star Jitter Radius").setValue(5.0);
            second.button(VisibleText.button("Apply to Settings")).target().doClick();
            grow.setValue(1.0);
            first.button(VisibleText.button("Apply to Settings")).target().doClick();
        });
        assertNumber(sigma, 2);
        assertNumber(grow, 1);
        assertNumber(jitter, 5);

        JFormattedTextField maskField = GuiActionRunner.execute(() ->
                ((JSpinner.DefaultEditor) spinner(first, "Master Sigma").getEditor()).getTextField());
        JComponent maskImage = robot().finder().find(first.target(), new GenericTypeMatcher<>(JComponent.class, true) {
            /** Matches the shared image by its accessible image role. */
            @Override protected boolean isMatching(JComponent component) {
                return component.isFocusable() && component.getAccessibleContext() != null
                        && component.getAccessibleContext().getAccessibleName() != null
                        && component.getAccessibleContext().getAccessibleName().startsWith("Star Mask Explorer image");
            }
        });
        GuiActionRunner.execute(() -> first.target().toFront());
        focus(maskField);
        GuiActionRunner.execute(() -> maskField.setText("6.0"));
        robot().pressAndReleaseKeys(KeyEvent.VK_ENTER);
        waitFor("mask Enter returns to the image", () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == maskImage);
        assertNumber(sigma, 2);
        focus(maskField);
        GuiActionRunner.execute(() -> maskField.setText("99999"));
        robot().pressAndReleaseKeys(KeyEvent.VK_ENTER);
        first.button(VisibleText.button("Apply to Settings")).requireDisabled();
        assertFalse(GuiActionRunner.execute(() -> ((JFrame) first.target()).getRootPane().getActionMap().get("hideMask").isEnabled()));
        robot().pressAndReleaseKeys(KeyEvent.VK_ESCAPE);
        waitFor("mask Escape discards text and returns to the image", () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == maskImage);
        assertNumber(spinner(first, "Master Sigma"), 6);
        first.button(VisibleText.button("Apply to Settings")).requireEnabled();

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try {
            boolean admitted = GuiActionRunner.execute(() -> application().getJobCoordinator().start(
                    JobKind.AUTO_TUNE, application().getSession(), context -> {
                        started.countDown();
                        if (!finish.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("The test did not release its job.");
                        return JobOutcome.completed(null);
                    }, (handle, outcome) -> {}).admitted());
            assertTrue(admitted);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            GuiActionRunner.execute(() -> {
                assertFalse(spinner(preview, "Detection Sigma").isEnabled());
                assertFalse(spinner(first, "Master Sigma").isEnabled());
                assertFalse(spinner(second, "Base Star Jitter Radius").isEnabled());
                assertFalse(preview.button(VisibleText.button("Apply to Settings")).target().isEnabled());
                assertFalse(first.button(VisibleText.button("Apply to Settings")).target().isEnabled());
                assertFalse(second.button(VisibleText.button("Apply to Settings")).target().isEnabled());
            });
        } finally {
            finish.countDown();
        }
        waitFor("all trial controls unlock after the job", () -> GuiActionRunner.execute(() ->
                application().getJobCoordinator().current() == null && spinner(preview, "Detection Sigma").isEnabled()
                        && spinner(first, "Master Sigma").isEnabled() && spinner(second, "Base Star Jitter Radius").isEnabled()));
    }

    /** Opens Preview Frame through its existing main-panel command. */
    private FrameFixture openPreview() {
        GuiActionRunner.execute(() -> {
            application().getTabbedPane().setSelectedComponent(application().getMainApplicationPanel());
            application().getFrame().toFront();
            window().table().target().setRowSelectionInterval(0, 0);
            window().button(VisibleText.button("Preview Frame")).target().doClick();
        });
        FrameFixture preview = viewer("Preview Frame", null);
        waitFor("the preview displays a frame", () -> preview.label(VisibleText.labelContaining("Frame 1 of")).target().isShowing());
        return preview;
    }

    /** Opens the real mask explorer; a second request uses the same session's cached master stack. */
    private FrameFixture openMask(Window previous) {
        GuiActionRunner.execute(() -> {
            application().getTabbedPane().setSelectedComponent(application().getDetectionConfigurationPanel());
            application().getFrame().toFront();
            window().button(VisibleText.button("Test Star Mask…")).target().doClick();
        });
        FrameFixture mask = viewer("Star Mask Explorer", previous);
        waitFor("the mask-stack job has ended", () -> GuiActionRunner.execute(() -> application().getJobCoordinator().current() == null));
        return mask;
    }

    /** Finds a showing viewer by its title while optionally excluding an earlier window of the same kind. */
    private FrameFixture viewer(String title, Window excluded) {
        return WindowFinder.findFrame(new GenericTypeMatcher<>(JFrame.class) {
            /** Matches only the requested showing window. */
            @Override protected boolean isMatching(JFrame frame) {
                return frame != excluded && frame.isShowing() && frame.getTitle().startsWith(title);
            }
        }).withTimeout(30_000).using(robot());
    }

    /** Finds a live settings input even when the preview is in front of its tab. */
    private JSpinner mainSpinner(String name) {
        return spinner(window(), name);
    }

    /** Uses accessible setting identities rather than a component index or internal field. */
    private JSpinner spinner(FrameFixture frame, String name) {
        return frame.spinner(new GenericTypeMatcher<>(JSpinner.class) {
            /** Matches the field by the label supplied to assistive technology. */
            @Override protected boolean isMatching(JSpinner spinner) {
                return name.equals(spinner.getAccessibleContext().getAccessibleName());
            }
        }).target();
    }

    /** Locates the shared image component by its accessible role and title. */
    private JComponent image(FrameFixture preview) {
        return robot().finder().find(preview.target(), new GenericTypeMatcher<>(JComponent.class, true) {
            /** Matches the named preview image. */
            @Override protected boolean isMatching(JComponent component) {
                return component.getAccessibleContext() != null
                        && component.getAccessibleContext().getAccessibleName() != null
                        && component.getAccessibleContext().getAccessibleName().startsWith("Preview Frame image");
            }
        });
    }

    /** Requests deliberate field focus and waits for actual keyboard ownership. */
    private void focus(JComponent field) {
        GuiActionRunner.execute(() -> field.requestFocusInWindow());
        waitFor("the input owns keyboard focus", () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == field);
    }

    /** Reads the actual model value on the EDT. */
    private static void assertNumber(JSpinner spinner, double expected) {
        assertEquals(expected, GuiActionRunner.execute(() -> ((Number) spinner.getValue()).doubleValue()), 0);
    }
}
