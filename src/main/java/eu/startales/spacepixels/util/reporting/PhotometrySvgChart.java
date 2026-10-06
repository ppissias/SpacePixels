/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */

package eu.startales.spacepixels.util.reporting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal inline-SVG chart builder for the photometry report section.
 *
 * <p>One x axis and one y axis per chart (optionally logarithmic or inverted for magnitudes),
 * a recessive grid, and marks that carry their own {@code <title>} so every data point has a
 * native hover tooltip. Colors are dark-surface steps validated against the report background.</p>
 */
final class PhotometrySvgChart {

    /** Chart surface and ink, matching the report's dark theme. */
    static final String SURFACE = "#2b2b2b";
    static final String TEXT_PRIMARY = "#e6e6e6";
    static final String TEXT_SECONDARY = "#a8a8a2";
    static final String GRID = "#3a3a38";
    static final String AXIS = "#6a6a66";
    /** Categorical slots (dark steps), validated all-pairs on the report surface. */
    static final String SERIES_BLUE = "#3987e5";
    static final String SERIES_ORANGE = "#d95926";
    static final String SERIES_AQUA = "#199e70";
    /** Context marks that are not a series of their own. */
    static final String CONTEXT_GRAY = "#6f6f6a";
    static final String REFERENCE_INK = "#c3c2b7";
    /** Status colors, always paired with a marker shape and a label. */
    static final String STATUS_GOOD = "#0ca30c";
    static final String STATUS_WARNING = "#fab219";
    static final String STATUS_CRITICAL = "#d03b3b";

    enum Marker { CIRCLE, HOLLOW_CIRCLE, CROSS }

    private static final int MARGIN_LEFT = 58;
    private static final int DEFAULT_MARGIN_RIGHT = 14;
    private static final int MARGIN_TOP = 12;
    private static final int MARGIN_BOTTOM = 40;

    private final int width;
    private final int height;
    private final String ariaLabel;
    private final String xLabel;
    private final String yLabel;
    private int marginRight = DEFAULT_MARGIN_RIGHT;
    private double xMin = Double.NaN;
    private double xMax = Double.NaN;
    private double yMin = Double.NaN;
    private double yMax = Double.NaN;
    private boolean logY;
    private boolean invertY;
    private final StringBuilder background = new StringBuilder();
    private final StringBuilder marks = new StringBuilder();
    private final List<double[]> extents = new ArrayList<>();

    PhotometrySvgChart(int width, int height, String ariaLabel, String xLabel, String yLabel) {
        this.width = width;
        this.height = height;
        this.ariaLabel = ariaLabel;
        this.xLabel = xLabel;
        this.yLabel = yLabel;
    }

    /** Extra room on the right, for direct labels at the end of lines. */
    PhotometrySvgChart rightMargin(int pixels) {
        this.marginRight = pixels;
        return this;
    }

    PhotometrySvgChart logY() {
        this.logY = true;
        return this;
    }

    /** Smaller values at the top, as for magnitudes. */
    PhotometrySvgChart invertY() {
        this.invertY = true;
        return this;
    }

    PhotometrySvgChart xRange(double min, double max) {
        this.xMin = min;
        this.xMax = max;
        return this;
    }

    PhotometrySvgChart yRange(double min, double max) {
        this.yMin = min;
        this.yMax = max;
        return this;
    }

    /** Includes values in the automatic axis ranges without drawing anything. */
    void includeRange(double[] xs, double[] ys) {
        extents.add(range(xs));
        extents.add(new double[]{Double.NaN, Double.NaN});
        extents.add(range(ys));
    }

    // =================================================================
    // Marks (recorded with data coordinates, rendered once ranges are known)
    // =================================================================

    private final List<Runnable> deferred = new ArrayList<>();

