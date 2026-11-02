package eu.startales.spacepixels.report.lookup;

import com.google.gson.Gson;
import eu.startales.spacepixels.fits.*;
import eu.startales.spacepixels.report.DetectionReportAstrometry;
import io.github.ppissias.jtransient.model.*;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Report snapshot checks: precise coordinates, independent exposure timing and immutable provenance.
 */
public class StreakTrackObservationTest {
    /**
     * Captured Measurements Survive Later Track Edits And Round Trip With The Same Identity.
     */
    @Test
    public void capturedMeasurementsSurviveLaterTrackEditsAndRoundTripWithTheSameIdentity() {
        var files = files();
        var track = new Track();
        for (int i = 0; i < 2; i++) {
            var p = new DetectedObject(20 + i * 40, 30, 100, 20);
            p.sourceFrameIndex = i;
            p.elongation = 10;
            p.majorExtent = 15;
            p.angle = .1;
            track.addPoint(p);
        }
        var context = new DetectionReportAstrometry.Context(files, WcsSolutionResolver.resolve(null, files, null),
                new DetectionReportAstrometry.ObserverSite(0, 280, Double.NaN, "synthetic"), null, null, 0);
        var observation = StreakTrackObservation.capture("ST1", context, track);
        observation.validate();
        assertEquals(-80, observation.observer().longitude(), 0);
        String json = new Gson().toJson(observation), digest = observation.digest();
        track.points.getFirst().x = 1000;
        assertEquals(json, new Gson().toJson(observation));
        var copy = new Gson().fromJson(json, StreakTrackObservation.class);
        assertEquals(digest, copy.digest());
        assertEquals(2, copy.exposures().size());
        assertEquals(120, (copy.exposures().getFirst().endJd() - copy.exposures().getFirst().startJd()) * 86400, .001);
        assertNotNull(copy.exposures().getFirst().fragments().getFirst().axisStartRa());
        assertTrue(copy.html("slot", "test").contains("data-observation-digest='" + digest + "'"));
        try {
            copy.exposures().clear();
            fail("Mutable observations");
        } catch (UnsupportedOperationException expected) {
        }
        try {
            ((Map<?, ?>) copy.astrometry().get("nominalWcs")).clear();
            fail("Mutable WCS provenance");
        } catch (UnsupportedOperationException expected) {
        }
    }

    /**
     * A Declared Non Utc Time Scale Is Retained As An Unavailable Measurement.
     */
    @Test
    public void aDeclaredNonUtcTimeScaleIsRetainedAsAnUnavailableMeasurement() {
        var files = files();
        var header = new HashMap<>(files[0].getFitsHeader());
        header.put("TIMESYS", "TT");
        files[0] = files[0].withFitsHeader(header);
        var context = new DetectionReportAstrometry.Context(files, WcsSolutionResolver.resolve(null, files, null), null, null, null, 0);
        var track = new Track();
        for (int i = 0; i < 2; i++) {
            var p = new DetectedObject(20, 30, 100, 20);
            p.sourceFrameIndex = i;
            track.addPoint(p);
        }
        var observation = StreakTrackObservation.capture("ST1", context, track);
        assertEquals(1, observation.exposures().size());
        assertTrue(observation.warnings().toString().contains("TIMESYS=TT"));
    }

    /**
     * Synthetic aligned TAN frames with millisecond timing and no observing-session files.
     */
    private static FitsFileInformation[] files() {
        var out = new FitsFileInformation[2];
        for (int i = 0; i < 2; i++) {
            var h = new LinkedHashMap<String, String>();
            h.put("DATE-OBS", "2026-10-01T00:0" + (i * 3) + ":00Z");
            h.put("EXPTIME", "120");
            h.put("CTYPE1", "RA---TAN");
            h.put("CTYPE2", "DEC--TAN");
            h.put("CRPIX1", "50");
            h.put("CRPIX2", "50");
            h.put("CRVAL1", "150");
            h.put("CRVAL2", "20");
            h.put("CD1_1", "-.0004");
            h.put("CD1_2", "0");
            h.put("CD2_1", "0");
            h.put("CD2_2", ".0004");
            h.put("RADESYS", "ICRS");
            out[i] = new FitsFileInformation("frame" + i + ".fits", "frame" + i + ".fits", true, 100, 100, h);
        }
        return out;
    }
}
