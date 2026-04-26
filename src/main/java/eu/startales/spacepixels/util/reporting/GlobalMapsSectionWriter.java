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
                && reportContext.suspectedStreakTracks.isEmpty()
                && reportContext.movingTargets.isEmpty()
                && reportContext.anomalies.isEmpty()
                && localRescueTracks.isEmpty()
                && reportContext.localActivityClusters.isEmpty()
                && reportContext.slowMoverCandidates.isEmpty()) {
            return;
        }

        BufferedImage globalMap = DetectionReportGenerator.createGlobalTrackMap(
                backgroundData,
                reportContext.anomalies,
                reportContext.singleStreaks,
                reportContext.streakTracks,
                reportContext.suspectedStreakTracks,
                reportContext.movingTargets,
                localRescueTracks,
                reportContext.localActivityClusters,
                reportContext.slowMoverCandidates);
        TrackVisualizationRenderer.saveLosslessPng(globalMap, new File(reportContext.exportDir, "global_track_map.png"));

        report.println("<div class='panel'>");
        report.println("<h3 style='color: #ffffff; margin-top: 0;'>Global Trajectory Map</h3>");
        report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>");
        report.println("An overview of the classified track outputs and single-frame events plotted over the master background. " +
                "Track paths are connected with lines (<strong>T#</strong> for moving object tracks, <strong>ST#</strong> for confirmed streak tracks, <strong>SST#</strong> for suspected streak groupings, <strong>LR#</strong> for local rescue candidates). " +
                "Local activity clusters are ringed as <strong>LC#</strong>, while deep-stack anomalies, single-frame anomalies, and single streaks are marked as <strong>DS#</strong>, <strong>A#</strong>, and <strong>S#</strong>.</p>");
        report.println(buildGlobalTrajectoryLegendHtml(
                reportContext.movingTargets.size(),
                reportContext.streakTracks.size(),
                reportContext.suspectedStreakTracks.size(),
                localRescueTracks.size(),
                reportContext.localActivityClusters.size(),
                reportContext.slowMoverCandidates.size(),
                reportContext.anomalies.size(),
                reportContext.singleStreaks.size()));
        report.println("<a href='global_track_map.png' target='_blank'><img src='global_track_map.png' class='native-size-image' style='border: 1px solid #555; border-radius: 4px;' alt='Global Track Map' /></a>");
        report.println("</div>");
    }

    private static void writeGlobalTransientMaps(PrintWriter report,
                                                 DetectionReportContext reportContext,
                                                 short[][] backgroundData,
                                                 List<List<SourceExtractor.DetectedObject>> allTransients) throws IOException {
        if (allTransients == null || allTransients.isEmpty()) {
            return;
        }

        BufferedImage transientMap = DetectionReportGenerator.createGlobalTransientMap(backgroundData, allTransients);
        TrackVisualizationRenderer.saveLosslessPng(transientMap, new File(reportContext.exportDir, "global_transient_map.png"));

        BufferedImage rainbowMap = DetectionReportGenerator.createRainbowClusterMap(backgroundData, allTransients);
        TrackVisualizationRenderer.saveLosslessPng(rainbowMap, new File(reportContext.exportDir, "rainbow_cluster_map.png"));

        report.println("<div class='panel'>");
        report.println("<h3 style='color: #ffffff; margin-top: 0;'>Global Transient Maps</h3>");
        report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>");
        report.println("Shows the full <strong>allTransients</strong> population carried into tracking after stationary-star vetoing. Colors map to time (Blue = Start, Red = End). This helps visualize noise floors, hot columns, surviving streak detections, and unlinked moving targets.</p>");
        report.println("<div class='image-container' style='flex-wrap: wrap;'>");
        report.println("<div style='flex: 1; min-width: 400px;'>");
        report.println("<h4 style='color: #ccc; margin-bottom: 5px;'>Exact Footprint Map</h4>");
        report.println("<p style='font-size: 12px; color: #888; margin-top: 0;'>Plots the exact raw pixels at a 1:1 scale. Both objects and streaks</p>");
        report.println("<a href='global_transient_map.png' target='_blank'><img src='global_transient_map.png' class='native-size-image' style='border: 1px solid #555; border-radius: 4px;' alt='Global Transient Map' /></a></div>");
        report.println("<div style='flex: 1; min-width: 400px;'>");
        report.println("<h4 style='color: #ccc; margin-bottom: 5px;'>Transient Cluster Map</h4>");
        report.println("<p style='font-size: 12px; color: #888; margin-top: 0;'>Cropped, downscaled, and dilated to make 'rainbows' (closely moving unlinked objects) highly visible. This map shows only point transients and not streaks. </p>");
        report.println("<div style='overflow-x: auto;'><a href='rainbow_cluster_map.png' target='_blank'><img src='rainbow_cluster_map.png' style='display: block; width: auto; max-width: none; height: auto; border: 1px solid #555; border-radius: 4px;' alt='Rainbow Cluster Map' /></a></div></div>");
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

    private static void appendGlobalTrajectoryLegendItem(StringBuilder html, String code, String label, Color color, int count) {
        if (count <= 0) {
            return;
        }
        html.append("<div class='legend-pill'>")
                .append("<span class='legend-code' style='background:")
                .append(colorToHex(color))
                .append(";'>")
                .append(DetectionReportGenerator.escapeHtml(code))
                .append("</span>")
                .append("<span>")
                .append(DetectionReportGenerator.escapeHtml(label))
                .append(": <strong>")
                .append(count)
                .append("</strong></span></div>");
    }

    private static String buildGlobalTrajectoryLegendHtml(int movingTargetCount,
                                                          int streakTrackCount,
                                                          int suspectedStreakCount,
                                                          int localRescueCount,
                                                          int localActivityCount,
                                                          int deepStackCount,
                                                          int anomalyCount,
                                                          int singleStreakCount) {
        StringBuilder html = new StringBuilder();
        html.append("<div class='map-legend'>");
        appendGlobalTrajectoryLegendItem(html, "T", "Moving object tracks", DetectionReportGenerator.GLOBAL_MAP_MOVING_TARGET_COLOR, movingTargetCount);
        appendGlobalTrajectoryLegendItem(html, "ST", "Confirmed streak tracks", DetectionReportGenerator.GLOBAL_MAP_STREAK_TRACK_COLOR, streakTrackCount);
        appendGlobalTrajectoryLegendItem(html, "SST", "Suspected streak tracks", DetectionReportGenerator.GLOBAL_MAP_SUSPECTED_STREAK_COLOR, suspectedStreakCount);
        appendGlobalTrajectoryLegendItem(html, "LR", "Local rescue candidates", DetectionReportGenerator.GLOBAL_MAP_LOCAL_RESCUE_COLOR, localRescueCount);
        appendGlobalTrajectoryLegendItem(html, "LC", "Local activity clusters", DetectionReportGenerator.GLOBAL_MAP_LOCAL_ACTIVITY_COLOR, localActivityCount);
        appendGlobalTrajectoryLegendItem(html, "DS", "Deep-stack anomalies", DetectionReportGenerator.GLOBAL_MAP_DEEP_STACK_COLOR, deepStackCount);
        appendGlobalTrajectoryLegendItem(html, "A", "Single-frame anomalies", DetectionReportGenerator.GLOBAL_MAP_ANOMALY_COLOR, anomalyCount);
        appendGlobalTrajectoryLegendItem(html, "S", "Single streaks", DetectionReportGenerator.GLOBAL_MAP_SINGLE_STREAK_COLOR, singleStreakCount);
        html.append("</div>");
        return html.toString();
    }

    private static String colorToHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }
}
