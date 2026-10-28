/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.fits;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.util.Cursor;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntConsumer;

/**
 * Converts FITS pixel kernels into SpacePixels' 16-bit formats, sharing storage decoding, padding and quantization.
 */
public final class FitsPixelConverter {

    /** Header keyword with the ADU that SpacePixels added to every pixel so values below zero were kept. */
    static final String ADDED_OFFSET_KEY = "SPOFFSET";

    private FitsPixelConverter() {
    }

    public static Fits createFitsFromData(Object newData, Header originalHeader) throws FitsException, IOException {
        return createFitsFromData(newData, originalHeader, 0);
    }

    /**
     * Wraps converted pixels in a new FITS with the original's non-structural cards. A non-zero {@code addedOffset} is
     * added to any offset the original already records, so the header always holds the total.
     */
    public static Fits createFitsFromData(Object newData, Header originalHeader, int addedOffset) throws FitsException, IOException {
        Fits updatedFits = new Fits();
        BasicHDU<?> newHDU = Fits.makeHDU(newData);
        updatedFits.addHDU(newHDU);

        Header newHeader = newHDU.getHeader();
        List<String> structuralKeys = List.of(
                "SIMPLE", "BITPIX", "NAXIS", "NAXIS1", "NAXIS2", "NAXIS3",
                "EXTEND", "BZERO", "BSCALE"
        );

        Cursor<String, HeaderCard> originalCursor = originalHeader.iterator();
        while (originalCursor.hasNext()) {
            HeaderCard card = originalCursor.next();
            String key = card.getKey();

            if (key != null && !structuralKeys.contains(key) && !newHeader.containsKey(key)
                    && !(addedOffset > 0 && ADDED_OFFSET_KEY.equals(key))) {
                newHeader.addLine(card);
            }
        }

        // Preserve SpacePixels' unsigned-16-bit interpretation for stored short arrays.
        newHeader.addValue("BZERO", 32768.0, "offset data range to that of unsigned short");
        newHeader.addValue("BSCALE", 1.0, "default scaling factor");
        if (addedOffset > 0) {
            long totalOffset = Math.round(originalHeader.getDoubleValue(ADDED_OFFSET_KEY, 0.0)) + addedOffset;
            newHeader.addValue(ADDED_OFFSET_KEY, totalOffset, "ADU added to every pixel to keep values below 0");
        }

        return updatedFits;
    }

    /** Whether the stored values can stand for pixels below zero once BZERO and BSCALE are applied. */
    static boolean canHoldNegativeValues(Header header) {
        int bitpix = header.getIntValue("BITPIX", 0);
        if (bitpix < 0) {
            return true;
        }
        double lowestStored;
        double highestStored;
        switch (bitpix) {
            case 8:
                lowestStored = 0;
                highestStored = 255;
                break;
            case 16:
                lowestStored = Short.MIN_VALUE;
                highestStored = Short.MAX_VALUE;
                break;
            case 32:
                lowestStored = Integer.MIN_VALUE;
                highestStored = Integer.MAX_VALUE;
                break;
            default:
                return true;
        }
        double offset = header.getDoubleValue("BZERO", 0.0);
        double scale = header.getDoubleValue("BSCALE", 1.0);
        return Math.min(lowestStored * scale, highestStored * scale) + offset < 0.0;
    }

    static short[][] standardizeTo16BitMono(Object kernel) throws IOException {
        return standardizeTo16BitMono(kernel, null);
    }

    static short[][] standardizeTo16BitMono(Object kernel, Header header) throws IOException {
        return standardizeTo16BitMono(kernel, header, 0);
    }

    /**
     * Applies BZERO/BSCALE and stores the result as unsigned 16-bit. {@code addedOffset} ADU are added to every pixel
     * except the zero fill that registration leaves at the frame edges; values still below zero become 0.
     */
    static short[][] standardizeTo16BitMono(Object kernel, Header header, int addedOffset) throws IOException {
        if (!(kernel instanceof short[][] || kernel instanceof int[][] || kernel instanceof float[][])) {
            throw new IOException("Unsupported FITS format for Mono Standardization");
        }
        return standardizePlanes(pixels(kernel, header), addedOffset)[0];
    }

    static short[][][] standardizeTo16BitColor(Object kernel) throws IOException {
        return standardizeTo16BitColor(kernel, null);
    }

    static short[][][] standardizeTo16BitColor(Object kernel, Header header) throws IOException {
        return standardizeTo16BitColor(kernel, header, 0);
    }

    /** The colour version of {@link #standardizeTo16BitMono(Object, Header, int)}. */
    static short[][][] standardizeTo16BitColor(Object kernel, Header header, int addedOffset) throws IOException {
        if (!(kernel instanceof short[][][] || kernel instanceof int[][][] || kernel instanceof float[][][])) {
            throw new IOException("Unsupported FITS format for Color Standardization");
        }
        return standardizePlanes(pixels(kernel, header), addedOffset);
    }

