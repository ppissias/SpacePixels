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

import java.awt.*;

/**
 * Lays out a toolbar narrower than its window: one group of controls at the left end of a row and one at the right
 * end, or the right group on a second row when they do not fit. Its minimum size is the two-row arrangement, so a
 * window never cuts the controls off. The panel must revalidate when its width changes, because the height follows.
 */
final class ToolbarRowLayout implements LayoutManager {

    private final Component left;
    private final Component right;
    private final int gap;

    ToolbarRowLayout(Component left, Component right) {
        this.left = left;
        this.right = right;
        this.gap = UiStyle.space(6);
    }

    private boolean oneRow(int width) {
        return width >= left.getPreferredSize().width + gap + right.getPreferredSize().width;
    }

    private int twoRowsHeight() {
        return left.getPreferredSize().height + gap + right.getPreferredSize().height;
    }

    private int oneRowHeight() {
        return Math.max(left.getPreferredSize().height, right.getPreferredSize().height);
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        Insets insets = parent.getInsets();
        int width = left.getPreferredSize().width + gap + right.getPreferredSize().width;
        boolean wrapped = parent.getWidth() > 0 && !oneRow(parent.getWidth() - insets.left - insets.right);
        return new Dimension(width + insets.left + insets.right,
                (wrapped ? twoRowsHeight() : oneRowHeight()) + insets.top + insets.bottom);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        Insets insets = parent.getInsets();
        return new Dimension(Math.max(left.getPreferredSize().width, right.getPreferredSize().width) + insets.left + insets.right,
                twoRowsHeight() + insets.top + insets.bottom);
    }

    @Override
    public void layoutContainer(Container parent) {
        Insets insets = parent.getInsets();
        int width = parent.getWidth() - insets.left - insets.right;
        Dimension l = left.getPreferredSize();
        Dimension r = right.getPreferredSize();
        int x = insets.left;
        int y = insets.top;
        if (oneRow(width)) {
            int row = oneRowHeight();
            left.setBounds(x, y + (row - l.height) / 2, l.width, l.height);
            right.setBounds(x + width - r.width, y + (row - r.height) / 2, r.width, r.height);
        } else {
            left.setBounds(x, y, Math.min(l.width, width), l.height);
            right.setBounds(x + Math.max(0, width - r.width), y + l.height + gap, Math.min(r.width, width), r.height);
        }
    }

    @Override
    public void addLayoutComponent(String name, Component component) {
    }

    @Override
    public void removeLayoutComponent(Component component) {
    }
}
