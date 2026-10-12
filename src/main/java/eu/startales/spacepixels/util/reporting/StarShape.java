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

import java.util.Arrays;

/**
 * The size, shape, brightness and lopsidedness of one star, measured on a small cut-out of a frame.
 * <p>
 * The shape comes from adaptive second moments: a round Gaussian weight matched to the star, with the weight's effect
 * taken out again (exact for a Gaussian star), so the size and the elongation do not depend on the noise far from the
 * star. The lopsidedness (coma) is the offset of the plain light-weighted centre, which follows the faint tail, from
 * the weighted centre, which follows the core.
 */
final class StarShape {

    private static final double FWHM_PER_SIGMA = 2.0 * Math.sqrt(2.0 * Math.log(2.0));

    /** Weighted (core) centre, frame pixels. */
    double x;
    double y;
    /** Full width at half maximum, pixels (geometric mean of the two axes). */
    double fwhm;
    /** Ellipticity components: (Cxx - Cyy) / (Cxx + Cyy) and 2 Cxy / (Cxx + Cyy). */
    double e1;
    double e2;
    /** Background-subtracted flux inside the aperture. */
    double flux;
    /** Local sky level per pixel (pixel units 0..65535) and its noise. */
    double background;
    double noise;
    /** Signal-to-noise of the aperture flux. */
    double snr;
    /** The brightest pixel (pixel units 0..65535). */
    double peak;
    /** Pixels within 0.5 % of the brightest one: three or more is the flat top of a saturated star. */
    int flatTopPixels;
    /** Light-weighted centre minus the core centre, pixels: points along a coma tail. */
    double offsetX;
    double offsetY;

    /** Major / minor axis length. */
    double axisRatio() {
        double e = Math.hypot(e1, e2);
        return Math.sqrt((1 + e) / Math.max(1e-9, 1 - e));
    }

