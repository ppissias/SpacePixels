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

import eu.startales.spacepixels.util.StagedFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The styles and scripts a report page loads, written beside it from the application's resources: {@code report.css}
 * and {@code report.js}. Each report folder holds its own copy, so a report keeps its look and behaviour after an
 * update, and every part is present whichever sections the report has. The page's data stays in the page.
 */
final class ReportAssets {

    static final String STYLE_FILE = "report.css";
    static final String SCRIPT_FILE = "report.js";
    /** Loads the styles; the custom style follows it, so its rules take precedence. */
    static final String STYLE_LINK = "<link rel='stylesheet' href='" + STYLE_FILE + "'>";
    /** Loads the scripts, at the end of the page: they work on the elements above them. */
    static final String SCRIPT_TAG = "<script src='" + SCRIPT_FILE + "'></script>";

    /** The report's own style sheets, after the colour roles. */
    private static final String[] REPORT_SHEETS = {"report.css", "session-pictures.css", "faint-mover-review.css",
            "unclassified-inspector.css"};
    /** The scripts in the order they run: the lookups use the catalogue comparison, the faint-mover review the animations. */
    private static final String[] REPORT_SCRIPTS_BEFORE_LOOKUPS = {"session-pictures.js", "unclassified-inspector.js",
            "catalogue-comparison.js"};
    private static final String[] REPORT_SCRIPTS_AFTER_LOOKUPS = {"report-interactions.js", "faint-mover-review.js"};

    private ReportAssets() {
    }

    /** Writes the report page's {@link #STYLE_FILE} and {@link #SCRIPT_FILE} into its folder, each only when complete. */
    static void writeReportFiles(File folder) throws IOException {
        publish(new File(folder, STYLE_FILE), reportStyle());
        publish(new File(folder, SCRIPT_FILE), reportScript());
    }

    /** Writes the {@link #STYLE_FILE} of an iterative run's results page: the colour roles and the page's own sheet. */
    static void writeIterativeResultsStyle(File folder) throws IOException {
        publish(new File(folder, STYLE_FILE), resource("report-theme.css") + resource("iterative-results.css"));
    }

    /** The colour roles first, so the sheets can use them, then the report's sheets in the order of its sections. */
    static String reportStyle() {
        StringBuilder style = new StringBuilder(resource("report-theme.css")).append(ReportThemeRoles.findingRoles());
        for (String sheet : REPORT_SHEETS) style.append(resource(sheet));
        return style.toString();
    }

    /** All the report's scripts. A failure in one stops only that one, as when each had its own script element. */
    static String reportScript() {
        StringBuilder script = new StringBuilder();
        for (String name : REPORT_SCRIPTS_BEFORE_LOOKUPS) appendScript(script, name, resource(name));
        appendScript(script, "report-lookups.js", ReportClientScriptWriter.lookupScript(resource("report-lookups.js")));
        for (String name : REPORT_SCRIPTS_AFTER_LOOKUPS) appendScript(script, name, resource(name));
        return script.toString();
    }

    private static void appendScript(StringBuilder script, String name, String code) {
        script.append("/* ").append(name).append(" */\ntry {\n").append(code)
                .append("\n} catch (failure) { console.error('SpacePixels report: ").append(name).append("', failure); }\n");
    }

    private static void publish(File file, String contents) throws IOException {
        try (StagedFile staged = new StagedFile(file)) {
            staged.writeString(contents);
            staged.publish();
        }
    }

    /** A style sheet or script shipped with the application. */
    private static String resource(String name) {
        try (InputStream in = ReportAssets.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("Missing report resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