    void line(double[] xs, double[] ys, String color, double strokeWidth, boolean dashed, String tooltip) {
        includeRange(xs, ys);
        deferred.add(() -> {
            StringBuilder path = new StringBuilder();
            boolean penDown = false;
            for (int i = 0; i < xs.length; i++) {
                if (!finite(xs[i]) || !finite(ys[i]) || (logY && ys[i] <= 0)) {
                    penDown = false;
                    continue;
                }
                path.append(penDown ? " L" : " M").append(fmt(px(xs[i]))).append(' ').append(fmt(py(ys[i])));
                penDown = true;
            }
            if (path.length() == 0) {
                return;
            }
            marks.append("<path d='").append(path).append("' fill='none' stroke='").append(color)
                    .append("' stroke-width='").append(fmt(strokeWidth)).append("' stroke-linejoin='round' stroke-linecap='round'")
                    .append(dashed ? " stroke-dasharray='6 4'" : "").append(">");
            if (tooltip != null) {
                marks.append("<title>").append(DetectionReportGenerator.escapeHtml(tooltip)).append("</title>");
            }
            marks.append("</path>");
        });
    }

    void points(double[] xs, double[] ys, String color, double radius, Marker marker, String[] tooltips) {
        includeRange(xs, ys);
        deferred.add(() -> {
            for (int i = 0; i < xs.length; i++) {
                if (!finite(xs[i]) || !finite(ys[i]) || (logY && ys[i] <= 0)) {
                    continue;
                }
                double cx = px(xs[i]);
                double cy = py(ys[i]);
                marks.append("<g>");
                if (tooltips != null && tooltips[i] != null) {
                    marks.append("<title>").append(DetectionReportGenerator.escapeHtml(tooltips[i])).append("</title>");
                    // Invisible hit target larger than the mark.
                    marks.append("<circle cx='").append(fmt(cx)).append("' cy='").append(fmt(cy))
                            .append("' r='").append(fmt(Math.max(7, radius + 4))).append("' fill='transparent'/>");
                }
                switch (marker) {
                    case CROSS:
                        double r = radius;
                        marks.append("<path d='M").append(fmt(cx - r)).append(' ').append(fmt(cy - r))
                                .append(" L").append(fmt(cx + r)).append(' ').append(fmt(cy + r))
                                .append(" M").append(fmt(cx - r)).append(' ').append(fmt(cy + r))
                                .append(" L").append(fmt(cx + r)).append(' ').append(fmt(cy - r))
                                .append("' stroke='").append(color).append("' stroke-width='2.5' stroke-linecap='round'/>");
                        break;
                    case HOLLOW_CIRCLE:
                        marks.append("<circle cx='").append(fmt(cx)).append("' cy='").append(fmt(cy)).append("' r='").append(fmt(radius))
                                .append("' fill='none' stroke='").append(color).append("' stroke-width='1.5'/>");
                        break;
                    default:
                        marks.append("<circle cx='").append(fmt(cx)).append("' cy='").append(fmt(cy)).append("' r='").append(fmt(radius))
                                .append("' fill='").append(color).append("' stroke='").append(SURFACE).append("' stroke-width='1'/>");
                        break;
                }
                marks.append("</g>");
            }
        });
    }

    /** Horizontal reference line spanning the plot, with a short direct label at the right end. */
    void horizontalLine(double y, String color, boolean dashed, String label) {
        extents.add(new double[]{Double.NaN, Double.NaN});
        extents.add(new double[]{Double.NaN, Double.NaN});
        extents.add(new double[]{y, y});
        deferred.add(() -> {
            if (!finite(y) || (logY && y <= 0)) {
                return;
            }
            double yy = py(y);
            marks.append("<line x1='").append(MARGIN_LEFT).append("' x2='").append(width - marginRight)
                    .append("' y1='").append(fmt(yy)).append("' y2='").append(fmt(yy)).append("' stroke='").append(color)
                    .append("' stroke-width='1.5'").append(dashed ? " stroke-dasharray='5 4'" : "").append("/>");
            if (label != null) {
                marks.append("<text x='").append(width - marginRight - 2).append("' y='").append(fmt(yy - 4))
                        .append("' fill='").append(TEXT_SECONDARY).append("' font-size='10' text-anchor='end'>")
                        .append(DetectionReportGenerator.escapeHtml(label)).append("</text>");
            }
        });
    }

