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
 * Lays out Blink's transport bar: the buttons, the timeline (which takes the free width), the frame's place and the
 * speed in one row when they fit, otherwise the speed on a second row. Its minimum size is the two-row arrangement,
 * so a window never cuts the controls off.
 */
final class TransportLayout implements LayoutManager {

    private final Component buttons;
    private final Component timeline;
    private final Component place;
    private final Component speed;
    private final int gap;
    private final int timelineMinimum;

    TransportLayout(Component buttons, Component timeline, Component place, Component speed) {
        this.buttons = buttons;
        this.timeline = timeline;
        this.place = place;
        this.speed = speed;
        this.gap = UiStyle.space(8);
        this.timelineMinimum = UiStyle.space(120);
    }

    private int firstRowWidth(int timelineWidth) {
        return buttons.getPreferredSize().width + timelineWidth + place.getPreferredSize().width + 2 * gap;
    }

    private int rowHeight() {
        return Math.max(Math.max(buttons.getPreferredSize().height, timeline.getPreferredSize().height),
                place.getPreferredSize().height);
    }

    /** Whether everything fits in one row of this width. */
    private boolean oneRow(int width) {
        return width >= firstRowWidth(timelineMinimum) + gap + speed.getPreferredSize().width;
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        Insets insets = parent.getInsets();
        int width = firstRowWidth(UiStyle.space(260)) + gap + speed.getPreferredSize().width;
        int height = Math.max(rowHeight(), speed.getPreferredSize().height);
        if (parent.getWidth() > 0 && !oneRow(parent.getWidth() - insets.left - insets.right)) {
            height = rowHeight() + UiStyle.space(4) + speed.getPreferredSize().height;
        }
        return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        Insets insets = parent.getInsets();
        int width = Math.max(firstRowWidth(timelineMinimum), speed.getPreferredSize().width);
        int height = rowHeight() + UiStyle.space(4) + speed.getPreferredSize().height;
        return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
    }

    @Override
    public void layoutContainer(Container parent) {
        Insets insets = parent.getInsets();
        int x = insets.left;
        int y = insets.top;
        int width = parent.getWidth() - insets.left - insets.right;
        boolean oneRow = oneRow(width);
        int row = oneRow ? Math.max(rowHeight(), speed.getPreferredSize().height) : rowHeight();
        Dimension b = buttons.getPreferredSize();
        Dimension p = place.getPreferredSize();
        Dimension s = speed.getPreferredSize();
        int right = x + width;
        if (oneRow) {
            speed.setBounds(right - s.width, y + (row - s.height) / 2, s.width, s.height);
            right -= s.width + gap;
        } else {
            speed.setBounds(x, y + row + UiStyle.space(4), Math.min(s.width, width), s.height);
        }
        buttons.setBounds(x, y + (row - b.height) / 2, b.width, b.height);
        place.setBounds(right - p.width, y + (row - p.height) / 2, p.width, p.height);
        int timelineX = x + b.width + gap;
        int timelineWidth = Math.max(0, right - p.width - gap - timelineX);
        int timelineHeight = timeline.getPreferredSize().height;
        timeline.setBounds(timelineX, y + (row - timelineHeight) / 2, timelineWidth, timelineHeight);
    }

    @Override
    public void addLayoutComponent(String name, Component component) {
    }

    @Override
    public void removeLayoutComponent(Component component) {
    }
}
