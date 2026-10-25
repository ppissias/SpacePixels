package eu.startales.spacepixels.detection;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class IterativeDetectionPlanTest {
    @Test public void includesTheExactMaximumAndCapsItAtTheDatasetSize() {
        assertEquals(List.of(5, 15, 25, 32), new IterativeDetectionPlan(5, 10, 32).frameCounts(100));
        assertEquals(List.of(5, 15, 23), new IterativeDetectionPlan(5, 10, 32).frameCounts(23));
        assertEquals(List.of(5, 10, 12), new IterativeDetectionPlan(5, 5, 0).frameCounts(12));
        assertEquals(List.of(7), new IterativeDetectionPlan(7, 5, 7).frameCounts(10));
        assertEquals(List.of(5, 9), new IterativeDetectionPlan(5, Integer.MAX_VALUE, 9).frameCounts(10));
    }

    @Test public void rejectsInvalidPassPlans() {
        assertThrows(IllegalArgumentException.class, () -> new IterativeDetectionPlan(4, 5, 10));
        assertThrows(IllegalArgumentException.class, () -> new IterativeDetectionPlan(5, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new IterativeDetectionPlan(5, 5, 4));
        assertThrows(IllegalArgumentException.class, () -> new IterativeDetectionPlan(5, 5, -1));
        assertThrows(IllegalArgumentException.class, () -> new IterativeDetectionPlan(8, 2, 0).frameCounts(7));
    }

    @Test public void timeSpacingIncludesEndpointsAndFallsBackWhenSamplesCollide() {
        assertEquals(List.of(0, 2, 4), IterativeDetectionPlan.sampleIndices(new long[]{0, 1, 4, 9, 10}, true, 5, 3));
        assertEquals(List.of(0, 2, 4, 6, 8), IterativeDetectionPlan.sampleIndices(new long[9], true, 9, 5));
        assertEquals(List.of(0, 1, 3, 4), IterativeDetectionPlan.sampleIndices(new long[]{0, 1, 1, 1, 100}, true, 5, 4));
        assertEquals(List.of(0, 2, 4), IterativeDetectionPlan.sampleIndices(new long[]{0, 1, 4, 9, 10}, false, 5, 3));
    }

    @Test public void eachSizeIsSampledIndependentlyWithoutDuplicateFrames() {
        List<Integer> smaller = IterativeDetectionPlan.sampleIndices(new long[9], false, 9, 5);
        List<Integer> larger = IterativeDetectionPlan.sampleIndices(new long[9], false, 9, 6);
        assertFalse(larger.containsAll(smaller));
        assertEquals(6, larger.stream().distinct().count());
        assertEquals(List.of(0, 1, 2, 3, 4), IterativeDetectionPlan.sampleIndices(new long[5], false, 5, 5));
    }
}
