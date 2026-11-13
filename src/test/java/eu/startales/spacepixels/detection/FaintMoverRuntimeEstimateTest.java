package eu.startales.spacepixels.detection;

import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import io.github.ppissias.jtransient.config.DetectionConfig;
import java.time.Instant;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Verifies the imported timing domain used by GUI conversions without changing scientific settings. */
public class FaintMoverRuntimeEstimateTest {
    /** A sparse later session must not inflate the timing span of the most populated session. */
    @Test public void choosesLargestSessionRatherThanWholeImportSpan() {
        var files = new FitsFileInformation[]{frame(0, 0), frame(30, 0), frame(60, 0), frame(240, 0), frame(241, 0)};
        var session = FaintMoverRuntimeEstimate.importedSession(files);
        assertTrue(session.hasSpan());
        assertEquals(3, session.frames());
        assertEquals(60, session.spanMinutes(), 0);
        assertEquals(30, session.halfSpanMinutes(), 0);
    }

    /** Exposure midpoints, including different exposure lengths, define the conversion span. */
    @Test public void usesMidpointsAndKeepsExactTwoHourGapsTogether() {
        var session = FaintMoverRuntimeEstimate.importedSession(new FitsFileInformation[]{frame(0, 0), frame(60, 20 * 60)});
        assertEquals(70, session.spanMinutes(), 0);
        assertEquals(35, session.halfSpanMinutes(), 0);
        assertEquals(2, FaintMoverRuntimeEstimate.importedSession(new FitsFileInformation[]{frame(0, 0), frame(120, 0)}).frames());
    }

    /** Missing/equal times leave conversions unavailable instead of inventing a session duration. */
    @Test public void unavailableTimingRemainsExplicit() {
        assertFalse(FaintMoverRuntimeEstimate.importedSession(null).hasSpan());
        var unknown = new FitsFileInformation("unknown.fit", "unknown.fit", true, 100, 100, Map.of());
        assertNotNull(FaintMoverRuntimeEstimate.importedSession(new FitsFileInformation[]{unknown}).unavailableReason());
        var equal = new FitsFileInformation[]{frame(0, 0), frame(0, 0), frame(0, 0), frame(0, 0),
                frame(0, 0), frame(0, 0), frame(0, 0), frame(0, 0)};
        assertFalse(FaintMoverRuntimeEstimate.importedSession(equal).hasSpan());
        assertTrue(FaintMoverRuntimeEstimate.describe(equal, new DetectionConfig(), Double.NaN).contains("Different capture times"));
    }

    /** Angular rates use a real WCS; missing metadata never invents a pixel scale. */
    @Test public void derivesAngularRatesFromFieldCentreWcs() {
        var header = Map.of("CRPIX1", "50", "CRPIX2", "50", "CRVAL1", "359.99", "CRVAL2", "30",
                "CTYPE1", "RA---TAN", "CTYPE2", "DEC--TAN", "CD1_1", "-0.0005555555555555556", "CD1_2", "0",
                "CD2_1", "0", "CD2_2", "0.0005555555555555556");
        var wcs = WcsCoordinateTransformer.fromHeader(header);
        assertEquals(2, FaintMoverRuntimeEstimate.angularPixelScale(wcs, 100, 100), 1e-6);
        var file = new FitsFileInformation("wcs.fit", "wcs.fit", true, 100, 100, header);
        assertEquals(2, FaintMoverRuntimeEstimate.angularPixelScale(new FitsFileInformation[]{null, file}), 1e-6);
        assertTrue(Double.isNaN(FaintMoverRuntimeEstimate.angularPixelScale((FitsFileInformation[]) null)));
        assertTrue(Double.isNaN(FaintMoverRuntimeEstimate.angularPixelScale(null, 100, 100)));
    }

    /** Creates metadata only; no image file or application settings are read or written. */
    private static FitsFileInformation frame(int minutes, int exposureSeconds) {
        var time = Instant.parse("2026-11-04T00:00:00Z").plusSeconds(minutes * 60L);
        return new FitsFileInformation("frame-" + minutes + ".fit", "frame-" + minutes + ".fit", true, 100, 100,
                Map.of("DATE-OBS", time.toString(), "EXPTIME", Integer.toString(exposureSeconds)));
    }
}
