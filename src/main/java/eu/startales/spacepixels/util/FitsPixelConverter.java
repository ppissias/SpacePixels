/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.FitsFactory;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.util.Cursor;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Converts FITS pixel kernels into SpacePixels' normalized 16-bit formats.
 */
final class FitsPixelConverter {

    /** Header keyword with the ADU that SpacePixels added to every pixel so values below zero were kept. */
    static final String ADDED_OFFSET_KEY = "SPOFFSET";

    private FitsPixelConverter() {
    }

    static Fits createFitsFromData(Object newData, Header originalHeader) throws FitsException, IOException {
        return createFitsFromData(newData, originalHeader, 0);
    }

    /**
     * Wraps converted pixels in a new FITS with the original's non-structural cards. A non-zero {@code addedOffset} is
     * added to any offset the original already records, so the header always holds the total.
     */
    static Fits createFitsFromData(Object newData, Header originalHeader, int addedOffset) throws FitsException, IOException {
        Fits updatedFits = new Fits();
        BasicHDU<?> newHDU = FitsFactory.hduFactory(newData);
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

    /** True for the layout the detection reads directly: 16-bit integers stored as unsigned (BZERO 32768, BSCALE 1). */
    static boolean isUnsigned16Bit(Header header) {
        return isUnsigned16Bit(header.getIntValue("BITPIX", 0), header.getDoubleValue("BZERO", 0.0),
                header.getDoubleValue("BSCALE", 1.0));
    }

    static boolean isUnsigned16Bit(int bitpix, double bzero, double bscale) {
        return bitpix == 16 && bzero == 32768.0 && bscale == 1.0;
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
        return standardizePlanes(new Object[]{kernel}, kernel instanceof float[][], header, addedOffset)[0];
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
        return standardizePlanes((Object[]) kernel, kernel instanceof float[][][], header, addedOffset);
    }

    private static short[][][] standardizePlanes(Object[] planes, boolean floatingPoint, Header header, int addedOffset)
            throws IOException {
        Scaling scaling = Scaling.of(planes, floatingPoint, header);
        Object[] firstPlane = (Object[]) planes[0];
        int height = firstPlane.length;
        int width = Array.getLength(firstPlane[0]);
        short[][][] result = new short[planes.length][height][width];
        for (int channel = 0; channel < planes.length; channel++) {
            Object[] rows = (Object[]) planes[channel];
            for (int row = 0; row < height; row++) {
                int[] image = addedOffset > 0 ? columnsInsidePadding(rows[row], scaling, width) : new int[]{0, width};
                for (int column = 0; column < width; column++) {
                    double physicalValue = scaling.apply(Array.getDouble(rows[row], column));
                    boolean padding = column < image[0] || column >= image[1];
                    result[channel][row][column] = toUnsigned16Storage(padding ? physicalValue : physicalValue + addedOffset);
                }
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
    private static int[] columnsInsidePadding(Object row, Scaling scaling, int width) {
        int start = 0;
        while (start < width && scaling.apply(Array.getDouble(row, start)) == 0.0) {
            start++;
        }
        int end = width;
        while (end > start && scaling.apply(Array.getDouble(row, end - 1)) == 0.0) {
            end--;
        }
        return new int[]{start, end};
    }

    /** The lowest pixel value of a sequence as the conversion scales it, and how many pixels would round below zero. */
    static final class NegativeValueScan {
        private double lowest = Double.POSITIVE_INFINITY;
        private long negativePixels;
        private long totalPixels;

        boolean hasNegativeValues() {
            return negativePixels > 0;
        }

        double lowest() {
            return lowest;
        }

        long negativePixels() {
            return negativePixels;
        }

        long totalPixels() {
            return totalPixels;
        }

        /** The whole ADU to add to every pixel so the lowest one becomes 0 (at most 65535); 0 when none is below zero. */
        int offsetToKeepNegativeValues() {
            return hasNegativeValues() ? (int) Math.min(65535, Math.round(-lowest)) : 0;
        }
    }

    /**
     * Reads every frame whose storage can go below zero and scales it as the conversion will; other frames (unsigned
     * 16-bit, for example) are skipped without reading their pixels.
     */
    static NegativeValueScan scanNegativeValues(File[] files, IntConsumer progress) throws IOException, FitsException {
        NegativeValueScan scan = new NegativeValueScan();
        for (int i = 0; i < files.length; i++) {
            if (progress != null) {
                progress.accept((int) Math.round(100.0 * i / files.length));
            }
            try (Fits fits = new Fits(files[i])) {
                BasicHDU<?> hdu = ImageProcessing.getImageHDU(fits);
                if (hdu == null || !canHoldNegativeValues(hdu.getHeader())) {
                    continue;
                }
                Object kernel = hdu.getKernel();
                Object[] planes = planesOf(kernel);
                if (planes == null) {
                    continue;
                }
                boolean floatingPoint = kernel instanceof float[][] || kernel instanceof float[][][];
                Scaling scaling = Scaling.of(planes, floatingPoint, hdu.getHeader());
                for (Object plane : planes) {
                    for (Object row : (Object[]) plane) {
                        int width = Array.getLength(row);
                        for (int column = 0; column < width; column++) {
                            double value = scaling.apply(Array.getDouble(row, column));
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

    private static Object[] planesOf(Object kernel) {
        if (kernel instanceof short[][] || kernel instanceof int[][] || kernel instanceof float[][]) {
            return new Object[]{kernel};
        }
        if (kernel instanceof short[][][] || kernel instanceof int[][][] || kernel instanceof float[][][]) {
            return (Object[]) kernel;
        }
        return null;
    }

    /** BZERO and BSCALE, then floating-point data in unit range stretched to 0..65535. */
    private static final class Scaling {
        private final double offset;
        private final double scale;
        private final double normalization;

        private Scaling(double offset, double scale, double normalization) {
            this.offset = offset;
            this.scale = scale;
            this.normalization = normalization;
        }

        static Scaling of(Object[] planes, boolean floatingPoint, Header header) throws IOException {
            double offset = header == null ? 0.0 : header.getDoubleValue("BZERO", 0.0);
            double scale = header == null ? 1.0 : header.getDoubleValue("BSCALE", 1.0);
            if (!Double.isFinite(offset) || !Double.isFinite(scale)) {
                throw new IOException("FITS BZERO and BSCALE must be finite.");
            }
            double maximum = Double.NEGATIVE_INFINITY;
            if (floatingPoint) {
                for (Object plane : planes) {
                    for (Object row : (Object[]) plane) {
                        int width = Array.getLength(row);
                        for (int column = 0; column < width; column++) {
                            double physicalValue = Array.getDouble(row, column) * scale + offset;
                            if (Double.isFinite(physicalValue)) {
                                maximum = Math.max(maximum, physicalValue);
                            }
                        }
                    }
                }
            }
            double normalization = floatingPoint && maximum > 0.0 && maximum <= 10.0 ? 65535.0 : 1.0;
            return new Scaling(offset, scale, normalization);
        }

        double apply(double storedValue) {
            return (storedValue * scale + offset) * normalization;
        }
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
