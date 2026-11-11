package eu.startales.spacepixels.report.lookup;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import eu.startales.spacepixels.report.PhotometryReportSectionWriter;

public class VsxLookupTest {

    @Test public void malformedSuccessBodiesAreNotEmptyCatalogueResults() {
        for (String body : java.util.List.of("null", "\"Maintenance\"", "{}", "{\"error\":\"Query failed\"}")) {
            try {
                ReportLookupUpstreamClient.validatePayload("vsx", JsonParser.parseString(body));
                fail("Malformed catalogue response accepted: " + body);
            } catch (IllegalArgumentException expected) { }
        }
        ReportLookupUpstreamClient.validatePayload("vsx", JsonParser.parseString("{\"metadata\":[{\"name\":\"Name\"}],\"data\":[]}"));
    }

    @Test
    public void aMirrorPageBecomesTheSameMatchShape() {
        String tap = PhotometryReportSectionWriter.vsxTapUrl(new PhotometryReportSectionWriter.SkyTarget(97.5225, 6.2234, 15.0));
        VsxMirrorLookup.Circle circle = VsxMirrorLookup.circle(tap);
        assertEquals(97.5225, circle.ra(), 1e-6);
        assertEquals(6.2234, circle.dec(), 1e-6);
        assertEquals(0.004167, circle.radiusDeg(), 1e-9);
        String page = "<?xml version=\"1.0\"?><VOTABLE><RESOURCE><TABLE>"
                + "<FIELD name=\"OID\"/><FIELD name=\"Name\"/><FIELD name=\"Type\"/>"
                + "<FIELD name=\"RAJ2000\"/><FIELD name=\"DEJ2000\"/><FIELD name=\"max\"/><FIELD name=\"Period\"/>"
                + "<DATA><TABLEDATA>"
                + "<TR><TD>2</TD><TD>Far</TD><TD>EB</TD><TD>97.60</TD><TD>6.30</TD><TD>11</TD><TD>1</TD></TR>"
                + "<TR><TD>684845</TD><TD>CoRoT 221707281</TD><TD>EA</TD><TD>97.5225</TD><TD>6.2234</TD><TD>13.2</TD><TD>2.18</TD></TR>"
                + "</TABLEDATA></DATA></TABLE></RESOURCE></VOTABLE>";
        java.util.concurrent.atomic.AtomicReference<String> asked = new java.util.concurrent.atomic.AtomicReference<>();
        JsonObject response = VsxMirrorLookup.lookup(tap, (url, host) -> {
            asked.set(url + " " + host);
            if (url.startsWith(VsxMirrorLookup.HARVARD)) {
                return new VsxMirrorLookup.Fetched(200, page);
            }
            throw new IOException("IDIA was asked");
        });
        assertTrue(asked.get(), asked.get().startsWith(VsxMirrorLookup.HARVARD));
        assertTrue(asked.get(), asked.get().contains("vizier.cfa.harvard.edu"));
        assertTrue(asked.get(), asked.get().contains("-source=B%2Fvsx%2Fvsx") || asked.get().contains("-source=B/vsx/vsx"));
        assertTrue(response.get("ok").getAsBoolean());
        JsonObject first = response.getAsJsonObject("normalized").getAsJsonArray("matches").get(0).getAsJsonObject();
        assertEquals("CoRoT 221707281", first.get("name").getAsString());
        assertEquals("AAVSO VSX via Harvard", first.get("source").getAsString());
        assertEquals(0.0, first.get("separationArcsec").getAsDouble(), 0.01);
        assertEquals(2, response.getAsJsonObject("normalized").get("matchCount").getAsInt());
    }

    @Test
    public void harvardIsSkippedWhenItsPageIsNotATable() {
        String tap = PhotometryReportSectionWriter.vsxTapUrl(new PhotometryReportSectionWriter.SkyTarget(10, 20, 10));
        String page = "<?xml version=\"1.0\"?><VOTABLE><RESOURCE><TABLE>"
                + "<FIELD name=\"OID\"/><FIELD name=\"Name\"/><FIELD name=\"RAJ2000\"/><FIELD name=\"DEJ2000\"/>"
                + "<DATA><TABLEDATA><TR><TD>1</TD><TD>From IDIA</TD><TD>10</TD><TD>20</TD></TR></TABLEDATA></DATA></TABLE></RESOURCE></VOTABLE>";
        JsonObject response = VsxMirrorLookup.lookup(tap, (url, host) -> {
            if (url.startsWith(VsxMirrorLookup.HARVARD)) {
                return new VsxMirrorLookup.Fetched(200, "<html><body>down</body></html>");
            }
            return new VsxMirrorLookup.Fetched(200, page);
        });
        JsonObject match = response.getAsJsonObject("normalized").getAsJsonArray("matches").get(0).getAsJsonObject();
        assertEquals("From IDIA", match.get("name").getAsString());
        assertEquals("AAVSO VSX via IDIA", match.get("source").getAsString());
        assertTrue(response.get("sourceUrl").getAsString().startsWith(VsxMirrorLookup.IDIA));
    }

