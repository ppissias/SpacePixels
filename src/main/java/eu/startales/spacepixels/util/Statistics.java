/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Medians used by the report, the sky-catalogue correction and the star measurements.
 *
 * <p>They come in variants that differ in how they treat an even count (the mean of the two middle values, or the
 * upper one), NaN values and empty input. Each keeps its exact behaviour so that the reports stay byte-identical, and
 * its name says how it differs. Whether they need to differ is an open question (DESIGN-IMPROVEMENTS.md, SP-10).
 * Several algorithms also take an upper-middle value inline from data they have already sorted.</p>
 */
public final class Statistics {

    private Statistics() {
    }

    /**
     * The middle value of a sorted copy, or the mean of the two middle values for an even count; NaN when empty.
     * NaN values are counted and sort last.
     */
    public static double median(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /** As {@link #median}, over the values that are not NaN; NaN when there are none. The array is not changed. */
    public static double medianIgnoringNaN(double[] values) {
        double[] sorted = Arrays.stream(values).filter(v -> !Double.isNaN(v)).sorted().toArray();
        if (sorted.length == 0) return Double.NaN;
        return sorted.length % 2 == 1 ? sorted[sorted.length / 2] : 0.5 * (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]);
    }

    /**
     * The middle value of values already sorted ascending, or the mean of the two middle values for an even count.
     * The array must not be empty.
     */
    public static double medianOfSorted(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    /** The upper of the two middle values of a sorted copy (the middle one for an odd count); {@code Double.MAX_VALUE} when empty. */
    public static double upperMedian(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.isEmpty() ? Double.MAX_VALUE : sorted.get(sorted.size() / 2);
    }

    /** The upper of the two middle values that are not NaN (the middle one for an odd count); NaN when there are none. */
    public static double upperMedianIgnoringNaN(List<Double> values) {
        List<Double> sorted = new ArrayList<>();
        for (Double value : values) {
            if (!Double.isNaN(value)) {
                sorted.add(value);
            }
        }
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }
}
