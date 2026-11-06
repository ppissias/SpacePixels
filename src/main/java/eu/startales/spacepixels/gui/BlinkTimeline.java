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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.function.IntConsumer;

/**
 * Blink's timeline: one tick per frame, placed by capture time, so the gaps of a session show. Frames left out of the
 * loop have hollow ticks; the frame shown has a larger accent tick, with a light fill up to it. Clicking near a tick
 * shows that frame. Below, the time since the first frame, the total, and a label in each gap. Frames without a
 * capture time are spaced evenly.
 */
final class BlinkTimeline extends JComponent {

    /** Positions on the time axis, in milliseconds from the first frame (or frame numbers without times). */
    private long[] offsets = new long[0];
    private boolean[] skipped = new boolean[0];
    private boolean[] gapBefore = new boolean[0];
    private boolean timed;
    private int current;
    private IntConsumer onSelect = index -> { };

    BlinkTimeline() {
        setOpaque(false);
        setFocusable(false);
        setToolTipText("Frames by capture time. Hollow ticks are left out of the loop; click a tick to show that frame.");
        getAccessibleContext().setAccessibleName("Blink timeline");
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int index = nearest(e.getX());
                if (index >= 0 && SwingUtilities.isLeftMouseButton(e)) onSelect.accept(index);
            }
        });
    }

    /** A plain component has no accessibility context; the timeline is described as a panel. */
    @Override
    public javax.accessibility.AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public javax.accessibility.AccessibleRole getAccessibleRole() {
                    return javax.accessibility.AccessibleRole.PANEL;
                }
            };
        }
        return accessibleContext;
    }

    /** Sets the frames: their capture times (0 or less when unknown), which are left out, and where gaps start. */
    void setFrames(long[] timestamps, boolean[] skipped, boolean[] gapBefore) {
        int count = timestamps.length;
        timed = count > 1;
        for (long timestamp : timestamps) timed &= timestamp > 0;
        offsets = new long[count];
        for (int i = 0; i < count; i++) offsets[i] = timed ? timestamps[i] - timestamps[0] : i;
        this.skipped = skipped;
        this.gapBefore = gapBefore;
        repaint();
    }

    void setCurrent(int current) {
        this.current = current;
        repaint();
    }

    /** Repaints after a frame was put in or out of the loop. */
    void loopChanged() {
        repaint();
    }

    void setOnSelect(IntConsumer onSelect) {
        this.onSelect = onSelect;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(scaleFont());
        return new Dimension(UiStyle.space(200), UiStyle.space(22) + metrics.getHeight());
    }

    private Font scaleFont() {
        return UiStyle.bodyFont().deriveFont(UiStyle.bodyFont().getSize2D() - 2f);
    }

    private int left() {
        return UiStyle.space(8);
    }

    private int right() {
        return getWidth() - UiStyle.space(8);
    }

    private long span() {
        return offsets.length == 0 ? 1 : Math.max(1, offsets[offsets.length - 1] - offsets[0]);
    }

    private double x(int index) {
        return left() + (right() - left()) * (offsets[index] - offsets[0]) / (double) span();
    }

    /** The frame whose tick is nearest to this column, within a few pixels; -1 when none is. */
    int nearest(int column) {
        int best = -1;
        double distance = UiStyle.space(8);
        for (int i = 0; i < offsets.length; i++) {
            double d = Math.abs(x(i) - column);
            if (d <= distance) {
                distance = d;
                best = i;
            }
        }
        return best;
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (offsets.length == 0) return;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fore = UIManager.getColor("Label.foreground");
            if (fore == null) fore = Color.GRAY;
            Color accent = SidePanelIcons.accent();
            int axis = UiStyle.space(12);
            int tick = UiStyle.space(5);
            double currentX = x(Math.min(current, offsets.length - 1));
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 50));
            g2.fill(new Rectangle2D.Double(left(), axis - tick, currentX - left(), 2 * tick));
            g2.setColor(new Color(fore.getRed(), fore.getGreen(), fore.getBlue(), 90));
            g2.drawLine(left(), axis, right(), axis);
            for (int i = 0; i < offsets.length; i++) {
                if (i == current) continue;
                Rectangle2D.Double mark = new Rectangle2D.Double(x(i) - 1.5, axis - tick, 3, 2 * tick);
                g2.setColor(fore);
                if (skipped[i]) g2.draw(mark);
                else g2.fill(mark);
            }
            g2.setColor(accent);
            g2.fill(new Rectangle2D.Double(currentX - 2.5, axis - tick - 3, 5, 2 * tick + 6));

            g2.setFont(scaleFont());
            FontMetrics metrics = g2.getFontMetrics();
            int baseline = axis + tick + UiStyle.space(4) + metrics.getAscent();
            g2.setColor(UiStyle.helpColor());
            String start = timed ? "+0:00" : "1";
            String end = timed ? "+" + clock(offsets[offsets.length - 1]) : String.valueOf(offsets.length);
            g2.drawString(start, left(), baseline);
            g2.drawString(end, right() - metrics.stringWidth(end), baseline);
            for (int i = 1; i < offsets.length; i++) {
                if (!gapBefore[i]) continue;
                String label = "↔ gap";
                double middle = (x(i - 1) + x(i)) / 2;
                int textX = (int) Math.round(middle - metrics.stringWidth(label) / 2.0);
                if (textX > left() + metrics.stringWidth(start) && textX + metrics.stringWidth(label) < right() - metrics.stringWidth(end)) {
                    g2.setColor(GAP_COLOUR);
                    g2.drawString(label, textX, baseline);
                }
            }
        } finally {
            g2.dispose();
        }
    }

    /** The amber of unsaved values and gaps. */
    static final Color GAP_COLOUR = new Color(0xE3C25A);

    /** "37:17" or "1:02:03" for a time in milliseconds. */
    static String clock(long millis) {
        long seconds = Math.round(Math.abs(millis) / 1000.0);
        return seconds >= 3600
                ? String.format(java.util.Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * Where gaps start: a frame whose interval to the previous one is more than 1.5 times the median interval. Frames
     * without capture times have no gaps.
     */
    static boolean[] gaps(long[] timestamps) {
        boolean[] gaps = new boolean[timestamps.length];
        if (timestamps.length < 3) return gaps;
        long[] intervals = new long[timestamps.length - 1];
        for (int i = 1; i < timestamps.length; i++) {
            if (timestamps[i] <= 0 || timestamps[i - 1] <= 0) return gaps;
            intervals[i - 1] = timestamps[i] - timestamps[i - 1];
        }
        long[] sorted = intervals.clone();
        java.util.Arrays.sort(sorted);
        double median = sorted.length % 2 == 1 ? sorted[sorted.length / 2]
                : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2.0;
        if (median <= 0) return gaps;
        for (int i = 1; i < timestamps.length; i++) gaps[i] = intervals[i - 1] > 1.5 * median;
        return gaps;
    }
}
