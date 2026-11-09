package eu.startales.spacepixels.detection;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import eu.startales.spacepixels.fits.FitsFileInformation;

/**
 * Covers the deterministic selection contract for the auto-tune candidate pool.
 */
public class AutoTuneCandidatePoolBuilderTest {

    /** Auto-Tune always uses the full imported sequence and snapshots the array so later edits cannot alter a captured job. */
    @Test public void inputFramesAlwaysUsesFullImportedSequenceAndSnapshotsTheArray() {
        FitsFileInformation[] imported = new FitsFileInformation[8];
        FitsFileInformation[] selected = new FitsFileInformation[5];
        imported[0] = new FitsFileInformation("imported.fit", "imported.fit", true, 100, 100);
        selected[0] = new FitsFileInformation("selected.fit", "selected.fit", true, 100, 100);
        FitsFileInformation[] chosen = AutoTuneCandidatePoolBuilder.inputFrames(imported, selected);
        assertEquals(8, chosen.length);
        assertSame(imported[0], chosen[0]);
        assertNotSame(imported, chosen);
        imported[0] = null;
        assertNotNull(chosen[0]);
        assertNull(AutoTuneCandidatePoolBuilder.inputFrames(null, selected));
        assertNull(AutoTuneCandidatePoolBuilder.inputFrames(null));
    }

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
