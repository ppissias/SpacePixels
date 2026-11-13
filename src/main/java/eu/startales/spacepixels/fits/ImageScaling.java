package eu.startales.spacepixels.fits;

import io.github.ppissias.jtransient.execution.CancellationScope;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.IntConsumer;

/** Owns sequence-wide linear scaling, bounded headroom and explicit outlier decisions for FITS and XISF. */
public final class ImageScaling {
    public static final double FULL_SCALE = 65_535.0;
    public static final double LOWER_HEADROOM = -0.5;
    public static final double UPPER_HEADROOM = 1.5;

    /** Headless default refuses extremes; clipping or reducing precision always requires an explicit choice. */
    public enum OutlierHandling { REQUIRE_DECISION, CLIP_EXTREMES, PRESERVE_ALL }

    /** Called once per sequence on its worker, only when samples exceed the accepted headroom. */
    @FunctionalInterface public interface Decision { OutlierHandling choose(Summary summary); }

    /** Immutable per-frame counts; RGB counts each pixel once even if several channels exceed a bound. */
    public record FrameExtremes(String file, long pixels, long below, long above) { }

    /**
     * Immutable whole-sequence inspection in nominal [0,1] units, independent of source storage. {@code floatsInAdu}
     * records that floating-point frames hold ADU (detector counts) rather than [0,1] values and were normalized like
     * integer data.
     */
    public record Summary(double minimum, double maximum, long pixels, long negativePixels, long below, long above,
                          List<FrameExtremes> frames, boolean floatsInAdu) {
        /** Detaches the diagnostic list from the scanning worker. */
        public Summary { frames = List.copyOf(frames); }
        /** Whether a user or headless caller must decide how to handle extreme measurements. */
        public boolean hasExtremes() { return below > 0 || above > 0; }
        /** Whether more than half of all pixels are outside the accepted range, so clipping would flatten the image. */
        public boolean mostPixelsOutside() { return below + above > pixels / 2; }
        /** Explains both possible conversions without classifying rare bright pixels as defects. */
        public String describe() {
            double span = Math.max(1, Math.max(0, maximum) - Math.min(0, minimum));
            StringBuilder message = new StringBuilder(String.format(Locale.ROOT,
                    "Accepted normalized range: [-0.5, 1.5].\nObserved range: [%.8g, %.8g].\n"
                    + "Pixels below/above the accepted range: %d (%.6g%%) / %d (%.6g%%) of %d.\n"
                    + "Preserving the full range leaves approximately %,.0f levels across the nominal [0,1] signal.\n",
                    minimum, maximum, below, 100.0 * below / Math.max(1, pixels), above,
                    100.0 * above / Math.max(1, pixels), pixels, FULL_SCALE / span));
            if (floatsInAdu) {
                message.append("The floating-point frames hold ADU (detector counts), not values from 0 to 1, "
                        + "so they were normalized like integer data.\n");
            }
            if (mostPixelsOutside()) {
                message.append("Most pixels are outside the accepted range, so the frames are probably not on the "
                        + "expected scale (0 to 1 for floating-point samples, ADU for integers). Clipping would "
                        + "replace most of each image with one value.\n");
            }
            message.append("Affected frames:\n");
            int shown = 0;
            for (FrameExtremes frame : frames) if (frame.below() > 0 || frame.above() > 0) {
                if (shown++ < 12) message.append(frame.file()).append(": ").append(frame.below()).append(" below, ")
                        .append(frame.above()).append(" above\n");
            }
            if (shown > 12) message.append("… and ").append(shown - 12).append(" more affected frames.\n");
            return message.toString();
        }
    }

    /** Typed headless failure with structured counts, raised before conversion writes an output sequence. */
    public static final class OutlierException extends IOException {
        private final Summary summary;
        /** Captures immutable diagnostics and names the explicit alternatives. */
        public OutlierException(Summary summary) {
            super(summary.describe() + "Choose CLIP_EXTREMES or PRESERVE_ALL explicitly to continue.");
            this.summary = summary;
        }
        /** Returns the inspection so API callers need not parse an error message. */
        public Summary summary() { return summary; }
    }