    /** Shaded horizontal band between two y values (for a tolerance window). */
    void band(double y0, double y1, String color, double opacity) {
        extents.add(new double[]{Double.NaN, Double.NaN});
        extents.add(new double[]{Double.NaN, Double.NaN});
        extents.add(new double[]{Math.min(y0, y1), Math.max(y0, y1)});
        deferred.add(() -> {
            double top = Math.min(py(y0), py(y1));
            double bottom = Math.max(py(y0), py(y1));
            background.append("<rect x='").append(MARGIN_LEFT).append("' y='").append(fmt(top))
                    .append("' width='").append(width - MARGIN_LEFT - marginRight).append("' height='").append(fmt(bottom - top))
                    .append("' fill='").append(color).append("' fill-opacity='").append(fmt(opacity)).append("'/>");
        });
    }

    /** Text placed at data coordinates (used for sparse direct labels). */
    void label(double x, double y, String text) {
        deferred.add(() -> {
            if (!finite(x) || !finite(y)) {
                return;
            }
            marks.append("<text x='").append(fmt(px(x) + 6)).append("' y='").append(fmt(py(y) + 4))
                    .append("' fill='").append(TEXT_SECONDARY).append("' font-size='10'>")
                    .append(DetectionReportGenerator.escapeHtml(text)).append("</text>");
        });
    }

    String render() {
        resolveRanges();
        for (Runnable r : deferred) {
            r.run();
        }
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns='http://www.w3.org/2000/svg' role='img' viewBox='0 0 ").append(width).append(' ').append(height)
                .append("' width='").append(width).append("' height='").append(height)
                .append("' style='max-width:100%; height:auto; background:").append(SURFACE).append("; border-radius:4px;'")
                .append(" font-family=\"'Segoe UI', Tahoma, sans-serif\">");
        svg.append("<title>").append(DetectionReportGenerator.escapeHtml(ariaLabel)).append("</title>");
        svg.append(background);
        appendAxes(svg);
        svg.append(marks);
        svg.append("</svg>");
        return svg.toString();
    }

    // =================================================================
    // Axes and scaling
    // =================================================================

    private void resolveRanges() {
        if (!finite(xMin) || !finite(xMax)) {
            double[] r = combined(0);
            xMin = finite(xMin) ? xMin : r[0];
            xMax = finite(xMax) ? xMax : r[1];
        }
        if (!finite(yMin) || !finite(yMax)) {
            double[] r = combined(2);
            double lo = finite(yMin) ? yMin : r[0];
            double hi = finite(yMax) ? yMax : r[1];
            if (logY) {
                lo = Math.max(lo, 1e-6);
                hi = Math.max(hi, lo * 10);
                yMin = Math.pow(10, Math.floor(Math.log10(lo)));
                yMax = Math.pow(10, Math.ceil(Math.log10(hi)));
            } else {
                double pad = (hi - lo) * 0.06;
                if (!(pad > 0)) {
                    pad = Math.max(Math.abs(hi) * 0.1, 0.01);
                }
                yMin = lo - pad;
                yMax = hi + pad;
            }
        }
        if (!finite(xMin) || !finite(xMax)) {
            xMin = 0;
            xMax = 1;
        }
        if (!finite(yMin) || !finite(yMax)) {
            yMin = 0;
            yMax = 1;
        }
        if (xMax <= xMin) {
            xMax = xMin + 1;
        }
        if (yMax <= yMin) {
            yMax = yMin + 1;
        }
    }

