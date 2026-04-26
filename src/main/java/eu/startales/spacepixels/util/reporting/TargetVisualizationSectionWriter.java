package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes the per-target visualization section for streaks, moving tracks, anomalies, and their supporting
 * astrometric summaries.
 */
final class TargetVisualizationSectionWriter {

    private TargetVisualizationSectionWriter() {
    }

    static void writeSection(PrintWriter report, DetectionReportContext context) throws IOException {
        report.println("<h2>Target Visualizations</h2>");

        if (context.singleStreaks.isEmpty()
                && context.streakTracks.isEmpty()
                && context.movingTargets.isEmpty()
                && context.anomalies.isEmpty()
                && context.suspectedStreakTracks.isEmpty()) {
            report.println("<div class='panel'><p>No moving tracks, single-frame streaks, anomaly rescues, or suspected streak tracks were detected in this session.</p></div>");
        }

        if (!context.singleStreaks.isEmpty()) {
            writeSingleStreaks(report, context);
        }

        if (!context.streakTracks.isEmpty() || !context.suspectedStreakTracks.isEmpty()) {
            writeStreakTracks(report, context);
        }

        if (!context.movingTargets.isEmpty()) {
            writeMovingTargets(report, context);
        }

        if (!context.anomalies.isEmpty()) {
            writeAnomalies(report, context);
        }
    }

    private static void writeSingleStreaks(PrintWriter report,
                                           DetectionReportContext context) throws IOException {
        report.println("<h3 style='color: #ff9933; margin-top: 30px; border-bottom: 1px solid #444; padding-bottom: 5px;'>Single Streaks</h3>");
        int counter = 1;
        for (TrackLinker.Track track : context.singleStreaks) {
            TrackCropGeometry.CropBounds cropBounds = new TrackCropGeometry.CropBounds(track, context.settings.getTrackCropPadding());
            SourceExtractor.DetectedObject point = track.points.get(0);
            int frameIndex = point.sourceFrameIndex;
            int partCount = track.points.size();
            String partBadge = partCount > 1
                    ? " <span style='background: #6b4a20; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>" + partCount + " Parts</span>"
                    : "";

            report.println("<div class='detection-card streak-title'>");
            report.println("<div class='detection-title'>Single Streak Event S" + counter + partBadge + "</div>");
            report.print(buildSingleFrameEventSummaryHtml(track, context.fitsFiles));

            short[][] croppedData = TrackVisualizationRenderer.robustEdgeAwareCrop(
                    context.rawFrames.get(frameIndex),
                    cropBounds.fixedCenterX,
                    cropBounds.fixedCenterY,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight);
            BufferedImage streakImage = TrackVisualizationRenderer.createDisplayImage(croppedData, context.settings);
            String streakFileName = "single_streak_" + counter + ".png";
            TrackVisualizationRenderer.saveLosslessPng(streakImage, new File(context.exportDir, streakFileName));

            String shapeFileName = "single_streak_" + counter + "_shape.png";
            BufferedImage streakShapeImage = TrackVisualizationRenderer.createSingleStreakShapeImage(
                    track.points,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight,
                    cropBounds.startX,
                    cropBounds.startY,
                    false);
            TrackVisualizationRenderer.saveLosslessPng(streakShapeImage, new File(context.exportDir, shapeFileName));

            report.println("<div class='image-container'>");
            report.println("<div><a href='" + streakFileName + "' target='_blank'><img src='" + streakFileName + "' alt='Detection Image' /></a><br/><center><small>Detection Image</small></center></div>");
            report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Shape Footprint' /></a><br/><center><small>Shape Footprint Map</small></center></div>");
            report.println("</div>");

            String entriesHtml = buildStreakPointEntriesHtml(track, context.astrometryContext, true);
            if (partCount > 1) {
                report.println(buildFoldableStreakDetailsHtml(
                        "Show individual streak parts (" + partCount + ")",
                        "Detection Coordinates & Part Metrics",
                        entriesHtml));
            } else {
                report.println("<strong>Detection Coordinates:</strong><ul class='source-list'>");
                report.println(entriesHtml);
                report.println("</ul>");
            }
            if (partCount > 1) {
                report.print(DetectionReportAstrometry.buildTrackSkyViewerHtml(
                        context.astrometryContext,
                        track,
                        "Reference epoch for single-streak frame lookup"));
            } else {
                report.print(DetectionReportAstrometry.buildSingleFrameSkyViewerHtml(
                        context.astrometryContext,
                        point,
                        "Reference epoch for streak lookup"));
            }
            report.println("</div>");
            counter++;
        }
    }

