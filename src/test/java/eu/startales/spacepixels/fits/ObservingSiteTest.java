package eu.startales.spacepixels.fits;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The observing site: the first frame header that has one wins over the configured fallback. */
public class ObservingSiteTest {

    @Test
    public void takesTheHeaderSiteThenTheFallback() {
        FitsFileInformation without = frame(Map.of());
        FitsFileInformation with = frame(Map.of("SITELAT", "-24.6272", "SITELONG", "289.597", "SITEELEV", "2635"));
        ObservingSite site = ObservingSite.resolve(new FitsFileInformation[]{without, with}, "37.9", "23.7");
        assertEquals(-24.6272, site.latitudeDeg(), 1e-12);
        assertEquals("east longitudes over 180° are brought to the west", -70.403, site.longitudeDeg(), 1e-9);
        assertEquals(2635, site.altitudeMeters(), 0);
        assertEquals(ObservingSite.FROM_HEADER, site.source());

        ObservingSite fallback = ObservingSite.resolve(new FitsFileInformation[]{without}, "24,6272S", "70.4030W");
        assertEquals(-24.6272, fallback.latitudeDeg(), 1e-12);
        assertEquals(-70.403, fallback.longitudeDeg(), 1e-12);
        assertEquals(0, fallback.altitudeMeters(), 0);
        assertEquals(ObservingSite.FROM_CONFIGURATION, fallback.source());

        assertNull(ObservingSite.resolve(new FitsFileInformation[]{without}, "", "23.7"));
        assertNull("not a latitude", ObservingSite.resolve(new FitsFileInformation[]{frame(Map.of("SITELAT", "95", "SITELONG", "10"))},
                null, null));
    }

    /** Professional pipelines such as the Zwicky Transient Facility write OBSLAT, OBSLON and OBSALT (east-positive). */
    @Test
    public void readsObservatoryCards() {
        FitsFileInformation palomar = frame(Map.of("OBSLAT", "33.3573", "OBSLON", "-116.8598", "OBSALT", "1668.0"));
        ObservingSite site = ObservingSite.fromHeaders(new FitsFileInformation[]{palomar});
        assertEquals(33.3573, site.latitudeDeg(), 1e-12);
        assertEquals(-116.8598, site.longitudeDeg(), 1e-12);
        assertEquals(1668, site.altitudeMeters(), 0);
        assertEquals(ObservingSite.FROM_HEADER, site.source());
        assertNull(ObservingSite.fromHeaders(new FitsFileInformation[]{frame(Map.of())}));
        assertNull(ObservingSite.fromHeaders(null));
        assertEquals(ObservingSite.FROM_CONFIGURATION, ObservingSite.fromConfiguration("37.9", "23.7").source());
        assertNull(ObservingSite.fromConfiguration("37.9", null));
    }

    private static FitsFileInformation frame(Map<String, String> site) {
        Map<String, String> header = new LinkedHashMap<>(site);
        header.put("NAXIS", "2");
        return new FitsFileInformation("f.fit", "f.fit", true, 10, 10).withFitsHeader(header);
    }
}
