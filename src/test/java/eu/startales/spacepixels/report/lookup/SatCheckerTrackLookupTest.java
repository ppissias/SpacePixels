package eu.startales.spacepixels.report.lookup;

import com.google.gson.*;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Provider-boundary tests for discovery, catalogue identity, failure accounting and current/legacy responses.
 */
public class SatCheckerTrackLookupTest {
    /**
     * A spuriously close, unresolved fast arc cannot outrank a complete adequately sampled candidate.
     */
    @Test
    public void provisionalFastSamplingDoesNotOutrankCompleteEvidence() {
        var o = SatelliteTrackComparisonTest.observation(List.of(
                new StreakTrackObservation.Exposure(1, 2461000, 2461000 + 100.0 / 86400, List.of(SatelliteTrackComparisonTest.fragment(.05, .001))),
                new StreakTrackObservation.Exposure(2, 2461000 + 200.0 / 86400, 2461000 + 300.0 / 86400, List.of(SatelliteTrackComparisonTest.fragment(.25, .001)))));
        var result = new SatCheckerTrackLookup(url -> {
            var response = reply(url, false);
            if (url.contains("/fov/")) {
                var satellites = response.getAsJsonObject("payload").getAsJsonObject("data").getAsJsonObject("satellites");
                var fast = satellites.entrySet().iterator().next().getValue().getAsJsonObject().deepCopy();
                fast.addProperty("norad_id", 124);
                fast.addProperty("name", "Unresolved fast candidate");
                satellites.add("fast", fast);
            } else if (url.contains("catalog=124&")) {
                for (var value : response.getAsJsonObject("payload").getAsJsonArray("data")) {
                    var row = value.getAsJsonArray();
                    row.set(2, new JsonPrimitive((row.get(2).getAsDouble() * 10000) % 360));
                    row.set(3, new JsonPrimitive(.001));
                }
            }
            return response;
        }, new SatCheckerTrackLookup.Control()).compare(o);
        var candidates = result.getAsJsonObject("normalized").getAsJsonArray("candidates");
        assertEquals(123, candidates.get(0).getAsJsonObject().get("noradId").getAsInt());
        assertTrue(candidates.get(0).getAsJsonObject().getAsJsonObject("comparison").get("samplingAdequate").getAsBoolean());
        assertFalse(candidates.get(1).getAsJsonObject().getAsJsonObject("comparison").get("samplingAdequate").getAsBoolean());
    }

    /**
     * Refuses an excessively wide search before spending provider time, while retaining an explicit search failure.
     */
    @Test
    public void wideExposureGeometryDoesNotBecomeAnUnboundedProviderRequest() {
        var exposure = new StreakTrackObservation.Exposure(1, 2461000, 2461000 + 100.0 / 86400,
                List.of(SatelliteTrackComparisonTest.fragment(0, 0), SatelliteTrackComparisonTest.fragment(30, 0)));
        var o = SatelliteTrackComparisonTest.observation(List.of(exposure,
                new StreakTrackObservation.Exposure(2, 2461000 + 200.0 / 86400, 2461000 + 300.0 / 86400, exposure.fragments())));
        var result = new SatCheckerTrackLookup(url -> {
            fail("Unbounded search geometry reached the provider");
            return null;
        },
                new SatCheckerTrackLookup.Control()).compare(o);
        assertFalse(result.getAsJsonObject("normalized").get("discoveryComplete").getAsBoolean());
        assertTrue(result.getAsJsonObject("normalized").get("warnings").toString().contains("five-degree"));
    }

    /**
     * Keeps two indistinguishable returned trajectories visible instead of manufacturing a unique identity.
     */
    @Test
    public void indistinguishableCandidatesRetainBothSetsOfMeasuredEvidence() {
        var result = new SatCheckerTrackLookup(url -> {
            var response = reply(url, false);
            if (url.contains("/fov/")) {
                var satellites = response.getAsJsonObject("payload").getAsJsonObject("data").getAsJsonObject("satellites");
                var other = satellites.entrySet().iterator().next().getValue().getAsJsonObject().deepCopy();
                other.addProperty("norad_id", 124);
                other.addProperty("name", "Indistinguishable candidate");
                satellites.add("other", other);
            }
            return response;
        }, new SatCheckerTrackLookup.Control()).compare(observation());
        var candidates = result.getAsJsonObject("normalized").getAsJsonArray("candidates");
        assertEquals(2, candidates.size());
        assertEquals(candidates.get(0).getAsJsonObject().get("comparison"), candidates.get(1).getAsJsonObject().get("comparison"));
        assertFalse(result.getAsJsonObject("normalized").has("confirmedIdentity"));
    }

