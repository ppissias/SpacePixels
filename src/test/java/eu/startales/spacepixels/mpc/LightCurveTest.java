package eu.startales.spacepixels.mpc;

import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import io.github.ppissias.jtransient.photometry.PhotometryFlags;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

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
        var curve = new LightCurve.Curve("T1", true, 3.0, 4.5, 12, true, points);
        assertEquals(-5 + 0.1 - 0.05, points.get(0).magnitude(), 1e-9);
        assertTrue(Double.isNaN(points.get(10).magnitude()));
        var summary = LightCurve.summarise(curve);
        assertEquals("the bad point is left out, the trailed one counts", 11, summary.points());
        assertEquals(0.2, summary.amplitude(), 1e-6);
        assertTrue(curve.corrected());
        String csv = LightCurve.csv(curve);
        assertTrue(csv, csv.startsWith("# T1 · measured positions · FWHM 3.00 px · aperture radius 4.50 px · 12 comparison stars · corrected · frames' median subtracted"));
        assertTrue(csv, csv.contains("\n11,") && csv.contains("BAD_FLUX"));
        var uncorrected = new LightCurve.Curve("FM1", false, 3, 4.5, 0, false,
                List.of(new LightCurve.Point(1, 0, 0, 0, 100, 1, Double.NaN, 0)));
        assertFalse(uncorrected.corrected());
        assertEquals("the median of the usable values", 0.7, LightCurve.median(new double[]{0.6, Double.NaN, 0.7, -1, 0.9}), 1e-12);
        assertTrue(Double.isNaN(LightCurve.median(new double[]{Double.NaN})));
    }

    /** Comparison stars: in the G range, off the edges and away from the path, at most thirty, spread in brightness. */
    @Test
    public void choosesComparisonStars() {
        var header = new LinkedHashMap<String, String>();
        header.put("CTYPE1", "RA---TAN"); header.put("CTYPE2", "DEC--TAN");
        header.put("CRPIX1", "500.5"); header.put("CRPIX2", "500.5");
        header.put("CRVAL1", "150"); header.put("CRVAL2", "20");
        header.put("CD1_1", Double.toString(-1.0 / 3600)); header.put("CD1_2", "0");
        header.put("CD2_1", "0"); header.put("CD2_2", Double.toString(1.0 / 3600));
        var wcs = WcsCoordinateTransformer.fromHeader(header);
        var stars = new ArrayList<SkyCatalogue.Star>();
        for (int k = 0; k < 60; k++) {
            var star = new SkyCatalogue.Star();
            var sky = wcs.pixelToSky(100 + (k % 10) * 80, 100 + (k / 10) * 130);
            star.ra = sky.getRaDegrees(); star.dec = sky.getDecDegrees();
            star.g = 11 + k * 0.1;
            stars.add(star);
        }
        var chosen = LightCurve.comparisonStars(stars, wcs, 1000, 1000, new double[]{380, 380, 420, 420}, 3);
        assertTrue(chosen.size() <= LightCurve.MAX_COMPARISON_STARS && chosen.size() >= 3);
        for (double[] p : chosen) {
            assertFalse("away from the path", p[0] > 362 && p[0] < 438 && p[1] > 362 && p[1] < 438);
        }
        assertEquals(List.of(), LightCurve.comparisonStars(null, wcs, 1000, 1000, new double[]{0, 0, 0, 0}, 3));
    }
}
