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
    public void buildsStellariumAltAzUrlWhenObserverSiteAndEpochAreAvailable() {
        long timestampMillis = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli();
        DetectionReportAstrometry.ObserverSite observerSite =
                new DetectionReportAstrometry.ObserverSite(37.9838, 23.7275, 120.0, "Test Site");
        DetectionReportAstrometry.HorizontalCoordinate coordinate =
                DetectionReportAstrometry.resolveHorizontalCoordinate(observerSite, timestampMillis, 150.0, -10.5);

        String url = DetectionReportAstrometry.buildStellariumWebAltAzUrl(
                observerSite,
                timestampMillis,
                coordinate,
                3.5);

        assertNotNull(url);
        assertTrue(url.startsWith("https://stellarium-web.org/?date=2026-04-01T00%3A00%3A00Z"));
        assertTrue(url.contains("&alt="));
        assertTrue(url.contains("&az="));
        assertTrue(url.contains("&fov=3.5000"));
        assertTrue(url.contains("&lat=37.98380"));
        assertTrue(url.contains("&lng=23.72750"));
        assertTrue(url.contains("&elev=120.0"));
    }

    @Test
    public void doesNotBuildStellariumAltAzUrlWithoutObserverSite() {
        long timestampMillis = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli();
        DetectionReportAstrometry.ObserverSite observerSite =
                new DetectionReportAstrometry.ObserverSite(37.9838, 23.7275, 120.0, "Test Site");
        DetectionReportAstrometry.HorizontalCoordinate coordinate =
                DetectionReportAstrometry.resolveHorizontalCoordinate(observerSite, timestampMillis, 150.0, -10.5);

        assertNull(DetectionReportAstrometry.buildStellariumWebAltAzUrl(
                null,
                timestampMillis,
                coordinate,
                3.5));
    }

    @Test
    public void buildsSatCheckerFovUrlForSynchronousTightCandidateLookup() {
        long midpointTimestampMillis = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli();
        DetectionReportAstrometry.ObserverSite observerSite =
                new DetectionReportAstrometry.ObserverSite(37.9838, 289.5970, 120.0, "Test Site");

        String url = DetectionReportAstrometry.buildSatCheckerFovUrl(
                observerSite,
                midpointTimestampMillis,
                37L,
                150.0,
                -10.5,
                2.25,
                false);

        assertNotNull(url);
        assertTrue(url.startsWith("https://satchecker.cps.iau.org/fov/satellite-passes/?"));
        assertTrue(url.contains("latitude=37.983800"));
        assertTrue(url.contains("longitude=-70.403000"));
        assertTrue(url.contains("elevation=120.0"));
        assertTrue(url.contains("mid_obs_time_jd="));
        assertTrue(url.contains("&duration=37"));
        assertTrue(url.contains("&ra=150.000000"));
        assertTrue(url.contains("&dec=-10.500000"));
        assertTrue(url.contains("&fov_radius=2.2500"));
        assertTrue(url.contains("&group_by=satellite"));
        assertTrue(url.contains("&data_source=any"));
        assertTrue(url.contains("&async=False"));
        assertFalse(url.contains("start_time_jd="));
        assertFalse(url.contains("include_tles"));
    }

    @Test
    public void rejectsSatCheckerFovUrlWithoutRequiredInputs() {
        long midpointTimestampMillis = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli();
        DetectionReportAstrometry.ObserverSite observerSite =
                new DetectionReportAstrometry.ObserverSite(37.9838, 23.7275, 120.0, "Test Site");

        assertNull(DetectionReportAstrometry.buildSatCheckerFovUrl(
                null,
                midpointTimestampMillis,
                30L,
                150.0,
                -10.5,
                2.25,
                false));
        assertNull(DetectionReportAstrometry.buildSatCheckerFovUrl(
                observerSite,
                -1L,
                30L,
                150.0,
                -10.5,
                2.25,
                false));
        assertNull(DetectionReportAstrometry.buildSatCheckerFovUrl(
                observerSite,
                midpointTimestampMillis,
                0L,
                150.0,
                -10.5,
                2.25,
                false));
    }

    @Test
    public void includesSidecarMetadataOnLiveRenderButtons() {
        String html = DetectionReportAstrometry.buildLiveRenderButtonHtml(
                "jpl",
                "https://ssd-api.jpl.nasa.gov/sb_ident.api?foo=bar",
                "moving-track-jpl-2",
                "Render JPL Results Here",
                "jpl_track_02_exact.json",
                "JPL Exact FOV Results");

        assertTrue(html.contains("data-provider='jpl'"));
        assertTrue(html.contains("data-slot-id='moving-track-jpl-2'"));
        assertTrue(html.contains("data-sidecar-file='jpl_track_02_exact.json'"));
        assertTrue(html.contains("data-render-title='JPL Exact FOV Results'"));
    }

    @Test
    public void normalizesSidecarFileNamesToJsonExtension() {
        String html = DetectionReportAstrometry.buildLiveRenderButtonHtml(
                "satchecker",
                "https://satchecker.cps.iau.org/fov/satellite-passes/?foo=bar",
                "streak-track-satchecker-1",
                "Render SatChecker Results Here",
                "satchecker_track_01",
                "SatChecker Tight Candidate Results");

        assertTrue(html.contains("data-sidecar-file='satchecker_track_01.json'"));
    }

    @Test
    public void flagsSiderealLikeRaDriftAsPotentialGeo() {
        assertTrue(DetectionReportAstrometry.isNearSiderealRaRate(54148.0));
        assertNotNull(DetectionReportAstrometry.classifyTrackMotionByRaRate(54148.0));
        assertTrue(DetectionReportAstrometry.classifyTrackMotionByRaRate(54148.0).contains("GEO-like"));
    }

    @Test
    public void doesNotFlagNonSiderealRaDriftAsPotentialGeo() {
        assertFalse(DetectionReportAstrometry.isNearSiderealRaRate(25000.0));
        assertNull(DetectionReportAstrometry.classifyTrackMotionByRaRate(25000.0));
    }

    @Test
    public void formatsApparentSkySpeedInHumanFriendlyUnits() {
        assertEquals("15.04 deg/h (15.04 arcsec/s)", DetectionReportAstrometry.formatApparentSkySpeed(54148.0));
        assertEquals("n/a", DetectionReportAstrometry.formatApparentSkySpeed(Double.NaN));
    }

    @Test
    public void doesNotFormatAltAzSummaryWithoutObserverSite() {
        long timestampMillis = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli();

        assertNull(DetectionReportAstrometry.formatHorizontalCoordinateSummary(null, timestampMillis, 150.0, -10.5));
    }
}
