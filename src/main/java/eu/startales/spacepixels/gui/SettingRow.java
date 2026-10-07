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

/**
 * One setting on a detailed settings page: its row, its input, and its default value. A setting that differs from
 * its default shows its title in the accent colour and a button that resets it.
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
    private Object defaultValue;
    private Runnable changeListener = () -> { };

    /** Section header label the row belongs to, set by the navigator. */
    SettingsNavigator.Section section;

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
        resetButton.addActionListener(e -> resetToDefault());

        if (input instanceof JSpinner) {
            ((JSpinner) input).addChangeListener(e -> refresh());
        } else if (input instanceof AbstractButton) {
            ((AbstractButton) input).addItemListener(e -> refresh());
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
        return ((AbstractButton) input).isSelected();
    }

    /** Records the current value as the default. */
    void captureDefault() {
        defaultValue = value();
    }

    boolean isChanged() {
        if (defaultValue == null) {
            return false;
        }
        Object current = value();
        if (current instanceof Number && defaultValue instanceof Number) {
            return Math.abs(((Number) current).doubleValue() - ((Number) defaultValue).doubleValue()) > 1e-9;
        }
        return !defaultValue.equals(current);
    }

    boolean matches(String query) {
        return title.toLowerCase(Locale.ROOT).contains(query)
                || description.toLowerCase(Locale.ROOT).contains(query)
                || (section != null && section.title.toLowerCase(Locale.ROOT).contains(query));
    }

    void resetToDefault() {
        if (defaultValue == null) {
            return;
        }
        if (input instanceof JSpinner) {
            ((JSpinner) input).setValue(defaultValue);
        } else {
            ((AbstractButton) input).setSelected((Boolean) defaultValue);
        }
    }

    void refresh() {
        boolean changed = isChanged();
        titleLabel.setForeground(changed ? DetectionConfigurationPanel.accentColor() : normalTitleColor);
        resetButton.setVisible(changed);
        resetButton.setToolTipText(defaultValue == null ? null : "Reset to the default (" + format(defaultValue) + ")");
        titleLabel.setToolTipText(changed ? "Differs from the default (" + format(defaultValue) + ")" : null);
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
