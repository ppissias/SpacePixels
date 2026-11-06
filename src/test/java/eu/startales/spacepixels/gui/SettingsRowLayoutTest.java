package eu.startales.spacepixels.gui;

import org.junit.Test;
import javax.swing.*;
import java.awt.*;
import static org.junit.Assert.*;

/** Measures form reflow and native control sizing at narrow/wide widths and larger base fonts. */
public class SettingsRowLayoutTest {
    private static final String LONG_HELP = "This description includes <b>important units in pixels</b> and several "
            + "lines of guidance about the effect of a setting. ".repeat(12);

    /** Input origins stay fixed across detailed pages, checkbox edits, Reset visibility and larger fonts. */
    @Test public void sharedColumnReservesResetAndAlignsDifferentControls() throws Exception {
        EventQueue.invokeAndWait(() -> {
            var column = new SettingsRowPanel.ControlColumn();
            var checkbox = new JCheckBox();
            var spinner = new JSpinner(new SpinnerNumberModel(1, 0, 100, 1));
            var units = new JLabel("arcsec/min");
            var input = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            input.add(spinner); input.add(units);
            var first = new SettingsRowPanel(new JLabel("Enable"), "Toggle this analysis", checkbox, column);
            var second = new SettingsRowPanel(new JLabel("Speed"), LONG_HELP, input, column);
            var reset = new JButton("Reset");
            first.setResetButton(reset);
            for (int font : new int[]{13, 20, 26}) {
                reset.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, font));
                units.setFont(reset.getFont());
                for (int width : new int[]{650, 1000}) {
                    first.setSize(width, first.getPreferredSize().height);
                    second.setSize(width, second.getPreferredSize().height);
                    layoutChildren(first); layoutChildren(second);
                    // Inputs are right-aligned: different controls end at the same place, just before the Reset slot.
                    int end = SwingUtilities.convertPoint(checkbox, checkbox.getWidth(), 0, first).x;
                    assertEquals(end, SwingUtilities.convertPoint(input, input.getWidth(), 0, second).x);
                    for (boolean dirty : new boolean[]{false, true, false}) {
                        checkbox.setSelected(dirty); reset.setVisible(dirty);
                        first.setSize(width, first.getPreferredSize().height); layoutChildren(first);
                        assertEquals("Reset must not shift the checkbox", end, SwingUtilities.convertPoint(checkbox, checkbox.getWidth(), 0, first).x);
                        assertTrue(reset.getParent().getWidth() >= reset.getPreferredSize().width);
                        if (dirty) assertTrue("Reset stays beside the input instead of at the column's far edge",
                                SwingUtilities.convertPoint(reset, 0, 0, first).x - end <= UiStyle.space(12));
                    }
                }
            }
        });
    }

    /** A wide auxiliary slider fits below its number field without widening the shared input column. */
    @Test public void auxiliarySliderUsesExistingWidthBelowInput() throws Exception {
        EventQueue.invokeAndWait(() -> {
            var column = new SettingsRowPanel.ControlColumn();
            var spinner = new JSpinner(new SpinnerNumberModel(2.0, 0, 10, 0.1));
            var row = new SettingsRowPanel(new JLabel("Stretch"), LONG_HELP, spinner, column);
            var reset = new JButton("Reset"); row.setResetButton(reset);
            row.setSize(800, row.getPreferredSize().height); layoutChildren(row);
            int inputX = SwingUtilities.convertPoint(spinner, 0, 0, row).x;
            var slider = new JSlider(); slider.setPreferredSize(new Dimension(900, slider.getPreferredSize().height));
            row.setAccessory(slider);
            row.setSize(800, row.getPreferredSize().height); layoutChildren(row);
            assertEquals("Sliders must not move the input column", inputX, SwingUtilities.convertPoint(spinner, 0, 0, row).x);
            assertTrue(slider.getWidth() < 900);
            assertTrue(SwingUtilities.convertPoint(slider, 0, 0, row).y >= SwingUtilities.convertPoint(spinner, 0, spinner.getHeight(), row).y);
        });
    }

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
                    SettingsRowPanel row = new SettingsRowPanel(new JLabel("A setting (pixels)"), LONG_HELP, editor,
                            new SettingsRowPanel.ControlColumn());
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

    /** Page padding must be excluded before measuring a paragraph's wrapped lines. */
    @Test public void paddedPageIntroReservesEveryRenderedLine() throws Exception {
        EventQueue.invokeAndWait(() -> {
            Font original = UIManager.getFont("Label.font");
            try {
                UIManager.put("Label.font", new Font(Font.SANS_SERIF, Font.PLAIN, UiStyle.space(20)));
                JPanel page = new JPanel();
                page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
                page.setBorder(BorderFactory.createEmptyBorder(10, UiStyle.space(20), 20, UiStyle.space(20)));
                WrappingHelpLabel intro = new WrappingHelpLabel(LONG_HELP, 600);
                page.add(intro);
                page.setSize(UiStyle.space(500), 5000);
                page.doLayout();
                javax.swing.text.View html = (javax.swing.text.View) intro.getClientProperty(
                        javax.swing.plaf.basic.BasicHTML.propertyKey);
                html.setSize(intro.getWidth(), 0);
                assertTrue("the allocated height contains the actual rendered paragraph",
                        intro.getHeight() >= Math.ceil(html.getPreferredSpan(javax.swing.text.View.Y_AXIS)));
            } finally {
                UIManager.put("Label.font", original);
            }
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
