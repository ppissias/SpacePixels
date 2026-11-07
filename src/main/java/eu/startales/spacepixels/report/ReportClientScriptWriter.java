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
import java.io.PrintWriter;
import eu.startales.spacepixels.report.lookup.ReportLookupProxyServer;

/** The lookup client's data in each static report, and its testable script with this application's addresses. */
final class ReportClientScriptWriter {
    /** Writes the data the lookup client reads from the page; the proxy saves its results into the marked block. */
    static void appendLiveReportData(PrintWriter report) {
        CatalogueComparisonWriter.appendSourceNotes(report);
        report.println(ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_START_MARKER);
        report.println("<script type='application/json' id='" + ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_SCRIPT_ID
                + "'>{\"version\":1,\"entries\":{}}</script>");
        report.println(ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_END_MARKER);
    }

    /** Fills in the lookup script's proxy address and the identity of the saved-results block. */
    static String lookupScript(String script) {
        Gson json = new Gson();
        return script.replace("__SPACEPIXELS_PROXY_URL__", json.toJson(ReportLookupProxyServer.getServiceBaseUrl()))
                .replace("__SPACEPIXELS_CACHE_ID__", json.toJson(ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_SCRIPT_ID));
    }
}
