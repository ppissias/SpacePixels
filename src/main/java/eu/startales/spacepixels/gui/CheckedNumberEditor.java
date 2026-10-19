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

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultFormatterFactory;
import javax.swing.text.NumberFormatter;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.ParsePosition;

/**
 * The editor of a number setting. Only text that is a number as a whole, within the spinner's range, becomes the
 * value: "4,5" in a field that expects "4.5" is not read as 4, and "4.5" in a whole-number field is not cut to 4.
 * Other text stays in the field when it loses focus, rather than silently returning to the previous value, and the
 * spinner is outlined in red until the text is corrected.
 */
final class CheckedNumberEditor extends JSpinner.NumberEditor {

    private final JSpinner spinner;

    /** {@code textChanged} runs after every change of the text, typed or set. */
    CheckedNumberEditor(JSpinner spinner, String pattern, Runnable textChanged) {
        super(spinner, pattern);
        this.spinner = spinner;
        JFormattedTextField field = getTextField();
        field.setFormatterFactory(new DefaultFormatterFactory(new WholeTextFormatter((SpinnerNumberModel) spinner.getModel(), getFormat())));
        field.setFocusLostBehavior(JFormattedTextField.COMMIT);
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }

            private void changed() {
                showOutline();
                textChanged.run();
            }
        });
        showOutline();
    }

    /** Whether the text in the field is a value of the spinner. */
    boolean isTextValid() {
        JFormattedTextField field = getTextField();
        try {
            field.getFormatter().stringToValue(field.getText());
            return true;
        } catch (ParseException e) {
            return false;
        }
    }

    /** What the field takes, for example "enter a number between 1.0 and 20.0". */
    String expected() {
        SpinnerNumberModel model = (SpinnerNumberModel) spinner.getModel();
        return "enter a " + (model.getValue() instanceof Integer ? "whole " : "") + "number between "
                + getFormat().format(model.getMinimum()) + " and " + getFormat().format(model.getMaximum());
    }

    private void showOutline() {
        spinner.putClientProperty("JComponent.outline", isTextValid() ? null : "error");
    }

    /** Reads the whole text as one number of the spinner's type and range, or refuses it. */
    private static final class WholeTextFormatter extends NumberFormatter {
        private final SpinnerNumberModel model;

        WholeTextFormatter(SpinnerNumberModel model, NumberFormat format) {
            super(format);
            this.model = model;
            setValueClass(model.getValue().getClass());
        }

        @Override
        public Comparable<?> getMinimum() {
            return model.getMinimum();
        }

        @Override
        public Comparable<?> getMaximum() {
            return model.getMaximum();
        }

        @Override
        public Object stringToValue(String text) throws ParseException {
            String trimmed = text == null ? "" : text.trim();
            ParsePosition position = new ParsePosition(0);
            Number number = ((NumberFormat) getFormat()).parse(trimmed, position);
            if (number == null || position.getIndex() < trimmed.length()) {
                throw new ParseException("Not a number: " + text, Math.max(0, position.getErrorIndex()));
            }
            double value = number.doubleValue();
            boolean whole = getValueClass() == Integer.class;
            if (!Double.isFinite(value) || (whole && value != Math.rint(value))
                    || value < ((Number) model.getMinimum()).doubleValue() || value > ((Number) model.getMaximum()).doubleValue()) {
                throw new ParseException("Not a value of this setting: " + text, 0);
            }
            if (whole) {
                return Integer.valueOf((int) value);
            }
            return Double.valueOf(value);
        }
    }
}