    private double[] combined(int offset) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = offset; i < extents.size(); i += 3) {
            double[] e = extents.get(i);
            if (finite(e[0])) lo = Math.min(lo, e[0]);
            if (finite(e[1])) hi = Math.max(hi, e[1]);
        }
        return new double[]{lo, hi};
    }

    private void appendAxes(StringBuilder svg) {
        int plotLeft = MARGIN_LEFT;
        int plotRight = width - marginRight;
        int plotTop = MARGIN_TOP;
        int plotBottom = height - MARGIN_BOTTOM;

        for (double t : ticks(xMin, xMax, Math.max(3, (plotRight - plotLeft) / 80))) {
            double x = px(t);
            svg.append("<line x1='").append(fmt(x)).append("' x2='").append(fmt(x)).append("' y1='").append(plotTop)
                    .append("' y2='").append(plotBottom).append("' stroke='").append(GRID).append("' stroke-width='1'/>");
            svg.append("<text x='").append(fmt(x)).append("' y='").append(plotBottom + 14).append("' fill='").append(TEXT_SECONDARY)
                    .append("' font-size='10' text-anchor='middle'>").append(tickLabel(t)).append("</text>");
        }
        List<Double> yTicks = logY ? logTicks() : ticks(yMin, yMax, Math.max(3, (plotBottom - plotTop) / 40));
        for (double t : yTicks) {
            double y = py(t);
            svg.append("<line x1='").append(plotLeft).append("' x2='").append(plotRight).append("' y1='").append(fmt(y))
                    .append("' y2='").append(fmt(y)).append("' stroke='").append(GRID).append("' stroke-width='1'/>");
            svg.append("<text x='").append(plotLeft - 6).append("' y='").append(fmt(y + 3)).append("' fill='").append(TEXT_SECONDARY)
                    .append("' font-size='10' text-anchor='end'>").append(tickLabel(t)).append("</text>");
        }
        svg.append("<line x1='").append(plotLeft).append("' x2='").append(plotRight).append("' y1='").append(plotBottom)
                .append("' y2='").append(plotBottom).append("' stroke='").append(AXIS).append("'/>");
        svg.append("<line x1='").append(plotLeft).append("' x2='").append(plotLeft).append("' y1='").append(plotTop)
                .append("' y2='").append(plotBottom).append("' stroke='").append(AXIS).append("'/>");
        svg.append("<text x='").append((plotLeft + plotRight) / 2).append("' y='").append(height - 6).append("' fill='")
                .append(TEXT_SECONDARY).append("' font-size='11' text-anchor='middle'>")
                .append(DetectionReportGenerator.escapeHtml(xLabel)).append("</text>");
        svg.append("<text x='12' y='").append((plotTop + plotBottom) / 2).append("' fill='").append(TEXT_SECONDARY)
                .append("' font-size='11' text-anchor='middle' transform='rotate(-90 12 ").append((plotTop + plotBottom) / 2)
                .append(")'>").append(DetectionReportGenerator.escapeHtml(yLabel)).append("</text>");
    }

    private double px(double x) {
        return MARGIN_LEFT + (x - xMin) / (xMax - xMin) * (width - MARGIN_LEFT - marginRight);
    }

    private double py(double y) {
        double t;
        if (logY) {
            t = (Math.log10(Math.max(y, 1e-12)) - Math.log10(yMin)) / (Math.log10(yMax) - Math.log10(yMin));
        } else {
            t = (y - yMin) / (yMax - yMin);
        }
        if (invertY) {
            t = 1.0 - t;
        }
        return height - MARGIN_BOTTOM - t * (height - MARGIN_BOTTOM - MARGIN_TOP);
    }

    private List<Double> logTicks() {
        List<Double> ticks = new ArrayList<>();
        for (int e = (int) Math.floor(Math.log10(yMin)); e <= (int) Math.ceil(Math.log10(yMax)); e++) {
            double t = Math.pow(10, e);
            if (t >= yMin * 0.999 && t <= yMax * 1.001) {
                ticks.add(t);
            }
        }
        return ticks;
    }

    static List<Double> ticks(double min, double max, int target) {
        List<Double> ticks = new ArrayList<>();
        double span = max - min;
        if (!(span > 0)) {
            ticks.add(min);
            return ticks;
        }
        double rough = span / Math.max(1, target);
        double magnitude = Math.pow(10, Math.floor(Math.log10(rough)));
        double residual = rough / magnitude;
        double step = residual >= 5 ? 10 * magnitude : residual >= 2 ? 5 * magnitude : residual >= 1 ? 2 * magnitude : magnitude;
        for (double t = Math.ceil(min / step) * step; t <= max + step * 1e-9; t += step) {
            ticks.add(Math.abs(t) < step * 1e-9 ? 0.0 : t);
        }
        return ticks;
    }

    private static String tickLabel(double t) {
        double a = Math.abs(t);
        if (a == 0) return "0";
        if (a >= 1000 || a < 0.001) return String.format(Locale.US, "%.0e", t);
        if (a >= 100) return String.format(Locale.US, "%.0f", t);
        if (a >= 1) return trim(String.format(Locale.US, "%.2f", t));
        return trim(String.format(Locale.US, "%.4f", t));
    }

    private static String trim(String s) {
        if (!s.contains(".")) return s;
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static double[] range(double[] values) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            if (finite(v)) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        return new double[]{lo, hi};
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v);
    }
}
