package eu.startales.spacepixels.fits;

import nom.tam.fits.Header;
import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Verifies scientific scaling invariants, explicit decisions and zero margins independently of any GUI. */
public class ImageScalingTest {
    /** Diagnostics report measured endpoints; the transform independently includes zero as its baseline. */
    @Test public void measuredRangeDoesNotInventAZeroSample() throws Exception {
        for (float[] values : new float[][]{{0.25f, 2}, {-3, -2}}) {
            ImageScaling.Scan scan = new ImageScaling.Scan();
            scan.add("one-sided.fit", FitsPixelConverter.pixels(new float[][]{values}, null));
            var summary = scan.summary();
            assertEquals(values[0], summary.minimum(), 0);
            assertEquals(values[1], summary.maximum(), 0);
            var plan = ImageScaling.plan(summary, true, ImageScaling.policy(ImageScaling.OutlierHandling.PRESERVE_ALL));
            double span = Math.max(1, Math.max(0, values[1]) - Math.min(0, values[0]));
            assertEquals(1 / span, plan.multiplier(), 0);
            assertEquals(Math.max(0, -values[0]) * ImageScaling.FULL_SCALE, plan.addedOffset(), 0);
        }
        assertEquals(0, new ImageScaling.Scan().summary().minimum(), 0);
        assertEquals(0, new ImageScaling.Scan().summary().maximum(), 0);
    }
    /** A cohort receives one transform; negative noise and registration margins survive within quantization error. */
    @Test public void preservesNormalNoiseAndRelativeFluxWithOnePlan() throws Exception {
        float[][] first = {{0, -0.02f, 0.25f, 1.08f, 0}};
        float[][] second = {{0, -0.01f, 0.50f, 1.0f, 0}};
        var a = FitsPixelConverter.pixels(first, null); var b = FitsPixelConverter.pixels(second, null);
        ImageScaling.Scan scan = new ImageScaling.Scan(); scan.add("a", a); scan.add("b", b);
        ImageScaling.Plan plan = ImageScaling.plan(scan.summary(), true, null);
        for (var pixels : new FitsPixelConverter.Pixels[]{a, b}) {
            short[][] output = FitsPixelConverter.to16BitMonochrome(pixels, plan, () -> false);
            assertEquals(Short.MIN_VALUE, output[0][0]); assertEquals(Short.MIN_VALUE, output[0][4]);
            for (int x = 1; x < 4; x++) {
                double restored = (output[0][x] + 32768.0) / plan.physicalMultiplier(true) - plan.physicalOffset(true);
                assertEquals(pixels.physicalValue(0, 0, x), restored, 0.51 / plan.physicalMultiplier(true));
            }
        }
        Header header = new Header(); plan.record(header, true);
        assertEquals(plan.addedOffset() * plan.multiplier(), header.getDoubleValue("SPOFFSET"), 1e-8);
        assertFalse(header.getBooleanValue("SPPCLIP"));
    }

    /** Default refusal is typed; one choice covers all frames, and clipping cannot be mistaken for preservation. */
    @Test public void reportsExtremesAndAsksOnlyOnce() throws Exception {
        var image = FitsPixelConverter.pixels(new float[][]{{0, -10.5f, -0.02f, 0.25f, 10.5f, 0}}, null);
        ImageScaling.Scan scan = new ImageScaling.Scan(); scan.add("first.fit", image); scan.add("second.fit", image);
        ImageScaling.Summary summary = scan.summary();
        var failure = assertThrows(ImageScaling.OutlierException.class, () -> ImageScaling.plan(summary, true, null));
        assertEquals(2, failure.summary().below()); assertEquals(2, failure.summary().above());
        assertEquals(2, failure.summary().frames().size());
        AtomicInteger decisions = new AtomicInteger();
        var clipped = ImageScaling.plan(summary, true, value -> { decisions.incrementAndGet(); return ImageScaling.OutlierHandling.CLIP_EXTREMES; });
        assertEquals(1, decisions.get()); assertTrue(clipped.clipExtremes());
        var preserved = ImageScaling.plan(summary, true, ImageScaling.policy(ImageScaling.OutlierHandling.PRESERVE_ALL));
        assertFalse(preserved.clipExtremes());
        assertTrue(clipped.physicalMultiplier(true) > preserved.physicalMultiplier(true) * 10);
        short[][] result = FitsPixelConverter.to16BitMonochrome(image, clipped, () -> false);
        assertEquals(Short.MIN_VALUE, result[0][0]); assertEquals(Short.MIN_VALUE, result[0][5]);
        assertEquals(32767, result[0][4]);
        assertTrue(result[0][2] > result[0][1]); // Normal negative noise was retained, not zero-clipped.
    }

    /** Crossing ten has no special meaning once a caller explicitly accepts full-range preservation. */
    @Test public void hasNoMultiplierCollapseAtTen() throws Exception {
        double previous = 0;
        for (float maximum : new float[]{9.99f, 10.05f}) {
            ImageScaling.Scan scan = new ImageScaling.Scan();
            scan.add("outlier.fit", FitsPixelConverter.pixels(new float[][]{{0.03f, 0.5f, maximum}}, null));
            var plan = ImageScaling.plan(scan.summary(), true, ImageScaling.policy(ImageScaling.OutlierHandling.PRESERVE_ALL));
            assertTrue(plan.physicalMultiplier(true) > 6_000);
            if (previous > 0) assertEquals(previous, plan.physicalMultiplier(true), 50);
            previous = plan.physicalMultiplier(true);
        }
    }

    /** Nonfinite samples are invalid measurements, independently of a finite-outlier policy. */
    @Test public void refusesNonfiniteSamples() throws Exception {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
            assertThrows(IOException.class, () -> new ImageScaling.Scan().add("bad.fit",
                    FitsPixelConverter.pixels(new float[][]{{invalid}}, null)));
    }

    /** XISF bounds normalization keeps raw border fill, while a real interior zero receives the declared mapping. */
    @Test public void normalizesDeclaredBoundsWithoutMovingMargins() throws Exception {
        double[][][] data = {{{0, 0, -0.5, 0, 0.5, 0}}};
        FitsPixelConverter.normalizeSamples(data, value -> (value + 1) / 2);
        assertArrayEquals(new double[]{0, 0, 0.25, 0.5, 0.75, 0}, data[0][0], 0);
    }
}
