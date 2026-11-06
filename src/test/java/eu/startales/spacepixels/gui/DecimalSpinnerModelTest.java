package eu.startales.spacepixels.gui;

import org.junit.Test;

import javax.swing.SpinnerNumberModel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The arrows of a decimal spinner give the decimal values a user expects, never a floating-point tail. */
public class DecimalSpinnerModelTest {

    /** Up from 19.2 by 0.1 is 19.3 (the floating-point sum is 19.299999999999997), and a hundred steps stay clean. */
    @Test public void stepsAreDecimal() {
        DecimalSpinnerModel model = new DecimalSpinnerModel(19.2, 0.1, 100.0, 0.1);
        assertEquals("19.3", model.getNextValue().toString());
        assertEquals("19.1", model.getPreviousValue().toString());
        model.setValue(0.1);
        for (int step = 1; step <= 100; step++) {
            model.setValue(model.getNextValue());
            String shown = model.getValue().toString();
            assertEquals(shown, java.math.BigDecimal.valueOf(1 + step).movePointLeft(1).doubleValue(), (Double) model.getValue(), 0.0);
            assertEquals(shown, true, shown.length() - shown.indexOf('.') <= 2);
        }
    }

    /** A value that already carries a floating-point tail steps to a clean one. */
    @Test public void aValueWithATailStepsToACleanValue() {
        DecimalSpinnerModel model = new DecimalSpinnerModel(19.299999999999997, 0.1, 100.0, 0.1);
        assertEquals("19.4", model.getNextValue().toString());
        assertEquals("19.2", model.getPreviousValue().toString());
    }

    /** Imported metadata may change a bound; preserve the saved value and let an arrow recover into the range. */
    @Test public void arrowsRecoverFromAChangedRange() {
        DecimalSpinnerModel model = new DecimalSpinnerModel(1.0, 0.1, 100.0, 0.001);
        model.setMinimum(12.5);
        assertEquals(1.0, model.getNumber().doubleValue(), 0);
        assertNull(model.getPreviousValue());
        assertEquals(12.5, model.getNextValue());
        model.setValue(model.getNextValue());
        assertEquals(12.501, model.getNextValue());
        model.setMaximum(10.0);
        model.setMinimum(0.1);
        assertNull(model.getNextValue());
        assertEquals(10.0, model.getPreviousValue());
    }

    /** Past the range there is no next or previous value, as with the standard model; whole numbers step as before. */
    @Test public void rangeAndWholeNumbersAsBefore() {
        DecimalSpinnerModel model = new DecimalSpinnerModel(100.0, 0.1, 100.0, 0.1);
        assertNull(model.getNextValue());
        model.setValue(0.1);
        assertNull(model.getPreviousValue());
        DecimalSpinnerModel whole = new DecimalSpinnerModel((Number) 5, (Comparable<Integer>) 1, (Comparable<Integer>) 10, (Number) 2);
        SpinnerNumberModel standard = new SpinnerNumberModel(5, 1, 10, 2);
        assertEquals(standard.getNextValue(), whole.getNextValue());
        assertEquals(standard.getPreviousValue(), whole.getPreviousValue());
    }
}
