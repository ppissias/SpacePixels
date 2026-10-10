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
import java.awt.*;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * One setting on a detailed settings page: its row, its input, and its saved value. A setting that differs from
 * the saved configuration shows its title in the accent colour and a button that returns it to the saved value.
 */
final class SettingRow {

    final JPanel parent;
    final JPanel row;
    final String title;
    final String description;
    final JComponent input;

    private final JLabel titleLabel;
    private final Color normalTitleColor;
    private final JButton resetButton = new JButton("Reset");
    private Object savedValue;
    private Runnable changeListener = () -> { };

    /** Section header label the row belongs to, set by the navigator. */
    SettingsNavigator.Section section;

    /** Whether the row applies at all, for settings that only matter while another switch is on. */
    BooleanSupplier applies = () -> true;

    SettingRow(JPanel parent, JPanel row, JLabel titleLabel, String title, String description, JComponent input) {
        this.parent = parent;
        this.row = row;
        this.titleLabel = titleLabel;
        this.title = title;
        this.description = description;
        this.input = input;
        this.normalTitleColor = titleLabel.getForeground();

        resetButton.putClientProperty("JButton.buttonType", "toolBarButton");
        resetButton.setFocusable(false);
        resetButton.setVisible(false);
        resetButton.addActionListener(e -> resetToSaved());

        if (input instanceof JSpinner) {
            ((JSpinner) input).addChangeListener(e -> refresh());
        } else if (input instanceof AbstractButton) {
            ((AbstractButton) input).addItemListener(e -> refresh());
        } else if (input instanceof JComboBox) {
            ((JComboBox<?>) input).addActionListener(e -> refresh());
        }
    }

    JButton resetButton() {
        return resetButton;
    }

    void onChange(Runnable listener) {
        this.changeListener = listener;
    }

    Object value() {
        if (input instanceof JSpinner) {
            return ((JSpinner) input).getValue();
        }
        if (input instanceof JComboBox) {
            // A preset chooser only sets other rows, and those rows carry the changes.
            return null;
        }
        return ((AbstractButton) input).isSelected();
    }

    /** Records the current value as the saved value (after loading or saving the configuration). */
    void captureSaved() {
        savedValue = value();
    }

    boolean isChanged() {
        if (savedValue == null) {
            return false;
        }
        Object current = value();
        if (current instanceof Number && savedValue instanceof Number) {
            return Math.abs(((Number) current).doubleValue() - ((Number) savedValue).doubleValue()) > 1e-9;
        }
        return !savedValue.equals(current);
    }

    boolean matches(String query) {
        return title.toLowerCase(Locale.ROOT).contains(query)
                || description.toLowerCase(Locale.ROOT).contains(query)
                || (section != null && section.title.toLowerCase(Locale.ROOT).contains(query));
    }

    void resetToSaved() {
        if (savedValue == null) {
            return;
        }
        if (input instanceof JSpinner) {
            ((JSpinner) input).setValue(savedValue);
        } else {
            ((AbstractButton) input).setSelected((Boolean) savedValue);
        }
    }

    void refresh() {
        boolean changed = isChanged();
        titleLabel.setForeground(changed ? DetectionConfigurationPanel.accentColor() : normalTitleColor);
        resetButton.setVisible(changed);
        resetButton.setToolTipText(savedValue == null ? null : "Back to the saved value (" + format(savedValue) + ")");
        titleLabel.setToolTipText(changed ? "Changed since the last save (saved: " + format(savedValue) + ")" : null);
        changeListener.run();
    }

    private static String format(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value ? "on" : "off";
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            return d == Math.rint(d) ? String.format(Locale.US, "%.1f", d)
                    : java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }
}
