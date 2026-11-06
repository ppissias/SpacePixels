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

import java.io.PrintWriter;

/**
 * Writes the shared HTML scaffolding, styles, and document boundaries for detection reports and index pages.
 */
final class DetectionReportDocumentWriter {

    /** The colour roles of the report themes, then the report's and the iterative index's styles. */
    private static final String THEME = resource("report-theme.css");
    private static final String REPORT_STYLE = resource("report.css");
    private static final String INDEX_STYLE = resource("report-index.css");
    /**
     * Applies the theme and text size remembered by the browser before the page is drawn. Storage can be
     * unavailable (some browsers, private windows, pages opened from disk); the report then opens in the defaults.
     */
    static final String REMEMBERED_APPEARANCE = "<script>(function(d){try{"
            + "var t=localStorage.getItem('spacepixels.report.theme'),s=localStorage.getItem('spacepixels.report.textSize');"
            + "if(t==='light')d.setAttribute('data-theme','light');"
            + "if(s==='small'||s==='large'||s==='x-large')d.setAttribute('data-text-size',s);"
            + "}catch(e){}})(document.documentElement);</script>";

    private DetectionReportDocumentWriter() {
    }

    static void appendDetectionReportStart(PrintWriter report) {
        report.println("<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>SpacePixels Session Report</title><style>");
        report.print(THEME);
        report.print(ReportThemeRoles.findingRoles());
        report.print(REPORT_STYLE);
        report.println("</style>" + REMEMBERED_APPEARANCE + "</head><body>");
        report.println("<h1>SpacePixels Session Report</h1>");
    }

    static void appendIterativeIndexReportStart(PrintWriter report) {
        report.println("<!DOCTYPE html><html><head><meta charset='utf-8'><title>Iterative Detection Summary</title><style>");
        report.print(THEME);
        report.print(INDEX_STYLE);
        report.println("</style>" + REMEMBERED_APPEARANCE + "</head><body>");
        report.println("<h1>SpacePixels Iterative Detection Summary</h1>");
    }

    /** The colour roles, for other report pages that use the themes. */
    static String themeStyle() {
        return THEME;
    }

    static void appendHtmlDocumentEnd(PrintWriter report) {
        report.println("</body></html>");
    }

    /** A stylesheet shipped with the application, to be written into the page. */
    private static String resource(String name) {
        try (java.io.InputStream in = DetectionReportDocumentWriter.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("Missing report resource " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