    /** A user declined the outlier choice; distinct from an owning job's asynchronous cancellation. */
    public static final class DecisionCancelledException extends CancellationException {
        /** Identifies a declined conversion without consulting or changing job state. */
        public DecisionCancelledException() { super("Image conversion cancelled at the outlier choice."); }
    }

    /**
     * One worker's inspection; source buffers are borrowed only for the duration of add(). Floating-point frames are
     * measured both as [0,1] values and as ADU, because the scale is a decision for the whole sequence: when every
     * floating-point frame's median exceeds the upper headroom, the frames hold ADU (as professional pipelines write
     * them) and are normalized like integer data. Sky in a [0,1] frame is always far below that level.
     */
    public static final class Scan {
        /** At most about this many pixels per frame are sampled for the median. */
        private static final int MEDIAN_SAMPLES = 65_536;
        private final List<FrameScan> frames = new ArrayList<>();

        /**
         * One frame's measurements in physical units. The unit counts treat samples as [0,1] values and the ADU counts
         * as integer-like ADU; integer frames use only the ADU counts. The median ignores exact zeros (registration fill).
         */
        private record FrameScan(String name, boolean floating, long pixels, double minimum, double maximum,
                                 long negative, long belowAsUnit, long aboveAsUnit, long belowAsAdu, long aboveAsAdu,
                                 double median) { }

        /** Inspects all physical channels; see {@link #summary()} for the scale of floating-point samples. */
        public void add(String name, FitsPixelConverter.Pixels image) throws IOException {
            add(name, image, () -> false);
        }
        /** Inspects a borrowed image while also observing an owning preparation worker's cancellation. */
        public void add(String name, FitsPixelConverter.Pixels image, java.util.function.BooleanSupplier cancelled) throws IOException {
            long belowUnit = 0, aboveUnit = 0, belowAdu = 0, aboveAdu = 0, neg = 0;
            double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
            long count = (long) image.width() * image.height();
            long stride = Math.max(1, count / MEDIAN_SAMPLES);
            double[] samples = new double[image.floatingPoint()
                    ? (int) ((count + stride - 1) / stride) * image.channels() : 0];
            int sampled = 0;
            long untilSample = 0;
            for (int y = 0; y < image.height(); y++) {
                if ((y & 63) == 0) {
                    CancellationScope.check();
                    if (cancelled.getAsBoolean()) throw new CancellationException("Image conversion cancelled.");
                }
                for (int x = 0; x < image.width(); x++) {
                    boolean lowUnit = false, highUnit = false, lowAdu = false, highAdu = false, negativeValue = false;
                    boolean sample = samples.length > 0 && untilSample-- == 0;
                    if (sample) untilSample = stride - 1;
                    for (int c = 0; c < image.channels(); c++) {
                        double value = image.physicalValue(c, y, x);
                        if (!Double.isFinite(value)) throw new IOException("Nonfinite image samples in " + name + ".");
                        double adu = value / FULL_SCALE;
                        minimum = Math.min(minimum, value); maximum = Math.max(maximum, value);
                        lowUnit |= value < LOWER_HEADROOM; highUnit |= value > UPPER_HEADROOM;
                        lowAdu |= adu < LOWER_HEADROOM; highAdu |= adu > UPPER_HEADROOM; negativeValue |= value < 0;
                        if (sample && value != 0) samples[sampled++] = value;
                    }
                    if (lowUnit) belowUnit++; if (highUnit) aboveUnit++;
                    if (lowAdu) belowAdu++; if (highAdu) aboveAdu++;
                    if (negativeValue) neg++;
                }
            }
            frames.add(new FrameScan(name, image.floatingPoint(), count, minimum, maximum, neg,
                    belowUnit, aboveUnit, belowAdu, aboveAdu, median(samples, sampled)));
        }

        /** The middle of the first {@code count} samples (the mean of the two middle ones for an even count); 0 when empty. */
        private static double median(double[] samples, int count) {
            if (count == 0) return 0;
            java.util.Arrays.sort(samples, 0, count);
            return count % 2 == 1 ? samples[count / 2] : (samples[count / 2 - 1] + samples[count / 2]) / 2;
        }

