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
            waitFor("all wrapped preview controls remain fully visible at " + width,
                    () -> GuiActionRunner.execute(() -> controlsVisible(preview.target())));
        }
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
        GuiActionRunner.execute(() -> mask.target().setSize(mask.target().getWidth(), 600));
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
            if (!child.isShowing()) continue;
            if ((child instanceof AbstractButton || child instanceof JSpinner || child instanceof JComboBox<?>)
                    && !fullyVisible((JComponent) child)) return false;
            if (child instanceof Container nested && !controlsVisible(nested)) return false;
        }
        return true;
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
