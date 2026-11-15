/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.skycatalog;

import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import io.github.ppissias.jtransient.photometry.MovingObjectPhotometry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Which catalogue stars can serve as comparison stars for moving-object light curves: measured on an aligned frame
 * with JTransient's moving-object photometry, a star is usable when nothing flags it (saturated, near the edge, odd
 * shape) and its signal-to-noise is at least {@value #MIN_SNR}. A light curve needs {@value #MIN_USABLE} of them;
 * {@link #check} counts them once after the catalogue fetch, so a catalogue that is not deep enough for the telescope
 * (its stars all saturated) is noticed before a light curve is measured. The stars are the fetched list and the faint
 * depth samples, both already saved with the catalogue.
 */
public final class PhotometryStars {
    /** Usable stars a session needs for its light curves. */
    public static final int MIN_USABLE = 10;
    /** Signal-to-noise a comparison star needs on one frame. */
    public static final double MIN_SNR = 20;
    /** Most comparison stars measured per frame. */
    public static final int MAX_COMPARISON_STARS = 30;
    /** Stars sampled for the FWHM. */
    static final int FWHM_SAMPLE = 400;
    /** FWHM assumed when no star's profile can be measured (all saturated or all faint), in pixels. */
    static final double FALLBACK_FWHM = 3.0;
    /** Signal-to-noise that defines the frames' limiting magnitude. */
    public static final double LIMIT_SNR = 5;
    /** Fewest stars for a limiting magnitude. */
    public static final int MIN_LIMIT_STARS = 10;
    /** Magnitudes below the frames' limit that asteroids and comets are fetched to. */
    public static final double DEPTH_MARGIN = 1.0;
    /** Distance from the frame edges, in FWHM, inside which no star is used. */
    static final double EDGE_FWHM = 6;

    /** One star measured on a frame: pixel position, Gaia G, flux, signal-to-noise and photometry flags. */
    public record Measured(double x, double y, double g, double flux, double snr, int flags) {
        /** Whether the star can serve as a comparison star. */
        public boolean usable() { return flags == 0 && snr >= MIN_SNR; }

        /** Whether the measurement is saturated. */
        public boolean saturated() { return (flags & io.github.ppissias.jtransient.photometry.PhotometryFlags.SATURATED) != 0; }
    }

    /**
     * The check after a fetch, saved in the catalogue file: the frame measured, its stars' FWHM in pixels (NaN when
     * none could be measured), how many
     * catalogue stars lie on it, how many are saturated, flagged otherwise, too faint, or usable, the faintest
     * saturated star's G (0 when none), and the catalogue's depth.
     */
    public static final class Check {
        public String frame;
        public double fwhm;
        public int onFrame;
        public int saturated;
        public int flagged;
        public int faint;
        public int usable;
        public double faintestSaturatedG;
        public double depth;
        /**
         * The Gaia G at which a star reaches a signal-to-noise of {@value #LIMIT_SNR} against the sky on the frame: the
         * engine's flux error is the sky's alone, so G + 2.5 log SNR is the same for every unsaturated star, and its
         * median over them gives the limit; NaN with fewer than {@value #MIN_LIMIT_STARS} such stars, or 0 in a file
         * written before it existed.
         */
        public double limitingMagnitude = Double.NaN;

        /**
         * The depth to fetch asteroids and comets to: {@value #DEPTH_MARGIN} magnitudes below the frames' limit (for the
         * colour of asteroids and the faint-mover search, which reaches below single frames), in steps of 0.5, at most
         * {@code maxDepth}; NaN when the limit is unknown.
         */
        public double solarSystemDepth(double maxDepth) {
            if (!(limitingMagnitude > 0)) return Double.NaN;
            return Math.min(maxDepth, Math.ceil((limitingMagnitude + DEPTH_MARGIN) * 2) / 2);
        }

        /** Whether the session has enough comparison stars for light curves. */
        public boolean enough() { return usable >= MIN_USABLE; }

        /** A sentence for the user when there are too few usable stars, or null. */
        public String warning() {
            if (enough()) return null;
            var text = new StringBuilder(String.format(Locale.US,
                    "Only %d of the %d catalogue stars on the frames can be measured as comparison stars for light curves "
                            + "(%d are needed)", usable, onFrame, MIN_USABLE));
            var reasons = new ArrayList<String>();
            if (saturated > 0) reasons.add(String.format(Locale.US, "%d saturated (to G %.1f)", saturated, faintestSaturatedG));
            if (faint > 0) reasons.add(faint + " too faint to measure well");
            if (flagged > 0) reasons.add(flagged + " near an edge or spoiled");
            if (!reasons.isEmpty()) text.append(": ").append(String.join(", ", reasons));
            text.append('.');
            if (saturated > 0 && saturated >= faint) {
                text.append(depth < 18 - 0.05
                        ? String.format(Locale.US, " Raise Star Depth (now G %.1f, up to 18) and fetch the sky catalogue again;", depth)
                        : "");
                text.append(" Asteroids & Comets fetches fainter stars itself when it measures a light curve,"
                        + " but the report's light curves use only the saved catalogue.");
            } else {
                text.append(" Light curves will be uncorrected for changes in transparency.");
            }
            return text.toString();
        }
    }

    private PhotometryStars() { }

    /** The catalogue's stars and the stars of its faint depth samples, each once. */
    public static List<SkyCatalogue.Star> candidates(SkyCatalogue catalogue) {
        if (catalogue == null) return List.of();
        var all = new ArrayList<SkyCatalogue.Star>(catalogue.stars);
        var seen = new HashSet<String>();
        for (var star : catalogue.stars) seen.add(key(star));
        if (catalogue.depthSamples != null) {
            for (var sample : catalogue.depthSamples) {
                for (var star : sample.stars) if (seen.add(key(star))) all.add(star);
            }
        }
        return all;
    }

    /** A star's identity: its Gaia source, else its position to about 0.4″. */
    private static String key(SkyCatalogue.Star star) {
        if (star.sourceId != null && !star.sourceId.isBlank()) return star.sourceId;
        return Math.round(star.ra * 1e4) + ":" + Math.round(star.dec * 1e4);
    }

    /** The stars' pixel positions {x, y, G} on a frame of this size, at least {@code margin} pixels inside its edges. */
    public static List<double[]> onFrame(List<SkyCatalogue.Star> stars, WcsCoordinateTransformer wcs, int width, int height, double margin) {
        var positions = new ArrayList<double[]>();
        for (var star : stars) {
            double[] p = wcs.skyToPixel(star.ra, star.dec);
            if (p == null || !(p[0] >= margin && p[1] >= margin && p[0] < width - margin && p[1] < height - margin)) continue;
            positions.add(new double[]{p[0], p[1], star.g});
        }
        return positions;
    }

    /** The stars' FWHM on the frame from their profiles (JTransient), NaN when too few can be measured. */
    public static double fwhm(short[][] frame, List<double[]> positions, double saturation) {
        var sorted = new ArrayList<>(positions);
        sorted.sort(Comparator.comparingDouble(p -> p[2]));
        // A few hundred spread over the brightness range: the brightest alone may all be saturated, the faintest too faint.
        int count = Math.min(FWHM_SAMPLE, sorted.size());
        double[][] sample = new double[count][];
        for (int k = 0; k < count; k++) {
            double[] p = sorted.get(count == sorted.size() ? k : (int) ((long) k * sorted.size() / count));
            sample[k] = new double[]{p[0], p[1]};
        }
        return MovingObjectPhotometry.starFwhm(frame, sample, saturation);
    }

    /** Measures the stars at {@code positions} ({x, y, G}) on one frame. */
    public static List<Measured> measure(short[][] frame, List<double[]> positions, double fwhm, double saturation) {
        var measured = new ArrayList<Measured>(positions.size());
        for (double[] p : positions) {
            var m = MovingObjectPhotometry.measure(frame, p[0], p[1], fwhm, 0, 0, saturation);
            double snr = m.fluxError() > 0 ? m.flux() / m.fluxError() : Double.NaN;
            measured.add(new Measured(p[0], p[1], p[2], m.flux(), snr, m.flags()));
        }
        return measured;
    }

    /**
     * Checks the catalogue against one aligned frame (best one from the middle of the session): which of its stars and
     * depth-sample stars are usable comparison stars. When no star's profile can be measured, a FWHM of
     * {@value #FALLBACK_FWHM} pixels is assumed, which still finds saturated cores and faint stars.
     */
    public static Check check(short[][] frame, String frameName, WcsCoordinateTransformer wcs, SkyCatalogue catalogue) {
        int height = frame.length, width = frame[0].length;
        double saturation = MovingObjectPhotometry.saturationThreshold(frame);
        var all = onFrame(candidates(catalogue), wcs, width, height, 0);
        double measuredFwhm = fwhm(frame, all, saturation);
        double fwhm = measuredFwhm > 0 ? measuredFwhm : FALLBACK_FWHM;
        double margin = Math.max(EDGE_FWHM, MovingObjectPhotometry.ANNULUS_OUTER_FWHM + 1) * fwhm;
        var check = new Check();
        check.frame = frameName;
        check.fwhm = measuredFwhm;
        check.depth = catalogue.starMagnitudeLimit;
        var limits = new ArrayList<Double>();
        for (Measured m : measure(frame, onFrame(candidates(catalogue), wcs, width, height, margin), fwhm, saturation)) {
            check.onFrame++;
            if (m.flags() == 0 && m.snr() >= LIMIT_SNR) limits.add(m.g() + 2.5 * Math.log10(m.snr()));
            if (m.saturated()) {
                check.saturated++;
                check.faintestSaturatedG = Math.max(check.faintestSaturatedG, m.g());
            } else if (m.flags() != 0) {
                check.flagged++;
            } else if (!(m.snr() >= MIN_SNR)) {
                check.faint++;
            } else {
                check.usable++;
            }
        }
        if (limits.size() >= MIN_LIMIT_STARS) {
            limits.sort(Double::compare);
            check.limitingMagnitude = limits.get(limits.size() / 2) - 2.5 * Math.log10(LIMIT_SNR);
        }
        return check;
    }

    /**
     * Up to {@value #MAX_COMPARISON_STARS} usable stars as {x, y}, spread over their measured brightness, outside the
     * box around the object's path ({minX, minY, maxX, maxY}) widened by {@code clearance} pixels.
     */
    public static List<double[]> choose(List<Measured> measured, double[] pathBox, double clearance) {
        var usable = new ArrayList<Measured>();
        for (Measured m : measured) {
            if (!m.usable()) continue;
            if (pathBox != null && m.x() > pathBox[0] - clearance && m.x() < pathBox[2] + clearance
                    && m.y() > pathBox[1] - clearance && m.y() < pathBox[3] + clearance) continue;
            usable.add(m);
        }
        usable.sort(Comparator.comparingDouble(Measured::flux));
        var chosen = new ArrayList<double[]>();
        if (usable.size() <= MAX_COMPARISON_STARS) {
            for (Measured m : usable) chosen.add(new double[]{m.x(), m.y()});
            return chosen;
        }
        for (int k = 0; k < MAX_COMPARISON_STARS; k++) {
            Measured m = usable.get((int) Math.round(k * (usable.size() - 1) / (double) (MAX_COMPARISON_STARS - 1)));
            chosen.add(new double[]{m.x(), m.y()});
        }
        return chosen;
    }
}