        /**
         * Publishes detached diagnostics after the final frame has been read. Floating-point frames are read as ADU
         * only when every one of them has a median above the upper headroom; otherwise they stay on the [0,1] scale.
         */
        public Summary summary() {
            boolean floatsInAdu = frames.stream().anyMatch(FrameScan::floating)
                    && frames.stream().filter(FrameScan::floating).allMatch(frame -> frame.median() > UPPER_HEADROOM);
            double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
            long pixels = 0, negative = 0, below = 0, above = 0;
            List<FrameExtremes> extremes = new ArrayList<>();
            for (FrameScan frame : frames) {
                boolean unitScale = frame.floating() && !floatsInAdu;
                double scale = unitScale ? 1 : FULL_SCALE;
                long low = unitScale ? frame.belowAsUnit() : frame.belowAsAdu();
                long high = unitScale ? frame.aboveAsUnit() : frame.aboveAsAdu();
                minimum = Math.min(minimum, frame.minimum() / scale);
                maximum = Math.max(maximum, frame.maximum() / scale);
                pixels += frame.pixels(); negative += frame.negative(); below += low; above += high;
                extremes.add(new FrameExtremes(frame.name(), frame.pixels(), low, high));
            }
            return new Summary(pixels == 0 ? 0 : minimum, pixels == 0 ? 0 : maximum,
                    pixels, negative, below, above, extremes, floatsInAdu);
        }
    }

    /** A fixed sequence transform; offsets are expressed in canonical 16-bit ADU before its multiplier. */
    public record Plan(double minimum, double maximum, double multiplier, double addedOffset,
                       boolean keepNegative, boolean clipExtremes, Summary summary) {
        /**
         * Applies only the agreed clipping and a linear transform; registration fill is handled by the converter.
         * {@code floating} is the source storage; the summary decides whether such samples are [0,1] values or ADU.
         */
        public double convert(double physical, boolean floating) {
            double value = unitScale(floating) ? physical : physical / FULL_SCALE;
            if (clipExtremes) value = Math.max(LOWER_HEADROOM, Math.min(UPPER_HEADROOM, value));
            if (!keepNegative) value = Math.max(0, value);
            return (value * FULL_SCALE + addedOffset) * multiplier;
        }
        /** Physical-sample multiplier recorded in a source FITS file's conversion provenance. */
        public double physicalMultiplier(boolean floating) { return multiplier * (unitScale(floating) ? FULL_SCALE : 1); }
        /** Physical-sample offset applied before the recorded multiplier. */
        public double physicalOffset(boolean floating) { return addedOffset / (unitScale(floating) ? FULL_SCALE : 1); }
        /** Whether samples of this storage are [0,1] values: floating-point ones, unless the scan found them in ADU. */
        private boolean unitScale(boolean floating) { return floating && !summary.floatsInAdu(); }
        /** Writes transform, selected policy and the pedestal in output ADU for report background correction. */
        public void record(Header header, boolean floating) throws IOException {
            header.addValue("SPPSCALE", physicalMultiplier(floating), "Common linear multiplier from physical samples");
            header.addValue("SPPOFF", physicalOffset(floating), "Physical offset before SPPSCALE; fill stays 0");
            if (floating) header.addValue("SPPFADU", summary.floatsInAdu(), "Floating-point samples read as ADU, not 0-1");
            double oldOffset = header.getDoubleValue("SPOFFSET", 0);
            double pedestal = (oldOffset + addedOffset) * multiplier;
            if (pedestal > 0) header.addValue("SPOFFSET", pedestal, "Total added pedestal in output ADU");
            else header.deleteKey("SPOFFSET");
            header.addValue("SPPCLIP", clipExtremes, "Explicit extreme clipping selected");
            header.addValue("SPPLOW", LOWER_HEADROOM, "Accepted normalized lower limit");
            header.addValue("SPPHIGH", UPPER_HEADROOM, "Accepted normalized upper limit");
            header.addValue("SPPMIN", summary.minimum(), "Observed normalized cohort minimum");
            header.addValue("SPPMAX", summary.maximum(), "Observed normalized cohort maximum");
            header.addValue("SPPNLOW", summary.below(), "Cohort pixels below accepted lower limit");
            header.addValue("SPPNHIGH", summary.above(), "Cohort pixels above accepted upper limit");
        }
    }

