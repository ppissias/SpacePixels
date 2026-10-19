package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * Writes the global trajectory and transient overview sections of the detection report, reusing
 * the low-level map renderers but keeping the HTML/export orchestration out of the main generator.
 */
final class GlobalMapsSectionWriter {

    private GlobalMapsSectionWriter() {
    }

    static void writeSections(PrintWriter report,
                              DetectionReportContext reportContext,
                              List<TrackLinker.Track> localRescueTracks,
                              List<List<SourceExtractor.DetectedObject>> allTransients) throws IOException {
        short[][] backgroundData = resolveBackgroundData(reportContext);
        if (backgroundData == null) {
            return;
        }

        writeGlobalTrajectoryMap(report, reportContext, backgroundData, localRescueTracks);
        writeGlobalTransientMaps(report, reportContext, backgroundData, allTransients);
    }

    private static void writeGlobalTrajectoryMap(PrintWriter report,
                                                 DetectionReportContext reportContext,
                                                 short[][] backgroundData,
                                                 List<TrackLinker.Track> localRescueTracks) throws IOException {
        if (reportContext.singleStreaks.isEmpty()
                && reportContext.streakTracks.isEmpty()
                && reportContext.movingTargets.isEmpty()
                && reportContext.anomalies.isEmpty()
                && localRescueTracks.isEmpty()
                && reportContext.localActivityClusters.isEmpty()
                && reportContext.slowMoverCandidates.isEmpty()
                && (reportContext.data == null || reportContext.data.variableCandidates.isEmpty())) {
            return;
        }

        BufferedImage globalMap = DetectionReportGenerator.createGlobalTrackMap(
                backgroundData,
                reportContext.anomalies,
                reportContext.singleStreaks,
                reportContext.streakTracks,
                reportContext.movingTargets,
                localRescueTracks,
                reportContext.localActivityClusters,
                reportContext.slowMoverCandidates, reportContext.settings);
        SessionCatalogue sessionCatalogue = SessionCatalogue.of(reportContext);
        int catalogueObjects = CatalogueMapPainter.paint(globalMap, sessionCatalogue.catalogue,
                reportContext.astrometryContext != null ? reportContext.astrometryContext.getTransformer() : null);
        paintVariableCandidates(globalMap, reportContext.data);
        DataAreaPresentation.paint(globalMap, reportContext.dataArea);
        TrackVisualizationRenderer.saveLosslessPng(globalMap, new File(reportContext.exportDir, "global_track_map.png"));

        report.println("<div class='panel'>");
        report.println("<h2>Map of All Detections</h2>");
        report.println("<p class='section-lede'>");
        report.println("Every classified track and single-frame event over the median stack. " +
                "Track paths are connected with lines (<strong>T#</strong> for moving object tracks, <strong>ST#</strong> for confirmed streak tracks, <strong>LR#</strong> for local rescue candidates). " +
                "Local activity clusters are ringed as <strong>LC#</strong>, while maximum-stack slow-mover shape candidates, single-frame anomalies, and single streaks are marked as <strong>DS#</strong>, <strong>A#</strong>, and <strong>S#</strong>. " +
                "Measured variable-star candidates are circled in magenta as <strong>V#</strong>. Common-motion groups and unclassified detections are in their own sections."
                + (catalogueObjects > 0 ? " From the sky catalogue of the session: Messier, NGC, IC and Sharpless objects in yellow, bright named stars in white and named variable stars in magenta." : "")
                + (reportContext.dataArea != null ? " The dashed blue outline is the data area, where every kept frame has image data." : "")
                + "</p>");
        report.println(detectionLegendHtml(reportContext, localRescueTracks));
        int width = globalMap.getWidth(), height = globalMap.getHeight();
        int displayWidth = Math.min(1800, width);
        TrackVisualizationRenderer.saveLosslessPng(SessionPictureSectionWriter.downscale(
                TrackVisualizationRenderer.createDisplayImage(backgroundData, reportContext.settings), displayWidth,
                Math.max(1, (int) Math.round(height * displayWidth / (double) width))), new File(reportContext.exportDir, "report_map_background.png"));
        report.println("<div class='report-map'><div class='map-category-controls'>");
        java.util.Map<String, String> categories = new java.util.LinkedHashMap<>();
        if (reportContext.data != null) for (var finding : reportContext.data.findings) {
            if (!finding.id().startsWith("CM") && !finding.id().startsWith("FC")) categories.putIfAbsent(finding.id().replaceAll("[0-9]+$", ""), finding.category());
        }
        for (var entry : categories.entrySet()) report.println("<button class='id-link' type='button' data-report-category='" + entry.getKey() + "' aria-pressed='true'>" + entry.getValue() + "</button> ");
        String catalogueLayer = sessionCatalogue.catalogue != null && reportContext.astrometryContext != null && reportContext.astrometryContext.getTransformer() != null
                ? SessionPictureSectionWriter.catalogueLayer(sessionCatalogue.catalogue, reportContext.astrometryContext.getTransformer(), width, height) : null;
        if (catalogueLayer != null) report.println("<button class='id-link' type='button' data-report-category='catalogue' aria-pressed='false'>Catalogue</button>");
        report.println("</div><div class='report-map-stage'><img loading='lazy' decoding='async' src='report_map_background.png' alt='Median stack with selectable detection layers' />");
        String area = SessionPictureSectionWriter.areaLayer(reportContext, width, height);
        if (area != null) report.println(area);
        String detections = SessionPictureSectionWriter.detectionsLayer(reportContext, localRescueTracks, width, height);
        if (detections != null) report.println(detections);
        String variables = SessionPictureSectionWriter.variablesLayer(reportContext, width, height);
        if (variables != null) report.println(variables);
        if (catalogueLayer != null) report.println(catalogueLayer.replace("data-layer='catalogue'", "data-layer='catalogue' data-category='catalogue'"));
        report.println("</div></div>");
        report.println("<p class='astro-note'>Click a detection marker to open its card. <a href='global_track_map.png' target='_blank'>Open full-size map</a></p>");
        report.println("</div>");
    }