    private static void writeStreakTracks(PrintWriter report,
                                          DetectionReportContext context) throws IOException {
        report.println("<h3 style='color: #ffcc33; margin-top: 30px; border-bottom: 1px solid #444; padding-bottom: 5px;'>Streak Tracks</h3>");
        if (!context.suspectedStreakTracks.isEmpty()) {
            report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>Suspected streak tracks grouped from same-frame rescued anomalies are shown here alongside the confirmed multi-frame streak tracks.</p>");
        }

        int counter = 1;
        for (TrackLinker.Track track : context.streakTracks) {
            TrackCropGeometry.CropBounds cropBounds = new TrackCropGeometry.CropBounds(track, context.settings.getTrackCropPadding());
            List<BufferedImage> starCentricFrames = new ArrayList<>();
            Set<Integer> processedFrames = new HashSet<>();

            for (SourceExtractor.DetectedObject point : track.points) {
                if (!processedFrames.add(point.sourceFrameIndex)) {
                    continue;
                }
                starCentricFrames.add(TrackVisualizationRenderer.createStarCentricHighlightedFrame(
                        context.rawFrames.get(point.sourceFrameIndex),
                        cropBounds,
                        point,
                        context.settings,
                        Color.WHITE,
                        null,
                        null,
                        false));
            }

            String shapeFileName = "streak_track_" + counter + "_shape.png";
            BufferedImage shapeImage = TrackVisualizationRenderer.createTrackShapeImage(
                    track,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight,
                    cropBounds.startX,
                    cropBounds.startY);
            TrackVisualizationRenderer.saveLosslessPng(shapeImage, new File(context.exportDir, shapeFileName));

            String starFileName = "streak_track_" + counter + "_star_centric.gif";
            TrackVisualizationRenderer.saveAnimatedGif(
                    starCentricFrames,
                    new File(context.exportDir, starFileName),
                    context.settings);
            SkyOrientationOverlay streakTrackOverlay = buildSkyOrientationOverlay(
                    context.astrometryContext,
                    cropBounds.fixedCenterX,
                    cropBounds.fixedCenterY,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight);

            report.println("<div class='detection-card streak-title' style='border-left-color: #ffcc33;'>");
            String timeBadge = track.isTimeBasedTrack ? " <span style='background: #005c99; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>⏱ Time-Based Kinematics</span>" : "";
            report.println("<div class='detection-title' style='color: #ffcc33;'>Confirmed Streak Track ST" + counter + timeBadge + "</div>");
            report.print(DetectionReportGenerator.buildTrackTimingSummaryHtml(track, context.astrometryContext));
            report.print(buildConfirmedStreakFrameFragmentSummaryHtml(track, context.fitsFiles));

            report.println("<div class='image-container'>");
            report.println(buildSkyOrientationImageTileHtml(
                    starFileName,
                    "Star Centric Animation",
                    "Star Centric",
                    null,
                    streakTrackOverlay));
            report.println(buildSkyOrientationImageTileHtml(
                    shapeFileName,
                    "Track Shape Map",
                    "Track Shape Map",
                    null,
                    streakTrackOverlay));
            report.println("</div>");
            report.println(buildFoldableStreakDetailsHtml(
                    "Show individual streak fragments (" + track.points.size() + ")",
                    "Detection Coordinates & Frames",
                    buildStreakPointEntriesHtml(track, context.astrometryContext, false)));
            report.print(DetectionReportAstrometry.buildConfirmedStreakTrackSatCheckerHtml(
                    context.astrometryContext,
                    track,
                    "streak-track-satchecker-" + counter,
                    String.format(Locale.US, "satchecker_track_%02d", counter)));
            report.print(DetectionReportAstrometry.buildTrackSkyViewerHtml(
                    context.astrometryContext,
                    track,
                    "Reference epoch for streak-track lookup",
                    streakTrackOverlay != null ? streakTrackOverlay.preferredViewerFovDegrees : null));
            report.println("</div>");
            counter++;
        }

        int suspectedCounter = 1;
        for (TrackLinker.Track track : context.suspectedStreakTracks) {
            TrackCropGeometry.CropBounds cropBounds = new TrackCropGeometry.CropBounds(track, context.settings.getTrackCropPadding());
            SourceExtractor.DetectedObject point = track.points.get(0);
            int frameIndex = point.sourceFrameIndex;
            int partCount = track.points.size();
            String partBadge = partCount > 1
                    ? " <span style='background: #6b4a20; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>" + partCount + " Parts</span>"
                    : "";
            String groupingBadge = " <span style='background: #7a5a12; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>Same-Frame Anomaly Grouping</span>";

            report.println("<div class='detection-card streak-title' style='border-left-color: #ff9933;'>");
            report.println("<div class='detection-title' style='color: #ffb347;'>Suspected Streak Track SST" + suspectedCounter + partBadge + groupingBadge + "</div>");
            report.print(buildSingleFrameEventSummaryHtml(track, context.fitsFiles));

            short[][] croppedData = TrackVisualizationRenderer.robustEdgeAwareCrop(
                    context.rawFrames.get(frameIndex),
                    cropBounds.fixedCenterX,
                    cropBounds.fixedCenterY,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight);
            BufferedImage streakImage = TrackVisualizationRenderer.createDisplayImage(croppedData, context.settings);
            String streakFileName = "suspected_streak_track_" + suspectedCounter + ".png";
            TrackVisualizationRenderer.saveLosslessPng(streakImage, new File(context.exportDir, streakFileName));

            String shapeFileName = "suspected_streak_track_" + suspectedCounter + "_shape.png";
            BufferedImage streakShapeImage = TrackVisualizationRenderer.createSingleStreakShapeImage(
                    track.points,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight,
                    cropBounds.startX,
                    cropBounds.startY,
                    false);
            TrackVisualizationRenderer.saveLosslessPng(streakShapeImage, new File(context.exportDir, shapeFileName));

            report.println("<div class='image-container'>");
            report.println("<div><a href='" + streakFileName + "' target='_blank'><img src='" + streakFileName + "' alt='Detection Image' /></a><br/><center><small>Detection Image</small></center></div>");
            report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Shape Footprint' /></a><br/><center><small>Shape Footprint Map</small></center></div>");
            report.println("</div>");

            String entriesHtml = buildStreakPointEntriesHtml(track, context.astrometryContext, true);
            if (partCount > 1) {
                report.println(buildFoldableStreakDetailsHtml(
                        "Show individual streak parts (" + partCount + ")",
                        "Detection Coordinates & Part Metrics",
                        entriesHtml));
            } else {
                report.println("<strong>Detection Coordinates:</strong><ul class='source-list'>");
                report.println(entriesHtml);
                report.println("</ul>");
            }
            if (partCount > 1) {
                report.print(DetectionReportAstrometry.buildTrackSkyViewerHtml(
                        context.astrometryContext,
                        track,
                        "Reference epoch for suspected streak frame lookup"));
            } else {
                report.print(DetectionReportAstrometry.buildSingleFrameSkyViewerHtml(
                        context.astrometryContext,
                        point,
                        "Reference epoch for suspected streak lookup"));
            }
            report.println("</div>");
            suspectedCounter++;
        }
    }

