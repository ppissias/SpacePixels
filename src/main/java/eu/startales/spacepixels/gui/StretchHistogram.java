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
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;

/**
 * The small histogram of the Display stretch section: the pixel values of the frame shown, on a logarithmic count
 * scale. Values the stretch shows as black are drawn darker, and an accent line marks the black point: the first
 * value the stretch shows brighter than black. This works for every stretch function, because it reads the stretch's
 * own lookup table. The range runs from the darkest value to a little beyond the value the stretch shows white.
 */
final class StretchHistogram extends JComponent {

    /** Bins and table entries are indexed by {@code value - Short.MIN_VALUE}, as {@code DisplayStretch} makes them. */
    private int[] histogram;
    private byte[] table;
    private int low;
    private int high;
    private int blackPoint = -1;

    StretchHistogram() {
        setOpaque(false);
        setFocusable(false);
        getAccessibleContext().setAccessibleName("Display stretch histogram");
    }

    /** A plain component has no accessibility context; the histogram is described as a picture. */
    @Override
    public javax.accessibility.AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public javax.accessibility.AccessibleRole getAccessibleRole() {
                    return javax.accessibility.AccessibleRole.ICON;
                }
            };
        }
        return accessibleContext;
    }

    /** Shows the histogram of the frame shown with the stretch table it is drawn with; nulls clear it. */
    void setData(int[] histogram, byte[] table) {
        this.histogram = histogram;
        this.table = table;
        measure();
        repaint();
    }

    /** The black point as a 16-bit pixel value, or -1 when the stretch shows no value as black. */
    int blackPointValue() {
        return blackPoint < 0 ? -1 : blackPoint;
    }

    private void measure() {
        blackPoint = -1;
        if (histogram == null || table == null) {
            setToolTipText(null);
            return;
        }
        int first = 0;
        while (first < histogram.length - 1 && histogram[first] == 0) first++;
        int last = histogram.length - 1;
        while (last > first && histogram[last] == 0) last--;
        int white = last;
        for (int value = first; value <= last; value++) {
            if ((table[value] & 0xFF) == 255) {
                white = value;
                break;
            }
        }
        int black = -1;
        for (int value = first; value <= last && (table[value] & 0xFF) == 0; value++) {
            black = value + 1;
        }
        low = first;
        high = Math.max(first + 1, Math.min(last, white + Math.max(1, (white - first) / 4)));
        blackPoint = black > first && black <= high ? black : -1;
        setToolTipText(blackPoint < 0
                ? "<html>Pixel values of the frame shown. No value is shown as black.</html>"
                : String.format(java.util.Locale.US, "<html>Pixel values of the frame shown. Darker: values shown as black, "
                + "below the black point (%,d).</html>", blackPoint));
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(UiStyle.space(200), UiStyle.space(44));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            int width = getWidth();
            int height = getHeight();
            Color fore = UIManager.getColor("Label.foreground");
            if (fore == null) fore = Color.GRAY;
            g2.setColor(new Color(fore.getRed(), fore.getGreen(), fore.getBlue(), 20));
            g2.fillRect(0, 0, width, height);
            if (histogram == null || width <= 2) return;
            float alpha = isEnabled() ? 1f : 0.4f;
            double[] counts = new double[width];
            double max = 0;
            double perColumn = (high - low + 1) / (double) width;
            for (int column = 0; column < width; column++) {
                int from = low + (int) Math.floor(column * perColumn);
                int to = Math.min(high, low + (int) Math.floor((column + 1) * perColumn) - 1);
                long sum = 0;
                for (int value = from; value <= Math.max(from, to); value++) sum += histogram[value];
                counts[column] = Math.log1p(sum);
                max = Math.max(max, counts[column]);
            }
            int blackColumn = blackPoint < 0 ? -1 : (int) Math.round((blackPoint - low) / perColumn);
            Color bars = new Color(fore.getRed(), fore.getGreen(), fore.getBlue(), Math.round(150 * alpha));
            Color clipped = new Color(fore.getRed(), fore.getGreen(), fore.getBlue(), Math.round(55 * alpha));
            for (int column = 0; column < width; column++) {
                double bar = max <= 0 ? 0 : counts[column] / max * (height - 2);
                g2.setColor(column < blackColumn ? clipped : bars);
                g2.fill(new Rectangle2D.Double(column, height - bar, 1, bar));
            }
            if (blackColumn >= 0) {
                Color accent = SidePanelIcons.accent();
                g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), Math.round(255 * alpha)));
                g2.setStroke(new BasicStroke(1.5f));
                g2.draw(new Line2D.Double(blackColumn + 0.5, 0, blackColumn + 0.5, height));
            }
        } finally {
            g2.dispose();
        }
    }
}
