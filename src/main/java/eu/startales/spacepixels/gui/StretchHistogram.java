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

import eu.startales.spacepixels.fits.DisplayStretch;
import io.github.ppissias.jtransient.core.DataArea;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.Locale;

/**
 * Renders a 16-bit logarithmic histogram together with summary statistics (Min, Max, Mean, Median, Std Dev)
 * for either the original linear frame data or the stretched display data.
 *
 * <p>Registration padding ({@code stored <= DataArea.FILL_THRESHOLD}) is excluded from the statistics and from
 * the display range so alignment borders do not skew the distribution or statistics. In stretched mode, input
 * ADU bins are distributed across their stretched output intervals so steep stretch curves remain smooth without
 * artificial quantization gaps.</p>
 */
final class StretchHistogram extends JComponent {

    /** Summary statistics over the valid 16-bit pixels (0..65,535 unsigned ADU). */
    record Stats(int min, int max, int median, double mean, double stdDev,
                 double blackClippedPercent, double whiteClippedPercent) {
    }

    private final boolean stretchedMode;
    private final String caption;
    private final boolean reserveClippingRow;

    /** Bins and table entries are indexed by {@code value - Short.MIN_VALUE}, as {@code DisplayStretch} makes them. */
    private int[] histogram;
    private short[] stretchedValues;
    private byte[] table;
    private int low;
    private int high;
    private int blackPoint = -1;
    private Stats stats;

    /** Creates a stretched histogram without a header caption. */
    StretchHistogram() {
        this(true, null, true);
    }

    private StretchHistogram(boolean stretchedMode, String caption, boolean reserveClippingRow) {
        this.stretchedMode = stretchedMode;
        this.caption = caption;
        this.reserveClippingRow = reserveClippingRow;
        setOpaque(false);
        setFocusable(false);
        getAccessibleContext().setAccessibleName(
                caption != null ? caption + " histogram" : (stretchedMode ? "Display stretch histogram" : "Original histogram"));
    }

    /** Creates an original (linear) 16-bit histogram view, optionally with a header caption. */
    static StretchHistogram original(String caption) {
        return new StretchHistogram(false, caption, caption == null);
    }