    private static void writeMovingTargets(PrintWriter report,
                                           DetectionReportContext context) throws IOException {
        report.println("<h3 style='color: #4da6ff; margin-top: 30px; border-bottom: 1px solid #444; padding-bottom: 5px;'>Moving Target Tracks</h3>");
        int counter = 1;
        for (TrackLinker.Track track : context.movingTargets) {
            TrackCropGeometry.CropBounds cropBounds = new TrackCropGeometry.CropBounds(track, context.settings.getTrackCropPadding());
            List<BufferedImage> objectCentricFrames = new ArrayList<>();
            List<BufferedImage> starCentricFrames = new ArrayList<>();
            Set<Integer> processedFrames = new HashSet<>();

            for (SourceExtractor.DetectedObject point : track.points) {
                if (!processedFrames.add(point.sourceFrameIndex)) {
                    continue;
                }
                short[][] rawImage = context.rawFrames.get(point.sourceFrameIndex);
                objectCentricFrames.add(TrackVisualizationRenderer.createCroppedDisplayImage(
                        rawImage,
                        (int) Math.round(point.x),
                        (int) Math.round(point.y),
                        context.settings.getTrackObjectCentricCropSize(),
                        context.settings.getTrackObjectCentricCropSize(),
                        context.settings));
                starCentricFrames.add(TrackVisualizationRenderer.createStarCentricHighlightedFrame(
                        rawImage,
                        cropBounds,
                        point,
                        context.settings,
                        Color.WHITE,
                        null,
                        null,
                        false));
            }

            String shapeFileName = "moving_track_" + counter + "_shape.png";
            BufferedImage shapeImage = TrackVisualizationRenderer.createTrackShapeImage(
                    track,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight,
                    cropBounds.startX,
                    cropBounds.startY);
            TrackVisualizationRenderer.saveLosslessPng(shapeImage, new File(context.exportDir, shapeFileName));

            String objectFileName = "moving_track_" + counter + "_object_centric.gif";
            String starFileName = "moving_track_" + counter + "_star_centric.gif";
            TrackVisualizationRenderer.saveAnimatedGif(objectCentricFrames, new File(context.exportDir, objectFileName), context.settings);
            TrackVisualizationRenderer.saveAnimatedGif(starCentricFrames, new File(context.exportDir, starFileName), context.settings);

            double[] meanCenter = computeTrackMeanPixelCenter(track);
            SkyOrientationOverlay movingObjectOverlay = meanCenter != null
                    ? buildSkyOrientationOverlay(
                    context.astrometryContext,
                    meanCenter[0],
                    meanCenter[1],
                    context.settings.getTrackObjectCentricCropSize(),
                    context.settings.getTrackObjectCentricCropSize())
                    : null;
            SkyOrientationOverlay movingTrackFrameOverlay = buildSkyOrientationOverlay(
                    context.astrometryContext,
                    cropBounds.fixedCenterX,
                    cropBounds.fixedCenterY,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight);

            report.println("<div class='detection-card'>");
            String timeBadge = track.isTimeBasedTrack ? " <span style='background: #005c99; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>⏱ Time-Based Kinematics</span>" : "";
            report.println("<div class='detection-title'>Moving Target Track T" + counter + timeBadge + "</div>");
            report.print(DetectionReportGenerator.buildTrackTimingSummaryHtml(track, context.astrometryContext));

            report.println("<div class='image-container'>");
            report.println(buildSkyOrientationImageTileHtml(
                    objectFileName,
                    "Object Centric",
                    "Object Centric",
                    null,
                    movingObjectOverlay));
            report.println(buildSkyOrientationImageTileHtml(
                    starFileName,
                    "Star Centric",
                    "Star Centric",
                    null,
                    movingTrackFrameOverlay));
            report.println(buildSkyOrientationImageTileHtml(
                    shapeFileName,
                    "Track Shape Map",
                    "Track Shape Map",
                    null,
                    movingTrackFrameOverlay));
            report.println("</div>");

            report.println("<div style='margin-bottom: 20px;'>");
            report.println("<strong style='color: #ccc;'>Pixel Evolution (Tight Crops):</strong>");
            report.println("<div style='display: flex; flex-wrap: wrap; gap: 10px; margin-top: 8px; align-items: flex-end;'>");

            StringBuilder shapeEvolutionHtml = new StringBuilder();
            shapeEvolutionHtml.append("<div style='margin-bottom: 20px;'>\n");
            shapeEvolutionHtml.append("<strong style='color: #ccc;'>Shape Evolution (Detected Pixels):</strong>\n");
            shapeEvolutionHtml.append("<div style='display: flex; flex-wrap: wrap; gap: 10px; margin-top: 8px; align-items: flex-end;'>\n");

            for (int i = 0; i < track.points.size(); i++) {
                SourceExtractor.DetectedObject point = track.points.get(i);
                double objectRadius = TrackCropGeometry.computeBoundingFootprintRadius(point);
                int tightCropSize = TrackCropGeometry.computeSquareCropSize(objectRadius, 50, 24);
                short[][] tightCropData = TrackVisualizationRenderer.robustEdgeAwareCrop(
                        context.rawFrames.get(point.sourceFrameIndex),
                        (int) Math.round(point.x),
                        (int) Math.round(point.y),
                        tightCropSize,
                        tightCropSize);
                BufferedImage tightImage = TrackVisualizationRenderer.createDisplayImage(tightCropData, context.settings);
                String tightFileName = "moving_track_" + counter + "_pt_" + (i + 1) + "_tight.png";
                TrackVisualizationRenderer.saveLosslessPng(tightImage, new File(context.exportDir, tightFileName));
                report.println("<div><a href='" + tightFileName + "' target='_blank'><img src='" + tightFileName + "' alt='Pt " + (i + 1) + "' style='max-width: none; min-width: 50px;' /></a><br/><center><small>[" + (i + 1) + "]</small></center></div>");

                int startX = (int) Math.round(point.x) - (tightCropSize / 2);
                int startY = (int) Math.round(point.y) - (tightCropSize / 2);
                BufferedImage tightShapeImage = TrackVisualizationRenderer.createSingleStreakShapeImage(
                        Collections.singletonList(point),
                        tightCropSize,
                        tightCropSize,
                        startX,
                        startY,
                        false);
                String tightShapeFileName = "moving_track_" + counter + "_pt_" + (i + 1) + "_shape.png";
                TrackVisualizationRenderer.saveLosslessPng(tightShapeImage, new File(context.exportDir, tightShapeFileName));

                shapeEvolutionHtml.append("<div><a href='")
                        .append(tightShapeFileName)
                        .append("' target='_blank'><img src='")
                        .append(tightShapeFileName)
                        .append("' alt='Shape ")
                        .append(i + 1)
                        .append("' style='max-width: none; min-width: 50px;' /></a><br/><center><small>[")
                        .append(i + 1)
                        .append("]</small></center></div>\n");
            }
            report.println("</div></div>");

            shapeEvolutionHtml.append("</div></div>\n");
            report.print(shapeEvolutionHtml.toString());

            report.println(buildFoldableStreakDetailsHtml(
                    "Show detection coordinates & frames (" + track.points.size() + ")",
                    "Detection Coordinates & Frames",
                    buildMovingTrackPointEntriesHtml(track, context.astrometryContext)));
            report.print(DetectionReportAstrometry.buildMovingTrackSolarSystemIdentificationHtml(
                    context.astrometryContext,
                    track,
                    "moving-track-jpl-" + counter,
                    String.format(Locale.US, "jpl_track_%02d", counter)));
            report.println("</div>");
            counter++;
        }
    }

