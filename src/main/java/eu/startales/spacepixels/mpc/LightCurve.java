package eu.startales.spacepixels.mpc;

import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.skycatalog.PhotometryStars;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import io.github.ppissias.jtransient.core.SubStackAstrometry;
import io.github.ppissias.jtransient.photometry.MovingObjectPhotometry;
import io.github.ppissias.jtransient.photometry.PhotometryFlags;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * A moving object's light curve from JTransient's aperture photometry (MP-1): one point per frame, its flux per second
 * and error, and the frame's offset from comparison stars when there are enough of them. Magnitudes are relative:
 * instrumental, corrected for changes common to the field or not, never calibrated. {@link #measure} runs the
 * measurement for the Asteroids &amp; Comets window and the report alike; the photometry itself is the engine's.
 */
public final class LightCurve {
    /** Bin sizes offered, in frames: single frames, pairs and fours. */
    public static final int[] BIN_SIZES = {1, 2, 4};

    /**
     * One frame's point. {@code offset} is the frame's comparison-star offset in magnitudes, NaN when there were too few;
     * {@code flags} are JTransient's photometry flags.
     */
    public record Point(int frameNumber, long midpointMillis, double x, double y, double flux, double fluxError,
                        double offset, int flags) {
        /** The relative magnitude, corrected by the offset when there is one; NaN when the flux is not positive. */
        public double magnitude() {
            return flux > 0 ? -2.5 * Math.log10(flux) - (Double.isFinite(offset) ? offset : 0) : Double.NaN;
        }

        /** The magnitude error from the flux error, NaN when the flux is not positive. */
        public double magnitudeError() { return flux > 0 ? 1.0857 * fluxError / flux : Double.NaN; }

        /** The flux corrected by the offset (the light the magnitude stands for), which may be zero or negative. */
        double correctedFlux() { return flux * correction(); }

        double correctedFluxError() { return fluxError * correction(); }

        private double correction() { return Double.isFinite(offset) ? Math.pow(10, 0.4 * offset) : 1.0; }
    }

    /**
     * A curve: the object, whether its positions were measured (a track) or fitted (a faint mover), the FWHM and
     * aperture radius in pixels, how many comparison stars were measured, whether the frames' median was subtracted
     * first (fixed stars and galaxies the object passes then cancel), a note on where the comparison stars came from
     * (null for the saved catalogue), and its points in time order.
     */
    public record Curve(String id, boolean measuredPositions, double fwhm, double apertureRadius, int comparisonStars,
                        boolean onReference, String comparisonNote, List<Point> points) {
        /** Whether every point with a magnitude has a comparison-star correction. */
        public boolean corrected() {
            return points.stream().filter(p -> p.flux() > 0).allMatch(p -> Double.isFinite(p.offset()));
        }

        /** Whether fewer comparison stars were usable than a session should have. */
        public boolean fewComparisonStars() { return comparisonStars < PhotometryStars.MIN_USABLE; }
    }

    /**
     * Consecutive counted points averaged: the first and last frame, how many, the mean time, and the mean corrected
     * flux with its error. Fluxes are averaged, not magnitudes, so faint and negative values keep their weight.
     */
    public record Bin(int firstFrame, int lastFrame, int points, long midpointMillis, double flux, double fluxError) {
        /** The relative magnitude of the mean flux, NaN when it is not positive. */
        public double magnitude() { return flux > 0 ? -2.5 * Math.log10(flux) : Double.NaN; }

        /** The magnitude error from the flux error, NaN when the flux is not positive. */
        public double magnitudeError() { return flux > 0 ? 1.0857 * fluxError / flux : Double.NaN; }
    }

    /** The median, the spread between the 5th and 95th percentiles (the amplitude) and the robust scatter, in magnitudes. */
    public record Summary(int points, double median, double amplitude, double scatter) { }

    /** One frame to measure: its number, exposure midpoint and length (UTC milliseconds), and the object's registered position. */
    public record Sample(int frameNumber, long midpointMillis, long exposureMillis, double x, double y) { }

    /** Reads a frame's stored signed 16-bit pixels by its number. */
    public interface Frames {
        short[][] pixels(int frameNumber) throws IOException;
    }

    private LightCurve() { }

    /**
     * Comparison stars for an object, chosen by measurement on one aligned frame ({@link PhotometryStars}): stars that
     * are not saturated or flagged and have a signal-to-noise of at least {@value PhotometryStars#MIN_SNR}, at least 6
     * FWHM from the edges and from the box around the object's path ({minX, minY, maxX, maxY}), at most
     * {@value PhotometryStars#MAX_COMPARISON_STARS} spread over their brightness, as {x, y}.
     */
    public static List<double[]> comparisonStars(short[][] frame, WcsCoordinateTransformer wcs, List<SkyCatalogue.Star> candidates,
                                                 double[] pathBox, double fwhm) {
        if (wcs == null || candidates.isEmpty()) return List.of();
        double saturation = MovingObjectPhotometry.saturationThreshold(frame);
        double margin = Math.max(6, MovingObjectPhotometry.ANNULUS_OUTER_FWHM + 1) * fwhm;
        var measured = PhotometryStars.measure(frame, PhotometryStars.onFrame(candidates, wcs, frame[0].length, frame.length, margin),
                fwhm, saturation);
        return PhotometryStars.choose(measured, pathBox, 6 * fwhm);
    }

    /**
     * Measures a curve: the object in every sample, on the median-subtracted cut-outs around its path when they fit in
     * {@code maxCutoutBytes} (else on the frames), the comparison stars on the full frames for each frame's offset and
     * typical shape, and the shape check. Returns null when {@code cancelled} turns true.
     *
     * @param pixelsPerMinute the object's speed, for the trail length
     */
    public static Curve measure(String id, boolean measuredPositions, List<Sample> samples, Frames frames, List<double[]> comparisons,
                                double fwhm, double pixelsPerMinute, long maxCutoutBytes, String comparisonNote,
                                BooleanSupplier cancelled) throws IOException {
        double[] box = pathBox(samples);
        int margin = (int) Math.ceil(MovingObjectPhotometry.ANNULUS_OUTER_FWHM * fwhm) + 3;
        int cx = (int) Math.floor(box[0]) - margin, cy = (int) Math.floor(box[1]) - margin;
        int cw = (int) Math.ceil(box[2]) + margin - cx, ch = (int) Math.ceil(box[3]) + margin - cy;
        // The crops around the path stay in memory for the frames' median; a path too long for that is measured directly.
        boolean onReference = (long) cw * ch * samples.size() * 2 <= maxCutoutBytes;
        int n = samples.size();
        var measurements = new ArrayList<MovingObjectPhotometry.Measurement>();
        var cutouts = new ArrayList<SubStackAstrometry.Cutout>();
        double[][] comparisonFlux = new double[n][comparisons.size()];
        double[] frameConcentration = new double[n];
        double[] saturation = new double[n];
        for (int k = 0; k < n; k++) {
            if (cancelled.getAsBoolean()) return null;
            Sample sample = samples.get(k);
            short[][] frame = frames.pixels(sample.frameNumber());
            double trail = pixelsPerMinute * sample.exposureMillis() / 60000.0;
            saturation[k] = MovingObjectPhotometry.saturationThreshold(frame);
            if (onReference) {
                cutouts.add(new SubStackAstrometry.Cutout(cx, cy, crop(frame, cx, cy, cw, ch), sample.midpointMillis(), sample.exposureMillis()));
            } else {
                measurements.add(MovingObjectPhotometry.measure(frame, sample.x(), sample.y(), fwhm, sample.exposureMillis(), trail, saturation[k]));
            }
            double[] starConcentration = new double[comparisons.size()];
            for (int s = 0; s < comparisons.size(); s++) {
                var star = MovingObjectPhotometry.measure(frame, comparisons.get(s)[0], comparisons.get(s)[1], fwhm,
                        sample.exposureMillis(), 0, saturation[k]);
                // A saturated or otherwise flagged star tells nothing about the frame and is left out.
                comparisonFlux[k][s] = star.flags() == 0 ? star.flux() : Double.NaN;
                starConcentration[s] = star.flags() == 0 ? star.concentration() : Double.NaN;
            }
            frameConcentration[k] = median(starConcentration);
        }
        if (onReference) {
            float[][] reference = SubStackAstrometry.medianReference(cutouts);
            for (int k = 0; k < n; k++) {
                Sample sample = samples.get(k);
                measurements.add(MovingObjectPhotometry.measureOnReference(cutouts.get(k), reference, sample.x(), sample.y(), fwhm,
                        pixelsPerMinute * sample.exposureMillis() / 60000.0, saturation[k]));
            }
        }
        double[] offsets = new double[n];
        if (comparisons.isEmpty()) Arrays.fill(offsets, Double.NaN);
        else offsets = MovingObjectPhotometry.ensembleOffsets(comparisonFlux);
        // A frame where the object's shape departs from its usual one (blurred, blended, a cosmic ray) is flagged.
        boolean[] contaminated = MovingObjectPhotometry.contaminated(
                measurements.stream().mapToDouble(MovingObjectPhotometry.Measurement::concentration).toArray(), frameConcentration);
        var points = new ArrayList<Point>();
        for (int k = 0; k < n; k++) {
            Sample sample = samples.get(k);
            var m = measurements.get(k);
            points.add(new Point(sample.frameNumber(), sample.midpointMillis(), sample.x(), sample.y(), m.flux(), m.fluxError(), offsets[k],
                    m.flags() | (contaminated[k] ? PhotometryFlags.CONTAMINATED : 0)));
        }
        return new Curve(id, measuredPositions, fwhm, measurements.isEmpty() ? 0 : measurements.getFirst().apertureRadius(),
                comparisons.size(), onReference, comparisonNote, List.copyOf(points));
    }

    /** The box around the samples' positions, {minX, minY, maxX, maxY}. */
    public static double[] pathBox(List<Sample> samples) {
        return new double[]{samples.stream().mapToDouble(Sample::x).min().orElse(0), samples.stream().mapToDouble(Sample::y).min().orElse(0),
                samples.stream().mapToDouble(Sample::x).max().orElse(0), samples.stream().mapToDouble(Sample::y).max().orElse(0)};
    }

    /** A rectangle of a frame; pixels outside it are fill. */
    public static short[][] crop(short[][] frame, int x, int y, int width, int height) {
        short[][] out = new short[height][width];
        for (int row = 0; row < height; row++) {
            Arrays.fill(out[row], Short.MIN_VALUE);
            int source = y + row;
            if (source < 0 || source >= frame.length) continue;
            int from = Math.max(0, -x), to = Math.min(width, frame[source].length - x);
            if (from < to) System.arraycopy(frame[source], x + from, out[row], from, to - from);
        }
        return out;
    }

    /**
     * Whether a point counts in the summary and the bins: no flag but {@code TRAILED}, which at a steady speed costs every
     * frame the same small share of light and so leaves a relative curve unchanged.
     */
    public static boolean counted(Point point) {
        return (point.flags() & ~PhotometryFlags.TRAILED) == 0;
    }

    /**
     * The counted points in consecutive groups of {@code size}, in time order; points left over at the end, fewer than
     * {@code size}, form no bin. A bin of one is the point itself.
     */
    public static List<Bin> bin(Curve curve, int size) {
        if (size < 1) throw new IllegalArgumentException("bin size must be positive");
        var counted = curve.points().stream().filter(LightCurve::counted).filter(p -> Double.isFinite(p.flux())).toList();
        var bins = new ArrayList<Bin>();
        for (int start = 0; start + size <= counted.size(); start += size) {
            double flux = 0, variance = 0;
            long time = 0;
            for (int k = start; k < start + size; k++) {
                Point p = counted.get(k);
                flux += p.correctedFlux();
                variance += p.correctedFluxError() * p.correctedFluxError();
                time += p.midpointMillis();
            }
            bins.add(new Bin(counted.get(start).frameNumber(), counted.get(start + size - 1).frameNumber(), size, time / size,
                    flux / size, Math.sqrt(variance) / size));
        }
        return bins;
    }

    /** The curve's summary over its counted points with a magnitude; NaN values when there are fewer than three. */
    public static Summary summarise(Curve curve) {
        return summarise(curve.points().stream().filter(LightCurve::counted).mapToDouble(Point::magnitude).toArray());
    }

    /** The summary of bins with a magnitude; NaN values when there are fewer than three. */
    public static Summary summarise(List<Bin> bins) {
        return summarise(bins.stream().mapToDouble(Bin::magnitude).toArray());
    }

    private static Summary summarise(double[] magnitudes) {
        double[] values = Arrays.stream(magnitudes).filter(Double::isFinite).sorted().toArray();
        if (values.length < 3) return new Summary(values.length, Double.NaN, Double.NaN, Double.NaN);
        double median = quantile(values, 0.5);
        double[] deviations = Arrays.stream(values).map(v -> Math.abs(v - median)).sorted().toArray();
        return new Summary(values.length, median, quantile(values, 0.95) - quantile(values, 0.05), 1.4826 * quantile(deviations, 0.5));
    }

    /** The median of the positive finite values, NaN when there are none. */
    public static double median(double[] values) {
        double[] kept = Arrays.stream(values).filter(v -> v > 0 && Double.isFinite(v)).sorted().toArray();
        return kept.length == 0 ? Double.NaN : quantile(kept, 0.5);
    }

    private static double quantile(double[] sorted, double q) {
        double position = q * (sorted.length - 1);
        int low = (int) Math.floor(position), high = (int) Math.ceil(position);
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low);
    }

    /**
     * The curve as CSV, one row per frame, with the measurement, its correction and its flags; with a bin size above
     * one, each counted frame also carries its bin's number, magnitude and error.
     */
    public static String csv(Curve curve, int binSize) {
        var out = new StringBuilder("# ").append(curve.id()).append(curve.measuredPositions() ? " · measured positions" : " · fitted positions")
                .append(String.format(Locale.US, " · FWHM %.2f px · aperture radius %.2f px · %d comparison stars · %s · %s",
                        curve.fwhm(), curve.apertureRadius(), curve.comparisonStars(), curve.corrected() ? "corrected" : "uncorrected",
                        curve.onReference() ? "frames' median subtracted" : "measured on the frames"));
        if (curve.comparisonNote() != null) out.append(" · ").append(curve.comparisonNote());
        if (binSize > 1) out.append(" · bins of ").append(binSize).append(" frames");
        out.append('\n');
        var bins = binSize > 1 ? bin(curve, binSize) : List.<Bin>of();
        out.append("frame,utc_midpoint,x,y,flux_per_s,flux_error_per_s,comparison_offset_mag,relative_mag,mag_error,flags");
        if (binSize > 1) out.append(",bin,bin_relative_mag,bin_mag_error");
        out.append('\n');
        for (Point p : curve.points()) {
            out.append(String.format(Locale.US, "%d,%s,%.3f,%.3f,%s,%s,%s,%s,%s,%s", p.frameNumber(), Instant.ofEpochMilli(p.midpointMillis()),
                    p.x(), p.y(), number(p.flux(), 5), number(p.fluxError(), 5), number(p.offset(), 4), number(p.magnitude(), 4),
                    number(p.magnitudeError(), 4), String.join(" ", PhotometryFlags.describe(p.flags()))));
            if (binSize > 1) {
                int index = -1;
                for (int b = 0; b < bins.size(); b++) {
                    if (counted(p) && p.frameNumber() >= bins.get(b).firstFrame() && p.frameNumber() <= bins.get(b).lastFrame()) index = b;
                }
                out.append(index < 0 ? ",,," : String.format(Locale.US, ",%d,%s,%s", index + 1, number(bins.get(index).magnitude(), 4),
                        number(bins.get(index).magnitudeError(), 4)));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String number(double value, int decimals) {
        return Double.isFinite(value) ? String.format(Locale.US, "%." + decimals + "f", value) : "";
    }
}