    /** Retains ordinary import's normalization, clipping and per-channel rounding policy. */
    private static short[][][] standardizePlanes(Pixels pixels, int addedOffset) throws IOException {
        double normalization = normalization(pixels);
        return convertPlanes(pixels, value -> value * normalization + addedOffset, false, false, () -> false);
    }

    /**
     * Borrows a mono or colour short/int/float/double kernel and captures its BZERO/BSCALE. No pixels are copied;
     * the caller owns the buffers and must keep them unchanged until scanning/conversion completes.
     */
    public static Pixels pixels(Object kernel, Header header) throws IOException {
        Object[] planes;
        if (kernel instanceof short[][] || kernel instanceof int[][] || kernel instanceof float[][] || kernel instanceof double[][]) {
            planes = new Object[]{kernel};
        } else if (kernel instanceof short[][][] || kernel instanceof int[][][] || kernel instanceof float[][][] || kernel instanceof double[][][]) {
            planes = (Object[]) kernel;
        } else throw new IOException("Unsupported FITS pixel type.");
        return new Pixels(planes, kernel instanceof float[][] || kernel instanceof float[][][]
                || kernel instanceof double[][] || kernel instanceof double[][][], header);
    }

    /** A read-only physical-value view over caller-owned pixels; storage decoding has one implementation. */
    public static final class Pixels {
        private final Object[] planes;
        private final int width, height;
        private final boolean floatingPoint;
        private final double scale, offset;

        /** Captures storage facts without retaining the mutable header or modifying the borrowed kernel. */
        private Pixels(Object[] planes, boolean floatingPoint, Header header) throws IOException {
            this.planes = planes;
            this.floatingPoint = floatingPoint;
            height = Array.getLength(planes[0]);
            width = Array.getLength(Array.get(planes[0], 0));
            offset = header == null ? 0 : header.getDoubleValue("BZERO", 0);
            scale = header == null ? 1 : header.getDoubleValue("BSCALE", 1);
            if (!Double.isFinite(offset) || !Double.isFinite(scale)) throw new IOException("FITS BZERO and BSCALE must be finite.");
        }

        /** The image width in pixels. */
        public int width() { return width; }
        /** The image height in pixels. */
        public int height() { return height; }
        /** The number of stored channels, before any monochrome reduction. */
        public int channels() { return planes.length; }

        /** Applies FITS storage scaling only; nonfinite values remain available for the caller's acceptance policy. */
        public double physicalValue(int channel, int row, int column) {
            double stored = switch (planes[channel]) {
                case short[][] data -> data[row][column];
                case int[][] data -> data[row][column];
                case float[][] data -> data[row][column];
                case double[][] data -> data[row][column];
                default -> throw new IllegalStateException("Unsupported captured FITS plane.");
            };
            return stored * scale + offset;
        }

        /** Fixed arithmetic mean in physical units, before output scaling, clipping or quantization. */
        public double monochromeValue(int row, int column) {
            double sum = 0;
            for (int c = 0; c < channels(); c++) sum += physicalValue(c, row, column);
            return sum / channels();
        }

        /** A negative channel index selects shared RGB fill: every channel must be zero, not just their mean. */
        private boolean zeroFill(int channel, int row, int column) {
            if (channel >= 0) return physicalValue(channel, row, column) == 0;
            for (int c = 0; c < channels(); c++) if (physicalValue(c, row, column) != 0) return false;
            return true;
        }
    }

    /**
     * Converts a sequence-planned image to mono16: (physical RGB mean + addedOffset) * multiplier. All frames
     * receive the same transform from their caller. Registration fill remains zero; nonfinite or out-of-range
     * measurements fail instead of clipping. Cancellation is checked every 64 rows on the calling worker.
     */
    public static short[][] to16BitMonochrome(Pixels pixels, double multiplier, double addedOffset, BooleanSupplier cancelled) throws IOException {
        if (!Double.isFinite(multiplier) || multiplier <= 0 || !Double.isFinite(addedOffset) || addedOffset < 0) {
            throw new IOException("Invalid common FITS conversion transform.");
        }
        return convertPlanes(pixels, value -> (value + addedOffset) * multiplier, true, true, cancelled)[0];
    }

    /** One conversion loop owns zero-fill preservation, output quantization and unsigned-short storage for both callers. */
    private static short[][][] convertPlanes(Pixels pixels, DoubleUnaryOperator transform, boolean averageChannels,
                                            boolean strict, BooleanSupplier cancelled) throws IOException {
        int channels = averageChannels ? 1 : pixels.channels();
        short[][][] result = new short[channels][pixels.height()][pixels.width()];
        for (int c = 0; c < channels; c++) for (int y = 0; y < pixels.height(); y++) {
            if ((y & 63) == 0 && cancelled.getAsBoolean()) throw new CancellationException("FITS conversion cancelled.");
            int[] image = columnsInsidePadding(pixels, averageChannels ? -1 : c, y);
            for (int x = 0; x < pixels.width(); x++) {
                double value = x < image[0] || x >= image[1] ? 0
                        : transform.applyAsDouble(averageChannels ? pixels.monochromeValue(y, x) : pixels.physicalValue(c, y, x));
                if (strict && (!Double.isFinite(value) || value < -0.000001 || value > 65535.000001)) {
                    throw new IOException("Nonfinite or out-of-range pixels in prepared FITS conversion.");
                }
                result[c][y][x] = toUnsigned16Storage(value);
            }
        }
        return result;
    }

