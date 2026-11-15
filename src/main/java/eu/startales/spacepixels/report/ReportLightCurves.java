/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.report;

import eu.startales.spacepixels.mpc.LightCurve;
import eu.startales.spacepixels.mpc.ReportedObjects;
import eu.startales.spacepixels.skycatalog.PhotometryStars;
import io.github.ppissias.jtransient.execution.CancellationScope;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The light curves of the report's moving-object tracks and faint movers (F34): each measured with the same
 * {@link LightCurve#measure} as the Asteroids &amp; Comets window, on the frames already in memory, with comparison
 * stars from the session's saved sky catalogue and its depth samples only (nothing is fetched while the report is
 * written). Each curve is saved as {@code light_curves/<id>_lightcurve.csv} and drawn in its card, with bins of 2 and 4
 * frames to choose from.
 */
final class ReportLightCurves {
    private static final Logger LOGGER = Logger.getLogger(ReportLightCurves.class.getName());
    /** Most cut-out pixels kept for one object's frames' median, in bytes; a longer path is measured on the frames. */
    static final long MAX_CUTOUT_BYTES = 64L << 20;
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC);

    /** The curves by object ID, and why an object has none. */
    final Map<String, LightCurve.Curve> curves = new LinkedHashMap<>();
    final Map<String, String> problems = new LinkedHashMap<>();
    /** How many comparison stars the session offers, before an object's path is left out; -1 without a catalogue. */
    int usableComparisonStars = -1;

    private ReportLightCurves() { }

    /** An empty set, for a report without moving objects or frames. */
    static ReportLightCurves none() { return new ReportLightCurves(); }

    /** Measures every object of the report data already written in {@code context.exportDir}. */
    static ReportLightCurves measure(DetectionReportContext context) {
        var result = new ReportLightCurves();
        if (context.rawFrames == null || context.rawFrames.isEmpty()) return result;
        ReportedObjects.Report report;
        try {
            report = ReportedObjects.load(context.exportDir);
        } catch (IOException unreadable) {
            LOGGER.log(Level.WARNING, "Light curves skipped: " + unreadable.getMessage());
            return result;
        }
        if (report.objects().isEmpty()) return result;
        var catalogue = SessionCatalogue.of(context).catalogue;
        var wcs = context.astrometryContext != null ? context.astrometryContext.getTransformer() : null;
        List<PhotometryStars.Measured> candidates = List.of();
        double sessionFwhm = sessionFwhm(report);
        if (catalogue != null && wcs != null) {
            short[][] middle = middleFrame(context);
            if (middle != null) {
                candidates = LightCurve.measureCandidates(middle, wcs, PhotometryStars.candidates(catalogue), sessionFwhm);
                result.usableComparisonStars = (int) candidates.stream().filter(PhotometryStars.Measured::usable).count();
            }
        }
        LightCurve.Frames frames = number -> {
            short[][] frame = number >= 1 && number <= context.rawFrames.size() ? context.rawFrames.get(number - 1) : null;
            if (frame == null) throw new IOException("frame " + number + " is not in memory");
            return frame;
        };
        for (var object : report.objects()) {
            CancellationScope.check();
            if (object.positions().size() < 3) {
                result.problems.put(object.id(), "too few positions to draw a curve");
                continue;
            }
            try {
                double fwhm = LightCurve.fwhm(report, object);
                var samples = LightCurve.samples(report, object);
                var comparisons = PhotometryStars.choose(candidates, LightCurve.pathBox(samples), LightCurve.CLEARANCE_FWHM * fwhm);
                var curve = LightCurve.measure(object.id(), object.measured(), samples, frames, comparisons, fwhm,
                        LightCurve.pixelsPerMinute(object, samples), MAX_CUTOUT_BYTES, null, () -> {
                            CancellationScope.check();
                            return false;
                        });
                if (curve == null) continue;
                result.curves.put(object.id(), curve);
                Files.writeString(context.file(csvLink(object.id())).toPath(), LightCurve.csv(curve, 1), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                LOGGER.log(Level.FINE, "Light curve of " + object.id() + " not measured", failure);
                result.problems.put(object.id(), String.valueOf(failure.getMessage()));
            } catch (RuntimeException failure) {
                if (eu.startales.spacepixels.util.Cancellation.isCancellation(failure)) throw failure;
                LOGGER.log(Level.FINE, "Light curve of " + object.id() + " not measured", failure);
                result.problems.put(object.id(), String.valueOf(failure.getMessage()));
            }
        }
        return result;
    }

    /** The FWHM for choosing comparison stars: the faint-mover search's, else the median over all tracks' detections. */
    private static double sessionFwhm(ReportedObjects.Report report) {
        if (report.faintMoverFwhm() != null && report.faintMoverFwhm() > 0) return report.faintMoverFwhm();
        double[] widths = report.objects().stream().flatMap(o -> o.positions().stream()).filter(p -> p.fwhm() != null && p.fwhm() > 0)
                .mapToDouble(ReportedObjects.Position::fwhm).sorted().toArray();
        return widths.length == 0 ? 3.0 : widths[widths.length / 2] / Math.sqrt(2);
    }

    /** The middle frame that quality control kept, else the middle frame. */
    private static short[][] middleFrame(DetectionReportContext context) {
        var kept = context.keptFrameIndices;
        int index = kept.isEmpty() ? context.rawFrames.size() / 2 : kept.get(kept.size() / 2);
        return context.rawFrames.get(index);
    }

    static String csvLink(String id) {
        return "light_curves/" + id.toLowerCase(Locale.ROOT) + "_lightcurve.csv";
    }

    /** The light-curve block of one object's card, or nothing when the report measured none. */
    void write(PrintWriter out, String id) {
        var curve = curves.get(id);
        if (curve == null) {
            String problem = problems.get(id);
            if (problem != null) out.println("<p class='compact-note'>Light curve: not measured (" + ReportFormat.escapeHtml(problem) + ").</p>");
            return;
        }
        out.println("<div class='lc' data-bin='1'>");
        out.println("<div class='lc-head'><strong>Light curve</strong> <select class='lc-bin' aria-label='Bins' "
                + "onchange=\"this.closest('.lc').dataset.bin=this.value\"><option value='1'>Single frames</option>"
                + "<option value='2'>Bins of 2</option><option value='4'>Bins of 4</option></select> "
                + "<a class='id-link' href='" + csvLink(id) + "' download>CSV</a></div>");
        for (int size : LightCurve.BIN_SIZES) {
            var summary = size > 1 ? LightCurve.summarise(LightCurve.bin(curve, size)) : LightCurve.summarise(curve);
            out.printf(Locale.US, "<p class='compact-note lc-s lc-s%d'>%d %s · %s · %s · median %s · amplitude (5–95 %%) %s mag · scatter %s mag.</p>%n",
                    size, summary.points(), size > 1 ? "bins of " + size + " frames" : "frames", correction(curve),
                    curve.onReference() ? "fixed stars subtracted" : "fixed stars not subtracted (path too long)",
                    number(summary.median(), 2), number(summary.amplitude(), 2), number(summary.scatter(), 2));
        }
        out.println(svg(curve));
        out.println("<p class='compact-note'>Relative magnitudes from aperture photometry (1.5 FWHM), not calibrated; brighter is up. "
                + "Hollow points are flagged and left out of the summary and the bins. "
                + (curve.measuredPositions() ? "" : "A faint mover is measured at its fitted positions, so single frames are noisy. ")
                + "A dip that lasts a few frames can come from the data rather than the object.</p>");
        out.println("</div>");
    }

    /** How the curve was corrected, in words. */
    private String correction(LightCurve.Curve curve) {
        if (curve.corrected()) {
            return curve.comparisonStars() + " comparison stars" + (curve.fewComparisonStars()
                    ? " (fewer than " + PhotometryStars.MIN_USABLE + ": the correction is uncertain)" : "");
        }
        return usableComparisonStars < 0 ? "uncorrected (no sky catalogue)" : "uncorrected (too few usable comparison stars)";
    }

    /** The curve as an inline SVG: single frames, and bins of 2 and 4 that the bin choice shows. */
    static String svg(LightCurve.Curve curve) {
        var points = curve.points().stream().filter(p -> Double.isFinite(p.magnitude())).toList();
        if (points.size() < 2) return "<p class='compact-note'>Too few positive measurements to draw.</p>";
        double width = 640, height = 190, left = 52, right = 8, top = 8, bottom = 24;
        double w = width - left - right, h = height - top - bottom;
        long t0 = points.getFirst().midpointMillis(), t1 = points.getLast().midpointMillis();
        double[] mags = points.stream().filter(LightCurve::counted).mapToDouble(LightCurve.Point::magnitude).sorted().toArray();
        if (mags.length == 0) mags = points.stream().mapToDouble(LightCurve.Point::magnitude).sorted().toArray();
        double low = mags[(int) (0.02 * (mags.length - 1))], high = mags[(int) Math.ceil(0.98 * (mags.length - 1))];
        double pad = Math.max(0.05, (high - low) * 0.25), bright = low - pad, faint = high + pad;
        java.util.function.DoubleUnaryOperator y = m -> top + h * (Math.max(bright, Math.min(faint, m)) - bright) / (faint - bright);
        java.util.function.LongToDoubleFunction x = t -> left + (t1 > t0 ? w * (t - t0) / (double) (t1 - t0) : w / 2);
        var svg = new StringBuilder(String.format(Locale.US, "<svg class='lc-plot' viewBox='0 0 %.0f %.0f' role='img' aria-label='Light curve of %s'>",
                width, height, curve.id()));
        svg.append(String.format(Locale.US, "<rect class='lc-area' x='%.1f' y='%.1f' width='%.1f' height='%.1f'/>", left, top, w, h));
        svg.append(String.format(Locale.US, "<text class='lc-axis' x='4' y='%.1f'>%.2f</text><text class='lc-axis' x='4' y='%.1f'>%.2f</text>",
                top + 10, bright, top + h, faint));
        svg.append(String.format(Locale.US, "<text class='lc-axis' x='%.1f' y='%.1f'>%s</text><text class='lc-axis' x='%.1f' y='%.1f' text-anchor='end'>%s UTC</text>",
                left, height - 6, UTC.format(Instant.ofEpochMilli(t0)), left + w, height - 6, UTC.format(Instant.ofEpochMilli(t1))));
        double median = LightCurve.summarise(curve).median();
        if (Double.isFinite(median)) {
            svg.append(String.format(Locale.US, "<line class='lc-median' x1='%.1f' x2='%.1f' y1='%.2f' y2='%.2f'/>", left, left + w,
                    y.applyAsDouble(median), y.applyAsDouble(median)));
        }
        svg.append("<g class='lc-frames'>");
        for (var p : points) {
            double px = x.applyAsDouble(p.midpointMillis()), py = y.applyAsDouble(p.magnitude()), e = p.magnitudeError();
            if (Double.isFinite(e)) svg.append(String.format(Locale.US, "<line x1='%.2f' x2='%.2f' y1='%.2f' y2='%.2f'/>", px, px,
                    y.applyAsDouble(p.magnitude() - e), y.applyAsDouble(p.magnitude() + e)));
            svg.append(String.format(Locale.US, "<circle class='%s' cx='%.2f' cy='%.2f' r='2.6'><title>Frame %d · %s UTC · %.3f ± %s mag%s</title></circle>",
                    LightCurve.counted(p) ? "lc-pt" : "lc-flagged", px, py, p.frameNumber(), UTC.format(Instant.ofEpochMilli(p.midpointMillis())),
                    p.magnitude(), number(e, 3), p.flags() == 0 ? "" : " · " + String.join(" ",
                            io.github.ppissias.jtransient.photometry.PhotometryFlags.describe(p.flags()))));
        }
        svg.append("</g>");
        for (int size : new int[]{2, 4}) {
            svg.append("<g class='lc-bins lc-bins").append(size).append("'>");
            for (var b : LightCurve.bin(curve, size)) {
                if (!Double.isFinite(b.magnitude())) continue;
                double px = x.applyAsDouble(b.midpointMillis()), py = y.applyAsDouble(b.magnitude()), e = b.magnitudeError();
                if (Double.isFinite(e)) svg.append(String.format(Locale.US, "<line x1='%.2f' x2='%.2f' y1='%.2f' y2='%.2f'/>", px, px,
                        y.applyAsDouble(b.magnitude() - e), y.applyAsDouble(b.magnitude() + e)));
                svg.append(String.format(Locale.US, "<circle cx='%.2f' cy='%.2f' r='3.6'><title>Frames %d–%d · %.3f ± %s mag</title></circle>",
                        px, py, b.firstFrame(), b.lastFrame(), b.magnitude(), number(e, 3)));
            }
            svg.append("</g>");
        }
        return svg.append("</svg>").toString();
    }

    private static String number(double value, int decimals) {
        return Double.isFinite(value) ? String.format(Locale.US, "%." + decimals + "f", value) : "–";
    }
}
