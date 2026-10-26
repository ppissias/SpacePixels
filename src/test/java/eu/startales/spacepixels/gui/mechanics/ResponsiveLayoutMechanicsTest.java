package eu.startales.spacepixels.gui.mechanics;

import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;

import static org.junit.Assert.*;

/** Verifies actual viewport allocation and keyboard access after control rows wrap in real windows. */
public class ResponsiveLayoutMechanicsTest extends MechanicsTestCase {
    /** A wrapped workflow leaves selectable table rows, and viewer controls reflow after successive resizes. */
    @Test(timeout = 180_000) public void resizingRetainsTableAndPreviewControls() throws Exception {
        importSyntheticSequence();
        resize(application().getFrame(), 920);
        waitFor("the file table retains several visible rows", () -> GuiActionRunner.execute(() -> {
            JTable table = window().table().target();
            return table.getVisibleRect().height >= table.getRowHeight() * 3;
        }));
        GuiActionRunner.execute(() -> {
            window().table().target().setRowSelectionInterval(0, 0);
            window().button(VisibleText.button("Preview Frame")).target().doClick();
        });
        FrameFixture preview = viewer("Preview Frame");
        for (int width : new int[]{1400, 920, 1400}) {
            resize(preview.target(), width);
            try {
                waitFor("all wrapped preview controls remain fully visible at " + width,
                        () -> GuiActionRunner.execute(() -> controlsVisible(preview.target())));
            } catch (AssertionError failure) {
                throw new AssertionError("At width " + width + ": " + GuiActionRunner.execute(() -> clippedControls(preview.target())), failure);
            }
        }
        shrink(preview.target());
        waitFor("minimum preview size protects every wrapped control", () ->
                GuiActionRunner.execute(() -> controlsVisible(preview.target())));
        close(preview);
    }

    /** Tab must reveal the sidebar's Apply action even when the content exceeds the window height. */
    @Test(timeout = 180_000) public void starMaskActionsRemainReachableByKeyboard() throws Exception {
        importSyntheticSequence();
        GuiActionRunner.execute(() -> {
            application().getTabbedPane().setSelectedComponent(application().getDetectionConfigurationPanel());
            window().button(VisibleText.button("Test Star Mask…")).target().doClick();
        });
        FrameFixture mask = viewer("Star Mask Explorer");
        resize(mask.target(), 1100);
        shrink(mask.target());
        JButton apply = mask.button(VisibleText.button("Apply to Settings")).target();
        JFormattedTextField field = GuiActionRunner.execute(() ->
                ((JSpinner.DefaultEditor) mask.spinner(new GenericTypeMatcher<>(JSpinner.class, true) {
                    /** Selects the last trial input before the sidebar actions. */
                    @Override protected boolean isMatching(JSpinner spinner) {
                        return "Base Star Jitter Radius".equals(spinner.getAccessibleContext().getAccessibleName());
                    }
                }).target().getEditor()).getTextField());
        GuiActionRunner.execute(() -> { field.requestFocusInWindow(); });
        waitFor("the last trial field owns focus", () -> GuiActionRunner.execute(field::isFocusOwner));
        for (int i = 0; i < 12 && !GuiActionRunner.execute(apply::isFocusOwner); i++) {
            robot().pressAndReleaseKeys(KeyEvent.VK_TAB);
            robot().waitForIdle();
        }
        assertTrue("Tab reaches Apply", GuiActionRunner.execute(apply::isFocusOwner));
        assertTrue("Focused Apply is fully visible", GuiActionRunner.execute(() -> fullyVisible(apply)));
        close(mask);
    }

    /** Full-size stretch and Blink share the protected viewer sizing, including their additional control rows. */
    @Test(timeout = 180_000) public void stretchAndBlinkCannotHideTheirControlsByShrinking() throws Exception {
        var sequence = eu.startales.spacepixels.testsupport.SyntheticDatasetFactory.createMono16FitsSequence(
                folder.newFolder("viewer-sizing").toPath(), "session", 5);
        GuiActionRunner.execute(() -> application().importFolder(sequence.toFile()));
        waitFor("viewer sizing session imported", () -> GuiActionRunner.execute(() ->
                application().getSession() != null && application().getJobCoordinator().current() == null));
        window().tabbedPane().selectTab("Image Stretch");
        GuiActionRunner.execute(() -> window().button(VisibleText.button("Show full size")).target().doClick());
        FrameFixture stretch = viewer("Full Size");
        shrink(stretch.target());
        waitFor("stretch controls fit at the minimum", () -> GuiActionRunner.execute(() -> controlsVisible(stretch.target())));
        close(stretch);
        window().tabbedPane().selectTab("Main");
        GuiActionRunner.execute(() -> window().table().target().setRowSelectionInterval(0, 2));
        GuiActionRunner.execute(() -> window().button(VisibleText.button("Blink Selected")).target().doClick());
        FrameFixture blink = viewer("Blink");
        shrink(blink.target());
        waitFor("Blink controls fit at the minimum", () -> GuiActionRunner.execute(() -> controlsVisible(blink.target())));
        close(blink);
    }

