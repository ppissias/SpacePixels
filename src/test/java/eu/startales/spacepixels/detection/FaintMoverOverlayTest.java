package eu.startales.spacepixels.detection;

import com.google.gson.Gson;
import eu.startales.spacepixels.fits.FitsFileInformation;
import io.github.ppissias.jtransient.core.FaintMoverCandidate;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.telemetry.FaintMoverSearchTelemetry;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** Checks that viewers retain only fitted values and draw them on the search's original frame identities. */
public class FaintMoverOverlayTest {
    /** A duplicate basename, rejected frame or other image size never inherits a fitted path. */
    @Test public void onlySearchedFullIdentitiesHaveMidpointPositions() {
        var files = new FitsFileInformation[]{file("searched/a.fit", 100), file("searched/b.fit", 100)};
        var t = new FaintMoverSearchTelemetry(); t.searchedFrameIndices = List.of(0);
        var telemetry = new PipelineTelemetry(); telemetry.faintMoverSearchTelemetry = t;
        long midpoint = files[0].getObservationTimestamp() + 60_000;
        var candidate = new Gson().fromJson("{\"x\":50,\"y\":40,\"vx\":0.4,\"vy\":-0.2,\"referenceTimeMillis\":"
                + midpoint + ",\"cutout\":[[12]]}", FaintMoverCandidate.class);
        var overlay = FaintMoverOverlay.from(PipelineResult.builder().telemetry(telemetry).faintMoverCandidates(List.of(candidate)).build(), files);
        assertEquals(Long.valueOf(midpoint), overlay.midpoint(files[0]));
        assertNull(overlay.midpoint(files[1]));
        assertNull(overlay.midpoint(file("other/a.fit", 100)));
        assertNull(overlay.midpoint(file("searched/a.fit", 200)));
        assertEquals(List.of(midpoint), overlay.times());
        assertEquals("FM1", overlay.candidates().getFirst().id());
        assertArrayEquals(new double[]{50.4, 39.8}, overlay.candidates().getFirst().positionAt(midpoint + 60_000), 1e-9);
        candidate.cutout[0][0] = 99;
        t.searchedFrameIndices = List.of(1);
        assertNotNull(overlay.midpoint(files[0]));
        assertNull(overlay.midpoint(files[1]));
        assertTrue(overlay.coversAnyOf(files));
        assertThrows(UnsupportedOperationException.class, () -> overlay.candidates().clear());
        t.skippedReason = "Not enough frames";
        assertFalse(FaintMoverOverlay.from(PipelineResult.builder().telemetry(telemetry).build(), files).coversAnyOf(files));
    }

    /** Estimates explain missing metadata and account for cadence and the squared maximum-motion cost. */
    @Test public void runtimeEstimateUsesMetadataAndRemainsExplicitlyApproximate() {
        var config = new io.github.ppissias.jtransient.config.DetectionConfig();
        assertTrue(FaintMoverRuntimeEstimate.describe(null, config, Double.NaN).contains("Import frames"));
        assertTrue(FaintMoverRuntimeEstimate.describe(new FitsFileInformation[]{file("a.fit", 100)}, config, Double.NaN).contains("8 kept"));
        FitsFileInformation[] files = new FitsFileInformation[8];
        for (int i = 0; i < files.length; i++) files[i] = new FitsFileInformation("f" + i + ".fit", "f" + i + ".fit", true, 8000, 6000,
                Map.of("DATE-OBS", "2026-10-01T00:" + String.format("%02d", i * 5) + ":00", "EXPTIME", "120"));
        String slow = FaintMoverRuntimeEstimate.describe(files, config, Double.NaN);
        config.faintMoverMaxSpeedPixelsPerMinute = 3;
        String fast = FaintMoverRuntimeEstimate.describe(files, config, Double.NaN);
        assertNotEquals(slow, fast);
        assertTrue(fast.contains("assumed 2.5 px star size"));
        assertTrue(fast.contains("MiB per search worker"));
        assertTrue(fast.contains("Approximate extra time: Normal "));
        assertTrue(fast.contains(" · Fast ") && fast.contains(" · Faster "));
        assertTrue(fast.contains("Selected: Fast"));
        // 2.5 px stars: Faster bins 2 x 2 pixels, Fast does not; 5 min apart is too far to add frames at 3 px/min.
        assertTrue(fast.contains("Faster ") && fast.contains("2×2 pixels in the first pass"));
        assertFalse(fast.contains("of 8 frames"));
        // A measured 5 px star size bins at Fast too and is named as measured.
        String measured = FaintMoverRuntimeEstimate.describe(files, config, 5.0);
        assertTrue(measured.contains("5.00 px the last detection run measured"));
        assertTrue(measured.indexOf("2×2 pixels") < measured.indexOf(" · Faster "));
    }

    /** Returns small immutable metadata with a two-minute exposure, whose midpoint differs from its start. */
    private static FitsFileInformation file(String path, int size) {
        return new FitsFileInformation(path, new java.io.File(path).getName(), true, size, size,
                Map.of("DATE-OBS", "2026-10-01T00:00:00", "EXPTIME", "120"));
    }
}
