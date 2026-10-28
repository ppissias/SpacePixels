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

    /** Immutable whole-sequence inspection in nominal [0,1] units, independent of source storage. */
    public record Summary(double minimum, double maximum, long pixels, long negativePixels, long below, long above,
                          List<FrameExtremes> frames) {
        /** Detaches the diagnostic list from the scanning worker. */
        public Summary { frames = List.copyOf(frames); }
        /** Whether a user or headless caller must decide how to handle extreme measurements. */
        public boolean hasExtremes() { return below > 0 || above > 0; }
        /** Explains both possible conversions without classifying rare bright pixels as defects. */
        public String describe() {
            double span = Math.max(1, Math.max(0, maximum) - Math.min(0, minimum));
            StringBuilder message = new StringBuilder(String.format(Locale.ROOT,
                    "Accepted normalized range: [-0.5, 1.5].\nObserved range: [%.8g, %.8g].\n"
                    + "Pixels below/above the accepted range: %d (%.6g%%) / %d (%.6g%%) of %d.\n"
                    + "Preserving the full range leaves approximately %,.0f levels across the nominal [0,1] signal.\n",
                    minimum, maximum, below, 100.0 * below / Math.max(1, pixels), above,
                    100.0 * above / Math.max(1, pixels), pixels, FULL_SCALE / span));
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

    /** One worker's inspection; source buffers are borrowed only for the duration of add(). */
    public static final class Scan {
        private double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
        private long pixels, negative, below, above;
        private final List<FrameExtremes> frames = new ArrayList<>();

        /** Inspects all physical channels, normalizing floating FITS samples to their nominal full scale. */
        public void add(String name, FitsPixelConverter.Pixels image) throws IOException {
            add(name, image, () -> false);
        }
        /** Inspects a borrowed image while also observing an owning preparation worker's cancellation. */
        public void add(String name, FitsPixelConverter.Pixels image, java.util.function.BooleanSupplier cancelled) throws IOException {
            long low = 0, high = 0, neg = 0;
            double scale = image.floatingPoint() ? 1 : FULL_SCALE;
            for (int y = 0; y < image.height(); y++) {
                if ((y & 63) == 0) {
                    CancellationScope.check();
                    if (cancelled.getAsBoolean()) throw new CancellationException("Image conversion cancelled.");
                }
                for (int x = 0; x < image.width(); x++) {
                    boolean lower = false, upper = false, negativeValue = false;
                    for (int c = 0; c < image.channels(); c++) {
                        double value = image.physicalValue(c, y, x) / scale;
                        if (!Double.isFinite(value)) throw new IOException("Nonfinite image samples in " + name + ".");
                        minimum = Math.min(minimum, value); maximum = Math.max(maximum, value);
                        lower |= value < LOWER_HEADROOM; upper |= value > UPPER_HEADROOM; negativeValue |= value < 0;
                    }
                    if (lower) low++; if (upper) high++; if (negativeValue) neg++;
                }
            }
            long count = (long) image.width() * image.height();
            pixels += count; below += low; above += high; negative += neg;
            frames.add(new FrameExtremes(name, count, low, high));
        }
        /** Publishes detached diagnostics after the final frame has been read. */
        public Summary summary() {
            return new Summary(pixels == 0 ? 0 : minimum, pixels == 0 ? 0 : maximum,
                    pixels, negative, below, above, frames);
        }
    }

    /** A fixed sequence transform; offsets are expressed in canonical 16-bit ADU before its multiplier. */
    public record Plan(double minimum, double maximum, double multiplier, double addedOffset,
                       boolean keepNegative, boolean clipExtremes, Summary summary) {
        /** Applies only the agreed clipping and a linear transform; registration fill is handled by the converter. */
        public double convert(double physical, boolean floating) {
            double value = floating ? physical : physical / FULL_SCALE;
            if (clipExtremes) value = Math.max(LOWER_HEADROOM, Math.min(UPPER_HEADROOM, value));
            if (!keepNegative) value = Math.max(0, value);
            return (value * FULL_SCALE + addedOffset) * multiplier;
        }
        /** Physical-sample multiplier recorded in a source FITS file's conversion provenance. */
        public double physicalMultiplier(boolean floating) { return multiplier * (floating ? FULL_SCALE : 1); }
        /** Physical-sample offset applied before the recorded multiplier. */
        public double physicalOffset(boolean floating) { return addedOffset / (floating ? FULL_SCALE : 1); }
        /** Writes transform, selected policy and the pedestal in output ADU for report background correction. */
        public void record(Header header, boolean floating) throws IOException {
            header.addValue("SPPSCALE", physicalMultiplier(floating), "Common linear multiplier from physical samples");
            header.addValue("SPPOFF", physicalOffset(floating), "Physical offset before SPPSCALE; fill stays 0");
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

    /** Reuses the ordinary worker-to-EDT interaction boundary for one whole-sequence outlier question. */
    public static Decision interactive(FitsImportInteraction interaction) {
        if (interaction == null) return null;
        return summary -> {
            String text = summary.describe() + "\nClip extremes: limit only values outside [-0.5, 1.5]; normal negative noise is preserved.\n"
                    + "Preserve all: retain every finite value with reduced precision.\nOne common transform is used for all frames.";
            int choice = interaction.choose(text, "Extreme pixel values", new String[]{"Clip extremes", "Preserve all values", "Cancel"});
            return choice == 0 ? OutlierHandling.CLIP_EXTREMES : choice == 1 ? OutlierHandling.PRESERVE_ALL : null;
        };
    }
}
