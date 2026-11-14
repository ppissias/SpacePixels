package eu.startales.spacepixels.mpc;

import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A moving object's light curve from JTransient's aperture photometry (MP-1): one point per frame, its flux per second
 * and error, and the frame's offset from comparison stars when there are enough of them. Magnitudes are relative:
 * instrumental, corrected for changes common to the field or not, never calibrated. This class only assembles and
 * summarises; the measurement is the engine's.
 */
public final class LightCurve {
    /** Gaia G range of comparison stars: bright enough to measure well, faint enough not to saturate. */
    static final double COMPARISON_BRIGHTEST = 12, COMPARISON_FAINTEST = 16;
    /** Most comparison stars measured per frame. */
    public static final int MAX_COMPARISON_STARS = 30;

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
    }

    /**
     * A curve: the object, whether its positions were measured (a track) or fitted (a faint mover), the FWHM and
     * aperture radius in pixels, how many comparison stars were measured, whether the frames' median was subtracted
     * first (fixed stars and galaxies the object passes then cancel), and its points in time order.
     */
    public record Curve(String id, boolean measuredPositions, double fwhm, double apertureRadius, int comparisonStars,
                        boolean onReference, List<Point> points) {
        /** Whether every point with a magnitude has a comparison-star correction. */
        public boolean corrected() {
            return points.stream().filter(p -> p.flux() > 0).allMatch(p -> Double.isFinite(p.offset()));
        }
    }

    /** The median, the spread between the 5th and 95th percentiles (the amplitude) and the robust scatter, in magnitudes. */
    public record Summary(int points, double median, double amplitude, double scatter) { }

    private LightCurve() { }

    /**
     * Up to {@value #MAX_COMPARISON_STARS} comparison stars, as pixel positions {x, y}: Gaia stars of G
     * {@value #COMPARISON_BRIGHTEST} to {@value #COMPARISON_FAINTEST} at least 6 FWHM from the edges and from the box
     * around the object's path ({minX, minY, maxX, maxY}), spread over the brightness range.
     */
    public static List<double[]> comparisonStars(List<SkyCatalogue.Star> stars, WcsCoordinateTransformer wcs, int width, int height,
                                                 double[] pathBox, double fwhm) {
        if (stars == null || wcs == null) return List.of();
        double margin = 6 * fwhm;
        var candidates = new ArrayList<double[]>();
        for (var star : stars) {
            if (!(star.g >= COMPARISON_BRIGHTEST && star.g <= COMPARISON_FAINTEST)) continue;
            double[] pixel = wcs.skyToPixel(star.ra, star.dec);
            if (pixel == null || !(pixel[0] >= margin && pixel[1] >= margin && pixel[0] < width - margin && pixel[1] < height - margin)) continue;
            if (pixel[0] > pathBox[0] - margin && pixel[0] < pathBox[2] + margin && pixel[1] > pathBox[1] - margin && pixel[1] < pathBox[3] + margin) continue;
            candidates.add(new double[]{pixel[0], pixel[1], star.g});
        }
        candidates.sort(Comparator.comparingDouble(c -> c[2]));
        if (candidates.size() <= MAX_COMPARISON_STARS) return candidates.stream().map(c -> new double[]{c[0], c[1]}).toList();
        var chosen = new ArrayList<double[]>();
        for (int k = 0; k < MAX_COMPARISON_STARS; k++) {
            var c = candidates.get((int) Math.round(k * (candidates.size() - 1) / (double) (MAX_COMPARISON_STARS - 1)));
            chosen.add(new double[]{c[0], c[1]});
        }
        return chosen;
    }

    /**
     * Whether a point counts in the summary: no flag but {@code TRAILED}, which at a steady speed costs every frame the
     * same small share of light and so leaves a relative curve unchanged.
     */
    public static boolean counted(Point point) {
        return (point.flags() & ~io.github.ppissias.jtransient.photometry.PhotometryFlags.TRAILED) == 0;
    }

    /** The curve's summary over its counted points with a magnitude; NaN values when there are fewer than three. */
    public static Summary summarise(Curve curve) {
        double[] values = curve.points().stream().filter(LightCurve::counted).mapToDouble(Point::magnitude)
                .filter(Double::isFinite).sorted().toArray();
        if (values.length < 3) return new Summary(values.length, Double.NaN, Double.NaN, Double.NaN);
        double median = quantile(values, 0.5);
        double[] deviations = java.util.Arrays.stream(values).map(v -> Math.abs(v - median)).sorted().toArray();
        return new Summary(values.length, median, quantile(values, 0.95) - quantile(values, 0.05), 1.4826 * quantile(deviations, 0.5));
    }

    /** The median of the positive finite values, NaN when there are none. */
    public static double median(double[] values) {
        double[] kept = java.util.Arrays.stream(values).filter(v -> v > 0 && Double.isFinite(v)).sorted().toArray();
        return kept.length == 0 ? Double.NaN : quantile(kept, 0.5);
    }

    private static double quantile(double[] sorted, double q) {
        double position = q * (sorted.length - 1);
        int low = (int) Math.floor(position), high = (int) Math.ceil(position);
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low);
    }

    /** The curve as CSV, one row per frame, with the measurement, its correction and its flags. */
    public static String csv(Curve curve) {
        var out = new StringBuilder("# ").append(curve.id()).append(curve.measuredPositions() ? " · measured positions" : " · fitted positions")
                .append(String.format(Locale.US, " · FWHM %.2f px · aperture radius %.2f px · %d comparison stars · %s · %s%n",
                        curve.fwhm(), curve.apertureRadius(), curve.comparisonStars(), curve.corrected() ? "corrected" : "uncorrected",
                        curve.onReference() ? "frames' median subtracted" : "measured on the frames"));
        out.append("frame,utc_midpoint,x,y,flux_per_s,flux_error_per_s,comparison_offset_mag,relative_mag,mag_error,flags\n");
        for (Point p : curve.points()) {
            out.append(String.format(Locale.US, "%d,%s,%.3f,%.3f,%s,%s,%s,%s,%s,%s%n", p.frameNumber(), Instant.ofEpochMilli(p.midpointMillis()),
                    p.x(), p.y(), number(p.flux(), 5), number(p.fluxError(), 5), number(p.offset(), 4), number(p.magnitude(), 4),
                    number(p.magnitudeError(), 4), String.join(" ", io.github.ppissias.jtransient.photometry.PhotometryFlags.describe(p.flags()))));
        }
        return out.toString();
    }

    private static String number(double value, int decimals) {
        return Double.isFinite(value) ? String.format(Locale.US, "%." + decimals + "f", value) : "";
    }
}