    private static void writeAnomalies(PrintWriter report,
                                       DetectionReportContext context) throws IOException {
        report.println("<h3 style='color: #ff3333; margin-top: 30px; border-bottom: 1px solid #444; padding-bottom: 5px;'>Single-Frame Anomalies (Optical Flashes)</h3>");
        report.println("<div class='astro-note' style='margin-bottom: 15px;'>Ordered by source frame index. If multiple anomalies land on the same frame, peak-sigma rescues are shown before integrated-sigma rescues.</div>");
        int counter = 1;
        for (TrackLinker.AnomalyDetection anomaly : context.anomalies) {
            SourceExtractor.DetectedObject point = anomaly.object;
            TrackCropGeometry.CropBounds cropBounds = new TrackCropGeometry.CropBounds(point, context.settings.getTrackCropPadding());
            int frameIndex = point.sourceFrameIndex;

            report.println("<div class='detection-card streak-title' style='border-left-color: #ff3333; color: #ff3333;'>");
            report.println("<div class='detection-title' style='color: #ff3333;'>Anomaly Event A" + counter + " <span style='background: #7a1f50; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>" + DetectionReportGenerator.escapeHtml(formatAnomalyTypeLabel(anomaly.type).replace("Rescue Type: ", "")) + "</span></div>");

            BufferedImage detectionImage = TrackVisualizationRenderer.createCroppedDisplayImage(
                    context.rawFrames.get(frameIndex),
                    cropBounds.fixedCenterX,
                    cropBounds.fixedCenterY,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight,
                    context.settings);
            String detectionFileName = "anomaly_" + counter + "_detection.png";
            TrackVisualizationRenderer.saveLosslessPng(detectionImage, new File(context.exportDir, detectionFileName));

            String shapeFileName = "anomaly_" + counter + "_shape.png";
            BufferedImage shapeImage = TrackVisualizationRenderer.createSingleStreakShapeImage(
                    Collections.singletonList(point),
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight,
                    cropBounds.startX,
                    cropBounds.startY,
                    false);
            TrackVisualizationRenderer.saveLosslessPng(shapeImage, new File(context.exportDir, shapeFileName));

            String maskFileName = null;
            DetectionReportGenerator.MaskOverlapStats maskOverlapStats = new DetectionReportGenerator.MaskOverlapStats(0, 0);
            if (context.masterStackData != null && context.masterVetoMask != null) {
                BufferedImage maskOverlayImage = DetectionReportGenerator.createCroppedMaskOverlay(
                        context.masterStackData,
                        context.masterVetoMask,
                        cropBounds.fixedCenterX,
                        cropBounds.fixedCenterY,
                        cropBounds.trackBoxWidth,
                        cropBounds.trackBoxHeight,
                        point,
                        new Color(255, 32, 32),
                        context.settings);
                maskFileName = "anomaly_" + counter + "_master_mask.png";
                TrackVisualizationRenderer.saveLosslessPng(maskOverlayImage, new File(context.exportDir, maskFileName));
                maskOverlapStats = DetectionReportGenerator.computeMaskOverlapStats(
                        point,
                        context.masterVetoMask,
                        context.masterStackData[0].length,
                        context.masterStackData.length);
            }

            String contextGifFileName = "anomaly_" + counter + "_context.gif";
            List<BufferedImage> contextFrames = new ArrayList<>();
            int[] frameSequence = {frameIndex - 1, frameIndex, frameIndex + 1};
            for (int index : frameSequence) {
                if (index >= 0 && index < context.rawFrames.size()) {
                    contextFrames.add(TrackVisualizationRenderer.createStarCentricHighlightedFrame(
                            context.rawFrames.get(index),
                            cropBounds,
                            point,
                            context.settings,
                            Color.WHITE,
                            null,
                            null,
                            false));
                }
            }
            TrackVisualizationRenderer.saveAnimatedGif(contextFrames, new File(context.exportDir, contextGifFileName), context.settings);

            report.println("<div class='image-container'>");
            report.println("<div><a href='" + detectionFileName + "' target='_blank'><img src='" + detectionFileName + "' alt='Detection Image' /></a><br/><center><small>Detection Image</small></center></div>");
            report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Shape Footprint' /></a><br/><center><small>Shape Footprint Map</small></center></div>");
            if (maskFileName != null) {
                report.println("<div><a href='" + maskFileName + "' target='_blank'><img src='" + maskFileName + "' alt='Master Veto Mask' /></a><br/><center><small>Master Veto Mask</small></center></div>");
            }
            report.println("<div><a href='" + contextGifFileName + "' target='_blank'><img src='" + contextGifFileName + "' alt='Anomaly Context' /></a><br/><center><small>Context (Before / Flash / After)</small></center></div>");
            report.println("</div>");

            String metricsText = buildAnomalyMetricsText(anomaly);
            String overlapColor = "#aaaaaa";
            String overlapAssessment = "n/a";
            if (maskOverlapStats.totalPixels > 0) {
                if (maskOverlapStats.fraction > context.config.maxMaskOverlapFraction) {
                    overlapColor = "#ff6b6b";
                    overlapAssessment = "above configured limit";
                } else if (maskOverlapStats.fraction > context.config.maxMaskOverlapFraction * 0.8) {
                    overlapColor = "#ffcc66";
                    overlapAssessment = "near configured limit";
                } else {
                    overlapColor = "#66d9a3";
                    overlapAssessment = "comfortably below limit";
                }
            }
            report.println("<strong>Detection Coordinate:</strong><ul class='source-list'>" + DetectionReportAstrometry.buildSourceCoordinateListEntry(point.sourceFilename, context.astrometryContext, point.x, point.y, metricsText) + "</ul>");
            if (maskOverlapStats.totalPixels > 0) {
                report.println("<div style='font-family: monospace; font-size: 12px; color: #aaa;'>Veto-mask overlap: <span style='color:" + overlapColor + "; font-weight: bold;'>" + String.format(Locale.US, "%.1f%%", maskOverlapStats.fraction * 100.0) + "</span> (" + maskOverlapStats.overlappingPixels + " / " + maskOverlapStats.totalPixels + " detection pixels) | Limit: " + String.format(Locale.US, "%.1f%%", context.config.maxMaskOverlapFraction * 100.0) + " <span style='color:" + overlapColor + ";'>[" + overlapAssessment + "]</span></div>");
            }
            report.println("</div>");
            counter++;
        }
    }

