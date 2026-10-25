package eu.startales.spacepixels.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Pins the contract of the median helpers in {@link Statistics}. */
public class StatisticsTest {
    private static final double NAN = Double.NaN;

    @Test
    public void medianAveragesTheMiddlePairSkipsNaNAndLeavesItsInputAlone() {
        List<Double> values = new ArrayList<>(List.of(4.0, 1.0, 3.0, 2.0));
        assertEquals(2.5, Statistics.median(values), 0);
        assertEquals(List.of(4.0, 1.0, 3.0, 2.0), values);
        assertEquals(2.0, Statistics.median(List.of(3.0, 1.0, 2.0)), 0);
        assertEquals("NaN is skipped", 1.5, Statistics.median(List.of(1.0, NAN, 2.0)), 0);
        assertTrue(Double.isNaN(Statistics.median(List.of(NAN))));
        assertTrue(Double.isNaN(Statistics.median(List.of())));
    }

    @Test
    public void medianIgnoringNaNSkipsNaNAndLeavesTheArrayAlone() {
        double[] values = {1.0, NAN, 2.0};
        assertEquals(1.5, Statistics.medianIgnoringNaN(values), 0);
        assertArrayEquals(new double[]{1.0, NAN, 2.0}, values, 0);
        assertTrue(Double.isNaN(Statistics.medianIgnoringNaN(new double[]{NAN})));
        assertTrue(Double.isNaN(Statistics.medianIgnoringNaN(new double[0])));
    }

    @Test
    public void medianOfSortedTrustsTheOrderAndNeedsAValue() {
        assertEquals(2.5, Statistics.medianOfSorted(new double[]{1, 2, 3, 4}), 0);
        assertEquals(2.0, Statistics.medianOfSorted(new double[]{1, 2, 3}), 0);
        assertEquals("an unsorted array is not sorted first", 9.5, Statistics.medianOfSorted(new double[]{1, 9, 10, 2}), 0);
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> Statistics.medianOfSorted(new double[0]));
    }

    @Test
    public void upperMedianTakesTheUpperMiddleNonNaNValue() {
        assertEquals(3.0, Statistics.upperMedian(List.of(4.0, 1.0, 3.0, 2.0)), 0);
        assertEquals(2.0, Statistics.upperMedian(List.of(3.0, 1.0, 2.0)), 0);
        assertEquals(3.0, Statistics.upperMedian(List.of(4.0, NAN, 1.0, 3.0, 2.0)), 0);
        assertTrue(Double.isNaN(Statistics.upperMedian(List.of())));
        assertTrue(Double.isNaN(Statistics.upperMedian(List.of(NAN))));
    }
}
