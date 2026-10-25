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
 * <p>{@link #median} and {@link #medianIgnoringNaN} take the mean of the two middle values for an even count;
 * {@link #upperMedian} takes the upper-middle value ({@code n / 2}), for callers that select a single middle sample
 * or order statistic. All methods ignore {@code NaN} values and return {@code NaN} when no finite/non-NaN values
 * remain, except {@link #medianOfSorted} which operates directly on a non-empty pre-sorted array.</p>
 */
public final class Statistics {

    private Statistics() {
    }

    /**
     * The middle value over the values that are not NaN, or the mean of the two middle values for an even count;
     * NaN when there are none. The list is not changed.
     */
    public static double median(List<Double> values) {
        List<Double> sorted = sortedNonNaN(values);
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /** As {@link #median}, over a {@code double[]} array; NaN when there are no non-NaN values. The array is not changed. */
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

    /**
     * The upper of the two middle values that are not NaN (the middle one for an odd count); NaN when there are none.
     * The list is not changed.
     */
    public static double upperMedian(List<Double> values) {
        List<Double> sorted = sortedNonNaN(values);
        return sorted.isEmpty() ? Double.NaN : sorted.get(sorted.size() / 2);
    }

    private static List<Double> sortedNonNaN(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values.size());
        for (Double value : values) {
            if (value != null && !Double.isNaN(value)) {
                sorted.add(value);
            }
        }
        Collections.sort(sorted);
        return sorted;
    }
}
