package eu.startales.spacepixels.util;

import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DetectionReportAstrometryTest {

    @Test
    public void prefersRealObservatoryCodeForJplQueries() {
        long timestampMillis = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli();

        String url = DetectionReportAstrometry.buildJplSbIdentUrl(
                "J95",
                37.9838,
                289.5970,
                120.0,
                timestampMillis,
                150.0,
                -10.5,
                0.25);

        assertNotNull(url);
        assertTrue(url.contains("mpc-code=J95"));
        assertFalse(url.contains("lat="));
        assertFalse(url.contains("lon="));
        assertTrue(url.contains("obs-time=2026-04-01_00%3A00%3A00"));
        assertTrue(url.contains("fov-ra-center=10-00-00.00"));
        assertTrue(url.contains("fov-dec-center=M10-30-00.00"));
    }

    @Test
    public void fallsBackToNormalizedCoordinatesWhenOnlyGeocenterCodeExists() {
        long timestampMillis = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli();

        String url = DetectionReportAstrometry.buildJplSbIdentUrl(
                "500",
                37.9838,
                289.5970,
                120.0,
                timestampMillis,
                0.0,
                20.0,
                0.25);

        assertNotNull(url);
        assertFalse(url.contains("mpc-code=500"));
        assertTrue(url.contains("lat=37.983800"));
        assertTrue(url.contains("lon=-70.403000"));
        assertTrue(url.contains("alt=0.1200"));
    }

    @Test
    public void rejectsGeocenterOnlyObserverCodeForJplQueries() {
        long timestampMillis = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli();

        assertFalse(DetectionReportAstrometry.isJplCompatibleObservatoryCode("500"));
        assertTrue(DetectionReportAstrometry.isJplCompatibleObservatoryCode("J95"));
        assertNull(DetectionReportAstrometry.buildJplSbIdentUrl(
                "500",
                null,
                null,
                null,
                timestampMillis,
                15.0,
                20.0,
                0.25));
    }

    @Test
    public void normalizesLongitudeIntoJplAcceptedRange() {
        assertEquals(-70.403, DetectionReportAstrometry.normalizeLongitudeDegrees(289.597), 1.0e-9);
        assertEquals(170.0, DetectionReportAstrometry.normalizeLongitudeDegrees(-190.0), 1.0e-9);
    }

    @Test
    public void canBuildNeoRecoveryJplQuery() {
        long timestampMillis = Instant.parse("2021-03-06T19:03:09Z").toEpochMilli();

        String url = DetectionReportAstrometry.buildJplSbIdentUrl(
                null,
                49.625833,
                8.753056,
                180.0,
                timestampMillis,
                140.851218,
                -7.037788,
                5.0,
                "neo");

        assertNotNull(url);
        assertTrue(url.contains("sb-group=neo"));
        assertTrue(url.contains("fov-dec-hwidth=5.0000"));
        assertTrue(url.contains("lat=49.625833"));
        assertTrue(url.contains("lon=8.753056"));
    }

    @Test
    public void formatsAltAzSummaryWhenObserverSiteAndEpochAreAvailable() {
        long timestampMillis = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli();
        DetectionReportAstrometry.ObserverSite observerSite =
                new DetectionReportAstrometry.ObserverSite(37.9838, 23.7275, 120.0, "Test Site");

        String summary = DetectionReportAstrometry.formatHorizontalCoordinateSummary(
                observerSite,
                timestampMillis,
                150.0,
                -10.5);

        assertNotNull(summary);
        assertTrue(summary.startsWith("Alt / Az at observer site: Alt "));
        assertTrue(summary.contains(", Az "));
        assertTrue(summary.endsWith("°."));

        DetectionReportAstrometry.HorizontalCoordinate coordinate =
                DetectionReportAstrometry.resolveHorizontalCoordinate(observerSite, timestampMillis, 150.0, -10.5);
        assertNotNull(coordinate);
        assertTrue(coordinate.altitudeDeg >= -90.0 && coordinate.altitudeDeg <= 90.0);
        assertTrue(coordinate.azimuthDeg >= 0.0 && coordinate.azimuthDeg <= 360.0);
    }

    @Test
    public void doesNotFormatAltAzSummaryWithoutObserverSite() {
        long timestampMillis = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli();

        assertNull(DetectionReportAstrometry.formatHorizontalCoordinateSummary(null, timestampMillis, 150.0, -10.5));
    }
}