    @Test
    public void anEmptyMirrorPageBecomesAnEmptyMatchList() {
        String tap = PhotometryReportSectionWriter.vsxTapUrl(new PhotometryReportSectionWriter.SkyTarget(249.533173, 35.981440, 10.0));
        String emptyPage = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><VOTABLE><RESOURCE><TABLE ID=\"B_vsx_vsx\" name=\"B/vsx/vsx\">"
                + "<FIELD name=\"OID\"/><FIELD name=\"Name\"/><FIELD name=\"Type\"/>"
                + "<FIELD name=\"RAJ2000\"/><FIELD name=\"DEJ2000\"/><FIELD name=\"max\"/><FIELD name=\"Period\"/>"
                + "<!--  EMPTY result, no DATA part  -->"
                + "</TABLE></RESOURCE></VOTABLE>";
        JsonObject response = VsxMirrorLookup.lookup(tap, (url, host) -> new VsxMirrorLookup.Fetched(200, emptyPage));
        assertNotNull(response);
        assertTrue(response.get("ok").getAsBoolean());
        JsonObject normalized = response.getAsJsonObject("normalized");
        assertEquals(0, normalized.getAsJsonArray("matches").size());
        assertEquals(0, response.getAsJsonObject("payload").getAsJsonArray("data").size());
    }

    @Test
    public void harvardDatabaseErrorFallsThroughToIdia() {
        String tap = PhotometryReportSectionWriter.vsxTapUrl(new PhotometryReportSectionWriter.SkyTarget(10, 20, 10));
        String errorPage = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><VOTABLE>"
                + "<INFO ID=\"Error\" name=\"Error\" value=\"The database is not currently reachable.\"/>"
                + "<INFO name=\"QUERY_STATUS\" value=\"ERROR\">Postgres connect error</INFO>"
                + "</VOTABLE>";
        String matchPage = "<?xml version=\"1.0\"?><VOTABLE><RESOURCE><TABLE>"
                + "<FIELD name=\"OID\"/><FIELD name=\"Name\"/><FIELD name=\"RAJ2000\"/><FIELD name=\"DEJ2000\"/>"
                + "<DATA><TABLEDATA><TR><TD>1</TD><TD>Recovered From IDIA</TD><TD>10</TD><TD>20</TD></TR></TABLEDATA></DATA></TABLE></RESOURCE></VOTABLE>";
        JsonObject response = VsxMirrorLookup.lookup(tap, (url, host) -> {
            if (url.startsWith(VsxMirrorLookup.HARVARD)) {
                return new VsxMirrorLookup.Fetched(200, errorPage);
            }
            return new VsxMirrorLookup.Fetched(200, matchPage);
        });
        assertNotNull(response);
        assertTrue(response.get("ok").getAsBoolean());
        JsonObject match = response.getAsJsonObject("normalized").getAsJsonArray("matches").get(0).getAsJsonObject();
        assertEquals("Recovered From IDIA", match.get("name").getAsString());
        assertEquals("AAVSO VSX via IDIA", match.get("source").getAsString());
    }

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
    public void preservesComparisonMetadataWithoutGuessingAnAmplitudeFromReversedValues() {
        String[] columns = {"OID", "Name", "max", "min", "n_max", "n_min", "f_min", "l_max", "u_max", "l_min", "u_min", "Period", "l_Period", "u_Period", "n_OID"};
        JsonObject payload = new JsonObject();
        JsonArray metadata = new JsonArray();
        for (String name : columns) {
            JsonObject column = new JsonObject(); column.addProperty("name", name); metadata.add(column);
        }
        payload.add("metadata", metadata);
        payload.add("data", JsonParser.parseString("[[1,\"example\",12,0.1,\"G\",\"g\",null,\">\",\":\",null,\"?\",2,null,\":\",\"B\"]]"));
        JsonObject match = ReportLookupPayloadNormalizer.normalizeVsxPayload(payload).getAsJsonArray("matches").get(0).getAsJsonObject();
        assertFalse(match.get("minIsAmplitude").getAsBoolean());
        assertEquals("G", match.get("maxBand").getAsString());
        assertEquals("g", match.get("minBand").getAsString());
        assertEquals(">", match.get("maxLimit").getAsString());
        assertEquals(":", match.get("periodUncertainty").getAsString());
        assertTrue(match.get("comparisonMetadataComplete").getAsBoolean());
        assertTrue(match.get("bibliographyAvailable").getAsBoolean());
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
