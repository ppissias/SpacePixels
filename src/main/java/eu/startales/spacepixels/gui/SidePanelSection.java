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
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A section of a viewer's side panel: a header with an arrow that folds the section, its title and optional controls
 * on the right (for example an on/off switch), and the section's content below.
 */
final class SidePanelSection extends JPanel {

    private final JButton arrow = new JButton();
    private final JLabel title;
    private final JPanel header = new JPanel(new GridBagLayout());
    private final JComponent body;
    private boolean expanded = true;

    SidePanelSection(String titleText, JComponent body) {
        super(new BorderLayout(0, UiStyle.space(6)));
        this.body = body;
        title = new JLabel(titleText);
        title.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD));
        setOpaque(false);
        Color line = UIManager.getColor("Separator.foreground");
        setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, line == null ? Color.GRAY : line),
                new EmptyBorder(UiStyle.space(10), UiStyle.space(12), UiStyle.space(10), UiStyle.space(12))));
        arrow.putClientProperty("JButton.buttonType", "borderless");
        arrow.setFocusable(false);
        arrow.setMargin(new Insets(0, 0, 0, 0));
        arrow.addActionListener(e -> setExpanded(!expanded));
        title.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                setExpanded(!expanded);
            }
        });
        header.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        header.add(arrow, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, UiStyle.space(4), 0, 0);
        header.add(title, c);
        body.setOpaque(false);
        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        updateArrow();
    }

    /** Adds a control to the right end of the header, for example the section's on/off switch. */
    SidePanelSection addHeaderControl(JComponent control) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.gridx = header.getComponentCount();
        c.anchor = GridBagConstraints.EAST;
        c.insets = new Insets(0, UiStyle.space(4), 0, 0);
        header.add(control, c);
        return this;
    }

    void setExpanded(boolean expanded) {
        this.expanded = expanded;
        body.setVisible(expanded);
        updateArrow();
        revalidate();
        repaint();
    }

    boolean isExpanded() {
        return expanded;
    }

    JLabel title() {
        return title;
    }

    private void updateArrow() {
        arrow.setText(expanded ? "▾" : "▸");
        arrow.setToolTipText((expanded ? "Fold " : "Unfold ") + title.getText() + ".");
        arrow.getAccessibleContext().setAccessibleName((expanded ? "Fold " : "Unfold ") + title.getText());
    }
}
