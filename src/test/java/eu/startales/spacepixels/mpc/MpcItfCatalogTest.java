package eu.startales.spacepixels.mpc;

import com.sun.net.httpserver.HttpServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

public class MpcItfCatalogTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Real-format 80-column MPC ITF lines (same-night match, nearby-night match, wrong-rate distractor, RA-wrap object). */
    private static String mpcLine(String trackletId, int year, int month, double dayFrac,
                                  double raDeg, double decDeg, String magBand, String obsCode) {
        double raH = ((raDeg % 360.0) + 360.0) % 360.0 / 15.0;
        int rh = (int) Math.floor(raH);
        double rmFull = (raH - rh) * 60.0;
        int rm = (int) Math.floor(rmFull);
        double rs = (rmFull - rm) * 60.0;

        char sign = decDeg < 0 ? '-' : '+';
        double decAbs = Math.abs(decDeg);
        int dd = (int) Math.floor(decAbs);
        double dmFull = (decAbs - dd) * 60.0;
        int dm = (int) Math.floor(dmFull);
        double ds = (dmFull - dm) * 60.0;

        String idField = String.format(Locale.US, "%-12s", "     " + trackletId);
        if (idField.length() > 12) idField = idField.substring(0, 12);
        String line = String.format(Locale.US,
                "%12s  C%04d %02d %08.5f %02d %02d %06.3f%c%02d %02d %05.2f         %-6s      %3s",
                idField, year, month, dayFrac, rh, rm, rs, sign, dd, dm, ds, magBand, obsCode);
        assertEquals("MPC 80-col length: " + line, 80, line.length());
        return line;
    }

    private static byte[] gzipLines(List<String> lines) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(baos)) {
            for (String line : lines) {
                gz.write(line.getBytes(StandardCharsets.US_ASCII));
                gz.write('\n');
            }
        }
        return baos.toByteArray();
    }

    @Test
    public void matchesSameNightAndNearbyNightsAndRejectsDistractors() throws Exception {
        // B1m T9-like object at 2022-08-24T05:44:19 UTC (~day 24.23911): RA=357.04052, Dec=-1.78763, dRA*cos=-24.60"/h, dDec=-3.44"/h
        long epochMillis = LocalDate.of(2022, 8, 24).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                + Math.round(0.23911 * 86_400_000.0);
        var t9 = new MpcItfCatalog.FindingQuery("T9", epochMillis, 357.04052, -1.78763, -24.60, -3.44);
        // Unreported faint mover elsewhere on the same chip
        var fm1 = new MpcItfCatalog.FindingQuery("FM1", epochMillis, 357.12128, -1.78755, -27.94, +1.62);

        double cosDec = Math.cos(Math.toRadians(-1.78763));
        double dRaDegPerHour = (-24.60 / 3600.0) / cosDec;
        double dDecDegPerHour = -3.44 / 3600.0;

        List<String> lines = List.of(
                // 1) Exact same-night match for T9 (3 obs over 1 hour)
                mpcLine("Iuja1KU", 2022, 8, 24.21828, 357.04052 - dRaDegPerHour * 0.5, -1.78763 - dDecDegPerHour * 0.5, "23.2 V", "W84"),
                mpcLine("Iuja1KU", 2022, 8, 24.23911, 357.04052, -1.78763, "23.2 V", "W84"),
                mpcLine("Iuja1KU", 2022, 8, 24.25994, 357.04052 + dRaDegPerHour * 0.5, -1.78763 + dDecDegPerHour * 0.5, "23.2 V", "W84"),
                // 2) Same-night distractor at same sky position but moving completely differently (eastward!)
                mpcLine("BadVel1", 2022, 8, 24.21828, 357.04052 - 0.005, -1.78763, "22.0 V", "W84"),
                mpcLine("BadVel1", 2022, 8, 24.25994, 357.04052 + 0.005, -1.78763, "22.0 V", "W84"),
                // 3) Nearby-night (+4.08 d) match for T9 with realistic +2.4"/h speedup and 0.8' curvature offset
                mpcLine("Iv9j1PI", 2022, 8, 28.30255, 356.35534 + 0.0038, -1.89607 + 0.0005, "22.9 V", "W84"),
                mpcLine("Iv9j1PI", 2022, 8, 28.34422, 356.35534 - 0.0038, -1.89607 - 0.0005, "22.9 V", "W84"),
                // 4) Single-observation nearby-night line (must NOT match nearby nights)
                mpcLine("OneObs1", 2022, 8, 28.32000, 356.35534, -1.89607, "23.0 V", "W84"),
                // 5) Out-of-window observation (2021) — skipped by fast date filter
                mpcLine("OldTrk1", 2021, 8, 24.23911, 357.04052, -1.78763, "21.0 V", "W84")
        );

        Path gz = folder.newFile("itf.txt.gz").toPath();
        Files.write(gz, gzipLines(lines));

        MpcItfCatalog.SearchResult result = MpcItfCatalog.search(gz, List.of(t9, fm1), 15, null);
        assertEquals(lines.size(), result.linesScanned());

        MpcItfCatalog.FindingMatches t9Matches = result.byFinding().get("T9");
        assertNotNull(t9Matches);
        assertEquals("T9 should have 1 same-night match (BadVel1 rejected)", 1, t9Matches.sameNight().size());
        assertEquals("W84 Iuja1KU", t9Matches.sameNight().getFirst().tracklet().label());
        assertTrue(t9Matches.sameNight().getFirst().offsetArcsec() < 2.0);
        assertEquals("23.2 V", t9Matches.sameNight().getFirst().tracklet().magnitude());

        assertEquals("T9 should have 1 nearby-night match (OneObs1 rejected)", 1, t9Matches.nearbyNights().size());
        assertEquals("W84 Iv9j1PI", t9Matches.nearbyNights().getFirst().tracklet().label());
        assertTrue(t9Matches.nearbyNights().getFirst().deltaDays() > 4.0);

        MpcItfCatalog.FindingMatches fm1Matches = result.byFinding().get("FM1");
        assertNotNull(fm1Matches);
        assertFalse("FM1 should have no same-night ITF match", fm1Matches.hasSameNight());
        assertFalse("FM1 should have no nearby-night ITF match", fm1Matches.hasNearbyNights());

        String sameText = MpcItfCatalog.describeMatch(t9Matches.sameNight().getFirst());
        assertTrue(sameText, sameText.contains("W84 Iuja1KU") && sameText.contains("3 obs") && sameText.contains("mag 23.2 V"));
        String nearText = MpcItfCatalog.describeMatch(t9Matches.nearbyNights().getFirst());
        assertTrue(nearText, nearText.contains("W84 Iv9j1PI") && nearText.contains("2022-08-28"));
    }

    @Test
    public void handlesRaZeroWrapAndNegativeZeroDeclination() throws Exception {
        long epochMillis = LocalDate.of(2024, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                + Math.round(0.25 * 86_400_000.0);
        // Object right across RA=0 deg and Dec=-0.25 deg (-00 15 00.0), moving at -20"/h (-0.005556 deg/h)
        var obj = new MpcItfCatalog.FindingQuery("T1", epochMillis, 359.999, -0.25, -20.0, 0.0);

        List<String> lines = List.of(
                mpcLine("Wrap001", 2024, 10, 1.22917, 0.00178, -0.25, "21.5 R", "F51"),
                mpcLine("Wrap001", 2024, 10, 1.27083, 359.99622, -0.25, "21.5 R", "F51")
        );
        Path gz = folder.newFile("wrap.txt.gz").toPath();
        Files.write(gz, gzipLines(lines));

        MpcItfCatalog.SearchResult result = MpcItfCatalog.search(gz, List.of(obj), 15, null);
        MpcItfCatalog.FindingMatches fm = result.byFinding().get("T1");
        assertNotNull(fm);
        assertEquals(1, fm.sameNight().size());
        assertEquals("F51 Wrap001", fm.sameNight().getFirst().tracklet().label());
        assertEquals(-0.25, fm.sameNight().getFirst().tracklet().midDecDegrees(), 1e-3);
    }

    @Test
    public void downloadSupportsConditionalRefresh304AndProgress() throws Exception {
        byte[] payload = gzipLines(List.of(
                mpcLine("Test001", 2022, 8, 24.25, 357.0, -1.8, "22.0 V", "W84")
        ));
        Instant remoteTime = Instant.parse("2026-11-26T15:00:00Z");
        String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(remoteTime.atZone(ZoneOffset.UTC));
        AtomicInteger requests = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/itf.txt.gz", exchange -> {
            requests.incrementAndGet();
            String ims = exchange.getRequestHeaders().getFirst("If-Modified-Since");
            if (httpDate.equals(ims)) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Last-Modified", httpDate);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/itf.txt.gz");
            Path home = folder.newFolder("home").toPath();
            Path dest = MpcItfCatalog.cacheFile(home);
            assertFalse(MpcItfCatalog.cacheStatus(home).exists());

            AtomicLong lastRead = new AtomicLong();
            MpcItfCatalog.DownloadResult first = MpcItfCatalog.downloadFrom(uri, dest, false, (read, total) -> lastRead.set(read));
            assertTrue(first.updated());
            assertEquals(payload.length, first.sizeBytes());
            assertEquals(payload.length, lastRead.get());
            assertTrue(MpcItfCatalog.cacheStatus(home).exists());

            MpcItfCatalog.DownloadResult second = MpcItfCatalog.downloadFrom(uri, dest, false, null);
            assertFalse("Unchanged snapshot should return HTTP 304 without rewriting", second.updated());
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }
}
