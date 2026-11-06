/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.gui;

import javax.swing.SpinnerNumberModel;
import java.math.BigDecimal;
import java.math.MathContext;

/**
 * A number spinner model whose arrows step in decimal: 19.2 up by 0.1 gives 19.3, where the floating-point sum of
 * {@link SpinnerNumberModel} gives 19.299999999999997, which the editor would show in full. The value is read to twelve
 * significant digits before the step, so a value that already carries such a tail steps to a clean one. Whole-number
 * spinners step as before.
 */
final class DecimalSpinnerModel extends SpinnerNumberModel {

    private static final MathContext SIGNIFICANT = new MathContext(12);

    DecimalSpinnerModel(double value, double minimum, double maximum, double stepSize) {
        super(value, minimum, maximum, stepSize);
    }

    DecimalSpinnerModel(Number value, Comparable<?> minimum, Comparable<?> maximum, Number stepSize) {
        super(value, minimum, maximum, stepSize);
    }

    @Override
    public Object getNextValue() {
        return step(1);
    }

    @Override
    public Object getPreviousValue() {
        return step(-1);
    }

    /** Steps in decimal; an arrow towards a newly changed range first returns its nearest bound. */
    private Object step(int direction) {
        Number value = getNumber();
        if (!(value instanceof Double || value instanceof Float)) {
            return direction > 0 ? super.getNextValue() : super.getPreviousValue();
        }
        // A metadata change may move a bound past the saved value without rewriting that value.
        if (direction > 0 && getMinimum() instanceof Number minimum && value.doubleValue() < minimum.doubleValue()) {
            return minimum;
        }
        if (direction < 0 && getMaximum() instanceof Number maximum && value.doubleValue() > maximum.doubleValue()) {
            return maximum;
        }
        BigDecimal current = new BigDecimal(value.toString()).round(SIGNIFICANT);
        BigDecimal step = new BigDecimal(getStepSize().toString());
        double next = (direction > 0 ? current.add(step) : current.subtract(step)).doubleValue();
        if ((getMaximum() != null && next > ((Number) getMaximum()).doubleValue())
                || (getMinimum() != null && next < ((Number) getMinimum()).doubleValue())) {
            return null;
        }
        return value instanceof Float ? Float.valueOf((float) next) : Double.valueOf(next);
    }
}