    /** The same labels, colours and counts on the overview picture and the full detection map. */
    static String detectionLegendHtml(DetectionReportContext reportContext, List<TrackLinker.Track> localRescueTracks) {
        return detectionLegendHtml(reportContext, localRescueTracks, false);
    }

    static String detectionLegendHtml(DetectionReportContext reportContext, List<TrackLinker.Track> localRescueTracks,
                                      boolean interactive) {
        return buildGlobalTrajectoryLegendHtml(
                reportContext.movingTargets.size(),
                reportContext.streakTracks.size(),
                localRescueTracks.size(),
                reportContext.localActivityClusters.size(),
                reportContext.slowMoverCandidates.size(),
                reportContext.anomalies.size(),
                reportContext.singleStreaks.size(),
                reportContext.data != null ? reportContext.data.variableCandidates.size() : 0, interactive);
    }

    private static void paintVariableCandidates(BufferedImage map, ReportData data) {
        if (data == null || data.variableCandidates.isEmpty()) return;
        var graphics = map.createGraphics();
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setStroke(new java.awt.BasicStroke(Math.max(2f, map.getWidth() / 2400f)));
            graphics.setFont(new java.awt.Font("Segoe UI", java.awt.Font.BOLD, Math.max(18, map.getWidth() / 220)));
            int radius = Math.max(10, map.getWidth() / 180);
            for (var star : data.variableCandidates) {
                var finding = data.variable(star.id);
                int x = (int) Math.round(star.x), y = (int) Math.round(star.y);
                graphics.setColor(CatalogueMapPainter.VARIABLE_COLOR);
                graphics.drawOval(x - radius, y - radius, 2 * radius, 2 * radius);
                graphics.setColor(Color.BLACK);
                graphics.drawString(finding.id(), x + radius + 4, y - radius + 1);
                graphics.setColor(CatalogueMapPainter.VARIABLE_COLOR);
                graphics.drawString(finding.id(), x + radius + 3, y - radius);
            }
        } finally { graphics.dispose(); }
    }

    private static void writeGlobalTransientMaps(PrintWriter report,
                                                 DetectionReportContext reportContext,
                                                 short[][] backgroundData,
                                                 List<List<SourceExtractor.DetectedObject>> allTransients) throws IOException {
        if (allTransients == null || allTransients.isEmpty()) {
            return;
        }

        BufferedImage transientMap = DetectionReportGenerator.createGlobalTransientMap(backgroundData, allTransients, reportContext.settings);
        TrackVisualizationRenderer.saveLosslessPng(transientMap, new File(reportContext.exportDir, "global_transient_map.png"));

        BufferedImage rainbowMap = DetectionReportGenerator.createRainbowClusterMap(backgroundData, allTransients);
        TrackVisualizationRenderer.saveLosslessPng(rainbowMap, new File(reportContext.exportDir, "rainbow_cluster_map.png"));

        report.println("<div class='panel'>");
        report.println("<h2>Time Maps of Everything That Survived the Star Mask</h2>");
        report.println("<p class='section-lede'>");
        report.println("Every detection that was not a star, before the tracker sorted it, coloured by time (blue = start, red = end). Useful for spotting noise patterns, hot columns, streaks and movers that were not linked into a track.</p>");
        report.println("<div class='image-container' style='flex-wrap: wrap;'>");
        report.println("<div style='flex: 1; min-width: 400px;'>");
        report.println("<h4 style='color: #ccc; margin-bottom: 5px;'>Exact Footprint Map</h4>");
        report.println("<p style='font-size: 12px; color: #888; margin-top: 0;'>The exact pixels of every detection, objects and streaks, at full resolution.</p>");
        report.println("<a href='global_transient_map.png' target='_blank'><img loading='lazy' decoding='async' src='global_transient_map.png' class='native-size-image' style='border: 1px solid #555; border-radius: 4px;' alt='Global Transient Map' /></a>");
        report.println("<div style='display:flex; align-items:center; gap:10px; margin-top:8px; font-size:12px; color:#aaa;'><span>Start</span><span style='flex:0 0 220px; height:12px; border-radius:3px; background:linear-gradient(90deg, hsl(240,100%,50%), hsl(180,100%,50%), hsl(120,100%,50%), hsl(60,100%,50%), hsl(0,100%,50%));'></span><span>End</span></div></div>");
        report.println("<div style='flex: 1; min-width: 400px;'>");
        report.println("<h4 style='color: #ccc; margin-bottom: 5px;'>Transient Cluster Map</h4>");
        report.println("<p style='font-size: 12px; color: #888; margin-top: 0;'>Every non-star detection, including streak pieces, as a dot at its centre. Dots of one colour are the same frame, so a line of one colour is often a streak. A rainbow chain changed frame as it moved. Cropped around the activity, downscaled and dilated so close neighbours merge. Linking a piece onto a streak does not remove it from this map.</p>");
        report.println("<div style='overflow-x: auto;'><a href='rainbow_cluster_map.png' target='_blank'><img loading='lazy' decoding='async' src='rainbow_cluster_map.png' style='display: block; width: auto; max-width: none; height: auto; border: 1px solid #555; border-radius: 4px;' alt='Rainbow Cluster Map' /></a></div></div>");
        report.println("</div>");
        report.println("</div>");
    }

    private static short[][] resolveBackgroundData(DetectionReportContext reportContext) {
        if (reportContext.masterStackData != null) {
            return reportContext.masterStackData;
        }
        if (!reportContext.rawFrames.isEmpty()) {
            return reportContext.rawFrames.get(0);
        }
        return null;
    }

    private static void appendGlobalTrajectoryLegendItem(StringBuilder html, String code, String label, Color color, int count, boolean interactive) {
        if (count <= 0) {
            return;
        }
        html.append(interactive ? "<button type='button' class='legend-pill' data-report-category='" + code
                        + "' aria-pressed='true' title='Show or hide " + DetectionReportGenerator.escapeHtml(label) + "'>"
                        : "<div class='legend-pill'>")
                .append("<span class='legend-code' style='background:")
                .append(colorToHex(color))
                .append(";'>")
                .append(DetectionReportGenerator.escapeHtml(code))
                .append("</span>")
                .append("<span>")
                .append(DetectionReportGenerator.escapeHtml(label))
                .append(": <strong>")
                .append(count)
                .append("</strong></span>").append(interactive ? "</button>" : "</div>");
    }

    private static String buildGlobalTrajectoryLegendHtml(int movingTargetCount,
                                                          int streakTrackCount,
                                                          int localRescueCount,
                                                          int localActivityCount,
                                                          int deepStackCount,
                                                          int anomalyCount,
                                                          int singleStreakCount,
                                                          int variableCount, boolean interactive) {
        StringBuilder html = new StringBuilder();
        html.append("<div class='map-legend map-category-controls'>");
        appendGlobalTrajectoryLegendItem(html, "T", "Moving object tracks", DetectionReportGenerator.GLOBAL_MAP_MOVING_TARGET_COLOR, movingTargetCount, interactive);
        appendGlobalTrajectoryLegendItem(html, "ST", "Confirmed streak tracks", DetectionReportGenerator.GLOBAL_MAP_STREAK_TRACK_COLOR, streakTrackCount, interactive);
        appendGlobalTrajectoryLegendItem(html, "LR", "Local rescue candidates", DetectionReportGenerator.GLOBAL_MAP_LOCAL_RESCUE_COLOR, localRescueCount, interactive);
        appendGlobalTrajectoryLegendItem(html, "LC", "Local activity clusters", DetectionReportGenerator.GLOBAL_MAP_LOCAL_ACTIVITY_COLOR, localActivityCount, interactive);
        appendGlobalTrajectoryLegendItem(html, "DS", "Maximum-stack shape candidates", DetectionReportGenerator.GLOBAL_MAP_DEEP_STACK_COLOR, deepStackCount, interactive);
        appendGlobalTrajectoryLegendItem(html, "A", "Single-frame anomalies", DetectionReportGenerator.GLOBAL_MAP_ANOMALY_COLOR, anomalyCount, interactive);
        appendGlobalTrajectoryLegendItem(html, "S", "Single streaks", DetectionReportGenerator.GLOBAL_MAP_SINGLE_STREAK_COLOR, singleStreakCount, interactive);
        appendGlobalTrajectoryLegendItem(html, "V", "Variable-star candidates", CatalogueMapPainter.VARIABLE_COLOR, variableCount, interactive);
        html.append("</div>");
        return html.toString();
    }

    private static String colorToHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }
}
