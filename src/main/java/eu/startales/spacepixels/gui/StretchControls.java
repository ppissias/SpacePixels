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

import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.DisplayStretch;
import eu.startales.spacepixels.fits.StretchAlgorithm;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.ItemListener;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The Display stretch section of the frame viewers' side panel. Its controls use the models of the Image Stretch
 * tab, so every viewer and the tab always show the same stretch, and a change here is remembered like a change
 * there. Each parameter has a slider and a number field on the same model. Keeps the stretch tables of the frames it
 * has drawn until the stretch changes.
 */
final class StretchControls {

    private final StretchPanel panel;
    private final JComboBox<StretchAlgorithm> algorithmCombo;
    private final JCheckBox displayStretchCheckbox = SidePanelIcons.onOff("Stretch display");
    private final JButton resetButton = new JButton("Reset display");
    private final JLabel functionLabel = new JLabel("Function");
    private final StretchHistogram histogram = new StretchHistogram();
    private final JSlider primarySlider;
    private final JSlider secondarySlider;
    private final JSpinner primarySpinner;
    private final JSpinner secondarySpinner;
    private final JLabel primaryLabel = new JLabel();
    private final JLabel secondaryLabel = new JLabel();
    private final SidePanelSection section;
    /** Undoes the number fields' links to the shared slider models, when the controls are detached. */
    private final java.util.List<Runnable> unlinks = new java.util.ArrayList<>();
    private final ChangeListener modelListener = e -> scheduleRefresh();
    private final ActionListener algorithmListener = e -> scheduleRefresh();
    private final ItemListener displayListener = e -> scheduleRefresh();
    private final Runnable onChange;
    /** Weak keys: a viewer that shows one frame at a time lets the old frames go. */
    private final Map<BlinkSequence.Frame, byte[][]> tables = new WeakHashMap<>();
    private boolean refreshPending;

    /** {@code onChange} redraws the viewer after the stretch changed. */
    StretchControls(StretchPanel panel, Runnable onChange) {
        this.panel = panel;
        this.onChange = onChange;
        displayStretchCheckbox.setModel(panel.getDisplayStretchModel());
        displayStretchCheckbox.setToolTipText("Apply the display stretch in all viewers and the Image Stretch tab. Untick for a linear view; your stretch settings are kept.");
        displayStretchCheckbox.getModel().addItemListener(displayListener);
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
            Dimension natural = slider.getPreferredSize();
            slider.setPreferredSize(new Dimension(UiStyle.space(120), natural.height));
            slider.getModel().addChangeListener(modelListener);
        }
        primarySpinner = linkedSpinner(primarySlider.getModel());
        secondarySpinner = linkedSpinner(secondarySlider.getModel());
        algorithmCombo.addActionListener(algorithmListener);
        // The side panel decides the width; the combo box fills what the label leaves.
        algorithmCombo.setPreferredSize(new Dimension(UiStyle.space(120), algorithmCombo.getPreferredSize().height));
        resetButton.putClientProperty("JButton.buttonType", "borderless");
        resetButton.setToolTipText("Return the stretch parameters to the defaults of the selected function. "
                + "The detection settings are not affected.");
        resetButton.addActionListener(e -> panel.resetStretchToDefaults());