    /** Stateless policy; each conversion owns its scanner and immutable plan. */
    private ImageScaling() { }

    /** Scans a FITS cohort without retaining any frame's pixel buffer. */
    public static Summary inspect(File[] files, IntConsumer progress) throws Exception {
        Scan scan = new Scan();
        for (int i = 0; i < files.length; i++) {
            CancellationScope.check();
            try (Fits fits = new Fits(files[i])) {
                var hdu = FitsInput.getImageHDU(fits);
                if (hdu == null) throw new IOException("Missing image in " + files[i].getName());
                scan.add(files[i].getName(), FitsPixelConverter.pixels(hdu.getKernel(), hdu.getHeader()));
            }
            if (progress != null) progress.accept((i + 1) * 100 / files.length);
        }
        return scan.summary();
    }

    /** Chooses once before writing; default headless behavior refuses extremes instead of clipping or rescaling silently. */
    public static Plan plan(Summary summary, boolean keepNegative, Decision decision) throws IOException {
        CancellationScope.check();
        OutlierHandling handling = summary.hasExtremes() && decision != null ? decision.choose(summary) : OutlierHandling.REQUIRE_DECISION;
        CancellationScope.check();
        if (handling == null) throw new DecisionCancelledException();
        if (summary.hasExtremes() && handling == OutlierHandling.REQUIRE_DECISION) throw new OutlierException(summary);
        boolean clip = summary.hasExtremes() && handling == OutlierHandling.CLIP_EXTREMES;
        double minimum = keepNegative ? Math.min(0, clip ? Math.max(LOWER_HEADROOM, summary.minimum()) : summary.minimum()) : 0;
        double maximum = Math.max(0, clip ? Math.min(UPPER_HEADROOM, summary.maximum()) : summary.maximum());
        double multiplier = 1 / Math.max(1, maximum - minimum);
        if (!Double.isFinite(multiplier) || multiplier <= 0) throw new IOException("Image intensity range cannot be represented.");
        return new Plan(minimum, maximum, multiplier, -minimum * FULL_SCALE, keepNegative, clip, summary);
    }

    /** Uses an explicit fixed policy for API/CLI requests. */
    public static Decision policy(OutlierHandling handling) { return summary -> java.util.Objects.requireNonNull(handling); }

    /** Honors a low-level caller's fixed ADU pedestal; sequence workflows normally derive it from the shared scan. */
    static Plan fixedPedestal(Summary summary, int pedestal) throws IOException {
        if (pedestal < 0) throw new IllegalArgumentException("A pedestal cannot be negative.");
        Plan checked = plan(summary, pedestal > 0, null);
        double range = Math.max(1, summary.maximum() + pedestal / FULL_SCALE);
        return new Plan(-pedestal / FULL_SCALE, summary.maximum(), 1 / range, pedestal,
                pedestal > 0, checked.clipExtremes(), summary);
    }

    /**
     * Reuses the ordinary worker-to-EDT interaction boundary for one whole-sequence outlier question. The first option
     * is the default; when most pixels are outside the accepted range, clipping would flatten the images, so
     * preserving comes first.
     */
    public static Decision interactive(FitsImportInteraction interaction) {
        if (interaction == null) return null;
        return summary -> {
            String clip = "Clip extremes", preserve = "Preserve all values";
            String[] options = summary.mostPixelsOutside()
                    ? new String[]{preserve, clip, "Cancel"} : new String[]{clip, preserve, "Cancel"};
            String text = summary.describe() + "\nClip extremes: limit only values outside [-0.5, 1.5]; normal negative noise is preserved.\n"
                    + "Preserve all: retain every finite value with reduced precision.\nOne common transform is used for all frames.";
            int choice = interaction.choose(text, "Extreme pixel values", options);
            if (choice < 0 || choice > 1) return null;
            return options[choice].equals(clip) ? OutlierHandling.CLIP_EXTREMES : OutlierHandling.PRESERVE_ALL;
        };
    }
}