    private static String buildStreakMetricsText(SourceExtractor.DetectedObject detection) {
        return String.format(
                Locale.US,
                "Flux: %.1f, Pixels: %d, Elongation: %.2f, Angle: %s, Peak Sigma: %.2f",
                detection.totalFlux,
                (int) detection.pixelArea,
                detection.elongation,
                formatAxisAngleDegrees(detection.angle),
                detection.peakSigma);
    }

    private static String buildMovingTrackMetricsText(SourceExtractor.DetectedObject detection) {
        return String.format(
                Locale.US,
                "Flux: %.1f, Pixels: %d, Elongation: %.2f, FWHM: %.2f, Peak Sigma: %.2f, UTC: %s",
                detection.totalFlux,
                (int) detection.pixelArea,
                detection.elongation,
                detection.fwhm,
                detection.peakSigma,
                formatUtcTimestamp(detection.timestamp));
    }

    private static String buildAnomalyMetricsText(TrackLinker.AnomalyDetection anomaly) {
        SourceExtractor.DetectedObject detection = anomaly.object;
        return String.format(
                Locale.US,
                "%s, Peak Sigma: %.2f, Integrated Sigma: %.2f, Flux: %.1f, Pixels: %d, Elongation: %.2f",
                formatAnomalyTypeLabel(anomaly.type),
                detection.peakSigma,
                detection.integratedSigma,
                detection.totalFlux,
                (int) detection.pixelArea,
                detection.elongation);
    }

    private static String formatAnomalyTypeLabel(TrackLinker.AnomalyType type) {
        if (type == null) {
            return "Rescue Type: Unknown";
        }
        switch (type) {
            case PEAK_SIGMA:
                return "Rescue Type: Peak-Sigma";
            case INTEGRATED_SIGMA:
                return "Rescue Type: Integrated-Sigma";
            default:
                return "Rescue Type: " + type.name();
        }
    }

