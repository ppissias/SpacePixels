package eu.startales.spacepixels.detection;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public record IterativeDetectionPlan(int startFrames, int increaseFrames, int maximumFrames) {
    public IterativeDetectionPlan {
        if (startFrames < 5) throw new IllegalArgumentException("Start must be at least 5 frames.");
        if (increaseFrames < 1) throw new IllegalArgumentException("Increase must be at least 1 frame.");
        if (maximumFrames < 0 || maximumFrames > 0 && maximumFrames < startFrames) {
            throw new IllegalArgumentException("Maximum must be at least Start, or 0 for all available frames.");
        }
    }

    public List<Integer> frameCounts(int availableFrames) {
        int maximum = maximumFrames == 0 ? availableFrames : Math.min(maximumFrames, availableFrames);
        if (maximum < startFrames) throw new IllegalArgumentException("The dataset has fewer frames than Start.");
        List<Integer> counts = new ArrayList<>();
        for (long count = startFrames; count < maximum; count += increaseFrames) counts.add((int) count);
        counts.add(maximum);
        return List.copyOf(counts);
    }

    public static List<Integer> sampleIndices(long[] timestamps, boolean timed, int availableFrames, int count) {
        if (count < 2 || count > availableFrames || timestamps.length < availableFrames) {
            throw new IllegalArgumentException("A sample needs 2 to the available number of frames.");
        }
        if (timed && timestamps[availableFrames - 1] > timestamps[0]) {
            TreeSet<Integer> indices = new TreeSet<>();
            indices.add(0);
            indices.add(availableFrames - 1);
            for (int sample = 1; sample < count - 1; sample++) {
                double target = timestamps[0] + ((double) timestamps[availableFrames - 1] - timestamps[0]) * sample / (count - 1);
                int lower = 0;
                int upper = availableFrames - 1;
                while (lower < upper) {
                    int middle = lower + (upper - lower) / 2;
                    if (timestamps[middle] < target) lower = middle + 1;
                    else upper = middle;
                }
                if (lower > 0 && target - timestamps[lower - 1] <= timestamps[lower] - target) lower--;
                indices.add(lower);
            }
            if (indices.size() == count) return List.copyOf(indices);
        }
        List<Integer> indices = new ArrayList<>();
        for (int sample = 0; sample < count; sample++) {
            indices.add((int) Math.round(sample * (availableFrames - 1.0) / (count - 1)));
        }
        return List.copyOf(indices);
    }
}