    /**
     * Registration fills the area outside the warped frame with zeros, and the warped frame is convex, so on each row
     * that fill is a run of exact zeros at the start and one at the end. Returns the first column after the first run
     * and the first column of the last run, so the fill can stay at 0 when an offset lifts every other pixel: the
     * detection recognises padding by its near-zero level.
     */
    private static int[] columnsInsidePadding(Pixels pixels, int channel, int row) {
        int start = 0;
        while (start < pixels.width() && pixels.zeroFill(channel, row, start)) {
            start++;
        }
        int end = pixels.width();
        while (end > start && pixels.zeroFill(channel, row, end - 1)) {
            end--;
        }
        return new int[]{start, end};
    }

    /** The lowest pixel value of a sequence as the conversion scales it, and how many pixels would round below zero. */
    public static final class NegativeValueScan {
        private double lowest = Double.POSITIVE_INFINITY;
        private long negativePixels;
        private long totalPixels;

        public boolean hasNegativeValues() {
            return negativePixels > 0;
        }

        public double lowest() {
            return lowest;
        }

        public long negativePixels() {
            return negativePixels;
        }

        public long totalPixels() {
            return totalPixels;
        }

        /** The whole ADU to add to every pixel so the lowest one becomes 0 (at most 65535); 0 when none is below zero. */
        public int offsetToKeepNegativeValues() {
            return hasNegativeValues() ? (int) Math.min(65535, Math.round(-lowest)) : 0;
        }
    }

    /**
     * Reads every frame whose storage can go below zero and scales it as the conversion will; other frames (unsigned
     * 16-bit, for example) are skipped without reading their pixels.
     */
    public static NegativeValueScan scanNegativeValues(File[] files, IntConsumer progress) throws IOException, FitsException {
        NegativeValueScan scan = new NegativeValueScan();
        for (int i = 0; i < files.length; i++) {
            if (progress != null) {
                progress.accept((int) Math.round(100.0 * i / files.length));
            }
            try (Fits fits = new Fits(files[i])) {
                BasicHDU<?> hdu = FitsInput.getImageHDU(fits);
                if (hdu == null || !canHoldNegativeValues(hdu.getHeader())) {
                    continue;
                }
                Object kernel = hdu.getKernel();
                if (!(kernel instanceof short[][] || kernel instanceof int[][] || kernel instanceof float[][]
                        || kernel instanceof short[][][] || kernel instanceof int[][][] || kernel instanceof float[][][])) {
                    continue;
                }
                Pixels pixels = pixels(kernel, hdu.getHeader());
                double normalization = normalization(pixels);
                for (int channel = 0; channel < pixels.channels(); channel++) {
                    for (int row = 0; row < pixels.height(); row++) {
                        for (int column = 0; column < pixels.width(); column++) {
                            double value = pixels.physicalValue(channel, row, column) * normalization;
                            if (!Double.isFinite(value)) {
                                continue;
                            }
                            scan.totalPixels++;
                            // Values above -0.5 round to 0 anyway.
                            if (value < -0.5) {
                                scan.negativePixels++;
                                scan.lowest = Math.min(scan.lowest, value);
                            }
                        }
                    }
                }
            }
        }
        if (progress != null) {
            progress.accept(100);
        }
        return scan;
    }

    /** Ordinary import's existing unit-range normalization, after physical FITS storage decoding. */
    private static double normalization(Pixels pixels) {
        if (!pixels.floatingPoint) return 1;
        double maximum = Double.NEGATIVE_INFINITY;
        for (int c = 0; c < pixels.channels(); c++) for (int y = 0; y < pixels.height(); y++) for (int x = 0; x < pixels.width(); x++) {
            double physical = pixels.physicalValue(c, y, x);
            if (Double.isFinite(physical)) maximum = Math.max(maximum, physical);
        }
        return maximum > 0 && maximum <= 10 ? 65535 : 1;
    }

    static short[][] extractLuminance(short[][][] color16) {
        int height = color16[0].length;
        int width = color16[0][0].length;
        short[][] monoData = new short[height][width];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = color16[0][y][x];
                int g = color16[1][y][x];
                int b = color16[2][y][x];
                monoData[y][x] = (short) ((r + g + b) / 3);
            }
        }
        return monoData;
    }

    static short[][] convertColorKernelToMono(Object kernelData) throws FitsException {
        if (kernelData instanceof short[][][]) {
            return extractLuminance((short[][][]) kernelData);
        }
        throw new FitsException(
                "Cannot convert to mono. Expected 16-bit color (short[][][]), but received type="
                        + kernelData.getClass().getName());
    }

    private static short toUnsigned16Storage(double value) {
        double clamped = Double.isNaN(value) ? 0.0 : Math.max(0.0, Math.min(65535.0, value));
        return (short) (Math.round(clamped) - 32768);
    }
}
