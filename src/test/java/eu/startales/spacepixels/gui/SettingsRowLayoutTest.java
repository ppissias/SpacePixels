package eu.startales.spacepixels.gui;

import org.junit.Test;
import javax.swing.*;
import java.awt.*;
import static org.junit.Assert.*;

/** Measures form reflow and native control sizing at narrow/wide widths and larger base fonts. */
public class SettingsRowLayoutTest {
    private static final String LONG_HELP = "This description includes <b>important units in pixels</b> and several "
            + "lines of guidance about the effect of a setting. ".repeat(12);

    /** Long descriptions grow in narrow forms, retain all lines and keep controls inside the allocated row. */
    @Test public void rowsReflowInsteadOfClippingDescriptions() throws Exception {
        EventQueue.invokeAndWait(() -> {
            Font original = UIManager.getFont("Label.font");
            try {
                for (int fontSize : new int[]{13, 20, 26}) {
                    UIManager.put("Label.font", new Font(Font.SANS_SERIF, Font.PLAIN, UiStyle.space(fontSize)));
                    JPanel parent = new JPanel();
                    parent.setLayout(new BoxLayout(parent, BoxLayout.Y_AXIS));
                    JSpinner editor = new JSpinner(new SpinnerNumberModel(1, 0, 100, 1));
                    SettingsRowPanel row = new SettingsRowPanel(new JLabel("A setting (pixels)"), LONG_HELP, editor);
                    parent.add(row);
                    parent.setSize(UiStyle.space(1000), 10000);
                    int wideHeight = row.getPreferredSize().height;
                    parent.setSize(UiStyle.space(500), 10000);
                    int narrowHeight = row.getPreferredSize().height;
                    assertTrue("Narrow forms need more lines at font " + fontSize, narrowHeight > wideHeight);
                    assertTrue("Long help must exceed the retired 85 px cap", narrowHeight > UiStyle.space(85));
                    parent.doLayout();
                    layoutChildren(parent);
                    assertTrue(editor.getWidth() >= editor.getPreferredSize().width);
                    assertTrue(editor.getX() + editor.getWidth() <= editor.getParent().getWidth());
                    WrappingHelpLabel help = helpLabel(row);
                    assertTrue(help.getHeight() >= help.getPreferredSize().height);
                    assertEquals(narrowHeight, row.getMaximumSize().height);
                }
            } finally {
                UIManager.put("Label.font", original);
            }
        });
    }

    /** Shared sizing preserves look-and-feel editor heights and enabled guidance uses the readable theme color. */
    @Test public void nativeHeightsAndHelpTypographyRemainThemeDriven() throws Exception {
        EventQueue.invokeAndWait(() -> {
            JTextField editor = new JTextField(8);
            int naturalHeight = editor.getPreferredSize().height;
            UiStyle.inputWidth(editor, 200);
            assertEquals(naturalHeight, editor.getPreferredSize().height);
            assertTrue(editor.getPreferredSize().width >= UiStyle.space(200));
            WrappingHelpLabel help = new WrappingHelpLabel("Guidance", 480);
            assertEquals(UiStyle.bodyFont(), help.getFont());
            assertEquals(UiStyle.helpColor(), help.getForeground());
            int unborderedHeight = help.getPreferredSize().height;
            help.setBorder(BorderFactory.createEmptyBorder(5, 8, 7, 8));
            assertTrue(help.getPreferredSize().height >= unborderedHeight + 12);
        });
    }

    /** Lays out real Swing descendants so assertions cover allocated bounds as well as preferred sizes. */
    private static void layoutChildren(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) {
            if (child instanceof Container container) layoutChildren(container);
        }
    }

    /** Finds the description label owned by the form row without relying on private field reflection. */
    private static WrappingHelpLabel helpLabel(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof WrappingHelpLabel help) return help;
            if (child instanceof Container container) {
                WrappingHelpLabel found = helpLabel(container);
                if (found != null) return found;
            }
        }
        return null;
    }
}
