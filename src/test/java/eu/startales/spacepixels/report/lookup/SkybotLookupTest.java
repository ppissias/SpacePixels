package eu.startales.spacepixels.report.lookup;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.WcsSolutionResolver;
import eu.startales.spacepixels.report.DetectionReportAstrometry;
import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Verifies SkyBoT live-lookup URL validation, payload validation, normalization, and report markup.
 */
public class SkybotLookupTest {

    private static final long EPOCH = java.time.Instant.parse("2026-03-10T03:19:19Z").toEpochMilli();

    @Test
    public void jsonUrlPassesProxyValidationAndNonJsonOrOtherHostsAreRejected() {
        DetectionReportAstrometry.Context context = geocenterContext();
        SolarSystemQueryTarget target = SolarSystemQueryTargetFactory.buildFaintMoverQueryTarget(context, 80, 60, EPOCH);
        String jsonUrl = DetectionReportAstrometry.buildSkybotJsonUrl(context, target, 0.025, false);
        String textUrl = DetectionReportAstrometry.buildSkybotUrl(context, target, 0.025, false);

        URI uri = ReportLookupUpstreamClient.validateTarget("skybot", jsonUrl);
        assertEquals(ReportLookupUpstreamClient.SKYBOT_HOST, uri.getHost());
        assertEquals(ReportLookupUpstreamClient.SKYBOT_PATH, uri.getPath());
        assertTrue(uri.getRawQuery().contains("-mime=json"));

        assertRejected(textUrl);
        assertRejected("https://vo.imcce.fr/webservices/skybot/skybotconesearch_query.php?-mime=json");
        assertRejected("http://ssp.imcce.fr/webservices/skybot/api/conesearch.php?-mime=json");
        assertRejected("https://ssp.imcce.fr/webservices/skybot/api/resolver.php?-mime=json");
        assertRejected("https://example.org/webservices/skybot/api/conesearch.php?-mime=json");
    }

    @Test
    public void malformedSuccessBodiesAreRejectedWhileArraysAreAccepted() {
        for (String body : List.of("null", "\"Maintenance\"", "{}", "{\"error\":\"boom\"}", "[123]")) {
            try {
                ReportLookupUpstreamClient.validatePayload("skybot", JsonParser.parseString(body));
                fail("Malformed SkyBoT response accepted: " + body);
            } catch (IllegalArgumentException expected) {
                // rejected as intended
            }
        }
        ReportLookupUpstreamClient.validatePayload("skybot", JsonParser.parseString("[]"));
        ReportLookupUpstreamClient.validatePayload("skybot", JsonParser.parseString("[{\"Name\":\"Sutoku\"}]"));
    }

    @Test
    public void normalizesRowsSortedByDistanceWithLinks() {
        String payload = "["
                + "{\"Num\":\"-\",\"Name\":\"2015 MH206\",\"RA (hms)\":\"06 39 40.7970\",\"DEC (dms)\":\"+07 15 9.510\","
                + "\"Class\":\"MB>Middle\",\"VMag (mag)\":22.3,\"Err (arcsec)\":0.893,\"d (arcsec)\":441.174,"
                + "\"dRA (arcsec/h)\":12.1,\"dDEC (arcsec/h)\":14.2,\"ssodnet\":{\"ssocard\":\"http://insecure.example/card\"}},"
                + "{\"Num\":4767,\"Name\":\"Sutoku\",\"RA (hms)\":\"06 40 4.0348\",\"DEC (dms)\":\"+07 19 48.226\","
                + "\"Class\":\"MB>Middle\",\"VMag (mag)\":17.6,\"Err (arcsec)\":0.004,\"d (arcsec)\":2.988,"
                + "\"dRA (arcsec/h)\":12.2673,\"dDEC (arcsec/h)\":14.896,"
                + "\"ssodnet\":{\"ssocard\":\"https://ssp.imcce.fr/webservices/ssodnet/api/ssocard/Sutoku\"}}"
                + "]";

        JsonObject normalized = ReportLookupPayloadNormalizer.normalize("skybot", JsonParser.parseString(payload));

        assertEquals("IMCCE SkyBoT", normalized.get("source").getAsString());
        assertEquals(2, normalized.get("matchCount").getAsInt());
        JsonArray matches = normalized.getAsJsonArray("matches");

        JsonObject first = matches.get(0).getAsJsonObject();
        assertEquals("4767", first.get("number").getAsString());
        assertEquals("4767 Sutoku", first.get("displayName").getAsString());
        assertEquals(2.988, first.get("distanceArcsec").getAsDouble(), 1e-6);
        assertEquals(17.6, first.get("vMag").getAsDouble(), 1e-6);
        assertEquals(12.2673, first.get("raRateArcsecPerHour").getAsDouble(), 1e-6);
        assertEquals("https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr=4767", first.get("sbdbLookupUrl").getAsString());
        assertEquals("https://ssp.imcce.fr/webservices/ssodnet/api/ssocard/Sutoku", first.get("ssoCardUrl").getAsString());

        JsonObject second = matches.get(1).getAsJsonObject();
        assertFalse(second.has("number"));
        assertEquals("2015 MH206", second.get("displayName").getAsString());
        assertEquals("https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr=2015+MH206", second.get("sbdbLookupUrl").getAsString());
        assertFalse("non-HTTPS ssoCardUrl must not be exposed", second.has("ssoCardUrl"));
    }

