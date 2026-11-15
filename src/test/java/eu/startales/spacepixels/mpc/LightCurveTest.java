package eu.startales.spacepixels.mpc;

import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.photometry.PhotometryFlags;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Light-curve points, their summary and CSV, and the choice of comparison stars. */
public class LightCurveTest {
    /** Magnitudes are corrected by the frame offset; flagged points stay in the CSV but not in the summary. */
    @Test
    public void pointsSummaryAndCsv() {
        var points = new ArrayList<LightCurve.Point>();
        for (int k = 0; k < 10; k++) points.add(new LightCurve.Point(k + 1, 1_000_000L + k * 60_000, 10, 20,
                100 * Math.pow(10, -0.4 * (k % 2 == 0 ? 0.1 : -0.1)), 1, 0.05, 0));
        points.add(new LightCurve.Point(11, 1_660_000L, 10, 20, -3, 1, 0.05, PhotometryFlags.BAD_FLUX));
        points.add(new LightCurve.Point(12, 1_720_000L, 10, 20, 100 * Math.pow(10, -0.04), 1, 0.05, PhotometryFlags.TRAILED));
        var curve = new LightCurve.Curve("T1", true, 3.0, 4.5, 12, true, null, points);
        assertEquals(-5 + 0.1 - 0.05, points.get(0).magnitude(), 1e-9);
        assertTrue(Double.isNaN(points.get(10).magnitude()));
        var summary = LightCurve.summarise(curve);
        assertEquals("the bad point is left out, the trailed one counts", 11, summary.points());
        assertEquals(0.2, summary.amplitude(), 1e-6);
        assertTrue(curve.corrected());
        String csv = LightCurve.csv(curve, 1);
        assertTrue(csv, csv.startsWith("# T1 · measured positions · FWHM 3.00 px · aperture radius 4.50 px · 12 comparison stars · corrected · frames' median subtracted"));
        assertTrue(csv, csv.contains("\n11,") && csv.contains("BAD_FLUX"));
        var uncorrected = new LightCurve.Curve("FM1", false, 3, 4.5, 0, false, null,
                List.of(new LightCurve.Point(1, 0, 0, 0, 100, 1, Double.NaN, 0)));
        assertFalse(uncorrected.corrected());
        assertEquals("the median of the usable values", 0.7, LightCurve.median(new double[]{0.6, Double.NaN, 0.7, -1, 0.9}), 1e-12);
        assertTrue(Double.isNaN(LightCurve.median(new double[]{Double.NaN})));
    }

    /** A synthetic sky of 400 x 400 pixels at 1″ per pixel, around RA 150, Dec 20. */
    static WcsCoordinateTransformer wcs() {
        var header = new LinkedHashMap<String, String>();
        header.put("CTYPE1", "RA---TAN"); header.put("CTYPE2", "DEC--TAN");
        header.put("CRPIX1", "200.5"); header.put("CRPIX2", "200.5");
        header.put("CRVAL1", "150"); header.put("CRVAL2", "20");
        header.put("CD1_1", Double.toString(-1.0 / 3600)); header.put("CD1_2", "0");
        header.put("CD2_1", "0"); header.put("CD2_2", Double.toString(1.0 / 3600));
        return WcsCoordinateTransformer.fromHeader(header);
    }

