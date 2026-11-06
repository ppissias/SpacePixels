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

import org.junit.Test;

import javax.swing.*;
import java.awt.image.BufferedImage;

import static org.junit.Assert.*;

/** Checks that the stretch histogram finds the black point from the stretch's own lookup table. */
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
}
