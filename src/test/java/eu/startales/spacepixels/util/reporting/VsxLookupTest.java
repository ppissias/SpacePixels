package eu.startales.spacepixels.util.reporting;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VsxLookupTest {

    @Test
    public void tapUrlPassesProxyValidation() {
        String url = PhotometryReportSectionWriter.vsxTapUrl(new PhotometryReportSectionWriter.SkyTarget(97.5225, 6.2234, 15.0));

        URI uri = ReportLookupUpstreamClient.validateTarget("vsx", url);

        assertEquals("tapvizier.cds.unistra.fr", uri.getHost());
        assertTrue(uri.getQuery().contains("FROM \"B/vsx/vsx\""));
        assertTrue(uri.getQuery().contains("CIRCLE('ICRS', 97.522500, 6.223400, 0.004167)"));
        assertFalse("spaces must be percent-encoded", url.contains("+"));
    }

    @Test
    public void proxyRejectsOtherHostsAndTables() {
        assertRejected("https://example.org/TAPVizieR/tap/sync?QUERY=SELECT%20*%20FROM%20%22B/vsx/vsx%22");
        assertRejected("https://tapvizier.cds.unistra.fr/TAPVizieR/tap/sync?QUERY=SELECT%20*%20FROM%20%22I/355/gaiadr3%22");
        assertRejected("http://tapvizier.cds.unistra.fr/TAPVizieR/tap/sync?QUERY=SELECT%20*%20FROM%20%22B/vsx/vsx%22");
    }

    @Test
    public void normalizesTapRowsIntoMatches() {
        String payload = "{\"metadata\":[{\"name\":\"OID\"},{\"name\":\"Name\"},{\"name\":\"Type\"},{\"name\":\"max\"},"
                + "{\"name\":\"n_max\"},{\"name\":\"f_min\"},{\"name\":\"min\"},{\"name\":\"n_min\"},{\"name\":\"Period\"},{\"name\":\"dist_arcsec\"}],"
                + "\"data\":[[608040,\"ASASSN-V J063632.76+064632.3  \",\"HADS   \",13.11,\"V   \",null,13.66,\"V \",0.10467,5.39],"
                + "[1,\"CoRoT 221707281\",\"EA\",13.23,\"R\",\"Y\",0.499,null,2.178775,9.1]]}";

        JsonObject normalized = ReportLookupPayloadNormalizer.normalize("vsx", JsonParser.parseString(payload));

        assertEquals(2, normalized.get("matchCount").getAsInt());
        JsonArray matches = normalized.getAsJsonArray("matches");
        JsonObject first = matches.get(0).getAsJsonObject();
        assertEquals("ASASSN-V J063632.76+064632.3", first.get("name").getAsString());
        assertEquals("HADS", first.get("type").getAsString());
        assertEquals(0.10467, first.get("periodDays").getAsDouble(), 1e-9);
        assertEquals(5.39, first.get("separationArcsec").getAsDouble(), 1e-9);
        assertFalse(first.get("minIsAmplitude").getAsBoolean());
        assertEquals("https://www.aavso.org/vsx/index.php?view=detail.top&oid=608040", first.get("vsxUrl").getAsString());
        assertTrue(matches.get(1).getAsJsonObject().get("minIsAmplitude").getAsBoolean());
    }

    private static void assertRejected(String url) {
        try {
            ReportLookupUpstreamClient.validateTarget("vsx", url);
            fail("Expected rejection of " + url);
        } catch (IllegalArgumentException expected) {
            // rejected as intended
        }
    }
}
