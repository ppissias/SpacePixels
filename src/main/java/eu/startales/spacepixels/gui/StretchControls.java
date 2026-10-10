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

import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.util.DisplayStretch;
import eu.startales.spacepixels.util.StretchAlgorithm;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The stretch row of the frame viewers. Its controls use the models of the Image Stretch tab, so every viewer and
 * the tab always show the same stretch, and a change here is remembered like a change there. Keeps the stretch
 * tables of the frames it has drawn until the stretch changes.
 */
final class StretchControls {

    private final StretchPanel panel;
    private final JComboBox<StretchAlgorithm> algorithmCombo;
    private final JSlider primarySlider;
    private final JSlider secondarySlider;
    private final JLabel primaryLabel = new JLabel();
    private final JLabel secondaryLabel = new JLabel();
    private final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final ChangeListener modelListener = e -> scheduleRefresh();
    private final ActionListener algorithmListener = e -> scheduleRefresh();
    private final Runnable onChange;
    /** Weak keys: a viewer that shows one frame at a time lets the old frames go. */
    private final Map<BlinkSequence.Frame, byte[][]> tables = new WeakHashMap<>();
    private boolean refreshPending;

    /** {@code onChange} redraws the viewer after the stretch changed. */
    StretchControls(StretchPanel panel, Runnable onChange) {
        this.panel = panel;
        this.onChange = onChange;
        algorithmCombo = new JComboBox<>(panel.getStretchAlgorithmModel());
        algorithmCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                list.setToolTipText(value == null ? null : StretchPanel.algorithmDescription((StretchAlgorithm) value));
                return c;
            }
        });
        algorithmCombo.setToolTipText("The display stretch, shared with the Image Stretch tab. It only changes how frames look.");
        primarySlider = new JSlider(panel.getStretchSlider().getModel());
        secondarySlider = new JSlider(panel.getStretchIterationsSlider().getModel());
        for (JSlider slider : new JSlider[]{primarySlider, secondarySlider}) {
            slider.setPreferredSize(new Dimension(150, slider.getPreferredSize().height));
            slider.getModel().addChangeListener(modelListener);
        }
        algorithmCombo.addActionListener(algorithmListener);
        JButton resetButton = new JButton("Reset");
        resetButton.setToolTipText("Return the sliders to the defaults of the selected algorithm.");
        resetButton.addActionListener(e -> panel.resetStretchToDefaults());

        row.setBorder(new EmptyBorder(4, 0, 0, 0));
        row.add(new JLabel("Stretch:"));
        row.add(algorithmCombo);
        row.add(Box.createHorizontalStrut(8));
        row.add(primaryLabel);
        row.add(primarySlider);
        row.add(Box.createHorizontalStrut(8));
        row.add(secondaryLabel);
        row.add(secondarySlider);
        row.add(resetButton);
        ViewerSupport.removeFocus(row);
        String widestPrimary = "";
        String widestSecondary = "";
        for (StretchAlgorithm algorithm : StretchAlgorithm.values()) {
            widestPrimary = longer(widestPrimary, algorithm.getPrimaryParameterLabel() + ": " + algorithm.getPrimaryMaximum());
            widestSecondary = longer(widestSecondary, algorithm.getSecondaryParameterLabel() + ": " + algorithm.getSecondaryMaximum());
        }
        ViewerSupport.fixWidth(primaryLabel, widestPrimary);
        ViewerSupport.fixWidth(secondaryLabel, widestSecondary);
        updateLabels();
    }

    StretchPanel getPanel() {
        return panel;
    }

    JPanel getRow() {
        return row;
    }

    /** The stretch table of each channel of this frame, for the current stretch. */
    byte[][] tablesFor(BlinkSequence.Frame frame) {
        return tables.computeIfAbsent(frame, f -> {
            StretchAlgorithm algorithm = panel.getStretchAlgorithm();
            int primary = panel.getStretchSlider().getValue();
            int secondary = panel.getStretchIterationsSlider().getValue();
            byte[][] channelTables = new byte[f.getChannelCount()][];
            for (int channel = 0; channel < channelTables.length; channel++) {
                channelTables[channel] = DisplayStretch.lookupTable(f.getHistogram(channel), algorithm, primary, secondary);
            }
            return channelTables;
        });
    }

    /** Whether a colour frame is drawn as its brightest channel, as the Extreme stretch does. */
    boolean extremeColour(BlinkSequence.Frame frame) {
        return panel.getStretchAlgorithm() == StretchAlgorithm.EXTREME && frame.getChannelCount() == 3;
    }

    void clearTables() {
        tables.clear();
    }

    /**
     * Lets go of the shared models, so a closed viewer is not kept alive by the Image Stretch tab. The controls must
     * not be used afterwards.
     */
    void detach() {
        algorithmCombo.removeActionListener(algorithmListener);
        for (JSlider slider : new JSlider[]{primarySlider, secondarySlider}) {
            slider.getModel().removeChangeListener(modelListener);
            slider.setModel(new DefaultBoundedRangeModel());
        }
        algorithmCombo.setModel(new DefaultComboBoxModel<>());
        tables.clear();
    }

    /** Redraws once after a burst of changes (a slider drag, or an algorithm switch that moves the sliders). */
    private void scheduleRefresh() {
        if (refreshPending) {
            return;
        }
        refreshPending = true;
        EventQueue.invokeLater(() -> {
            refreshPending = false;
            tables.clear();
            updateLabels();
            onChange.run();
        });
    }

    private void updateLabels() {
        StretchAlgorithm algorithm = panel.getStretchAlgorithm();
        if (algorithm == null) {
            return;
        }
        primaryLabel.setText(algorithm.getPrimaryParameterLabel() + ": " + primarySlider.getValue());
        secondaryLabel.setText(algorithm.getSecondaryParameterLabel() + ": " + secondarySlider.getValue());
    }

    private static String longer(String a, String b) {
        return b.length() > a.length() ? b : a;
    }
}
