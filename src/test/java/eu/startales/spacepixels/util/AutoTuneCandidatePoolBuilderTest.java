package eu.startales.spacepixels.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers the deterministic selection contract for the auto-tune candidate pool.
 */
public class AutoTuneCandidatePoolBuilderTest {

    @Test
    public void returnsAllFramesInSequenceOrderWhenPoolIsAlreadySmallEnough() {
        List<AutoTuneCandidatePoolBuilder.FrameQualityRecord> frameQualityRecords = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            frameQualityRecords.add(new AutoTuneCandidatePoolBuilder.FrameQualityRecord(i, i + 1.0d));
        }

        List<Integer> selected = AutoTuneCandidatePoolBuilder.selectCandidateSequenceIndices(frameQualityRecords, 20);

        assertEquals(List.of(0, 1, 2, 3, 4), selected);
    }

    @Test
    public void buildsDeterministicSortedCandidatePoolWithoutDuplicates() {
        List<AutoTuneCandidatePoolBuilder.FrameQualityRecord> frameQualityRecords = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            frameQualityRecords.add(new AutoTuneCandidatePoolBuilder.FrameQualityRecord(i, i + 1.0d));
        }

        List<Integer> firstSelection = AutoTuneCandidatePoolBuilder.selectCandidateSequenceIndices(frameQualityRecords, 10);
        List<Integer> secondSelection = AutoTuneCandidatePoolBuilder.selectCandidateSequenceIndices(frameQualityRecords, 10);

        assertEquals(10, firstSelection.size());
        assertEquals(firstSelection, secondSelection);
        assertEquals(List.copyOf(firstSelection.stream().distinct().sorted().toList()), firstSelection);
        assertTrue(firstSelection.contains(0));
        assertTrue(firstSelection.contains(49));

        long medianBandCount = firstSelection.stream().filter(index -> index >= 20 && index <= 30).count();
        assertTrue(medianBandCount >= 3);
    }
}