    /**
     * A frame of sky 1000 and noise 10 with Gaussian stars {x, y, peak} of FWHM 3; values clip at 50000, as a sensor
     * saturates, and {@code transparency} scales every star.
     */
    static short[][] frame(double[][] stars, double transparency, long seed) {
        Random random = new Random(seed);
        double sigma = 3 / 2.3548;
        short[][] pixels = new short[400][400];
        for (int j = 0; j < 400; j++) for (int i = 0; i < 400; i++) {
            double value = 1000 + 10 * random.nextGaussian();
            for (double[] s : stars) {
                double dx = i - s[0], dy = j - s[1];
                if (dx * dx + dy * dy < 100) value += transparency * s[2] * Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma));
            }
            pixels[j][i] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(Math.min(50_000, value)));
        }
        return pixels;
    }

    /** Catalogue stars at these pixel positions. */
    static List<SkyCatalogue.Star> catalogue(WcsCoordinateTransformer wcs, double[][] stars) {
        var list = new ArrayList<SkyCatalogue.Star>();
        for (double[] p : stars) {
            var star = new SkyCatalogue.Star();
            var sky = wcs.pixelToSky(p[0], p[1]);
            star.ra = sky.getRaDegrees(); star.dec = sky.getDecDegrees();
            star.g = 15 - 2.5 * Math.log10(Math.max(1, p[2]) / 1000);
            list.add(star);
        }
        return list;
    }

    /** Comparison stars are chosen by measurement: saturated, faint and path-side stars are left out. */
    @Test
    public void choosesComparisonStarsByMeasurement() {
        var wcs = wcs();
        double[][] stars = {{60, 60, 2000}, {340, 60, 3000}, {60, 340, 4000}, {340, 340, 5000}, {200, 60, 2500},
                {60, 200, 200_000}, {340, 200, 300_000}, {200, 340, 8}, {200, 200, 3000}};
        short[][] pixels = frame(stars, 1, 1);
        var chosen = LightCurve.comparisonStars(pixels, wcs, catalogue(wcs, stars), new double[]{190, 190, 210, 210}, 3);
        assertEquals("five clear stars; two saturated, one faint and the one beside the path are left out", 5, chosen.size());
        for (double[] c : chosen) {
            assertFalse("not the saturated ones", Math.abs(c[1] - 200) < 2 && (Math.abs(c[0] - 60) < 2 || Math.abs(c[0] - 340) < 2));
            assertFalse("not beside the path", Math.abs(c[0] - 200) < 2 && Math.abs(c[1] - 200) < 2);
        }
        assertEquals(List.of(), LightCurve.comparisonStars(pixels, null, catalogue(wcs, stars), new double[]{0, 0, 0, 0}, 3));
    }

    /** Bins average corrected fluxes of counted points; flagged points are skipped and a short remainder forms no bin. */
    @Test
    public void binsAverageFluxes() {
        var points = new ArrayList<LightCurve.Point>();
        double[] flux = {100, 120, -5, 60, 80, 100, 90};
        for (int k = 0; k < flux.length; k++) points.add(new LightCurve.Point(k + 1, k * 60_000L, 0, 0, flux[k], 2, 0, 0));
        points.add(2, new LightCurve.Point(99, 90_000L, 0, 0, 500, 2, 0, PhotometryFlags.CONTAMINATED));
        var curve = new LightCurve.Curve("FM1", false, 3, 4.5, 12, true, null, points);
        var bins = LightCurve.bin(curve, 2);
        assertEquals("seven counted points: three pairs, the last point left over", 3, bins.size());
        assertEquals(110, bins.get(0).flux(), 1e-9);
        assertEquals("a negative flux keeps its weight", 27.5, bins.get(1).flux(), 1e-9);
        assertEquals(2 * Math.sqrt(2) / 2, bins.get(0).fluxError(), 1e-9);
        assertEquals(30_000L, bins.get(0).midpointMillis());
        assertEquals(1, LightCurve.bin(curve, 4).size());
        var few = LightCurve.summarise(bins);
        assertEquals("three bins: a median", 3, few.points());
        assertTrue("but no amplitude or scatter from three values", Double.isNaN(few.amplitude()) && Double.isNaN(few.scatter()));
        assertEquals(7, LightCurve.bin(curve, 1).size());
        String csv = LightCurve.csv(curve, 2);
        assertTrue(csv, csv.contains("bins of 2 frames") && csv.contains(",bin,bin_relative_mag,bin_mag_error"));
        assertTrue("the flagged frame has no bin", csv.contains("CONTAMINATED,,,"));
    }

    /**
     * A constant object moving across 20 frames: with one frame 30 % less transparent, the comparison stars correct it
     * and the curve stays flat; without them the frame dips by 0.39 mag.
     */
    @Test
    public void measuresACorrectedCurve() throws Exception {
        var wcs = wcs();
        double[][] stars = {{60, 60, 2000}, {340, 60, 3000}, {60, 340, 4000}, {340, 340, 5000}, {200, 40, 2500}, {40, 200, 3500}};
        var samples = new ArrayList<LightCurve.Sample>();
        for (int k = 0; k < 20; k++) samples.add(new LightCurve.Sample(k + 1, k * 120_000L, 120_000, 150 + 5 * k, 200));
        LightCurve.Frames frames = number -> {
            double[][] all = java.util.Arrays.copyOf(stars, stars.length + 1);
            all[stars.length] = new double[]{150 + 5 * (number - 1), 200, 800};
            return frame(all, number == 7 ? 0.7 : 1, number);
        };
        var comparisons = LightCurve.comparisonStars(frames.pixels(10), wcs, catalogue(wcs, stars), LightCurve.pathBox(samples), 3);
        var curve = LightCurve.measure("T1", true, samples, frames, comparisons, 3, 2.5, 512L << 20, null, () -> false);
        assertTrue(curve.corrected());
        assertTrue(curve.onReference());
        double reference = LightCurve.summarise(curve).median();
        assertEquals("the dimmed frame is corrected", reference, curve.points().get(6).magnitude(), 0.05);
        var uncorrected = LightCurve.measure("T1", true, samples, frames, List.of(), 3, 2.5, 512L << 20, null, () -> false);
        assertEquals("uncorrected, it dips", 0.39, uncorrected.points().get(6).magnitude() - LightCurve.summarise(uncorrected).median(), 0.06);
        assertEquals("cancelled", null, LightCurve.measure("T1", true, samples, frames, comparisons, 3, 2.5, 512L << 20, null, () -> true));
    }
}