        JPanel body = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, UiStyle.space(6), UiStyle.space(8));
        c.gridy = 0;
        c.gridx = 0;
        body.add(functionLabel, c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(0, 0, UiStyle.space(6), 0);
        body.add(algorithmCombo, c);
        c = new GridBagConstraints();
        c.gridy = 1;
        c.gridx = 0;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(0, 0, UiStyle.space(4), 0);
        body.add(histogram, c);
        addParameter(body, 2, primaryLabel, primarySlider, primarySpinner);
        addParameter(body, 4, secondaryLabel, secondarySlider, secondarySpinner);
        c = new GridBagConstraints();
        c.gridy = 6;
        c.gridx = 0;
        c.gridwidth = 3;
        c.anchor = GridBagConstraints.WEST;
        body.add(resetButton, c);
        section = new SidePanelSection("Display stretch", body).addHeaderControl(displayStretchCheckbox);
        ViewerSupport.prepareControls(section);
        functionLabel.setLabelFor(algorithmCombo);
        primaryLabel.setLabelFor(primarySlider);
        secondaryLabel.setLabelFor(secondarySlider);
        algorithmCombo.getAccessibleContext().setAccessibleName("Display stretch algorithm");
        updateLabels();
    }

    /** A parameter's name on one line, its slider and number field on the next. */
    private static void addParameter(JPanel body, int row, JLabel label, JSlider slider, JSpinner spinner) {
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 3;
        c.insets = new Insets(UiStyle.space(4), 0, 0, 0);
        body.add(label, c);
        c.gridy = row + 1;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, UiStyle.space(4), UiStyle.space(8));
        body.add(slider, c);
        c.gridx = 2;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 0, UiStyle.space(4), 0);
        UiStyle.numberInput(spinner);
        body.add(spinner, c);
    }

    /** A number field that follows the slider model both ways, including its range when the function changes. */
    private JSpinner linkedSpinner(BoundedRangeModel range) {
        SpinnerNumberModel number = new SpinnerNumberModel(range.getValue(), range.getMinimum(), range.getMaximum(), 1);
        JSpinner spinner = UiStyle.spinner(number);
        boolean[] syncing = {false};
        ChangeListener fromRange = e -> {
            if (syncing[0]) return;
            syncing[0] = true;
            try {
                number.setMinimum(range.getMinimum());
                number.setMaximum(range.getMaximum());
                number.setValue(range.getValue());
            } finally {
                syncing[0] = false;
            }
        };
        number.addChangeListener(e -> {
            if (syncing[0]) return;
            syncing[0] = true;
            try {
                range.setValue(((Number) number.getValue()).intValue());
            } finally {
                syncing[0] = false;
            }
        });
        range.addChangeListener(fromRange);
        unlinks.add(() -> range.removeChangeListener(fromRange));
        return spinner;
    }

    StretchPanel getPanel() {
        return panel;
    }

    /** The section for the viewer's side panel. */
    SidePanelSection getSection() {
        return section;
    }

    /** The stretch table of each channel of this frame, for the current stretch. */
    byte[][] tablesFor(BlinkSequence.Frame frame) {
        byte[][] frameTables = tables.computeIfAbsent(frame, f -> snapshot().tablesFor(f));
        // The histogram follows the frame drawn: the green channel of a colour frame, else the only one.
        int channel = frameTables.length == 3 ? 1 : 0;
        histogram.setData(frame.getHistogram(channel), frameTables[channel]);
        return frameTables;
    }

    /** Captures controls on the EDT so an export worker never consults live Swing models. */
    Snapshot snapshot() {
        return new Snapshot(panel.isDisplayStretchEnabled(), panel.getStretchAlgorithm(),
                panel.getStretchSlider().getValue(), panel.getStretchIterationsSlider().getValue());
    }

    /** Immutable display settings; LUT calculation reads only the immutable frame histograms on its caller's worker. */
    record Snapshot(boolean enabled, StretchAlgorithm algorithm, int primary, int secondary) {
        /** Calculates one frame's LUTs without reading a live panel or retaining previous frames' LUTs. */
        byte[][] tablesFor(BlinkSequence.Frame frame) {
            byte[][] channelTables = new byte[frame.getChannelCount()][];
            for (int channel = 0; channel < channelTables.length; channel++) {
                channelTables[channel] = enabled
                        ? DisplayStretch.lookupTable(frame.getHistogram(channel), algorithm, primary, secondary)
                        : DisplayStretch.linearLookupTable();
            }
            return channelTables;
        }
        /** Uses the same colour presentation as the visible viewer. */
        boolean extremeColour(BlinkSequence.Frame frame) { return enabled && algorithm == StretchAlgorithm.EXTREME && frame.getChannelCount() == 3; }
    }

    /** Whether a colour frame is drawn as its brightest channel, as the Extreme stretch does. */
    boolean extremeColour(BlinkSequence.Frame frame) {
        return panel.isDisplayStretchEnabled() && panel.getStretchAlgorithm() == StretchAlgorithm.EXTREME && frame.getChannelCount() == 3;
    }

    void clearTables() {
        tables.clear();
        histogram.setData(null, null);
    }

    /**
     * Lets go of the shared models, so a closed viewer is not kept alive by the Image Stretch tab. The controls must
     * not be used afterwards.
     */
    void detach() {
        displayStretchCheckbox.getModel().removeItemListener(displayListener);
        displayStretchCheckbox.setModel(new JToggleButton.ToggleButtonModel());
        algorithmCombo.removeActionListener(algorithmListener);
        unlinks.forEach(Runnable::run);
        unlinks.clear();
        for (JSlider slider : new JSlider[]{primarySlider, secondarySlider}) {
            slider.getModel().removeChangeListener(modelListener);
            slider.setModel(new DefaultBoundedRangeModel());
        }
        algorithmCombo.setModel(new DefaultComboBoxModel<>());
        tables.clear();
        histogram.setData(null, null);
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
        boolean enabled = panel.isDisplayStretchEnabled();
        algorithmCombo.setEnabled(enabled);
        primarySlider.setEnabled(enabled);
        secondarySlider.setEnabled(enabled);
        primarySpinner.setEnabled(enabled);
        secondarySpinner.setEnabled(enabled);
        functionLabel.setEnabled(enabled);
        primaryLabel.setEnabled(enabled);
        secondaryLabel.setEnabled(enabled);
        resetButton.setEnabled(enabled);
        histogram.setEnabled(enabled);
        StretchAlgorithm algorithm = panel.getStretchAlgorithm();
        if (algorithm == null) {
            return;
        }
        primaryLabel.setText(algorithm.getPrimaryParameterLabel());
        secondaryLabel.setText(algorithm.getSecondaryParameterLabel());
        primarySpinner.getAccessibleContext().setAccessibleName(algorithm.getPrimaryParameterLabel());
        secondarySpinner.getAccessibleContext().setAccessibleName(algorithm.getSecondaryParameterLabel());
    }
}
