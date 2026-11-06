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
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Locale;

/**
 * One overlay layer in a viewer's side panel: {@code [swatch] Name …… count [Aa] [eye]}. The switches change the
 * shared layer state, so every viewer follows. A layer that cannot be shown says why in place of its count.
 */
final class LayerRow extends JPanel {

    /** Widths of the right-hand columns, so the rows of a section line up. */
    private static final int COUNT_WIDTH = 96;

    private final ViewerLayer layer;
    private final ViewerLayerState state;
    private final OverlayStyle.Swatch swatch;
    private final JLabel name = new JLabel();
    private final JLabel count = new JLabel(" ");
    private final JCheckBox labels;
    private final JCheckBox eye;
    private boolean available = true;
    private String tooltip;
    /** The key that switches the layer, named in the eye's tooltip; null for none. */
    private String key;

    /** {@code leading} is put before the name, for example the expand arrow of a group; may be null. */
    LayerRow(ViewerLayer layer, ViewerLayerState state, JComponent leading, int indent) {
        super(new GridBagLayout());
        this.layer = layer;
        this.state = state;
        setOpaque(false);
        setBorder(new EmptyBorder(1, UiStyle.space(indent), 1, 0));
        swatch = OverlayStyle.swatch(layer);
        name.setText(layer.title);
        name.setIcon(swatch);
        // The swatch dims itself on a disabled label; without this, Swing would draw no icon at all.
        name.setDisabledIcon(swatch);
        name.setIconTextGap(UiStyle.space(8));
        count.setForeground(UiStyle.helpColor());
        count.setHorizontalAlignment(SwingConstants.RIGHT);
        labels = SidePanelIcons.labels("Labels for " + layer.title.toLowerCase(Locale.ROOT));
        eye = SidePanelIcons.eye("Show " + layer.title.toLowerCase(Locale.ROOT));
        labels.addActionListener(e -> state.setLabelled(layer, labels.isSelected()));
        eye.addActionListener(e -> state.setVisible(layer, eye.isSelected()));
        labels.setVisible(layer.hasLabels);

        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        int column = 0;
        if (leading != null) {
            c.gridx = column++;
            add(leading, c);
        }
        c.gridx = column++;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        add(name, c);
        c.gridx = column++;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        count.setPreferredSize(new Dimension(UiStyle.space(COUNT_WIDTH), count.getPreferredSize().height));
        add(count, c);
        c.gridx = column++;
        Component labelsCell = layer.hasLabels ? labels : Box.createHorizontalStrut(labels.getPreferredSize().width);
        add(labelsCell, c);
        c.gridx = column;
        add(eye, c);
        refresh();
    }

    ViewerLayer layer() {
        return layer;
    }

    /**
     * Whether the layer has anything to show. {@code countText} is the count, or the reason it is unavailable, for
     * example "none in report"; {@code tooltip} explains the layer, or how to make it available.
     */
    void setAvailable(boolean available, String countText, String tooltip) {
        this.available = available;
        this.tooltip = tooltip;
        count.setText(countText == null || countText.isEmpty() ? " " : countText);
        refresh();
    }

    /** Shows the shared state: the switches, and the name and swatch dimmed while the layer is off. */
    void refresh() {
        labels.setSelected(state.isLabelled(layer));
        eye.setSelected(state.isVisible(layer));
        labels.setEnabled(available);
        eye.setEnabled(available);
        boolean shown = available && state.isVisible(layer);
        if (swatch != null) swatch.setDimmed(!shown);
        Color fore = UIManager.getColor("Label.foreground");
        name.setForeground(shown || fore == null ? fore : UiStyle.helpColor());
        name.setEnabled(available);
        String hint = tooltip == null ? layer.title : tooltip;
        setToolTipText(hint);
        name.setToolTipText(hint);
        count.setToolTipText(hint);
        eye.setToolTipText((state.isVisible(layer) ? "Hide " : "Show ") + layer.title.toLowerCase(Locale.ROOT)
                + (key == null ? "." : " (" + key + ")."));
        labels.setToolTipText((state.isLabelled(layer) ? "Hide" : "Show") + " the labels of " + layer.title.toLowerCase(Locale.ROOT) + ".");
        repaint();
    }

    /** The show/hide switch. */
    JCheckBox eye() {
        return eye;
    }

    /** Names the key that switches this layer in the eye's tooltip, for example "T". */
    void setKey(String key) {
        this.key = key;
        refresh();
    }
}
