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
        if (kernel instanceof float[][]) {
            float[][] floatData = (float[][]) kernel;
            int height = floatData.length;
            int width = floatData[0].length;
            short[][] shortData = new short[height][width];

            float maxVal = -Float.MAX_VALUE;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (floatData[y][x] > maxVal) {
                        maxVal = floatData[y][x];
                    }
                }
            }
            float scaleFactor = (maxVal <= 10.0f && maxVal > 0.0f) ? 65535.0f : 1.0f;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    shortData[y][x] = toUnsigned16Storage(floatData[y][x] * scaleFactor);
                }
            }
            return shortData;
        } else if (kernel instanceof int[][]) {
            int[][] intData = (int[][]) kernel;
            int height = intData.length;
            int width = intData[0].length;
            short[][] shortData = new short[height][width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    shortData[y][x] = toUnsigned16Storage(intData[y][x]);
                }
            }
            return shortData;
        }
        throw new IOException("Unsupported FITS format for Mono Standardization");
    }

    static short[][][] standardizeTo16BitColor(Object kernel) throws IOException {
        if (kernel instanceof float[][][]) {
            float[][][] floatData = (float[][][]) kernel;
            int depth = floatData.length;
            int height = floatData[0].length;
            int width = floatData[0][0].length;
            short[][][] shortData = new short[depth][height][width];

            float maxVal = -Float.MAX_VALUE;
            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        if (floatData[z][y][x] > maxVal) {
                            maxVal = floatData[z][y][x];
                        }
                    }
                }
            }
            float scaleFactor = (maxVal <= 10.0f && maxVal > 0.0f) ? 65535.0f : 1.0f;

            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        shortData[z][y][x] = toUnsigned16Storage(floatData[z][y][x] * scaleFactor);
                    }
                }
            }
            return shortData;
        } else if (kernel instanceof int[][][]) {
            int[][][] intData = (int[][][]) kernel;
            int depth = intData.length;
            int height = intData[0].length;
            int width = intData[0][0].length;
            short[][][] shortData = new short[depth][height][width];
            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        shortData[z][y][x] = toUnsigned16Storage(intData[z][y][x]);
                    }
                }
            }
            return shortData;
        }
        throw new IOException("Unsupported FITS format for Color Standardization");
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

    private static short toUnsigned16Storage(float value) {
        float clamped = value;
        if (clamped < 0) {
            clamped = 0;
        }
        if (clamped > 65535) {
            clamped = 65535;
        }
        return (short) (Math.round(clamped) - 32768);
    }

    private static short toUnsigned16Storage(int value) {
        int clamped = value;
        if (clamped < 0) {
            clamped = 0;
        }
        if (clamped > 65535) {
            clamped = 65535;
        }
        return (short) (clamped - 32768);
    }
}
