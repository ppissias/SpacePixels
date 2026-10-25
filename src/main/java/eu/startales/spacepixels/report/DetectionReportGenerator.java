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

import eu.startales.spacepixels.config.VisualizationSettings;
import io.github.ppissias.jtransient.model.AnomalyDetection;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes the iterative index that links the passes of a large run. Map pictures are {@link ReportMaps}
 * and text formatting is {@link ReportFormat}.
 */
public class DetectionReportGenerator {

    private static final Logger LOGGER = Logger.getLogger(DetectionReportGenerator.class.getName());


    // =================================================================
    // DATA MODELS
    // =================================================================

    public static class IterationSummary {
        public int frameCount;
        public String folderName;
        public int trackCount;
        public int anomalyCount;
        /** Why this pass has no report, or null when its report was written. */
        public String reportFailure;

        public IterationSummary(int frameCount, String folderName, int trackCount, int anomalyCount) {
            this(frameCount, folderName, trackCount, anomalyCount, null);
        }

        public IterationSummary(int frameCount, String folderName, int trackCount, int anomalyCount, String reportFailure) {
            this.frameCount = frameCount;
            this.folderName = folderName;
            this.trackCount = trackCount;
            this.anomalyCount = anomalyCount;
            this.reportFailure = reportFailure;
        }
    }

    /**
     * Returns an evenly spaced sample of chronological frame indices, prioritising detection frames
     * within the requested frame limit.
     */
    static List<Integer> getRepresentativeSequence(int totalFrames, java.util.Set<Integer> mandatoryFrames, int maxFrames) {
        java.util.TreeSet<Integer> selected = new java.util.TreeSet<>();

        if (totalFrames <= maxFrames) {
            for (int i = 0; i < totalFrames; i++) selected.add(i);
            return new ArrayList<>(selected);
        }

        List<Integer> mandatoryList = new ArrayList<>(mandatoryFrames);
        java.util.Collections.sort(mandatoryList);

        if (mandatoryList.size() >= maxFrames) {
            addEvenlySpacedIndices(selected, mandatoryList, maxFrames, true);
            return new ArrayList<>(selected);
        }

        selected.addAll(mandatoryList);

        List<Integer> available = new ArrayList<>();
        for (int i = 0; i < totalFrames; i++) {
            if (!mandatoryFrames.contains(i)) available.add(i);
        }

        int needed = maxFrames - selected.size();
        if (needed > 0 && !available.isEmpty()) {
            addEvenlySpacedIndices(selected, available, needed, false);
        }

        return new ArrayList<>(selected);
    }

    private static void addEvenlySpacedIndices(Set<Integer> selected,
                                               List<Integer> source,
                                               int count,
                                               boolean preferFirstWhenSingle) {
        if (selected == null || source == null || source.isEmpty() || count <= 0) {
            return;
        }
        if (count == 1) {
            selected.add(source.get(preferFirstWhenSingle ? 0 : source.size() / 2));
            return;
        }
        for (int i = 0; i < count; i++) {
            int idx = (int) Math.round(i * (source.size() - 1) / (double) (count - 1));
            selected.add(source.get(idx));
        }
    }








    // =================================================================
    // HTML EXPORT
    // =================================================================

    public static void exportIterativeIndexReport(File masterDir, List<IterationSummary> summaries) throws IOException {
        exportIterativeIndexReport(masterDir, summaries, null);
    }

    /** Writes the index of the passes; {@code note} (or null) says why the list stops early, for example after a cancel. */
    public static void exportIterativeIndexReport(File masterDir, List<IterationSummary> summaries, String note) throws IOException {
        exportIterativeIndexReportInternal(masterDir, summaries, note);
    }

    static void exportIterativeIndexReportInternal(File masterDir, List<IterationSummary> summaries, String note) throws IOException {
        File indexFile = new File(masterDir, "index.html");
        try (AtomicReportFile output = new AtomicReportFile(indexFile)) {
            java.io.PrintWriter report = output.writer();
            DetectionReportDocumentWriter.appendIterativeIndexReportStart(report);
            report.println("<div class='panel'>");
            report.println("<h2>Iteration Results</h2>");
            report.println("<p>Each iteration analyses an overlapping selection of frames. Counts must not be added: the same object can occur in several reports. Compare its position and timestamps in the detailed reports.</p>");
            if (note != null) {
                report.println("<p class='alert'>" + ReportFormat.escapeHtml(note) + "</p>");
            }
            report.println("<table><tr><th>Iteration (Frames Used)</th><th>Total Tracks Found</th><th>Anomalies Found</th><th>Action</th></tr>");

            for (IterationSummary summary : summaries) {
                report.println("<tr>");
                report.println("<td>" + summary.frameCount + " Frames</td>");
                String trackStr = summary.trackCount > 0 ? "<span class='highlight'>" + summary.trackCount + "</span>" : "0";
                report.println("<td>" + trackStr + "</td>");
                String anomalyStr = summary.anomalyCount > 0 ? "<span style='color:#ff9933;'>" + summary.anomalyCount + "</span>" : "0";
                report.println("<td>" + anomalyStr + "</td>");
                if (summary.reportFailure != null) {
                    report.println("<td class='alert'>Report not written: " + ReportFormat.escapeHtml(summary.reportFailure) + "</td>");
                } else {
                    String reportLink = summary.folderName + "/" + ReportExporter.REPORT_FILE_NAME;
                    report.println("<td><a href='" + reportLink + "'>View Detailed Report &rarr;</a></td>");
                }
                report.println("</tr>");
            }
            report.println("</table></div>");
            DetectionReportDocumentWriter.appendHtmlDocumentEnd(report);
            output.commit();
        }
    }
}
