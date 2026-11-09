/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.DisplayStretch;
import eu.startales.spacepixels.fits.StretchAlgorithm;
import org.junit.Test;

import javax.swing.*;
import java.awt.image.BufferedImage;

import static org.junit.Assert.*;

/** Checks 16-bit original and stretched histograms, registration-fill exclusion, statistics and gap-free binning. */
public class StretchHistogramTest {

    private static final int VALUES = 65536;

    /** The black point is the first value the table shows brighter than black; none when nothing is black. */
    @Test public void blackPointIsWhereTheStretchLeavesBlack() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int[] histogram = new int[VALUES];
            for (int value = 1000; value <= 3000; value++) histogram[value] = 10 + value % 7;
            byte[] table = new byte[VALUES];
            for (int value = 1500; value < VALUES; value++) table[value] = (byte) Math.min(255, value - 1499);
            StretchHistogram view = new StretchHistogram();
            view.setData(histogram, table);
            assertEquals(1500, view.blackPointValue());
            view.setSize(200, 44);
            view.paint(new BufferedImage(200, 44, BufferedImage.TYPE_INT_ARGB).createGraphics());

            byte[] bright = new byte[VALUES];
            java.util.Arrays.fill(bright, (byte) 40);
            view.setData(histogram, bright);
            assertEquals("no value is shown as black", -1, view.blackPointValue());
            view.setData(null, null);
            assertEquals(-1, view.blackPointValue());
            assertNotNull(view.getAccessibleContext());
        });
    }

    /** Registration border padding at bin 0 is excluded from original statistics and plot range. */
    @Test public void originalHistogramExcludesRegistrationFillAndComputesExactStats() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int[] histogram = new int[VALUES];
            histogram[0] = 50_000; // Registration border fill
            histogram[15_000] = 100;
            histogram[15_100] = 200;
            histogram[15_200] = 100;

            StretchHistogram original = StretchHistogram.original("Original (linear)");
            original.setOriginalData(histogram);
            StretchHistogram.Stats stats = original.stats();
            assertNotNull(stats);
            assertEquals(15_000, stats.min());
            assertEquals(15_200, stats.max());
            assertEquals(15_100, stats.median());
            assertEquals(15_100.0, stats.mean(), 1e-6);
            // Variance: (100 * 100^2 + 200 * 0^2 + 100 * 100^2) / 400 = 5000 -> stdDev = sqrt(5000) = 70.710678
            assertEquals(Math.sqrt(5000.0), stats.stdDev(), 1e-4);

            double[] columns = original.sampleColumns(100);
            assertTrue("first column starts at min valid data (15,000), not bin 0", columns[0] > 0);
            assertTrue("last column ends at max valid data (15,200)", columns[columns.length - 1] > 0);

            original.setSize(260, 110);
            original.paint(new BufferedImage(260, 110, BufferedImage.TYPE_INT_ARGB).createGraphics());
        });
    }

    /** Steep 16-bit stretches distribute adjacent ADU bins across output columns without comb gaps. */
    @Test public void stretchedHistogramDistributesSteepStepsWithoutGapsAndComputesStats() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int[] histogram = new int[VALUES];
            histogram[0] = 5_000; // Registration border fill
            for (int v = 15_350; v <= 15_450; v++) {
                histogram[v] = 1000;
            }
            short[] stretchedValues = DisplayStretch.stretchedValues(histogram, StretchAlgorithm.ASINH, 10, 50);
            byte[] table = DisplayStretch.lookupTable(histogram, StretchAlgorithm.ASINH, 10, 50);

            StretchHistogram stretched = StretchHistogram.stretched("Stretched");
            stretched.setStretchedData(histogram, stretchedValues, table);
            StretchHistogram.Stats stats = stretched.stats();
            assertNotNull(stats);
            assertEquals(0, stats.min());
            assertEquals(65_535, stats.max());
            assertTrue("black clipping is positive", stats.blackClippedPercent() > 0.0);
            assertTrue("white clipping is positive", stats.whiteClippedPercent() > 0.0);

            double[] columns = stretched.sampleColumns(200);
            int emptyInteriorColumns = 0;
            for (int col = 1; col < columns.length - 1; col++) {
                if (columns[col] == 0.0) {
                    emptyInteriorColumns++;
                }
            }
            assertEquals("no artificial comb gaps across the stretched range", 0, emptyInteriorColumns);

            stretched.setSize(260, 120);
            stretched.paint(new BufferedImage(260, 120, BufferedImage.TYPE_INT_ARGB).createGraphics());
        });
    }
}
