package eu.startales.spacepixels.util.reporting;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared navigation and presentation helpers for the static report. */
final class ReportPresentation {
    static String version(String component) { return eu.startales.spacepixels.util.BuildInfo.version(component); }
    /** Delegates every finding anchor to the shared category contract. */
    static String anchor(String id) { return FindingCategory.anchorForId(id); }
    static String findingLink(String id) { return "<a href='#" + anchor(id) + "'>" + id + "</a>"; }
    /** A title badge that links a finding to the track card it belongs to. */
    static String trackBadge(String text, String id) {
        return " <a class='track-link-badge' href='#" + anchor(id) + "'>" + text + "</a>";
    }
    static String linkLabel(String label, String svg) {
        try {
            if (!FindingCategory.fromId(label).onDetectionMap()) return svg;
        } catch (IllegalArgumentException malformed) {
            return svg;
        }
        return "<a href='#" + anchor(label) + "'>" + svg + "</a>";
    }

    static void controls(PrintWriter out, DetectionReportContext context) {
        out.println("<div class='report-controls'><button type='button' class='id-link' id='report-animation-toggle' aria-pressed='false'>Pause animations</button>"
                + "<button type='button' class='id-link' id='report-previous'>Previous finding</button>"
                + "<label>Finding <select id='report-finding-select'><option value=''>Choose a finding</option>");
        if (context.data != null) for (var f : context.data.findings) {
            out.println("<option value='" + f.anchor() + "'>" + f.id() + " · " + f.category() + "</option>");
        }
        out.println("</select></label><button type='button' class='id-link' id='report-next'>Next finding</button>"
                + "<a href='report-data.json' class='id-link' download>Report data JSON</a></div>");
        if (context.data != null) out.println("<script type='application/json' id='report-findings-data'>"
                + new Gson().toJson(context.data.findings) + "</script>");
    }

    static void appendScript(PrintWriter out) {
        try (InputStream in = ReportPresentation.class.getResourceAsStream("report-interactions.js")) {
            if (in == null) throw new IllegalStateException("Missing report interactions");
            out.println("<script>" + new String(in.readAllBytes(), StandardCharsets.UTF_8) + "</script>");
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

}
