package eu.startales.spacepixels.mpc;

import io.github.ppissias.jtransient.core.SubStackAstrometry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Round-trip and robustness tests for {@link SavedMeasurements}.
 */
public class SavedMeasurementsTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    /**
     * Sub-stacks, observation selections and light curves (including NaN offsets) survive save and load.
     */
    @Test
    public void roundTripsSubStacksSelectionsAndLightCurves() throws Exception {
        File reportDir = folder.newFolder("detections_1");

        var m1 = new SubStackAstrometry.Measurement(0, 3, 4, 1_700_000_000_000L, 480_000L, 120.5, 240.25, 8.4, null);
        var m2 = new SubStackAstrometry.Measurement(4, 7, 4, 1_700_000_600_000L, 480_000L, 123.1, 242.0, 3.2, "SNR 3.2 is below 5.0");
        var o1 = new MpcObservation(2, m1.midpointMillis(), m1.exposureMillis(), m1.x(), m1.y(), 83.5, -5.2, 0.12, 0.14, "Gaia3", "K");
        var o2 = new MpcObservation(6, m2.midpointMillis(), m2.exposureMillis(), m2.x(), m2.y(), 83.6, -5.19, 0.25, 0.26, "Gaia3", "K");

        var fmEntry = new SavedMeasurements.Entry();
        fmEntry.id = "FM1";
        fmEntry.subStacks = SavedMeasurements.pairSubStacks(List.of(m1, m2), List.of(o1, o2));
        fmEntry.selectedIndices = List.of(0);

        var curve = new LightCurve.Curve("T1", true, 2.4, 3.6, 12, true, null, List.of(
                new LightCurve.Point(1, 1_700_000_000_000L, 100.0, 200.0, 1500.0, 35.0, -0.12, 0),
                new LightCurve.Point(2, 1_700_000_120_000L, 101.0, 201.0, 1450.0, 40.0, Double.NaN, 1)));
        var trackEntry = new SavedMeasurements.Entry();
        trackEntry.id = "T1";
        trackEntry.selectedIndices = List.of(0, 2, 5);
        trackEntry.lightCurve = SavedMeasurements.SavedCurve.from(curve);

        var toSave = new LinkedHashMap<String, SavedMeasurements.Entry>();
        toSave.put(fmEntry.id, fmEntry);
        toSave.put(trackEntry.id, trackEntry);
        SavedMeasurements.save(reportDir, toSave);

        File file = new File(reportDir, SavedMeasurements.FILE_NAME);
        assertTrue(file.isFile());
        String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertFalse("Strict JSON must not contain bare NaN", raw.contains("NaN"));

        var loaded = SavedMeasurements.load(reportDir);
        assertEquals(2, loaded.size());

        var restoredFm = loaded.get("FM1");
        assertNotNull(restoredFm);
        var stacks = restoredFm.restoredSubStacks();
        assertEquals(2, stacks.size());
        assertTrue(stacks.get(0).clear());
        assertFalse(stacks.get(1).clear());
        assertEquals("SNR 3.2 is below 5.0", stacks.get(1).problem());
        assertEquals(8.4, stacks.get(0).snr(), 1e-6);

        var obs = restoredFm.restoredSubStackObservations();
        assertEquals(2, obs.size());
        assertEquals(o1, obs.get(0));
        assertEquals(o2, obs.get(1));
        assertEquals(Set.of(0), restoredFm.restoredSelection(obs.size()));
        assertEquals(List.of(o1), SavedMeasurements.selectedOrDefault(obs, restoredFm));
        assertEquals(obs, SavedMeasurements.selectedOrDefault(obs, null));

        var restoredTrack = loaded.get("T1");
        assertNotNull(restoredTrack);
        assertEquals("Out-of-bounds indices are dropped safely", Set.of(0, 2), restoredTrack.restoredSelection(4));
        var restoredCurve = restoredTrack.lightCurve.toCurve();
        assertEquals("T1", restoredCurve.id());
        assertEquals(12, restoredCurve.comparisonStars());
        assertEquals(2, restoredCurve.points().size());
        assertEquals(-0.12, restoredCurve.points().get(0).offset(), 1e-9);
        assertTrue(Double.isNaN(restoredCurve.points().get(1).offset()));
    }

    /**
     * Unreadable files return empty and saving an empty map deletes the sidecar file.
     */
    @Test
    public void corruptFileIsIgnoredAndEmptySaveRemovesFile() throws Exception {
        File reportDir = folder.newFolder("detections_2");
        File file = new File(reportDir, SavedMeasurements.FILE_NAME);
        Files.writeString(file.toPath(), "{ broken json", StandardCharsets.UTF_8);
        assertTrue(SavedMeasurements.load(reportDir).isEmpty());

        SavedMeasurements.save(reportDir, new LinkedHashMap<>());
        assertFalse(file.exists());
    }
}