    /**
     * Measures the star near (x0, y0) on a square cut-out of the given radius; null when the cut-out leaves the frame,
     * touches empty (padded) pixels, or the star cannot be measured.
     *
     * @param fwhmHint the session's typical star size, pixels, for the first weight
     * @param apertureFactor radius of the flux aperture and of the light-weighted centre, in units of the star's own
     *                       size, so stars of any size lose the same share of their light
     */
    static StarShape measure(short[][] data, double x0, double y0, int radius, double fwhmHint, double apertureFactor) {
        int height = data.length;
        int width = data[0].length;
        int cx0 = (int) Math.round(x0);
        int cy0 = (int) Math.round(y0);
        if (cx0 - radius < 0 || cy0 - radius < 0 || cx0 + radius >= width || cy0 + radius >= height) {
            return null;
        }
        int side = 2 * radius + 1;
        double[] values = new double[side * side];
        double[] ring = new double[side * side];
        int ringCount = 0;
        double peak = 0;
        for (int j = 0; j < side; j++) {
            short[] row = data[cy0 - radius + j];
            for (int i = 0; i < side; i++) {
                int value = row[cx0 - radius + i] + 32768;
                if (value <= 0) {
                    return null; // padding left by the alignment
                }
                values[j * side + i] = value;
                peak = Math.max(peak, value);
                double r = Math.hypot(i - radius, j - radius);
                if (r >= radius - 2) {
                    ring[ringCount++] = value;
                }
            }
        }
        if (ringCount < 20) {
            return null;
        }
        double[] sky = Arrays.copyOf(ring, ringCount);
        Arrays.sort(sky);
        double background = median(sky);
        double[] deviations = new double[ringCount];
        for (int k = 0; k < ringCount; k++) {
            deviations[k] = Math.abs(sky[k] - background);
        }
        Arrays.sort(deviations);
        double noise = Math.max(1e-3, 1.4826 * median(deviations));

        StarShape star = new StarShape();
        star.background = background;
        star.noise = noise;
        star.peak = peak;
        double flatLimit = peak - Math.max(1, 0.005 * peak);
        for (double value : values) {
            if (value >= flatLimit) {
                star.flatTopPixels++;
            }
        }
        for (int k = 0; k < values.length; k++) {
            values[k] -= background;
        }

        // Adaptive moments: weight matched to the star, its effect removed (exact for a Gaussian). One pass per
        // iteration gives the weighted centroid and the moments about it.
        double cx = x0 - (cx0 - radius);
        double cy = y0 - (cy0 - radius);
        double sigmaW = Math.max(0.7, fwhmHint / FWHM_PER_SIGMA);
        double maxSigmaW = radius / 2.5;
        double cxx = 0;
        double cyy = 0;
        double cxy = 0;
        boolean converged = false;
        for (int iteration = 0; iteration < 25; iteration++) {
            double twoSigma2 = 2 * sigmaW * sigmaW;
            double reach = Math.min(radius, 4 * sigmaW);
            int i0 = Math.max(0, (int) Math.floor(cx - reach));
            int i1 = Math.min(side - 1, (int) Math.ceil(cx + reach));
            int j0 = Math.max(0, (int) Math.floor(cy - reach));
            int j1 = Math.min(side - 1, (int) Math.ceil(cy + reach));
            double s0 = 0;
            double sx = 0;
            double sy = 0;
            double sxx = 0;
            double syy = 0;
            double sxy = 0;
            for (int j = j0; j <= j1; j++) {
                double dy = j - cy;
                double wy = dy * dy;
                int row = j * side;
                for (int i = i0; i <= i1; i++) {
                    double dx = i - cx;
                    double w = Math.exp(-(dx * dx + wy) / twoSigma2) * values[row + i];
                    s0 += w;
                    sx += w * dx;
                    sy += w * dy;
                    sxx += w * dx * dx;
                    syy += w * wy;
                    sxy += w * dx * dy;
                }
            }
            if (s0 <= 0) {
                return null;
            }
            double shiftX = sx / s0;
            double shiftY = sy / s0;
            double mxx = sxx / s0 - shiftX * shiftX;
            double myy = syy / s0 - shiftY * shiftY;
            double mxy = sxy / s0 - shiftX * shiftY;
            double detM = mxx * myy - mxy * mxy;
            if (!(detM > 0) || mxx <= 0 || myy <= 0) {
                return null;
            }
            // C = (M^-1 - W^-1)^-1
            double inv = 1 / (sigmaW * sigmaW);
            double axx = myy / detM - inv;
            double ayy = mxx / detM - inv;
            double axy = -mxy / detM;
            double detA = axx * ayy - axy * axy;
            if (!(detA > 0) || axx <= 0 || ayy <= 0) {
                return null;
            }
            cxx = ayy / detA;
            cyy = axx / detA;
            cxy = -axy / detA;
            double nextSigma = Math.min(maxSigmaW, Math.max(0.5, Math.sqrt((cxx + cyy) / 2)));
            cx += shiftX;
            cy += shiftY;
            if (cx < 1 || cy < 1 || cx > side - 2 || cy > side - 2) {
                return null;
            }
            if (Math.abs(nextSigma - sigmaW) < 2e-4 * sigmaW && Math.hypot(shiftX, shiftY) < 2e-4) {
                converged = true;
                break;
            }
            sigmaW = nextSigma;
        }
        if (!converged) {
            return null;
        }
        // The pixels themselves add 1/12 px^2 to each axis.
        double pxx = Math.max(1e-3, cxx - 1.0 / 12);
        double pyy = Math.max(1e-3, cyy - 1.0 / 12);
        double det = pxx * pyy - cxy * cxy;
        if (!(det > 0)) {
            return null;
        }
        star.fwhm = FWHM_PER_SIGMA * Math.pow(det, 0.25);
        star.e1 = (pxx - pyy) / (pxx + pyy);
        star.e2 = 2 * cxy / (pxx + pyy);
        star.x = cx + cx0 - radius;
        star.y = cy + cy0 - radius;

        // Aperture flux and the plain light-weighted centre.
        double flux = 0;
        double fx = 0;
        double fy = 0;
        int pixels = 0;
        double apertureRadius = Math.max(2.5, Math.min(radius - 2, apertureFactor * star.fwhm));
        double r2 = apertureRadius * apertureRadius;
        for (int j = 0; j < side; j++) {
            for (int i = 0; i < side; i++) {
                double dx = i - cx;
                double dy = j - cy;
                if (dx * dx + dy * dy <= r2) {
                    double v = values[j * side + i];
                    flux += v;
                    fx += v * dx;
                    fy += v * dy;
                    pixels++;
                }
            }
        }
        star.flux = flux;
        star.snr = flux / (noise * Math.sqrt(Math.max(1, pixels)));
        if (flux > 0) {
            star.offsetX = fx / flux;
            star.offsetY = fy / flux;
        }
        return star;
    }

    private static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }
}