    @Test
    public void reportMarkupIncludesLiveSkybotButtonsAndSlotEvenWithoutJplSite() {
        DetectionReportAstrometry.Context context = geocenterContext();
        SolarSystemQueryTarget target = SolarSystemQueryTargetFactory.buildFaintMoverQueryTarget(context, 80, 60, EPOCH);
        String html = AstrometryIdentificationHtmlBuilder.buildSolarSystemIdentificationHtml(
                context, target, "Epoch", "Search SkyBoT (Track Midpoint)", "SkyBoT search radius",
                "moving-track-jpl-1", "jpl_track_01", true);

        assertTrue(html, html.contains("Render SkyBoT Results Here"));
        assertTrue(html, html.contains("Render SkyBoT Wide Cone Here"));
        assertTrue(html, html.contains("data-provider='skybot'"));
        assertTrue(html, html.contains("data-slot-id='moving-track-jpl-1-skybot'"));
        assertTrue(html, html.contains("id='moving-track-jpl-1-skybot'"));
        assertTrue(html, html.contains("data-sidecar-file='skybot_track_01_nominal.json'"));
        assertTrue(html, html.contains("data-sidecar-file='skybot_track_01_wide.json'"));
        assertTrue(html, html.contains("Search SkyBoT (Track Midpoint)"));

        String expectedNominalJson = DetectionReportAstrometry.buildSkybotJsonUrl(context, target, target.searchRadiusDegrees, false);
        String encodedTarget = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(expectedNominalJson.getBytes(StandardCharsets.UTF_8));
        assertTrue(html, html.contains("data-target='" + encodedTarget + "'"));
    }

    private static DetectionReportAstrometry.Context geocenterContext() {
        Map<String, String> header = new HashMap<>();
        header.put("SHUTOPEN", "2026-03-10T03:19:19");
        header.put("EXPTIME", "30");
        header.put("CTYPE1", "RA---TAN");
        header.put("CTYPE2", "DEC--TAN");
        header.put("CRPIX1", "80.5");
        header.put("CRPIX2", "60.5");
        header.put("CRVAL1", "100.016279");
        header.put("CRVAL2", "7.329423");
        header.put("CD1_1", "-0.000281");
        header.put("CD1_2", "0");
        header.put("CD2_1", "0");
        header.put("CD2_2", "0.000281");
        FitsFileInformation frame = new FitsFileInformation("frame.fit", "frame.fit", true, 160, 120, header);
        FitsFileInformation[] frames = new FitsFileInformation[]{frame};
        return new DetectionReportAstrometry.Context(
                frames, WcsSolutionResolver.resolveUncorrected(frame, frames), null, null, null, EPOCH);
    }

    private static void assertRejected(String url) {
        try {
            ReportLookupUpstreamClient.validateTarget("skybot", url);
            fail("Expected rejection of " + url);
        } catch (IllegalArgumentException expected) {
            // rejected as intended
        }
    }
}