    /** Creates a stretched 16-bit histogram view, optionally with a header caption. */
    static StretchHistogram stretched(String caption) {
        return new StretchHistogram(true, caption, true);
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

    /** Updates this view with an original 16-bit histogram (for linear mode). */
    void setOriginalData(int[] histogram) {
        this.histogram = histogram;
        this.stretchedValues = null;
        this.table = null;
        measure();
        repaint();
    }

    /** Updates this view with an original 16-bit histogram and its stretched 16-bit mapping and lookup table. */
    void setStretchedData(int[] histogram, short[] stretchedValues, byte[] table) {
        this.histogram = histogram;
        this.stretchedValues = stretchedValues;
        this.table = table;
        measure();
        repaint();
    }

    /** Shows the histogram of the frame shown with the stretch table it is drawn with; nulls clear it. */
    void setData(int[] histogram, byte[] table) {
        setStretchedData(histogram, null, table);
    }

    /** The black point as a 16-bit pixel value, or -1 when the stretch shows no value as black. */
    int blackPointValue() {
        return blackPoint < 0 ? -1 : blackPoint;
    }

    /** Current computed statistics, or {@code null} when no frame data is set. */
    Stats stats() {
        return stats;
    }

    /** First valid bin index, excluding registration fill unless the entire histogram is in the fill bin. */
    static int firstValidBin(int[] histogram) {
        if (histogram == null || histogram.length == 0) {
            return 0;
        }
        int threshold = Math.max(0, Math.min(histogram.length - 1, DataArea.FILL_THRESHOLD - Short.MIN_VALUE + 1));
        for (int v = threshold; v < histogram.length; v++) {
            if (histogram[v] > 0) {
                return threshold;
            }
        }
        return 0;
    }

    /** Maps a 16-bit input histogram through 16-bit stretched values into a 65,536-bin stretched histogram. */
    static int[] mapHistogram(int[] originalHistogram, short[] stretchedValues) {
        if (originalHistogram == null) {
            return null;
        }
        if (stretchedValues == null) {
            return originalHistogram.clone();
        }
        int[] mapped = new int[DisplayStretch.VALUES];
        int firstValid = firstValidBin(originalHistogram);
        for (int v = firstValid; v < originalHistogram.length; v++) {
            int count = originalHistogram[v];
            if (count > 0) {
                int s = Math.max(0, Math.min(DisplayStretch.VALUES - 1, stretchedValues[v] - Short.MIN_VALUE));
                mapped[s] += count;
            }
        }
        return mapped;
    }

    private int stretchedValueAt(int v) {
        if (stretchedValues != null && v < stretchedValues.length) {
            return Math.max(0, Math.min(DisplayStretch.VALUES - 1, stretchedValues[v] - Short.MIN_VALUE));
        }
        if (table != null && v < table.length) {
            int scale = histogram != null && histogram.length == 256 ? 1 : 257;
            return (table[v] & 0xFF) * scale;
        }
        return v;
    }

    private void measure() {
        blackPoint = -1;
        stats = null;
        if (histogram == null || histogram.length == 0) {
            setToolTipText(null);
            return;
        }
        int firstValid = firstValidBin(histogram);
        int first = firstValid;
        while (first < histogram.length - 1 && histogram[first] == 0) {
            first++;
        }
        int last = histogram.length - 1;
        while (last > first && histogram[last] == 0) {
            last--;
        }
        if (histogram[first] == 0) {
            setToolTipText(null);
            return;
        }

        if (!stretchedMode) {
            stats = computeLinearStats(histogram, firstValid);
            low = stats != null ? stats.min() : first;
            high = stats != null ? Math.max(low + 1, stats.max()) : Math.max(first + 1, last);
            if (stats != null) {
                setToolTipText(String.format(Locale.US,
                        "<html>Original 16-bit linear pixel values (excluding registration padding).<br>"
                                + "Min: %,d · Max: %,d · Median: %,d · Mean: %,.1f · Std Dev: %,.1f</html>",
                        stats.min(), stats.max(), stats.median(), stats.mean(), stats.stdDev()));
            } else {
                setToolTipText(null);
            }
            return;
        }

        if (table != null) {
            int white = last;
            for (int value = first; value <= last && value < table.length; value++) {
                if ((table[value] & 0xFF) == 255) {
                    white = value;
                    break;
                }
            }
            int black = -1;
            for (int value = first; value <= last && value < table.length && (table[value] & 0xFF) == 0; value++) {
                black = value + 1;
            }
            int reach = Math.max(first + 1, Math.min(last, white + Math.max(1, (white - first) / 4)));
            blackPoint = black > first && black <= reach ? black : -1;
        }

        stats = computeStretchedStats(firstValid);
        low = 0;
        high = histogram.length == 256 ? 255 : DisplayStretch.VALUES - 1;
        if (stats != null) {
            String blackInfo = blackPoint < 0
                    ? "No value is shown as black."
                    : String.format(Locale.US, "Black point: %,d ADU.", blackPoint);
            setToolTipText(String.format(Locale.US,
                    "<html>Stretched 16-bit pixel values. %s<br>"
                            + "Min: %,d · Max: %,d · Median: %,d · Mean: %,.1f · Std Dev: %,.1f<br>"
                            + "Clipped: %.1f%% black · %.1f%% white</html>",
                    blackInfo, stats.min(), stats.max(), stats.median(), stats.mean(), stats.stdDev(),
                    stats.blackClippedPercent(), stats.whiteClippedPercent()));
        } else {
            setToolTipText(null);
        }
    }

    static Stats computeLinearStats(int[] histogram, int firstValid) {
        long count = 0;
        int min = -1;
        int max = -1;
        double sum = 0;
        double sumSq = 0;
        for (int v = firstValid; v < histogram.length; v++) {
            int c = histogram[v];
            if (c > 0) {
                if (min < 0) {
                    min = v;
                }
                max = v;
                count += c;
                sum += (double) v * c;
                sumSq += (double) v * v * c;
            }
        }
        if (count == 0) {
            return null;
        }
        double mean = sum / count;
        double variance = Math.max(0.0, (sumSq / count) - (mean * mean));
        double stdDev = Math.sqrt(variance);
        long target = (count - 1) / 2;
        long running = 0;
        int median = min;
        for (int v = min; v <= max; v++) {
            running += histogram[v];
            if (running > target) {
                median = v;
                break;
            }
        }
        return new Stats(min, max, median, mean, stdDev, 0.0, 0.0);
    }

    private Stats computeStretchedStats(int firstValid) {
        int maxBin = histogram.length == 256 && stretchedValues == null && table == null ? 256 : DisplayStretch.VALUES;
        int[] stretchedHist = new int[maxBin];
        long count = 0;
        long blackClipped = 0;
        long whiteClipped = 0;
        for (int v = firstValid; v < histogram.length; v++) {
            int c = histogram[v];
            if (c <= 0) {
                continue;
            }
            int s = Math.max(0, Math.min(maxBin - 1, stretchedValueAt(v)));
            stretchedHist[s] += c;
            count += c;
            boolean isBlack = (s == 0) || (table != null && v < table.length && (table[v] & 0xFF) == 0);
            boolean isWhite = (s == maxBin - 1) || (table != null && v < table.length && (table[v] & 0xFF) == 255);
            if (isBlack) {
                blackClipped += c;
            }
            if (isWhite) {
                whiteClipped += c;
            }
        }
        if (count == 0) {
            return null;
        }
        int min = -1;
        int max = -1;
        double sum = 0;
        double sumSq = 0;
        for (int s = 0; s < stretchedHist.length; s++) {
            int c = stretchedHist[s];
            if (c > 0) {
                if (min < 0) {
                    min = s;
                }
                max = s;
                sum += (double) s * c;
                sumSq += (double) s * s * c;
            }
        }
        double mean = sum / count;
        double variance = Math.max(0.0, (sumSq / count) - (mean * mean));
        double stdDev = Math.sqrt(variance);
        long target = (count - 1) / 2;
        long running = 0;
        int median = min;
        for (int s = min; s <= max; s++) {
            running += stretchedHist[s];
            if (running > target) {
                median = s;
                break;
            }
        }
        double blackPct = 100.0 * blackClipped / count;
        double whitePct = 100.0 * whiteClipped / count;
        return new Stats(min, max, median, mean, stdDev, blackPct, whitePct);
    }

    /**
     * Samples the 16-bit histogram into {@code width} display columns (logarithmic counts).
     * In stretched mode, each discrete input ADU bin is distributed across its stretched interval
     * {@code [S(v), S(v+1)]} so steep stretches never produce artificial comb gaps.
     */
    double[] sampleColumns(int width) {
        double[] counts = new double[Math.max(0, width)];
        if (histogram == null || width <= 0 || stats == null) {
            return counts;
        }
        int firstValid = firstValidBin(histogram);
        if (!stretchedMode) {
            long span = (long) high - low + 1;
            for (int column = 0; column < width; column++) {
                int from = low + (int) ((column * span) / width);
                int to = column == width - 1 ? high : Math.min(high, low + (int) (((column + 1) * span) / width) - 1);
                long sum = 0;
                for (int value = from; value <= Math.max(from, to); value++) {
                    if (value >= firstValid && value < histogram.length) {
                        sum += histogram[value];
                    }
                }
                counts[column] = Math.log1p(sum);
            }
            return counts;
        }

        double[] rawColumnCounts = new double[width];
        int domain = Math.max(1, high - low + 1);
        int lastPopulatedNext = -1;
        for (int v = firstValid; v < histogram.length; v++) {
            int c = histogram[v];
            if (c <= 0) {
                continue;
            }
            int s0 = stretchedValueAt(v);
            int s1 = v + 1 < histogram.length ? stretchedValueAt(v + 1) : s0;
            int col0 = Math.max(0, Math.min(width - 1, (int) (((long) (s0 - low) * width) / domain)));
            int col1 = Math.max(col0, Math.min(width - 1, (int) (((long) (Math.max(s0, s1 - 1) - low) * width) / domain)));
            int span = col1 - col0 + 1;
            double share = (double) c / span;
            for (int col = col0; col <= col1; col++) {
                rawColumnCounts[col] += share;
            }
            lastPopulatedNext = col1;
        }
        if (lastPopulatedNext >= 0) {
            for (int column = 0; column < width; column++) {
                counts[column] = Math.log1p(rawColumnCounts[column]);
            }
        }
        return counts;
    }

    private Font captionFont() {
        Font base = UiStyle.bodyFont();
        return base.deriveFont(Font.BOLD, Math.max(10f, base.getSize2D() - 1f));
    }

    private Font statsFont() {
        Font base = UiStyle.bodyFont();
        return base.deriveFont(Math.max(10f, base.getSize2D() - 1.5f));
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics statsMetrics = getFontMetrics(statsFont());
        int statsRows = (stretchedMode || reserveClippingRow) ? 3 : 2;
        int statsHeight = statsRows * statsMetrics.getHeight() + UiStyle.space(4);
        int captionHeight = caption != null ? getFontMetrics(captionFont()).getHeight() + UiStyle.space(2) : 0;
        int plotHeight = UiStyle.space(caption != null ? 44 : 52);
        return new Dimension(UiStyle.space(220), captionHeight + plotHeight + statsHeight);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            if (width <= 2 || height <= 2) {
                return;
            }
            float alpha = isEnabled() ? 1f : 0.45f;
            Color fore = UIManager.getColor("Label.foreground");
            if (fore == null) {
                fore = Color.LIGHT_GRAY;
            }
            Color help = UiStyle.helpColor();
            if (help == null) {
                help = fore;
            }
            Color textColor = new Color(help.getRed(), help.getGreen(), help.getBlue(),
                    Math.min(255, Math.round(help.getAlpha() * alpha)));

            int topY = 0;
            if (caption != null) {
                g2.setFont(captionFont());
                FontMetrics cm = g2.getFontMetrics();
                g2.setColor(new Color(fore.getRed(), fore.getGreen(), fore.getBlue(), Math.round(210 * alpha)));
                g2.drawString(caption, 0, cm.getAscent());
                topY = cm.getHeight() + UiStyle.space(2);
            }

            g2.setFont(statsFont());
            FontMetrics sm = g2.getFontMetrics();
            int lineH = sm.getHeight();
            int statsRows = (stretchedMode || reserveClippingRow) ? 3 : 2;
            int reservedStatsH = statsRows * lineH + UiStyle.space(4);
            int plotHeight = Math.max(UiStyle.space(20), height - topY - reservedStatsH);
            if (height - topY < reservedStatsH + UiStyle.space(16)) {
                // Compact fallback when painted into a tiny test canvas (e.g. 200x44).
                plotHeight = Math.max(1, height - topY);
                reservedStatsH = 0;
            }

            g2.setColor(new Color(fore.getRed(), fore.getGreen(), fore.getBlue(), 20));
            g2.fillRect(0, topY, width, plotHeight);

            if (histogram != null && stats != null && plotHeight > 2) {
                double[] counts = sampleColumns(width);
                double max = 0;
                for (double c : counts) {
                    max = Math.max(max, c);
                }
                Color bars = stretchedMode
                        ? new Color(0x8f, 0xb4, 0xe8, Math.round(185 * alpha))
                        : new Color(fore.getRed(), fore.getGreen(), fore.getBlue(), Math.round(160 * alpha));
                g2.setColor(bars);
                for (int column = 0; column < width; column++) {
                    double bar = max <= 0 ? 0 : counts[column] / max * (plotHeight - 2);
                    if (bar > 0) {
                        g2.fill(new Rectangle2D.Double(column, topY + plotHeight - bar, 1, bar));
                    }
                }
                if (stretchedMode && blackPoint >= 0 && stats.blackClippedPercent() > 0.0) {
                    Color accent = SidePanelIcons.accent();
                    g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), Math.round(220 * alpha)));
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.draw(new Line2D.Double(0.5, topY, 0.5, topY + plotHeight));
                }
            }

            if (stats != null && reservedStatsH > 0) {
                g2.setColor(textColor);
                int textY = topY + plotHeight + UiStyle.space(2) + sm.getAscent();
                String row1 = String.format(Locale.US, "Min: %,d · Max: %,d · Median: %,d",
                        stats.min(), stats.max(), stats.median());
                String row2 = String.format(Locale.US, "Mean: %,.1f · Std Dev: %,.1f",
                        stats.mean(), stats.stdDev());
                drawFittedString(g2, row1, 1, textY, width - 2);
                drawFittedString(g2, row2, 1, textY + lineH, width - 2);
                if (stretchedMode) {
                    String row3 = String.format(Locale.US, "Clipped: %.1f%% black · %.1f%% white",
                            stats.blackClippedPercent(), stats.whiteClippedPercent());
                    drawFittedString(g2, row3, 1, textY + 2 * lineH, width - 2);
                }
            }
        } finally {
            g2.dispose();
        }
    }

    private static void drawFittedString(Graphics2D g2, String text, int x, int y, int availableWidth) {
        FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(text) <= availableWidth || availableWidth <= 20) {
            g2.drawString(text, x, y);
            return;
        }
        Font original = g2.getFont();
        float size = original.getSize2D();
        while (size > 8.5f && g2.getFontMetrics().stringWidth(text) > availableWidth) {
            size -= 0.5f;
            g2.setFont(original.deriveFont(size));
        }
        g2.drawString(text, x, y);
        g2.setFont(original);
    }
}
