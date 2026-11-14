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

    /** The admitted job keeps the sequence captured at the start, including after the caller replaces an entry. */
    @Test public void inputFramesSnapshotsTheImportedSequence() {
        FitsFileInformation[] imported = new FitsFileInformation[8];
        imported[0] = new FitsFileInformation("imported.fit", "imported.fit", true, 100, 100);
        FitsFileInformation[] chosen = AutoTuneCandidatePoolBuilder.inputFrames(imported);
        assertEquals(8, chosen.length);
        assertSame(imported[0], chosen[0]);
        assertNotSame(imported, chosen);
        imported[0] = null;
        assertNotNull(chosen[0]);
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

    /**
     * With more frames than the pool limit, the pool comes from the engine's quality control over the whole session:
     * a frame it rejects (here a blank one) is never a candidate, though it would otherwise fill the pool.
     */
    @Test
    public void framesThatQualityControlRejectsStayOutOfThePool() throws Exception {
        org.junit.rules.TemporaryFolder folder = new org.junit.rules.TemporaryFolder();
        folder.create();
        try {
            java.nio.file.Path directory = eu.startales.spacepixels.testsupport.SyntheticDatasetFactory
                    .createMono16FitsSequence(folder.newFolder("frames").toPath(), "session", 7);
            java.nio.file.Path blankFile = directory.resolve("frame_03.fit");
            short[][] blank = new short[96][96];
            for (short[] row : blank) java.util.Arrays.fill(row, Short.MIN_VALUE);
            var hdu = nom.tam.fits.Fits.makeHDU(blank);
            hdu.getHeader().addValue("BZERO", 32768, null);
            hdu.getHeader().addValue("BSCALE", 1, null);
            hdu.getHeader().addValue("DATE-OBS", "2026-04-08T00:00:02", null);
            hdu.getHeader().addValue("EXPTIME", 30.0, null);
            java.nio.file.Files.delete(blankFile);
            try (var fits = new nom.tam.fits.Fits()) { fits.addHDU(hdu); fits.write(blankFile.toFile()); }
            FitsFileInformation[] files;
            try (var importer = new eu.startales.spacepixels.fits.FitsImportService(directory.toFile(), null)) {
                files = importer.readFiles();
            }
            assertEquals(7, files.length);
            var config = new io.github.ppissias.jtransient.config.DetectionConfig();
            var verdicts = new QualityControlService().evaluate(files, config, null).result();
            assertTrue("quality control rejects the blank frame", verdicts.frames().get(2).rejected());
            List<io.github.ppissias.jtransient.engine.ImageFrame> pool = AutoTuneCandidatePoolBuilder.buildCandidatePool(
                    files, config, 6, (percent, message) -> { });
            List<Integer> chosen = pool.stream().map(frame -> frame.sequenceIndex).toList();
            assertFalse("the rejected frame is not in the pool: " + chosen, chosen.contains(2));
            for (int index : chosen) assertFalse(verdicts.frames().get(index).rejected());
            assertEquals(verdicts.keptFrames(), chosen.size());
        } finally {
            folder.delete();
        }
    }
}
