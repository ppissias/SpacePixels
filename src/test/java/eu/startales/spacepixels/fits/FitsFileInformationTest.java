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
