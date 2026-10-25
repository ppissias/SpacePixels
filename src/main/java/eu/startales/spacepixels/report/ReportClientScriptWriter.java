/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.report;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import eu.startales.spacepixels.report.lookup.ReportLookupProxyServer;

/** Embeds the standalone, testable lookup client in each static report. */
final class ReportClientScriptWriter {
    static void appendLiveReportRenderingScript(PrintWriter report) {
        CatalogueComparisonWriter.appendScript(report);
        report.println(ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_START_MARKER);
        report.println("<script type='application/json' id='" + ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_SCRIPT_ID
                + "'>{\"version\":1,\"entries\":{}}</script>");
        report.println(ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_END_MARKER);
        try (InputStream in = ReportClientScriptWriter.class.getResourceAsStream("report-lookups.js")) {
            if (in == null) throw new IllegalStateException("Missing report lookup script");
            Gson json = new Gson();
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("__SPACEPIXELS_PROXY_URL__", json.toJson(ReportLookupProxyServer.getServiceBaseUrl()))
                    .replace("__SPACEPIXELS_CACHE_ID__", json.toJson(ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_SCRIPT_ID));
            report.println("<script>" + script + "</script>");
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
