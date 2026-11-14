package eu.startales.spacepixels.fits;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The observing site: the first frame header that has one wins over the configured fallback. */
public class ObservingSiteTest {

    /** FITS elevations override the configured height; a missing elevation uses the fallback, including zero. */
    @Test public void altitudeUsesHeaderThenConfiguredDefault() {
        var with = frame(Map.of("SITELAT", "12", "SITELONG", "34", "SITEELEV", "2500"));
        assertEquals(2500, ObservingSite.resolve(new FitsFileInformation[]{with}, "56", "78", "1000").altitudeMeters(), 0);
        var without = frame(Map.of("SITELAT", "12", "SITELONG", "34"));
        var result = ObservingSite.resolve(new FitsFileInformation[]{without}, "56", "78", "1000,5");
        assertEquals(12, result.latitudeDeg(), 0);
        assertEquals(1000.5, result.altitudeMeters(), 0);
        assertEquals(0, ObservingSite.resolve(new FitsFileInformation[]{without}, "56", "78", "0").altitudeMeters(), 0);
        assertEquals(0, ObservingSite.resolve(new FitsFileInformation[]{without}, "56", "78", "").altitudeMeters(), 0);
        assertEquals(2500, ObservingSite.resolve(new FitsFileInformation[]{without, with}, "56", "78", "1000").altitudeMeters(), 0);
    }

    /** Standard geodetic-height aliases are read and invalid cards cannot hide a later valid value. */
    @Test public void readsStandardHeightAndIgnoresInvalidElevations() {
        var input = frame(Map.of("SITELAT", "12", "SITELONG", "34", "SITEELEV", "NaN", "OBSGEO-H", "-120.5"));
        assertEquals(-120.5, ObservingSite.resolve(new FitsFileInformation[]{input}, "", "", "400").altitudeMeters(), 0);
        assertNull(ObservingSite.parseAltitude("Infinity"));
        assertNull(ObservingSite.parseAltitude("NaN"));
        assertNull(ObservingSite.parseAltitude("high"));
        assertEquals(-120.5, ObservingSite.fromConfiguration("12", "34", "-120.5").altitudeMeters(), 0);
    }

    /** An elevation-only header can supply the height of a configured location without fabricating coordinates. */
    @Test public void altitudeOnlyHeaderStillHasPriority() {
        var files = new FitsFileInformation[]{frame(Map.of("OBSALT", "1668"))};
        assertEquals(1668, ObservingSite.resolve(files, "12", "34", "1000").altitudeMeters(), 0);
        assertNull(ObservingSite.resolve(files, "", "", "1000"));
    }

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

    /**
     * A DECam session at Cerro Tololo (2022-08-24, 05:12 to 09:57 UTC, field at RA 357.18°, Dec -1.79°): the real site
     * sees it; the same coordinates typed without their signs put the site in Central Asia, in daylight, where the
     * field is far below the horizon.
     */
    @Test
    public void aSiteThatCannotSeeTheFieldIsAProblem() {
        long start = 1_661_317_942_149L, end = 1_661_335_034_182L;
        assertNull(new ObservingSite(-30.16966, -70.80653, 2207, ObservingSite.FROM_CONFIGURATION)
                .horizonProblem(357.18, -1.79, start, end));
        String problem = new ObservingSite(30.16966, 70.80653, 2280, ObservingSite.FROM_CONFIGURATION)
                .horizonProblem(357.18, -1.79, start, end);
        assertTrue(problem, problem.startsWith("The field is ") && problem.contains("below the horizon")
                && problem.contains("30.170° N, 70.807° E") && problem.contains("Astrometry Config tab"));
        assertNull("unknown times are not checked", new ObservingSite(30.16966, 70.80653, 0, ObservingSite.FROM_HEADER)
                .horizonProblem(357.18, -1.79, 0, 0));
    }
}
