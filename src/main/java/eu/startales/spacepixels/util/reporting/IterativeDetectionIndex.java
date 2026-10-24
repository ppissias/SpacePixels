package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.config.VisualizationSettings;
import com.google.gson.GsonBuilder;
import eu.startales.spacepixels.util.IterativeDetectionPlan;
import eu.startales.spacepixels.util.StagedFile;
import io.github.ppissias.jtransient.config.DetectionConfig;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class IterativeDetectionIndex {
    private IterativeDetectionIndex() {}

    public record Frame(String filename, long timestamp, long exposureMillis, int width, int height) {}
    public record Pass(int frameCount, String outcome, String report, String inputManifest, List<Frame> observingSpan,
                       Map<String, Integer> counts, String detail) {}
    public record Run(int schemaVersion, String sourceDirectory, String preparedDirectory,
                      IterativeDetectionPlan plan, String autoTuneProfile, int autoTuneSampleLimit,
                      DetectionConfig config, VisualizationSettings visualization, String outcome, List<Pass> passes) {}

    public static void write(File directory, Run run) throws IOException {
        String json = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(run);
        publish(new File(directory, "run-data.json"), json);
        StringBuilder html = new StringBuilder("<!doctype html><html lang='en'><meta charset='utf-8'>"
                + "<title>Iterative detection results</title><style>body{font:16px system-ui;margin:2rem;max-width:100rem}"
                + "table{border-collapse:collapse;width:100%}th,td{border:1px solid #aaa;padding:.6rem;text-align:left}"
                + "td{vertical-align:top}small{display:block;margin-top:.4rem}</style><h1>Iterative detection results</h1>");
        html.append("<p>Source: ").append(escape(run.sourceDirectory())).append("</p><p>Outcome: ")
                .append(escape(label(run.outcome()))).append("</p><p>Settings: ")
                .append(run.autoTuneProfile() == null ? "current Detection Settings" : "Auto-Tune once: " + escape(label(run.autoTuneProfile())))
                .append(". Each pass uses the ordinary detection workflow and builds its own master.</p>")
                .append("<p>Counts describe each pass separately. Findings can repeat across passes; these are not unique-object totals.</p>")
                .append("<p><a href='run-data.json'>Run settings and selected input files (JSON)</a></p>")
                .append("<table><thead><tr><th>Frames</th><th>Observing span (UTC)</th><th>Outcome</th><th>Findings</th><th>Report</th></tr></thead><tbody>");
        for (Pass pass : run.passes()) {
            html.append("<tr><td>").append(pass.frameCount());
            if (pass.inputManifest() != null) html.append("<small><a href='").append(escape(pass.inputManifest())).append("'>Selected inputs</a></small>");
            html.append("</td><td>").append(escape(span(pass.observingSpan())))
                    .append("</td><td>").append(escape(label(pass.outcome())));
            if (pass.detail() != null) html.append("<small>").append(escape(pass.detail())).append("</small>");
            html.append("</td><td>");
            if (pass.counts().isEmpty()) html.append("Unavailable");
            for (Map.Entry<String, Integer> count : pass.counts().entrySet()) {
                html.append("<div>").append(escape(count.getKey())).append(": ").append(count.getValue()).append("</div>");
            }
            html.append("</td><td>");
            if (pass.report() != null) html.append("<a href='").append(escape(pass.report())).append("'>Open report</a>");
            else html.append("Unavailable");
            html.append("</td></tr>");
        }
        html.append("</tbody></table></html>");
        publish(new File(directory, "index.html"), html.toString());
    }

    public static String writeInputs(File directory, int frameCount, List<Frame> inputs) throws IOException {
        String filename = frameCount + "_inputs.json";
        publish(new File(directory, filename), new GsonBuilder().setPrettyPrinting().create().toJson(inputs));
        return filename;
    }

    private static String span(List<Frame> frames) {
        if (frames.isEmpty() || frames.getFirst().timestamp() < 0 || frames.getLast().timestamp() < 0) return "Unavailable";
        return Instant.ofEpochMilli(frames.getFirst().timestamp()) + " to " + Instant.ofEpochMilli(frames.getLast().timestamp());
    }

    private static void publish(File file, String contents) throws IOException {
        try (StagedFile staged = new StagedFile(file)) {
            staged.writeString(contents);
            staged.publish();
        }
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String label(String value) {
        String text = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
