package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import eu.startales.spacepixels.util.FitsFileInformation;
import org.junit.Test;
import java.time.Instant;
import static org.junit.Assert.*;

public class ReportingConsistencyTest {
    @Test public void skyLabelsAndLookupCoordinatesCarryRoundedSeconds() {
        assertEquals("10:00:00.0", eu.startales.spacepixels.util.WcsCoordinateTransformer.formatRa(149.9999999));
        assertEquals("00:00:00.0", eu.startales.spacepixels.util.WcsCoordinateTransformer.formatRa(359.9999999));
        assertEquals("-21:00:00.0", eu.startales.spacepixels.util.WcsCoordinateTransformer.formatDec(-20.9999999));
        String url = JplSbIdentUrlBuilder.build("568", null, null, null, Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), 149.9999999, -20.9999999, .1, null);
        assertTrue(url, url.contains("fov-ra-center=10-00-00.00"));
        assertTrue(url, url.contains("fov-dec-center=M21-00-00.00"));
    }
    private static FitsFileInformation[] files() {
        FitsFileInformation[] files = new FitsFileInformation[3];
        for (int i = 0; i < files.length; i++) {
            files[i] = new FitsFileInformation("frame" + i + ".fit", "frame" + i + ".fit", true, 600, 400);
            files[i].getFitsHeader().put("DATE-OBS", "2026-10-01T00:0" + (2 * i) + ":00");
            files[i].getFitsHeader().put("EXPTIME", "120");
        }
        return files;
    }

    private static Track track(FitsFileInformation[] files) {
        Track track = new Track();
        for (int i = 0; i < files.length; i++) {
            DetectedObject point = new DetectedObject(100 + 10 * i, 100, 500, 10);
            point.sourceFrameIndex = i;
            point.timestamp = files[i].getObservationTimestamp();
            point.exposureDuration = files[i].getExposureDurationMillis();
            track.addPoint(point);
        }
        return track;
    }

    @Test public void lookupUsesTheEpochOfTheMeasuredCentroids() {
        FitsFileInformation[] files = files();
        var context = DetectionReportAstrometry.buildContext(files, null);
        var target = SolarSystemQueryTargetFactory.buildTrackQueryTarget(context, track(files));
        assertEquals(Instant.parse("2026-10-01T00:03:00Z").toEpochMilli(), target.timestampMillis);
    }

    @Test public void pixelSpeedUsesElapsedTimeBetweenExposureMidpoints() {
        FitsFileInformation[] files = files();
        String html = DetectionReportGenerator.buildTrackTimingSummaryHtml(track(files),
                DetectionReportAstrometry.buildContext(files, null));
        assertTrue(html, html.contains("5.00 px/min"));
        assertTrue("Keep the full observing span separate from motion time", html.contains("6.0 min"));
    }
}
