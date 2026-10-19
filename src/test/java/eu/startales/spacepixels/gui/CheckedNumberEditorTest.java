package eu.startales.spacepixels.gui;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.*;
import java.text.ParseException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Text that is not a value of the setting is never taken as one, stays visible, and is outlined. */
public class CheckedNumberEditorTest {

    private Locale previousLocale;

    @Before
    public void useDecimalPoints() {
        previousLocale = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
    }

    @After
    public void restoreLocale() {
        Locale.setDefault(Locale.Category.FORMAT, previousLocale);
    }

    @Test
    public void onlyANumberAsAWholeInRangeIsValid() {
        CheckedNumberEditor editor = editor(new SpinnerNumberModel(5.0, 1.0, 20.0, 0.1), "#0.0", () -> { });

        assertTrue(valid(editor, "4.5"));
        assertTrue(valid(editor, " 4.5 "));
        assertTrue(valid(editor, "20"));
        assertFalse("a decimal comma is not read as 4", valid(editor, "4,5"));
        assertFalse(valid(editor, "4.5x"));
        assertFalse(valid(editor, "25"));
        assertFalse(valid(editor, "0.5"));
        assertFalse(valid(editor, ""));
        assertFalse(valid(editor, "abc"));
        assertEquals("enter a number between 1.0 and 20.0", editor.expected());
    }

    @Test
    public void aWholeNumberFieldDoesNotCutDecimals() throws Exception {
        CheckedNumberEditor editor = editor(new SpinnerNumberModel(5, 1, 2000, 1), "#0", () -> { });

        assertFalse("not cut to 4", valid(editor, "4.5"));
        assertFalse("not wrapped into range", valid(editor, "4294967297"));
        assertEquals("enter a whole number between 1 and 2000", editor.expected());
        assertTrue(valid(editor, "12"));
        editor.getSpinner().commitEdit();
        assertEquals("stays a whole number", Integer.valueOf(12), editor.getSpinner().getValue());
    }

    @Test
    public void committingTheShownValueKeepsItsType() throws Exception {
        CheckedNumberEditor whole = editor(new SpinnerNumberModel(3, 1, 2000, 1), "#0", () -> { });
        whole.getSpinner().commitEdit();
        assertEquals(Integer.valueOf(3), whole.getSpinner().getValue());

        CheckedNumberEditor decimal = editor(new SpinnerNumberModel(3.0, 1.0, 20.0, 0.1), "#0.0", () -> { });
        decimal.getSpinner().commitEdit();
        assertEquals(Double.valueOf(3.0), decimal.getSpinner().getValue());
    }

    @Test
    public void invalidTextStaysOutlinedAndLeavesTheValue() throws Exception {
        AtomicInteger changes = new AtomicInteger();
        CheckedNumberEditor editor = editor(new SpinnerNumberModel(5.0, 1.0, 20.0, 0.1), "#0.0", changes::incrementAndGet);
        JSpinner spinner = editor.getSpinner();
        JFormattedTextField field = editor.getTextField();
        assertEquals("kept when the field loses focus", JFormattedTextField.COMMIT, field.getFocusLostBehavior());

        field.setText("4,5");
        assertTrue(changes.get() > 0);
        assertEquals("error", spinner.getClientProperty("JComponent.outline"));
        assertThrows(ParseException.class, spinner::commitEdit);
        assertEquals(5.0, spinner.getValue());
        assertEquals("4,5", field.getText());

        field.setText("4.5");
        assertNull(spinner.getClientProperty("JComponent.outline"));
        spinner.commitEdit();
        assertEquals(4.5, spinner.getValue());
    }

    @Test
    public void aValueWithMoreDecimalsThanThePatternIsNotRounded() throws Exception {
        CheckedNumberEditor editor = editor(new SpinnerNumberModel(5.5, 1.0, 20.0, 0.1), "#0.0", () -> { });
        JSpinner spinner = editor.getSpinner();

        spinner.setValue(4.25);
        assertEquals("4.25", editor.getTextField().getText());
        spinner.commitEdit();
        assertEquals(4.25, spinner.getValue());

        spinner.setValue(4.0);
        assertEquals("the pattern's decimals are still shown", "4.0", editor.getTextField().getText());
    }

    @Test
    public void aNewEditorClearsTheOutline() {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(5.0, 1.0, 20.0, 0.1));
        CheckedNumberEditor first = new CheckedNumberEditor(spinner, "#0.0", () -> { });
        spinner.setEditor(first);
        first.getTextField().setText("x");
        assertEquals("error", spinner.getClientProperty("JComponent.outline"));

        spinner.setEditor(new CheckedNumberEditor(spinner, "#0.00", () -> { }));

        assertNull(spinner.getClientProperty("JComponent.outline"));
    }

    private static CheckedNumberEditor editor(SpinnerNumberModel model, String pattern, Runnable textChanged) {
        JSpinner spinner = new JSpinner(model);
        CheckedNumberEditor editor = new CheckedNumberEditor(spinner, pattern, textChanged);
        spinner.setEditor(editor);
        return editor;
    }

    private static boolean valid(CheckedNumberEditor editor, String text) {
        editor.getTextField().setText(text);
        return editor.isTextValid();
    }
}