    private static String buildConfirmedStreakFrameFragmentSummaryHtml(TrackLinker.Track track,
                                                                       FitsFileInformation[] fitsFiles) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return "";
        }

        LinkedHashMap<Integer, List<Integer>> frameToFragmentIndices = new LinkedHashMap<>();
        LinkedHashMap<Integer, String> frameToFilename = new LinkedHashMap<>();
        LinkedHashMap<Integer, Long> frameToTimestamp = new LinkedHashMap<>();

        for (int i = 0; i < track.points.size(); i++) {
            SourceExtractor.DetectedObject point = track.points.get(i);
            if (point == null) {
                continue;
            }

            int frameIndex = point.sourceFrameIndex;
            frameToFragmentIndices.computeIfAbsent(frameIndex, ignored -> new ArrayList<>()).add(i + 1);

            if (!frameToFilename.containsKey(frameIndex)) {
                String fileLabel = point.sourceFilename;
                if ((fileLabel == null || fileLabel.isBlank())
                        && fitsFiles != null
                        && frameIndex >= 0
                        && frameIndex < fitsFiles.length
                        && fitsFiles[frameIndex] != null) {
                    fileLabel = fitsFiles[frameIndex].getFileName();
                }
                frameToFilename.put(frameIndex, fileLabel);
            }

            if (!frameToTimestamp.containsKey(frameIndex)) {
                frameToTimestamp.put(frameIndex, resolveFrameTimestampMillis(point, fitsFiles));
            }
        }

        if (frameToFragmentIndices.isEmpty()) {
            return "";
        }

        StringBuilder html = new StringBuilder();
        html.append("<div style='font-family: monospace; font-size: 12px; color: #aaa; margin-bottom: 15px;'>");
        html.append("Frames &amp; Fragments:<br>");

        for (Map.Entry<Integer, List<Integer>> entry : frameToFragmentIndices.entrySet()) {
            int frameIndex = entry.getKey();
            List<Integer> fragmentIndices = entry.getValue();
            String filename = frameToFilename.get(frameIndex);
            long timestampMillis = frameToTimestamp.getOrDefault(frameIndex, -1L);

            html.append("<span style='color:#fff;'>Frame ");
            html.append(frameIndex >= 0 ? frameIndex + 1 : "?");
            html.append("</span>");
            if (filename != null && !filename.isBlank()) {
                html.append(" <span style='color:#7fbfff;'>(").append(DetectionReportGenerator.escapeHtml(filename)).append(")</span>");
            }
            if (timestampMillis > 0L) {
                html.append(" <span style='color:#88c999;'>").append(DetectionReportGenerator.escapeHtml(formatUtcTimestamp(timestampMillis))).append("</span>");
            }
            html.append(": fragments <span style='color:#ffcc66;'>");
            for (int i = 0; i < fragmentIndices.size(); i++) {
                if (i > 0) {
                    html.append(", ");
                }
                html.append("[").append(fragmentIndices.get(i)).append("]");
            }
            html.append("</span><br>");
        }

        html.append("</div>");
        return html.toString();
    }

    private static String buildFoldableStreakDetailsHtml(String summaryLabel,
                                                         String headingLabel,
                                                         String entriesHtml) {
        if (entriesHtml == null || entriesHtml.isBlank()) {
            return "";
        }

        StringBuilder html = new StringBuilder();
        html.append("<details class='foldable-streak-details'>");
        html.append("<summary>").append(DetectionReportGenerator.escapeHtml(summaryLabel)).append("</summary>");
        html.append("<div class='foldable-streak-body'>");
        html.append("<strong>").append(DetectionReportGenerator.escapeHtml(headingLabel)).append(":</strong>");
        html.append("<ul class='source-list'>").append(entriesHtml).append("</ul>");
        html.append("</div>");
        html.append("</details>");
        return html.toString();
    }

    private static String buildStreakPointEntriesHtml(TrackLinker.Track track,
                                                      DetectionReportAstrometry.Context astrometryContext,
                                                      boolean usePartLabel) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return "";
        }

        int partCount = track.points.size();
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < track.points.size(); i++) {
            SourceExtractor.DetectedObject point = track.points.get(i);
            String metricsText = buildStreakMetricsText(point);
            String sourceFilename = point.sourceFilename != null ? point.sourceFilename : "Unknown";
            String fileLabel;
            if (partCount == 1) {
                fileLabel = sourceFilename;
            } else if (usePartLabel) {
                fileLabel = "[Part " + (i + 1) + "] " + sourceFilename;
            } else {
                fileLabel = "[" + (i + 1) + "] " + sourceFilename;
            }
            entries.append(DetectionReportAstrometry.buildSourceCoordinateListEntry(
                    fileLabel,
                    astrometryContext,
                    point.x,
                    point.y,
                    metricsText));
        }
        return entries.toString();
    }

    private static String buildMovingTrackPointEntriesHtml(TrackLinker.Track track,
                                                           DetectionReportAstrometry.Context astrometryContext) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return "";
        }

        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < track.points.size(); i++) {
            SourceExtractor.DetectedObject point = track.points.get(i);
            String metricsText = buildMovingTrackMetricsText(point);
            String sourceFilename = point.sourceFilename != null ? point.sourceFilename : "Unknown";
            entries.append(DetectionReportAstrometry.buildSourceCoordinateListEntry(
                    "[" + (i + 1) + "] " + sourceFilename,
                    astrometryContext,
                    point.x,
                    point.y,
                    metricsText));
        }
        return entries.toString();
    }

    private static String buildSingleFrameEventSummaryHtml(TrackLinker.Track track,
                                                           FitsFileInformation[] fitsFiles) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return "";
        }

        int partCount = 0;
        int frameIndex = -1;
        long frameTimestamp = -1L;
        long exposureMillis = -1L;

        for (SourceExtractor.DetectedObject point : track.points) {
            if (point == null) {
                continue;
            }

            partCount++;
            if (frameIndex < 0 && point.sourceFrameIndex >= 0) {
                frameIndex = point.sourceFrameIndex;
            }

            long candidateTimestamp = resolveFrameTimestampMillis(point, fitsFiles);
            if (candidateTimestamp > 0L && frameTimestamp <= 0L) {
                frameTimestamp = candidateTimestamp;
            }

            long candidateExposure = resolveFrameExposureMillis(point, fitsFiles);
            if (candidateExposure > 0L) {
                exposureMillis = candidateExposure;
            }
        }

        if (partCount == 0) {
            return "";
        }

        StringBuilder html = new StringBuilder();
        html.append("<div style='font-family: monospace; font-size: 12px; color: #aaa; margin-bottom: 15px;'>");
        if (frameIndex >= 0) {
            html.append("Frame: <span style='color:#fff;'>").append(frameIndex + 1).append("</span><br>");
        }
        html.append("Frame UTC: <span style='color:#fff;'>")
                .append(DetectionReportGenerator.escapeHtml(formatUtcTimestamp(frameTimestamp)))
                .append("</span><br>");
        if (exposureMillis > 0L) {
            html.append("Exposure: <span style='color:#fff;'>")
                    .append(DetectionReportGenerator.escapeHtml(formatDuration(exposureMillis)))
                    .append("</span><br>");
        }
        html.append("Streak Parts: <span style='color:#fff;'>").append(partCount).append("</span>");
        if (partCount > 1) {
            html.append(" <span style='color:#ff9933;'>(grouped same-frame components)</span>");
        }
        html.append("</div>");
        return html.toString();
    }

    private static SkyOrientationOverlay buildSkyOrientationOverlay(DetectionReportAstrometry.Context astrometryContext,
                                                                    double centerPixelX,
                                                                    double centerPixelY,
                                                                    int cropWidthPixels,
                                                                    int cropHeightPixels) {
        if (astrometryContext == null
                || !astrometryContext.hasAstrometricSolution()
                || cropWidthPixels <= 0
                || cropHeightPixels <= 0) {
            return null;
        }

        WcsCoordinateTransformer transformer = astrometryContext.getTransformer();
        if (transformer == null) {
            return null;
        }

        WcsCoordinateTransformer.SkyCoordinate center = transformer.pixelToSky(centerPixelX, centerPixelY);
        WcsCoordinateTransformer.SkyCoordinate offsetX = transformer.pixelToSky(centerPixelX + 1.0, centerPixelY);
        WcsCoordinateTransformer.SkyCoordinate offsetY = transformer.pixelToSky(centerPixelX, centerPixelY + 1.0);
        if (center == null || offsetX == null || offsetY == null) {
            return null;
        }

        double cosDec = Math.cos(Math.toRadians(center.getDecDegrees()));
        if (!Double.isFinite(cosDec) || Math.abs(cosDec) < 1.0e-9) {
            return null;
        }

        double eastPerPixelX = normalizeSkyAngleDeltaDegrees(offsetX.getRaDegrees() - center.getRaDegrees()) * cosDec;
        double northPerPixelX = offsetX.getDecDegrees() - center.getDecDegrees();
        double eastPerPixelY = normalizeSkyAngleDeltaDegrees(offsetY.getRaDegrees() - center.getRaDegrees()) * cosDec;
        double northPerPixelY = offsetY.getDecDegrees() - center.getDecDegrees();
        double determinant = eastPerPixelX * northPerPixelY - eastPerPixelY * northPerPixelX;
        if (!Double.isFinite(determinant) || Math.abs(determinant) < 1.0e-12) {
            return null;
        }

        double northDx = -eastPerPixelY / determinant;
        double northDy = eastPerPixelX / determinant;
        double eastDx = northPerPixelY / determinant;
        double eastDy = -northPerPixelX / determinant;
        double northMagnitude = Math.hypot(northDx, northDy);
        double eastMagnitude = Math.hypot(eastDx, eastDy);
        if (!Double.isFinite(northMagnitude) || !Double.isFinite(eastMagnitude)
                || northMagnitude < 1.0e-9 || eastMagnitude < 1.0e-9) {
            return null;
        }

        double northAngleFromUp = angleClockwiseFromImageUp(northDx / northMagnitude, northDy / northMagnitude);
        double eastAngleFromUp = angleClockwiseFromImageUp(eastDx / eastMagnitude, eastDy / eastMagnitude);
        double fovWidthDegrees = Math.hypot(eastPerPixelX, northPerPixelX) * cropWidthPixels;
        double fovHeightDegrees = Math.hypot(eastPerPixelY, northPerPixelY) * cropHeightPixels;
        if (!Double.isFinite(fovWidthDegrees) || !Double.isFinite(fovHeightDegrees)
                || fovWidthDegrees <= 0.0d || fovHeightDegrees <= 0.0d) {
            return null;
        }

        return new SkyOrientationOverlay(
                northAngleFromUp,
                eastAngleFromUp,
                fovWidthDegrees,
                fovHeightDegrees,
                Math.max(fovWidthDegrees, fovHeightDegrees),
                WcsCoordinateTransformer.formatRa(center.getRaDegrees()),
                WcsCoordinateTransformer.formatDec(center.getDecDegrees()));
    }

    private static double[] computeTrackMeanPixelCenter(TrackLinker.Track track) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return null;
        }

        double sumX = 0.0d;
        double sumY = 0.0d;
        int count = 0;
        for (SourceExtractor.DetectedObject point : track.points) {
            if (point == null) {
                continue;
            }
            sumX += point.x;
            sumY += point.y;
            count++;
        }

        if (count == 0) {
            return null;
        }
        return new double[]{sumX / count, sumY / count};
    }

    private static String buildSkyOrientationImageTileHtml(String fileName,
                                                           String altText,
                                                           String captionText,
                                                           String imageStyle,
                                                           SkyOrientationOverlay overlay) {
        StringBuilder html = new StringBuilder();
        html.append("<div>");
        html.append("<div class='sky-orientation-figure'>");
        html.append("<a href='").append(DetectionReportGenerator.escapeHtml(fileName)).append("' target='_blank'>");
        html.append("<img src='").append(DetectionReportGenerator.escapeHtml(fileName)).append("' alt='").append(DetectionReportGenerator.escapeHtml(altText)).append("'");
        if (imageStyle != null && !imageStyle.isBlank()) {
            html.append(" style='").append(DetectionReportGenerator.escapeHtml(imageStyle)).append("'");
        }
        html.append(" /></a>");
        if (overlay != null) {
            html.append("<div class='sky-orientation-overlay'>");
            html.append("<div class='sky-orientation-title'>Sky Orientation</div>");
            html.append("<div class='sky-orientation-compass'>");
            html.append("<div class='sky-orientation-arrow north' style='--rotation:")
                    .append(DetectionReportGenerator.escapeHtml(formatDecimal(overlay.northAngleFromUpDegrees, 1)))
                    .append("deg;'><span>N</span></div>");
            html.append("<div class='sky-orientation-arrow east' style='--rotation:")
                    .append(DetectionReportGenerator.escapeHtml(formatDecimal(overlay.eastAngleFromUpDegrees, 1)))
                    .append("deg;'><span>E</span></div>");
            html.append("<div class='sky-orientation-center'></div>");
            html.append("</div>");
            html.append("<div class='sky-orientation-meta'>FOV ")
                    .append(DetectionReportGenerator.escapeHtml(formatFieldOfViewDimensions(overlay.fovWidthDegrees, overlay.fovHeightDegrees)))
                    .append("</div>");
            html.append("<div class='sky-orientation-meta'>Center RA ")
                    .append(DetectionReportGenerator.escapeHtml(overlay.centerRaText))
                    .append("</div>");
            html.append("<div class='sky-orientation-meta'>Center Dec ")
                    .append(DetectionReportGenerator.escapeHtml(overlay.centerDecText))
                    .append("</div>");
            html.append("</div>");
        }
        html.append("</div>");
        html.append("<br/><center><small>").append(DetectionReportGenerator.escapeHtml(captionText)).append("</small></center>");
        html.append("</div>");
        return html.toString();
    }

    private static double normalizeSkyAngleDeltaDegrees(double deltaDegrees) {
        double normalized = deltaDegrees % 360.0d;
        if (normalized > 180.0d) {
            normalized -= 360.0d;
        }
        if (normalized < -180.0d) {
            normalized += 360.0d;
        }
        return normalized;
    }

    private static double angleClockwiseFromImageUp(double dx, double dy) {
        double angle = Math.toDegrees(Math.atan2(dx, -dy));
        return angle < 0.0d ? angle + 360.0d : angle;
    }

    private static String formatFieldOfViewDimensions(double widthDegrees, double heightDegrees) {
        double largestDimension = Math.max(widthDegrees, heightDegrees);
        if (!Double.isFinite(largestDimension) || largestDimension <= 0.0d) {
            return "n/a";
        }
        if (largestDimension >= 1.0d) {
            return formatDecimal(widthDegrees, 2) + "° x " + formatDecimal(heightDegrees, 2) + "°";
        }
        return formatDecimal(widthDegrees * 60.0d, 1) + "' x " + formatDecimal(heightDegrees * 60.0d, 1) + "'";
    }

    private static long resolveFrameTimestampMillis(SourceExtractor.DetectedObject detection,
                                                    FitsFileInformation[] fitsFiles) {
        if (detection == null) {
            return -1L;
        }
        if (detection.timestamp > 0L) {
            return detection.timestamp;
        }
        if (fitsFiles == null || detection.sourceFrameIndex < 0 || detection.sourceFrameIndex >= fitsFiles.length) {
            return -1L;
        }
        FitsFileInformation frameInfo = fitsFiles[detection.sourceFrameIndex];
        return frameInfo != null ? frameInfo.getObservationTimestamp() : -1L;
    }

    private static long resolveFrameExposureMillis(SourceExtractor.DetectedObject detection,
                                                   FitsFileInformation[] fitsFiles) {
        if (detection == null) {
            return -1L;
        }
        if (detection.exposureDuration > 0L) {
            return detection.exposureDuration;
        }
        if (fitsFiles == null || detection.sourceFrameIndex < 0 || detection.sourceFrameIndex >= fitsFiles.length) {
            return -1L;
        }
        FitsFileInformation frameInfo = fitsFiles[detection.sourceFrameIndex];
        return frameInfo != null ? frameInfo.getExposureDurationMillis() : -1L;
    }

    private static String formatUtcTimestamp(long timestampMillis) {
        if (timestampMillis <= 0L) {
            return "n/a";
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(timestampMillis));
    }

    private static String formatDuration(long durationMillis) {
        if (durationMillis <= 0L) {
            return "n/a";
        }
        if (durationMillis < 1000L) {
            return durationMillis + " ms";
        }
        double durationSeconds = durationMillis / 1000.0d;
        if (durationSeconds < 60.0d) {
            return formatDecimal(durationSeconds, 1) + " s";
        }
        double durationMinutes = durationSeconds / 60.0d;
        if (durationMinutes < 60.0d) {
            return formatDecimal(durationMinutes, 1) + " min";
        }
        return formatDecimal(durationMinutes / 60.0d, 2) + " h";
    }

    private static String formatAxisAngleDegrees(double angleRadians) {
        if (Double.isNaN(angleRadians) || Double.isInfinite(angleRadians)) {
            return "n/a";
        }
        double degrees = Math.toDegrees(angleRadians);
        while (degrees < 0.0d) {
            degrees += 180.0d;
        }
        while (degrees >= 180.0d) {
            degrees -= 180.0d;
        }
        return formatDecimal(degrees, 1) + "°";
    }

    private static String formatDecimal(double value, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", value);
    }

    private static final class SkyOrientationOverlay {
        private final double northAngleFromUpDegrees;
        private final double eastAngleFromUpDegrees;
        private final double fovWidthDegrees;
        private final double fovHeightDegrees;
        private final double preferredViewerFovDegrees;
        private final String centerRaText;
        private final String centerDecText;

        private SkyOrientationOverlay(double northAngleFromUpDegrees,
                                      double eastAngleFromUpDegrees,
                                      double fovWidthDegrees,
                                      double fovHeightDegrees,
                                      double preferredViewerFovDegrees,
                                      String centerRaText,
                                      String centerDecText) {
            this.northAngleFromUpDegrees = northAngleFromUpDegrees;
            this.eastAngleFromUpDegrees = eastAngleFromUpDegrees;
            this.fovWidthDegrees = fovWidthDegrees;
            this.fovHeightDegrees = fovHeightDegrees;
            this.preferredViewerFovDegrees = preferredViewerFovDegrees;
            this.centerRaText = centerRaText;
            this.centerDecText = centerDecText;
        }
    }
}
