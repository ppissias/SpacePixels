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

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

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
    /** The custom style a report folder can hold; the pages apply it after their built-in styles. */
    static final String CUSTOM_STYLE_FILE = "report-custom.css";
    /** Links the folder's custom style, after the built-in styles so its rules take precedence. */
    static final String CUSTOM_STYLE = "<link rel='stylesheet' href='" + CUSTOM_STYLE_FILE + "'>";
    /** The custom style of a report without a chosen style file. */
    private static final String CUSTOM_STYLE_NOTE = """
            /* Your own styles for this report. The report applies this file after its built-in styles, so the rules
               you add here take precedence; save the file and refresh the browser to see them. SpacePixels includes
               config/report-custom-example.css, which lists every colour role of the dark and light themes.
               To give every new report your style, choose a file under Detection Settings > Report Visualization. */
            """;

    private DetectionReportDocumentWriter() {
    }

    static void appendDetectionReportStart(PrintWriter report) {
        report.println("<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>SpacePixels Session Report</title><style>");
        report.print(THEME);
        report.print(ReportThemeRoles.findingRoles());
        report.print(REPORT_STYLE);
        report.println("</style>" + CUSTOM_STYLE + REMEMBERED_APPEARANCE + "</head><body>");
        report.println("<h1>SpacePixels Session Report</h1>");
    }

    static void appendIterativeIndexReportStart(PrintWriter report) {
        report.println("<!DOCTYPE html><html><head><meta charset='utf-8'><title>Iterative Detection Summary</title><style>");
        report.print(THEME);
        report.print(INDEX_STYLE);
        report.println("</style>" + CUSTOM_STYLE + REMEMBERED_APPEARANCE + "</head><body>");
        report.println("<h1>SpacePixels Iterative Detection Summary</h1>");
    }

    /** The colour roles, for other report pages that use the themes. */
    static String themeStyle() {
        return THEME;
    }

    /**
     * Gives a report folder its {@link #CUSTOM_STYLE_FILE}: a copy of the chosen style file (a relative path is taken in
     * the home folder), or else, unless the folder has one already, a file holding only a comment on how to use it.
     * A missing or unreadable style file leaves the report with its built-in styles, and is logged.
     */
    static void writeCustomStyle(String styleFile, File folder) {
        Path target = folder.toPath().resolve(CUSTOM_STYLE_FILE);
        try {
            if (styleFile != null && !styleFile.isBlank()) {
                try {
                    Path source = Path.of(System.getProperty("user.home")).resolve(styleFile);
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                } catch (IOException | InvalidPathException failure) {
                    Logger.getLogger(DetectionReportDocumentWriter.class.getName()).warning(
                            "The report style file was not copied into the report: " + failure.getMessage());
                }
            }
            if (!Files.exists(target)) Files.writeString(target, CUSTOM_STYLE_NOTE, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            Logger.getLogger(DetectionReportDocumentWriter.class.getName()).warning(
                    "The report's custom style file was not written: " + failure.getMessage());
        }
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
