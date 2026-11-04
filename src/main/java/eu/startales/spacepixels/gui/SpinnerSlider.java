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

import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Dimension;
import java.beans.PropertyChangeListener;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A slider that moves with a number spinner of a setting row, one notch per step of the spinner. Dragging the slider
 * sets the spinner's value as it moves. A number typed into the spinner's field moves the slider before it is
 * committed, without committing it, and a committed, reset or loaded value moves the slider; a value between two
 * notches stays as it is, with the slider at the nearer notch.
 */
final class SpinnerSlider {

    private SpinnerSlider() {
    }

    /**
     * Creates a slider linked to {@code spinner}; its caller places it below the setting's input line. {@code changed} runs after every move of either
     * control and while a number is typed, for a preview that follows the value as it is being chosen.
     */
    static JSlider create(JSpinner spinner, String title, Runnable changed) {
        SpinnerNumberModel model = (SpinnerNumberModel) spinner.getModel();
        BigDecimal minimum = BigDecimal.valueOf(((Number) model.getMinimum()).doubleValue());
        BigDecimal step = BigDecimal.valueOf(model.getStepSize().doubleValue());
        JSlider slider = new JSlider(0, notch(((Number) model.getMaximum()).doubleValue(), minimum, step),
                notch(((Number) spinner.getValue()).doubleValue(), minimum, step));
        slider.setPreferredSize(new Dimension(UiStyle.space(160), slider.getPreferredSize().height));
        slider.getAccessibleContext().setAccessibleName(title);
        // Set while one control moves the other, so that the slider does not round a typed value to its notch.
        boolean[] syncing = {false};
        slider.addChangeListener(e -> {
            if (syncing[0]) {
                return;
            }
            syncing[0] = true;
            try {
                spinner.setValue(minimum.add(step.multiply(BigDecimal.valueOf(slider.getValue()))).doubleValue());
            } finally {
                syncing[0] = false;
            }
        });
        Runnable follow = () -> {
            if (!syncing[0]) {
                syncing[0] = true;
                try {
                    slider.setValue(notch(typedOrValue(spinner), minimum, step));
                } finally {
                    syncing[0] = false;
                }
            }
            changed.run();
        };
        spinner.addChangeListener(e -> follow.run());
        DocumentListener typing = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                follow.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                follow.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                follow.run();
            }
        };
        // A settings page replaces the editor at each change of the value, so each new one gets the listener.
        PropertyChangeListener listenToEditor = e -> {
            if (spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
                editor.getTextField().getDocument().addDocumentListener(typing);
            }
        };
        spinner.addPropertyChangeListener("editor", listenToEditor);
        listenToEditor.propertyChange(null);
        return slider;
    }

    /** The number typed into {@code spinner}'s field while it is a value of the spinner, else the spinner's value. */
    static double typedOrValue(JSpinner spinner) {
        if (spinner.getEditor() instanceof CheckedNumberEditor editor) {
            Number typed = editor.typedValue();
            if (typed != null) {
                return typed.doubleValue();
            }
        }
        return ((Number) spinner.getValue()).doubleValue();
    }

    private static int notch(double value, BigDecimal minimum, BigDecimal step) {
        return BigDecimal.valueOf(value).subtract(minimum).divide(step, 0, RoundingMode.HALF_UP).intValue();
    }
}
