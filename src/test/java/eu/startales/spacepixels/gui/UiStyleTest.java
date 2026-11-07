package eu.startales.spacepixels.gui;

import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.BadLocationException;
import javax.swing.text.Position;
import javax.swing.text.View;
import java.awt.Rectangle;

import static org.junit.Assert.assertTrue;

public class UiStyleTest {

    /** The note under Auto-Tune lost the day of its date when its lines were wrapped wider than the label. */
    @Test
    public void wrappedHtmlKeepsEveryCharacterInsideItsColumn() throws BadLocationException {
        String note = "Settings: High, auto-tuned, run 2026-10-29 15:06<br>Auto-tune saved: run at 2026-10-29 15:06 (4 min 56 s)";
        for (int column : new int[]{180, 213, 400}) {
            View view = htmlView(UiStyle.htmlWrappedTo(column, note));
            Rectangle area = new Rectangle(0, 0, column, 400);
            view.setSize(column, 400);
            for (int position = 0; position < view.getDocument().getLength(); position++) {
                double right = view.modelToView(position, area, Position.Bias.Forward).getBounds2D().getMaxX();
                assertTrue("Character " + position + " ends at " + right + " in a column of " + column, right <= column);
            }
        }
    }

    @Test
    public void wrappedHtmlUsesItsWholeColumn() {
        for (int column : new int[]{120, 213, 400}) {
            float width = htmlView(UiStyle.htmlWrappedTo(column, "x")).getPreferredSpan(View.X_AXIS);
            assertTrue("Width " + width + " for a column of " + column, width <= column && width > column - 3);
        }
    }

    private static View htmlView(String html) {
        return (View) new JLabel(html).getClientProperty(BasicHTML.propertyKey);
    }
}