    /**
     * A Time Budget Returns Partial Evidence While Explicit Cancellation Still Aborts.
     */
    @Test
    public void aTimeBudgetReturnsPartialEvidenceWhileExplicitCancellationStillAborts() {
        var control = new SatCheckerTrackLookup.Control();
        var result = new SatCheckerTrackLookup(url -> {
            var response = reply(url, false);
            if (url.contains("/ephemeris/")) control.expire();
            return response;
        }, control).compare(observation());
        var normalized = result.getAsJsonObject("normalized");
        assertTrue(result.get("ok").getAsBoolean());
        assertFalse(normalized.get("followUpComplete").getAsBoolean());
        assertTrue(normalized.get("warnings").toString().contains("time budget"));
        assertEquals(3, result.getAsJsonObject("payload").getAsJsonArray("queries").size());
    }

    /**
     * Long Ephemerides Are Chunked Below The Live Providers Thousand Row Limit.
     */
    @Test
    public void longEphemeridesAreChunkedBelowTheLiveProvidersThousandRowLimit() {
        var o = SatelliteTrackComparisonTest.observation(List.of(SatelliteTrackComparisonTest.exposure(1, 0, 600, .3), SatelliteTrackComparisonTest.exposure(2, 600, 1200, .9)));
        var urls = new ArrayList<String>();
        var result = new SatCheckerTrackLookup(url -> {
            urls.add(url);
            return reply(url, false);
        }, new SatCheckerTrackLookup.Control()).compare(o);
        assertEquals(4, urls.size());
        assertTrue(result.getAsJsonObject("normalized").getAsJsonArray("candidates").get(0).getAsJsonObject().getAsJsonObject("comparison").get("completeCoverage").getAsBoolean());
    }

    /**
     * Candidate Discovery Uses Actual Exposures And Follow Up Uses Norad Identity.
     */
    @Test
    public void candidateDiscoveryUsesActualExposuresAndFollowUpUsesNoradIdentity() {
        var urls = new ArrayList<String>();
        var result = new SatCheckerTrackLookup(url -> {
            urls.add(url);
            return reply(url, false);
        }, new SatCheckerTrackLookup.Control()).compare(observation());
        assertEquals(3, urls.size());
        assertTrue(urls.get(0).contains("duration=105") || urls.get(0).contains("duration=104"));
        assertTrue(urls.get(2).contains("catalog=123"));
        assertFalse(urls.get(2).contains("name="));
        var n = result.getAsJsonObject("normalized");
        assertTrue(n.get("discoveryComplete").getAsBoolean());
        assertEquals(0, n.getAsJsonArray("candidates").get(0).getAsJsonObject().getAsJsonObject("comparison").get("medianDistanceArcsec").getAsDouble(), 1e-4);
        assertEquals(observation().digest(), result.get("observationDigest").getAsString());
        assertEquals(3, result.getAsJsonObject("payload").getAsJsonArray("queries").size());
    }

    /**
     * A Different Returned Identity Is Unavailable And Cannot Become ABest Match.
     */
    @Test
    public void aDifferentReturnedIdentityIsUnavailableAndCannotBecomeABestMatch() {
        var result = new SatCheckerTrackLookup(url -> reply(url, true), new SatCheckerTrackLookup.Control()).compare(observation());
        var candidate = result.getAsJsonObject("normalized").getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertFalse(candidate.has("comparison"));
        assertTrue(candidate.get("unavailableReason").getAsString().contains("NORAD"));
    }

    /**
     * Failed Search Is Not An Empty Successful Catalogue Or AComplete Search.
     */
    @Test
    public void failedSearchIsNotAnEmptySuccessfulCatalogueOrACompleteSearch() {
        var count = new AtomicInteger();
        var result = new SatCheckerTrackLookup(url -> {
            if (count.getAndIncrement() == 0)
                return JsonParser.parseString("{\"ok\":false,\"message\":\"timeout\"}").getAsJsonObject();
            return reply(url, false);
        }, new SatCheckerTrackLookup.Control()).compare(observation());
        var n = result.getAsJsonObject("normalized");
        assertFalse(n.get("discoveryComplete").getAsBoolean());
        assertEquals(1, n.get("searchedExposures").getAsInt());
        assertTrue(n.get("warnings").toString().contains("timeout"));
    }

    /**
     * Cancelled Run Starts No Network Request.
     */
    @Test
    public void cancelledRunStartsNoNetworkRequest() {
        var control = new SatCheckerTrackLookup.Control();
        control.cancel();
        try {
            new SatCheckerTrackLookup(url -> {
                fail("Network after cancellation");
                return null;
            }, control).compare(observation());
            fail("Cancellation lost");
        } catch (CancellationException expected) {
        }
    }