    /** Attempts a deliberately unusable size, checking native minimum enforcement and physical-screen bounds. */
    private void shrink(Frame frame) {
        GuiActionRunner.execute(() -> { frame.setExtendedState(JFrame.NORMAL); frame.setSize(80, 60); });
        robot().waitForIdle();
        GuiActionRunner.execute(() -> {
            Dimension minimum = frame.getMinimumSize();
            Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            assertTrue("A useful minimum width is installed", minimum.width >= 600);
            assertTrue("A useful minimum height is installed", minimum.height >= 380);
            assertTrue("Native width limit is enforced", frame.getWidth() >= minimum.width);
            assertTrue("Native height limit is enforced", frame.getHeight() >= minimum.height);
            assertTrue("Minimum width fits the monitor", minimum.width <= usable.width);
            assertTrue("Minimum height fits the monitor", minimum.height <= usable.height);
        });
    }

    /** Resizes within the physical desktop so visibility assertions refer to usable on-screen windows. */
    private void resize(Frame frame, int width) {
        GuiActionRunner.execute(() -> {
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            frame.setExtendedState(JFrame.NORMAL);
            frame.setBounds(screen.x + 20, screen.y + 20, Math.min(width, screen.width - 40), Math.min(900, screen.height - 60));
            frame.toFront();
        });
        robot().waitForIdle();
    }

    /** Finds the showing viewer by its user-visible title. */
    private FrameFixture viewer(String title) {
        return WindowFinder.findFrame(new GenericTypeMatcher<>(JFrame.class) {
            /** Matches only this test's active viewer. */
            @Override protected boolean isMatching(JFrame frame) {
                return frame.isShowing() && frame.getTitle().startsWith(title);
            }
        }).withTimeout(30_000).using(robot());
    }

    /** Checks allocated control rectangles rather than preferred sizes alone. */
    private static boolean controlsVisible(Container parent) {
        for (Component child : parent.getComponents()) {
            if (!child.isShowing() || child instanceof JScrollBar) continue;
            if ((child instanceof AbstractButton || child instanceof JSpinner || child instanceof JComboBox<?>)
                    && !fullyVisible((JComponent) child)) return false;
            if (child instanceof Container nested && !controlsVisible(nested)) return false;
        }
        return true;
    }

    /** Describes allocated rectangles on failure, so a layout regression identifies the clipped input or action. */
    private static String clippedControls(Container parent) {
        StringBuilder result = new StringBuilder();
        for (Component child : parent.getComponents()) {
            if (!child.isShowing() || child instanceof JScrollBar) continue;
            if ((child instanceof AbstractButton || child instanceof JSpinner || child instanceof JComboBox<?>)
                    && !fullyVisible((JComponent) child)) result.append(child.getClass().getSimpleName()).append(' ')
                    .append(child instanceof AbstractButton button ? button.getText() : child.getAccessibleContext().getAccessibleName())
                    .append(" bounds=").append(child.getBounds()).append(" visible=").append(((JComponent) child).getVisibleRect()).append(';');
            if (child instanceof Container nested) result.append(clippedControls(nested));
        }
        return result.toString();
    }

    /** Allows only the one-pixel rounding used by native borders. */
    private static boolean fullyVisible(JComponent control) {
        Rectangle visible = control.getVisibleRect();
        return control.getWidth() > 0 && control.getHeight() > 0
                && visible.width >= control.getWidth() - 1 && visible.height >= control.getHeight() - 1;
    }

    /** Uses normal viewer cleanup instead of leaving workers alive between tests. */
    private void close(FrameFixture viewer) {
        GuiActionRunner.execute(() -> viewer.target().dispatchEvent(new WindowEvent(viewer.target(), WindowEvent.WINDOW_CLOSING)));
        waitFor("the viewer closes", () -> !GuiActionRunner.execute(viewer.target()::isShowing));
    }
}
