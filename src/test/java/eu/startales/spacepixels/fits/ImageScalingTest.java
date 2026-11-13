package eu.startales.spacepixels.fits;

import nom.tam.fits.Header;
import org.junit.Test;
import java.io.IOException;
import java.util.List;
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

    /**
     * Floating-point frames in ADU, as the Zwicky Transient Facility writes them (sky of a few hundred, stars to
     * 66,000), convert exactly like the same values stored as integers, with no question and no clipping.
     */
    @Test public void floatingFramesInAduConvertLikeTheSameIntegers() throws Exception {
        int[][][] counts = {{{0, 240, 263, 300, 4_000, 66_175, 0}}, {{0, 251, 270, 310, 3_000, 59_800, 0}}};
        ImageScaling.Scan floatScan = new ImageScaling.Scan(), integerScan = new ImageScaling.Scan();
        FitsPixelConverter.Pixels[] floats = new FitsPixelConverter.Pixels[2], integers = new FitsPixelConverter.Pixels[2];
        for (int f = 0; f < 2; f++) {
            float[][] stored = new float[1][counts[f][0].length];
            for (int x = 0; x < stored[0].length; x++) stored[0][x] = counts[f][0][x];
            floats[f] = FitsPixelConverter.pixels(stored, null);
            integers[f] = FitsPixelConverter.pixels(counts[f], null);
            floatScan.add("float" + f, floats[f]);
            integerScan.add("integer" + f, integers[f]);
        }
        ImageScaling.Summary summary = floatScan.summary();
        assertTrue(summary.floatsInAdu());
        assertFalse(summary.hasExtremes());
        assertEquals(66_175 / ImageScaling.FULL_SCALE, summary.maximum(), 1e-12);
        ImageScaling.Plan floatPlan = ImageScaling.plan(summary, true, null);
        ImageScaling.Plan integerPlan = ImageScaling.plan(integerScan.summary(), true, null);
        assertFalse(integerScan.summary().floatsInAdu());
        assertEquals(integerPlan.physicalMultiplier(false), floatPlan.physicalMultiplier(true), 0);
        assertEquals(integerPlan.physicalOffset(false), floatPlan.physicalOffset(true), 0);
        for (int f = 0; f < 2; f++) {
            assertArrayEquals(FitsPixelConverter.to16BitMonochrome(integers[f], integerPlan, () -> false),
                    FitsPixelConverter.to16BitMonochrome(floats[f], floatPlan, () -> false));
        }
        Header header = new Header(); floatPlan.record(header, true);
        assertTrue(header.getBooleanValue("SPPFADU"));
        Header integerHeader = new Header(); integerPlan.record(integerHeader, false);
        assertFalse("only floating-point sources record the reading", integerHeader.containsKey("SPPFADU"));
    }

    /** Registration fill (exact zeros) does not count towards the level that identifies ADU frames. */
    @Test public void zeroFillDoesNotHideAduFrames() throws Exception {
        ImageScaling.Scan scan = new ImageScaling.Scan();
        scan.add("registered.fit", FitsPixelConverter.pixels(new float[][]{{0, 0, 0, 0, 0, 0, 300, 310, 320}}, null));
        assertTrue(scan.summary().floatsInAdu());
    }

    /** One floating-point frame on the [0,1] scale keeps the whole sequence there, so the outliers are reported. */
    @Test public void aduReadingNeedsEveryFloatingFrame() throws Exception {
        ImageScaling.Scan scan = new ImageScaling.Scan();
        scan.add("adu.fit", FitsPixelConverter.pixels(new float[][]{{0, 240, 300, 4_000}}, null));
        scan.add("unit.fit", FitsPixelConverter.pixels(new float[][]{{0, 0.01f, 0.02f, 0.9f}}, null));
        ImageScaling.Summary summary = scan.summary();
        assertFalse(summary.floatsInAdu());
        assertEquals(3, summary.above());
        assertEquals(List.of(3L, 0L), summary.frames().stream().map(ImageScaling.FrameExtremes::above).toList());
    }

    /**
     * The first option is the dialog's default. It is clipping when only a few pixels are outside the range, and
     * preserving when most are, because clipping would then flatten the images.
     */
    @Test public void defaultChoiceNeverFlattensTheImages() throws Exception {
        ImageScaling.Scan few = new ImageScaling.Scan();
        few.add("few.fit", FitsPixelConverter.pixels(new float[][]{{0, -10.5f, -0.02f, 0.25f, 10.5f, 0}}, null));
        Answer first = new Answer(0);
        assertEquals(ImageScaling.OutlierHandling.CLIP_EXTREMES, ImageScaling.interactive(first).choose(few.summary()));
        assertEquals("Clip extremes", first.options[0]);
        assertFalse(first.message.contains("Most pixels"));

        ImageScaling.Scan most = new ImageScaling.Scan();
        most.add("most.fit", FitsPixelConverter.pixels(new int[][]{{200_000, 210_000, 220_000, 300}}, null));
        assertTrue(most.summary().mostPixelsOutside());
        first = new Answer(0);
        assertEquals(ImageScaling.OutlierHandling.PRESERVE_ALL, ImageScaling.interactive(first).choose(most.summary()));
        assertEquals(List.of("Preserve all values", "Clip extremes", "Cancel"), List.of(first.options));
        assertTrue(first.message.contains("Most pixels are outside the accepted range"));
        assertEquals(ImageScaling.OutlierHandling.CLIP_EXTREMES, ImageScaling.interactive(new Answer(1)).choose(most.summary()));
        assertNull(ImageScaling.interactive(new Answer(2)).choose(most.summary()));
        assertNull("a closed dialog cancels", ImageScaling.interactive(new Answer(-1)).choose(most.summary()));
    }

    /** Answers the outlier question with a fixed button and remembers what it was shown. */
    private static final class Answer implements FitsImportInteraction {
        private final int choice;
        private String message;
        private String[] options;

        /** Answers with the button at {@code choice}, or -1 for a closed dialog. */
        Answer(int choice) { this.choice = choice; }
        @Override public int choose(String message, String title, String[] options) {
            assertEquals("Extreme pixel values", title);
            this.message = message; this.options = options; return choice;
        }
        @Override public int confirm(String message, String title) { throw new AssertionError(title); }
        @Override public void error(String message, String title) { throw new AssertionError(title); }
        @Override public Progress progress(String title) { throw new AssertionError(title); }
    }

    /** XISF bounds normalization keeps raw border fill, while a real interior zero receives the declared mapping. */
    @Test public void normalizesDeclaredBoundsWithoutMovingMargins() throws Exception {
        double[][][] data = {{{0, 0, -0.5, 0, 0.5, 0}}};
        FitsPixelConverter.normalizeSamples(data, value -> (value + 1) / 2);
        assertArrayEquals(new double[]{0, 0, 0.25, 0.5, 0.75, 0}, data[0][0], 0);
    }
}
