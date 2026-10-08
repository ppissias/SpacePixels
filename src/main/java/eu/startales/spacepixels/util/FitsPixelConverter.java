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

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.List;

/**
 * Converts FITS pixel kernels into SpacePixels' normalized 16-bit formats.
 */
final class FitsPixelConverter {

    private FitsPixelConverter() {
    }

    static Fits createFitsFromData(Object newData, Header originalHeader) throws FitsException, IOException {
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

            if (key != null && !structuralKeys.contains(key) && !newHeader.containsKey(key)) {
                newHeader.addLine(card);
            }
        }

        // Preserve SpacePixels' unsigned-16-bit interpretation for stored short arrays.
        newHeader.addValue("BZERO", 32768.0, "offset data range to that of unsigned short");
        newHeader.addValue("BSCALE", 1.0, "default scaling factor");

        return updatedFits;
    }

    static short[][] standardizeTo16BitMono(Object kernel) throws IOException {
        return standardizeTo16BitMono(kernel, null);
    }

    static short[][] standardizeTo16BitMono(Object kernel, Header header) throws IOException {
        if (!(kernel instanceof short[][] || kernel instanceof int[][] || kernel instanceof float[][])) {
            throw new IOException("Unsupported FITS format for Mono Standardization");
        }
        return standardizePlanes(new Object[]{kernel}, kernel instanceof float[][], header)[0];
    }

    static short[][][] standardizeTo16BitColor(Object kernel) throws IOException {
        return standardizeTo16BitColor(kernel, null);
    }

    static short[][][] standardizeTo16BitColor(Object kernel, Header header) throws IOException {
        if (!(kernel instanceof short[][][] || kernel instanceof int[][][] || kernel instanceof float[][][])) {
            throw new IOException("Unsupported FITS format for Color Standardization");
        }
        return standardizePlanes((Object[]) kernel, kernel instanceof float[][][], header);
    }

    private static short[][][] standardizePlanes(Object[] planes, boolean floatingPoint, Header header) throws IOException {
        double offset = header == null ? 0.0 : header.getDoubleValue("BZERO", 0.0);
        double scale = header == null ? 1.0 : header.getDoubleValue("BSCALE", 1.0);
        if (!Double.isFinite(offset) || !Double.isFinite(scale)) {
            throw new IOException("FITS BZERO and BSCALE must be finite.");
        }
        Object[] firstPlane = (Object[]) planes[0];
        int height = firstPlane.length;
        int width = Array.getLength(firstPlane[0]);
        double maximum = Double.NEGATIVE_INFINITY;
        if (floatingPoint) {
            for (Object plane : planes) {
                for (Object row : (Object[]) plane) {
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
        short[][][] result = new short[planes.length][height][width];
        for (int channel = 0; channel < planes.length; channel++) {
            Object[] rows = (Object[]) planes[channel];
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    double physicalValue = Array.getDouble(rows[row], column) * scale + offset;
                    result[channel][row][column] = toUnsigned16Storage(physicalValue * normalization);
                }
            }
        }
        return result;
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
