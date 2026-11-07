package eu.startales.spacepixels.gui.mechanics;

import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.WindowFinder;
import org.junit.Test;

import javax.swing.JDialog;
import javax.swing.JLabel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The tips screen, opened from the tab bar: it goes through the tips, remembers its choice and blocks nothing. */
public class TipsMechanicsTest extends MechanicsTestCase {

    @Test(timeout = 120_000)
    public void tipsOpenStepAndClose() {
        window().button(VisibleText.button("Tips")).click();
        var tips = WindowFinder.findDialog(new GenericTypeMatcher<>(JDialog.class) {
            /** The showing tips screen. */
            @Override protected boolean isMatching(JDialog dialog) { return dialog.isShowing() && "SpacePixels tips".equals(dialog.getTitle()); }
        }).withTimeout(10_000).using(robot());
        tips.label(VisibleText.labelContaining("Did you know?")).requireVisible();
        var place = new GenericTypeMatcher<>(JLabel.class, true) {
            /** The place in the list, such as "3 of 22"; a tip's own text can also say "of". */
            @Override protected boolean isMatching(JLabel label) { return label.getText() != null && label.getText().matches("\\d+ of \\d+"); }
        };
        String first = GuiActionRunner.execute(() -> tips.label(place).target().getText());
        tips.button(VisibleText.button("Next ▶")).click();
        waitFor("the next tip", () -> !first.equals(GuiActionRunner.execute(() -> tips.label(place).target().getText())));
        tips.button(VisibleText.button("◀ Previous")).click();
        waitFor("back to the first tip", () -> first.equals(GuiActionRunner.execute(() -> tips.label(place).target().getText())));
        assertTrue("the main window stays usable", GuiActionRunner.execute(() -> window().target().isEnabled()));
        tips.checkBox(VisibleText.checkBox("Show tips at startup")).uncheck();
        tips.button(VisibleText.button("Close")).click();
        waitFor("the tips close", () -> !tips.target().isShowing());
        assertFalse(GuiActionRunner.execute(() -> tips.target().isShowing()));
    }
}
