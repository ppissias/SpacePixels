package eu.startales.spacepixels.fits;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class FitsFileInformationTest {

    @Test
    public void getLocationUsesObsGeoFallbackHeadersWhenSiteLatLonAreMissing() {
        FitsFileInformation fileInformation = new FitsFileInformation("sample.fit", "sample.fit", true, 100, 100,
                Map.of("OBSGEO-B", "36.97166667", "OBSGEO-L", "22.23138889"));

        assertEquals("36.97166667 / 22.23138889", fileInformation.getLocation());
    }

    @Test
    public void getLocationFallsBackToObservatoryNameWhenCoordinatesAreUnavailable() {
        FitsFileInformation fileInformation = new FitsFileInformation("sample.fit", "sample.fit", true, 100, 100,
                Map.of("OBSERVAT", "'Hellas Sky'"));

        assertEquals("Hellas Sky", fileInformation.getLocation());
    }

    /** Frames of the Zwicky Transient Facility have no DATE-OBS or SITELAT; their own cards give the time and site. */
    @Test
    public void readsTheTimeAndSiteOfFramesWithoutDateObs() {
        Map<String, String> ztf = new HashMap<>(Map.of("SHUTOPEN", "'2024-01-08T02:49:37.926'", "OBSMJD", "60317.1177894",
                "OBSLAT", "33.3573", "OBSLON", "-116.8598", "OBSALT", "1668.0", "OBSERVAT", "'Palomar Observatory'"));
        FitsFileInformation frame = new FitsFileInformation("z.fits", "z.fits", true, 3072, 3080, ztf);
        assertEquals(Instant.parse("2024-01-08T02:49:37.926Z").toEpochMilli(), frame.getObservationTimestamp());
        assertEquals("2024-01-08 02:49:37.926 UTC", frame.getObservationDate());
        assertTrue(frame.getObservationTimestampDiagnostics().contains("SHUTOPEN"));
        assertEquals("33.3573 / -116.8598", frame.getLocation());
        assertTrue(frame.hasGoogleEarthLocation());

        ztf.remove("SHUTOPEN");
        assertEquals("OBSMJD is on whole seconds", Instant.parse("2024-01-08T02:49:37.004Z").toEpochMilli(),
                frame.withFitsHeader(ztf).getObservationTimestamp());
        assertEquals("the standard MJD-OBS comes first", Instant.parse("2023-02-25T12:00:00Z").toEpochMilli(),
                frame.withFitsHeader(Map.of("MJD-OBS", "60000.5", "OBSMJD", "60317.1177894")).getObservationTimestamp());
        assertEquals(Instant.parse("2026-10-01T00:02:03.500Z").toEpochMilli(),
                frame.withFitsHeader(Map.of("DATE-BEG", "2026-10-01T00:02:03.5")).getObservationTimestamp());
        assertEquals("a malformed fallback card gives way to the next", Instant.parse("2023-02-25T12:00:00Z").toEpochMilli(),
                frame.withFitsHeader(Map.of("DATE-BEG", "yesterday", "MJD-OBS", "60000.5")).getObservationTimestamp());
        assertEquals("before 1970", -1L, frame.withFitsHeader(Map.of("MJD-OBS", "40000")).getObservationTimestamp());
        assertEquals(-1L, frame.withFitsHeader(Map.of("OBSERVAT", "Palomar")).getObservationTimestamp());
    }

    /** DATE-OBS keeps its precedence, and a malformed one is reported as such rather than replaced by another card. */
    @Test
    public void dateObsComesBeforeTheFallbackCards() {
        FitsFileInformation frame = new FitsFileInformation("f.fit", "f.fit", true, 10, 10,
                Map.of("DATE-OBS", "2026-10-01T00:05:00", "SHUTOPEN", "2024-01-08T02:49:37.926", "MJD-OBS", "60000.5"));
        assertEquals(Instant.parse("2026-10-01T00:05:00Z").toEpochMilli(), frame.getObservationTimestamp());
        FitsFileInformation malformed = frame.withFitsHeader(Map.of("DATE-OBS", "2026-13-45T00:05:00", "MJD-OBS", "60000.5"));
        assertEquals(-1L, malformed.getObservationTimestamp());
        assertTrue(malformed.getObservationDate().startsWith("UNPARSEABLE"));
    }

    @Test
    public void descriptorAndHeaderMapAreImmutableAndTimestampsNormaliseAtConstruction() {
        Map<String, String> sourceHeader = new HashMap<>();
        sourceHeader.put("DATE-OBS", "2026-10-01T00.02.03,250");
        sourceHeader.put("EXPTIME", "120");
        sourceHeader.put("COMMENT", null);

        FitsFileInformation info = new FitsFileInformation("sample.fit", "sample.fit", true, 640, 480, sourceHeader);
        sourceHeader.put("DATE-OBS", "2099-01-01T00:00:00Z");

        assertEquals(Instant.parse("2026-10-01T00:02:03.250Z").toEpochMilli(), info.getObservationTimestamp());
        assertEquals("2026-10-01 00:02:03.250 UTC", info.getObservationDate());
        assertTrue(info.getObservationTimestampDiagnostics().contains("parseStatus=OK"));
        assertThrows(UnsupportedOperationException.class, () -> info.getFitsHeader().put("OBJECT", "mutated"));
        assertThrows(UnsupportedOperationException.class, () -> info.getFitsHeader().remove("DATE-OBS"));
        assertThrows(UnsupportedOperationException.class, () -> info.getFitsHeader().clear());

        FitsFileInformation solved = info.withFitsHeader(Map.of(
                "DATE-OBS", "2026-10-01T00:05:00Z",
                "CTYPE1", "RA---TAN",
                "CRVAL1", "150.0"));
        assertNotSame(info, solved);
        assertFalse(info.isWcsSolved());
        assertTrue(solved.isWcsSolved());
        assertEquals(Instant.parse("2026-10-01T00:02:03.250Z").toEpochMilli(), info.getObservationTimestamp());
        assertEquals(Instant.parse("2026-10-01T00:05:00Z").toEpochMilli(), solved.getObservationTimestamp());

        for (Method method : FitsFileInformation.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                assertFalse("Unexpected mutator or snapshot method: " + method.getName(),
                        method.getName().startsWith("set") || method.getName().equals("snapshot"));
            }
        }
    }
}