    /**
     * Explicitly Unsupported Reference Frame Abstains Before Network Work.
     */
    @Test
    public void explicitlyUnsupportedReferenceFrameAbstainsBeforeNetworkWork() {
        var o = observation();
        var bad = new StreakTrackObservation(o.version(), o.findingId(), o.observer(), Map.of("coordinateMetadata", Map.of("RADESYS", "FK4")), o.exposures(), o.warnings());
        try {
            new SatCheckerTrackLookup(url -> {
                fail("Network for unsupported frame");
                return null;
            }, new SatCheckerTrackLookup.Control()).compare(bad);
            fail("Unsupported frame accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("frame"));
        }
    }

    /**
     * Only The Known Ephemeris Endpoint Is Allowed And Its Shape Is Checked.
     */
    @Test
    public void onlyTheKnownEphemerisEndpointIsAllowedAndItsShapeIsChecked() {
        ReportLookupUpstreamClient.validateTarget("satchecker", "https://satchecker.cps.iau.org/ephemeris/catalog-number-jdstep/?catalog=123");
        for (String url : List.of("https://satchecker.cps.iau.org/other/", "https://example.org/ephemeris/catalog-number-jdstep/", "https://satchecker.cps.iau.org:1234/fov/satellite-passes/")) {
            try {
                ReportLookupUpstreamClient.validateTarget("satchecker", url);
                fail("URL allowed");
            } catch (IllegalArgumentException expected) {
            }
        }
        try {
            ReportLookupUpstreamClient.validateEphemerisPayload(JsonParser.parseString("{\"status\":\"PENDING\"}"));
            fail("Pending is not a trajectory");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Orbital Metadata Reads Current And Older Provider Fields.
     */
    @Test
    public void orbitalMetadataReadsCurrentAndOlderProviderFields() {
        for (String epochKey : List.of("orbital_data_epoch", "tle_epoch")) {
            var root = JsonParser.parseString("{\"data\":{\"satellites\":{\"a\":{\"norad_id\":123,\"positions\":[{\"" + epochKey + "\":\"epoch\",\"orbital_data_source\":\"provider\"}]}}}}");
            var n = ReportLookupPayloadNormalizer.normalize("satchecker", root).getAsJsonObject();
            var c = n.getAsJsonArray("candidates").get(0).getAsJsonObject();
            assertEquals("epoch", c.get("tleEpochUtc").getAsString());
            assertEquals("provider", c.get("orbitalDataSource").getAsString());
        }
    }

    /**
     * Minimal two-frame observation with a gap between exposures.
     */
    private static StreakTrackObservation observation() {
        return SatelliteTrackComparisonTest.observation(List.of(SatelliteTrackComparisonTest.exposure(1, 0, 100, .05), SatelliteTrackComparisonTest.exposure(2, 200, 300, .25)));
    }

    /**
     * Returns a deterministic FOV or ephemeris payload through the production envelope boundary.
     */
    private static JsonObject reply(String url, boolean wrongIdentity) {
        var out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("sourceUrl", url);
        if (url.contains("/fov/"))
            out.add("payload", JsonParser.parseString("{\"data\":{\"satellites\":{\"Same display name\":{\"name\":\"Same display name\",\"norad_id\":123,\"positions\":[{\"orbital_data_epoch\":\"epoch\",\"orbital_data_source\":\"test\"}]}}}}"));
        else {
            var p = new JsonObject();
            p.add("fields", new Gson().toJsonTree(List.of("catalog_id", "julian_date", "right_ascension_deg", "declination_deg", "tle_epoch", "data_source")));
            var rows = new JsonArray();
            var params = new HashMap<String, String>();
            for (String pair : java.net.URI.create(url).getRawQuery().split("&")) {
                var parts = pair.split("=", 2);
                params.put(parts[0], parts[1]);
            }
            double start = Double.parseDouble(params.get("startjd")), stop = Double.parseDouble(params.get("stopjd")), step = Double.parseDouble(params.get("stepjd"));
            int count = (int) Math.ceil((stop - start) / step) + 1;
            assertTrue("Provider has a 1,000-row response limit", count <= 1000);
            for (int i = 0; i < count; i++) {
                double jd = start + i * step;
                double ra = (((jd - 2461000) * 86400 * .001) % 360 + 360) % 360;
                rows.add(new Gson().toJsonTree(List.of(wrongIdentity ? 999 : Integer.parseInt(params.get("catalog")), jd, ra, 0, "epoch", "test")));
            }
            p.add("data", rows);
            out.add("payload", p);
        }
        return out;
    }
}
