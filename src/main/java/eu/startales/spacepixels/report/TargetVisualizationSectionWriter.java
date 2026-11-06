package eu.startales.spacepixels.report;
import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.util.SkyMath;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
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
import java.util.function.Function;

/**
 * Writes the per-target visualization section for streaks, moving tracks, anomalies, and their supporting
 * astrometric summaries.
 */
final class TargetVisualizationSectionWriter {

    private TargetVisualizationSectionWriter() {
    }

    static void writeSection(PrintWriter report, DetectionReportContext context) throws IOException {
        report.println("<h2>Moving Objects &amp; Single-Frame Events</h2>");

        if (context.singleStreaks.isEmpty()
                && context.streakTracks.isEmpty()
                && context.movingTargets.isEmpty()
                && context.anomalies.isEmpty()) {
            report.println("<div class='panel'><p>No moving tracks, single-frame streaks or anomaly rescues were detected in this session.</p></div>");
        }
        context.progress.expect(context.singleStreaks.size() + context.streakTracks.size()
                + context.movingTargets.size() + context.anomalies.size());

        if (!context.singleStreaks.isEmpty()) {
            writeSingleStreaks(report, context);
        }

        if (!context.streakTracks.isEmpty()) {
            writeStreakTracks(report, context);
        }

        if (!context.movingTargets.isEmpty()) {
            writeMovingTargets(report, context);
        }

        if (!context.anomalies.isEmpty()) {
            AnomalySectionWriter.writeSection(report, context);
        }
    }

    private static List<DetectedObject> uniqueFramePoints(Track track) {
        Map<Integer, DetectedObject> frames = new LinkedHashMap<>();
        for (var point : track.points) frames.putIfAbsent(point.sourceFrameIndex, point);
        return new ArrayList<>(frames.values());
    }

    /** A detection image with its footprint laid over it on hover (the footprint image is black outside the detection). */
    static String footprintFigure(String imageFile, String footprintFile) {
        return "<div class='footprint-figure'><a href='" + imageFile + "' target='_blank'><img loading='lazy' decoding='async' src='" + imageFile + "' alt='Detection image' />"
                + "<img loading='lazy' decoding='async' class='footprint-overlay' src='" + footprintFile + "' alt='Detected footprint' /></a>"
                + "<br/><center><small>Detection image &middot; hover to show the detected footprint in red (<a href='" + footprintFile + "' target='_blank'>open footprint</a>)</small></center></div>";
    }

    private static void writeSingleStreaks(PrintWriter report,
                                           DetectionReportContext context) throws IOException {
        report.println("<h3 id='single-streaks' style='color: var(--sp-finding-s); margin-top: 30px; border-bottom: 1px solid var(--sp-border); padding-bottom: 5px;'>Single-Frame Streaks</h3>");
        int counter = 1;
        for (Track track : context.singleStreaks) {
            context.progress.next();
            TrackCropGeometry.CropBounds cropBounds = new TrackCropGeometry.CropBounds(track, context.settings.getTrackCropPadding(), context.frameDimensions());
            DetectedObject point = track.points.get(0);
            int frameIndex = point.sourceFrameIndex;
            int partCount = track.points.size();
            String partBadge = partCount > 1
                    ? " <span style='background: var(--sp-badge-amber); color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>" + partCount + " Parts</span>"
                    : "";

            report.println("<div class='detection-card streak-title' id='" + context.findingAnchor(FindingCategory.SINGLE_STREAK, counter) + "'>");
            report.println("<div class='detection-title'>Single Streak Event " + FindingCategory.SINGLE_STREAK.id(counter) + partBadge + faintSearchBadge(track) + "</div>");
            report.print(buildSingleFrameEventSummaryHtml(track, context.fitsFiles));

            short[][] croppedData = TrackVisualizationRenderer.robustEdgeAwareCrop(
                    context.rawFrames.get(frameIndex),
                    cropBounds.fixedCenterX,
                    cropBounds.fixedCenterY,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight);
            BufferedImage streakImage = TrackVisualizationRenderer.createDisplayImage(croppedData, context.settings);
            String streakFileName = ReportFiles.link(FindingCategory.SINGLE_STREAK, "single_streak_" + counter + ".png");
            TrackVisualizationRenderer.saveLosslessPng(streakImage, context.file(streakFileName));

            String shapeFileName = ReportFiles.link(FindingCategory.SINGLE_STREAK, "single_streak_" + counter + "_shape.png");
            BufferedImage streakShapeImage = TrackVisualizationRenderer.createSingleStreakShapeImage(
                    track.points,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight,
                    cropBounds.startX,
                    cropBounds.startY,
                    false);
            TrackVisualizationRenderer.saveLosslessPng(streakShapeImage, context.file(shapeFileName));

            report.println("<div class='image-container'>");
            report.println(footprintFigure(streakFileName, shapeFileName));
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
        report.println("<h3 id='streak-tracks' style='color: var(--sp-finding-st); margin-top: 30px; border-bottom: 1px solid var(--sp-border); padding-bottom: 5px;'>Streak Tracks</h3>");
        int counter = 1;
        for (Track track : context.streakTracks) {
            context.progress.next();
            TrackCropGeometry.CropBounds cropBounds = new TrackCropGeometry.CropBounds(track, context.settings.getTrackCropPadding(), context.frameDimensions());
            List<DetectedObject> framePoints = uniqueFramePoints(track);

            String shapeFileName = ReportFiles.link(FindingCategory.STREAK_TRACK, "streak_track_" + counter + "_shape.png");
            BufferedImage shapeImage = TrackVisualizationRenderer.createTrackShapeImage(
                    track,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight,
                    cropBounds.startX,
                    cropBounds.startY);
            TrackVisualizationRenderer.saveLosslessPng(shapeImage, context.file(shapeFileName));

            String starFileName = ReportFiles.link(FindingCategory.STREAK_TRACK, "streak_track_" + counter + "_star_centric.gif");
            Iterable<short[][]> streakCrops = () -> framePoints.stream().map(point -> TrackVisualizationRenderer.robustEdgeAwareCrop(
                    context.rawFrames.get(point.sourceFrameIndex),
                    cropBounds.fixedCenterX, cropBounds.fixedCenterY,
                    cropBounds.trackBoxWidth, cropBounds.trackBoxHeight)).iterator();
            double[] streakLevels = TrackVisualizationRenderer.sharedDisplayLevels(streakCrops, context.settings);
            GifSequenceWriter.saveAnimatedGif(framePoints.stream().map(point -> TrackVisualizationRenderer.createStarCentricHighlightedFrame(
                    context.rawFrames.get(point.sourceFrameIndex), cropBounds, point, context.settings, Color.WHITE,
                    null, TrackVisualizationRenderer.frameLabel(context, point.sourceFrameIndex), false, streakLevels)).iterator(),
                    context.file(starFileName), context.settings.getGifBlinkSpeedMs(), context.animations);
            SkyOrientationOverlay streakTrackOverlay = buildSkyOrientationOverlay(
                    context.astrometryContext,
                    cropBounds.fixedCenterX,
                    cropBounds.fixedCenterY,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight);

            report.println("<div class='detection-card streak-title' id='" + context.findingAnchor(FindingCategory.STREAK_TRACK, counter) + "' style='border-left-color: var(--sp-finding-st);'>");
            String timeBadge = track.isTimeBasedTrack ? " <span style='background: var(--sp-badge-blue); color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>⏱ Time-Based Kinematics</span>" : "";
            report.println("<div class='detection-title' style='color: var(--sp-finding-st);'>Confirmed Streak Track " + FindingCategory.STREAK_TRACK.id(counter) + timeBadge + faintSearchBadge(track) + "</div>");
            report.print(ReportFormat.buildTrackTimingSummaryHtml(track, context.astrometryContext));
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
            report.print(context.data.streakObservations.get(context.trackFindings.id(track)).html(
                    "streak-track-satchecker-" + counter, String.format(Locale.US, "satchecker_track_%02d", counter)));
            report.print(DetectionReportAstrometry.buildTrackSkyViewerHtml(
                    context.astrometryContext,
                    track,
                    "Reference epoch for streak-track lookup",
                    streakTrackOverlay != null ? streakTrackOverlay.preferredViewerFovDegrees : null));
            report.println("</div>");
            counter++;
        }

    }

    private static void writeMovingTargets(PrintWriter report,
                                           DetectionReportContext context) throws IOException {
        report.println("<h3 id='moving-targets' style='color: var(--sp-accent); margin-top: 30px; border-bottom: 1px solid var(--sp-border); padding-bottom: 5px;'>Moving-Object Tracks</h3>");
        int counter = 1;
        for (Track track : context.movingTargets) {
            context.progress.next();
            TrackCropGeometry.CropBounds cropBounds = new TrackCropGeometry.CropBounds(track, context.settings.getTrackCropPadding(), context.frameDimensions());
            List<DetectedObject> framePoints = uniqueFramePoints(track);

            String shapeFileName = ReportFiles.link(FindingCategory.MOVING_TARGET, "moving_track_" + counter + "_shape.png");
            BufferedImage shapeImage = TrackVisualizationRenderer.createTrackShapeImage(
                    track,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight,
                    cropBounds.startX,
                    cropBounds.startY);
            TrackVisualizationRenderer.saveLosslessPng(shapeImage, context.file(shapeFileName));

            String objectFileName = ReportFiles.link(FindingCategory.MOVING_TARGET, "moving_track_" + counter + "_object_centric.gif");
            String starFileName = ReportFiles.link(FindingCategory.MOVING_TARGET, "moving_track_" + counter + "_star_centric.gif");
            String cleanNarrowFileName = ReportFiles.link(FindingCategory.MOVING_TARGET, "moving_track_" + counter + "_clean_narrow.gif");
            String cleanWideFileName = ReportFiles.link(FindingCategory.MOVING_TARGET, "moving_track_" + counter + "_clean_wide.gif");

            int[] frameDims = context.frameDimensions();
            int[] wideDimensions = computeWideSourceDimensions(cropBounds.trackBoxWidth, cropBounds.trackBoxHeight, frameDims[0], frameDims[1]);

            // Each crop is made when it is needed, once to measure the animation's levels and again to draw it, so a
            // long track never holds all its crops at once.
            int objectCropSize = context.settings.getTrackObjectCentricCropSize();
            Function<DetectedObject, short[][]> objectCrop = point -> TrackVisualizationRenderer.robustEdgeAwareCrop(
                    context.rawFrames.get(point.sourceFrameIndex), (int) Math.round(point.x), (int) Math.round(point.y),
                    objectCropSize, objectCropSize);
            Function<DetectedObject, short[][]> starCrop = point -> TrackVisualizationRenderer.robustEdgeAwareCrop(
                    context.rawFrames.get(point.sourceFrameIndex), cropBounds.fixedCenterX, cropBounds.fixedCenterY,
                    cropBounds.trackBoxWidth, cropBounds.trackBoxHeight);
            Function<DetectedObject, short[][]> wideCrop = point -> cropWideDownloadSource(
                    context.rawFrames.get(point.sourceFrameIndex), cropBounds, wideDimensions);
            double[] objectLevels = TrackVisualizationRenderer.sharedDisplayLevels(
                    () -> framePoints.stream().map(objectCrop).iterator(), context.settings);
            double[] starLevels = TrackVisualizationRenderer.sharedDisplayLevels(
                    () -> framePoints.stream().map(starCrop).iterator(), context.settings);
            double[] wideLevels = TrackVisualizationRenderer.sharedDisplayLevels(
                    () -> framePoints.stream().map(wideCrop).iterator(), context.settings);

            GifSequenceWriter.saveAnimatedGif(framePoints.stream().map(point ->
                    TrackVisualizationRenderer.createDisplayImageAtLevels(objectCrop.apply(point), objectLevels)).iterator(),
                    context.file(objectFileName), context.settings.getGifBlinkSpeedMs(), context.animations);
            GifSequenceWriter.saveAnimatedGif(framePoints.stream().map(point -> TrackVisualizationRenderer.createStarCentricHighlightedFrame(
                    context.rawFrames.get(point.sourceFrameIndex), cropBounds, point, context.settings, Color.WHITE, null,
                    TrackVisualizationRenderer.frameLabel(context, point.sourceFrameIndex), false, starLevels)).iterator(),
                    context.file(starFileName), context.settings.getGifBlinkSpeedMs(), context.animations);
            GifSequenceWriter.saveAnimatedGif(framePoints.stream().map(point ->
                    TrackVisualizationRenderer.createDisplayImageAtLevels(starCrop.apply(point), starLevels)).iterator(),
                    context.file(cleanNarrowFileName), context.settings.getGifBlinkSpeedMs(), context.animations);
            GifSequenceWriter.saveAnimatedGif(framePoints.stream().map(point ->
                    scaleWideDownloadFrame(TrackVisualizationRenderer.createDisplayImageAtLevels(wideCrop.apply(point), wideLevels))).iterator(),
                    context.file(cleanWideFileName), context.settings.getGifBlinkSpeedMs(), context.animations);

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

            report.println("<div class='detection-card' id='" + context.findingAnchor(FindingCategory.MOVING_TARGET, counter) + "'>");
            String timeBadge = track.isTimeBasedTrack ? " <span style='background: var(--sp-badge-blue); color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>⏱ Time-Based Kinematics</span>" : "";
            report.println("<div class='detection-title'>Moving Target Track " + FindingCategory.MOVING_TARGET.id(counter) + timeBadge + "</div>");
            KnownSolarSystemReport.writeTrackIdentification(report, context, FindingCategory.MOVING_TARGET.id(counter));
            RescueTrackAssociations.write(report, context, FindingCategory.MOVING_TARGET.id(counter));
            report.print(ReportFormat.buildTrackTimingSummaryHtml(track, context.astrometryContext));

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

            report.println("<div class='download-controls' style='margin-top: 8px; margin-bottom: 16px;'>");
            report.println("<strong style='color: var(--sp-text);'>Download animation:</strong> <a class='id-link' href='"
                    + cleanNarrowFileName + "' download='" + ReportFiles.name(cleanNarrowFileName) + "' target='_blank'>Narrow</a> <a class='id-link' href='"
                    + cleanWideFileName + "' download='" + ReportFiles.name(cleanWideFileName) + "' target='_blank'>Wide</a>");
            report.println("</div>");

            report.println("<div style='margin-bottom: 20px;'>");
            report.println("<strong style='color: var(--sp-text);'>Pixel Evolution (Tight Crops):</strong>");
            report.println("<div style='display: flex; flex-wrap: wrap; gap: 10px; margin-top: 8px; align-items: flex-end;'>");

            StringBuilder shapeEvolutionHtml = new StringBuilder();
            shapeEvolutionHtml.append("<div style='margin-bottom: 20px;'>\n");
            shapeEvolutionHtml.append("<strong style='color: var(--sp-text);'>Shape Evolution (Detected Pixels):</strong>\n");
            shapeEvolutionHtml.append("<div style='display: flex; flex-wrap: wrap; gap: 10px; margin-top: 8px; align-items: flex-end;'>\n");

            for (int i = 0; i < track.points.size(); i++) {
                DetectedObject point = track.points.get(i);
                double objectRadius = TrackCropGeometry.computeBoundingFootprintRadius(point);
                int tightCropSize = TrackCropGeometry.computeSquareCropSize(objectRadius, 50, 24);
                short[][] tightCropData = TrackVisualizationRenderer.robustEdgeAwareCrop(
                        context.rawFrames.get(point.sourceFrameIndex),
                        (int) Math.round(point.x),
                        (int) Math.round(point.y),
                        tightCropSize,
                        tightCropSize);
                BufferedImage tightImage = TrackVisualizationRenderer.createDisplayImage(tightCropData, context.settings);
                String tightFileName = ReportFiles.link(FindingCategory.MOVING_TARGET, "moving_track_" + counter + "_pt_" + (i + 1) + "_tight.png");
                TrackVisualizationRenderer.saveLosslessPng(tightImage, context.file(tightFileName));
                report.println("<div><a href='" + tightFileName + "' target='_blank'><img loading='lazy' decoding='async' src='" + tightFileName + "' alt='Pt " + (i + 1) + "' style='max-width: none; min-width: 50px;' /></a><br/><center><small>[" + (i + 1) + "]</small></center></div>");

                int startX = (int) Math.round(point.x) - (tightCropSize / 2);
                int startY = (int) Math.round(point.y) - (tightCropSize / 2);
                BufferedImage tightShapeImage = TrackVisualizationRenderer.createSingleStreakShapeImage(
                        Collections.singletonList(point),
                        tightCropSize,
                        tightCropSize,
                        startX,
                        startY,
                        false);
                String tightShapeFileName = ReportFiles.link(FindingCategory.MOVING_TARGET, "moving_track_" + counter + "_pt_" + (i + 1) + "_shape.png");
                TrackVisualizationRenderer.saveLosslessPng(tightShapeImage, context.file(tightShapeFileName));

                shapeEvolutionHtml.append("<div><a href='")
                        .append(tightShapeFileName)
                        .append("' target='_blank'><img loading='lazy' decoding='async' src='")
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

    private static String buildStreakMetricsText(DetectedObject detection) {
        return String.format(
                Locale.US,
                "Flux: %.1f, Pixels: %d, Elongation: %.2f, Angle: %s, Peak Sigma: %.2f",
                detection.totalFlux,
                (int) detection.pixelArea,
                detection.elongation,
                formatAxisAngleDegrees(detection.angle),
                detection.peakSigma)
                + (detection.fromFaintStreakSearch ? " (faint-streak search: measured on the smoothed frame)" : "");
    }

    /**
     * States what the faint-streak search contributed to a streak finding: found it (every detection is its own) or
     * includes some of its detections. Empty when it contributed nothing. It claims nothing about a run without it.
     */
    static String faintSearchBadge(Track track) {
        int faint = 0;
        for (DetectedObject point : track.points) {
            if (point != null && point.fromFaintStreakSearch) {
                faint++;
            }
        }
        if (faint == 0) {
            return "";
        }
        String text = faint == track.points.size() ? "Found by the faint-streak search"
                : "Includes " + faint + (faint == 1 ? " detection" : " detections") + " from the faint-streak search";
        return " <span title='Found on frames smoothed to the size of the stars; the measurements of these detections are made on the smoothed frame' "
                + "style='background: var(--sp-badge-purple); color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>"
                + text + "</span>";
    }

    private static String buildMovingTrackMetricsText(DetectedObject detection) {
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

    private static String buildConfirmedStreakFrameFragmentSummaryHtml(Track track,
                                                                       FitsFileInformation[] fitsFiles) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return "";
        }

        LinkedHashMap<Integer, List<Integer>> frameToFragmentIndices = new LinkedHashMap<>();
        LinkedHashMap<Integer, String> frameToFilename = new LinkedHashMap<>();
        LinkedHashMap<Integer, Long> frameToTimestamp = new LinkedHashMap<>();

        for (int i = 0; i < track.points.size(); i++) {
            DetectedObject point = track.points.get(i);
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
        html.append("<div style='font-family: monospace; font-size: 0.75rem; color: var(--sp-text-soft); margin-bottom: 15px;'>");
        html.append("Frames &amp; Fragments:<br>");

        for (Map.Entry<Integer, List<Integer>> entry : frameToFragmentIndices.entrySet()) {
            int frameIndex = entry.getKey();
            List<Integer> fragmentIndices = entry.getValue();
            String filename = frameToFilename.get(frameIndex);
            long timestampMillis = frameToTimestamp.getOrDefault(frameIndex, -1L);

            html.append("<span style='color:var(--sp-text-strong);'>Frame ");
            html.append(frameIndex >= 0 ? frameIndex + 1 : "?");
            html.append("</span>");
            if (filename != null && !filename.isBlank()) {
                html.append(" <span style='color:var(--sp-link);'>(").append(ReportFormat.escapeHtml(filename)).append(")</span>");
            }
            if (timestampMillis > 0L) {
                html.append(" <span style='color:var(--sp-time);'>").append(ReportFormat.escapeHtml(formatUtcTimestamp(timestampMillis))).append("</span>");
            }
            html.append(": fragments <span style='color:var(--sp-finding-st);'>");
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
        html.append("<summary>").append(ReportFormat.escapeHtml(summaryLabel)).append("</summary>");
        html.append("<div class='foldable-streak-body'>");
        html.append("<strong>").append(ReportFormat.escapeHtml(headingLabel)).append(":</strong>");
        html.append("<ul class='source-list'>").append(entriesHtml).append("</ul>");
        html.append("</div>");
        html.append("</details>");
        return html.toString();
    }

    private static String buildStreakPointEntriesHtml(Track track,
                                                      DetectionReportAstrometry.Context astrometryContext,
                                                      boolean usePartLabel) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return "";
        }

        int partCount = track.points.size();
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < track.points.size(); i++) {
            DetectedObject point = track.points.get(i);
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

    private static String buildMovingTrackPointEntriesHtml(Track track,
                                                           DetectionReportAstrometry.Context astrometryContext) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return "";
        }

        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < track.points.size(); i++) {
            DetectedObject point = track.points.get(i);
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

    private static String buildSingleFrameEventSummaryHtml(Track track,
                                                           FitsFileInformation[] fitsFiles) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return "";
        }

        int partCount = 0;
        int frameIndex = -1;
        long frameTimestamp = -1L;
        long exposureMillis = -1L;

        for (DetectedObject point : track.points) {
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
        html.append("<div style='font-family: monospace; font-size: 0.75rem; color: var(--sp-text-soft); margin-bottom: 15px;'>");
        if (frameIndex >= 0) {
            html.append("Frame: <span style='color:var(--sp-text-strong);'>").append(frameIndex + 1).append("</span><br>");
        }
        html.append("Frame UTC: <span style='color:var(--sp-text-strong);'>")
                .append(ReportFormat.escapeHtml(formatUtcTimestamp(frameTimestamp)))
                .append("</span><br>");
        if (exposureMillis > 0L) {
            html.append("Exposure: <span style='color:var(--sp-text-strong);'>")
                    .append(ReportFormat.escapeHtml(formatDuration(exposureMillis)))
                    .append("</span><br>");
        }
        html.append("Streak Parts: <span style='color:var(--sp-text-strong);'>").append(partCount).append("</span>");
        if (partCount > 1) {
            html.append(" <span style='color:var(--sp-finding-s);'>(grouped same-frame components)</span>");
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

        double eastPerPixelX = SkyMath.normalizeSignedDegrees(offsetX.getRaDegrees() - center.getRaDegrees()) * cosDec;
        double northPerPixelX = offsetX.getDecDegrees() - center.getDecDegrees();
        double eastPerPixelY = SkyMath.normalizeSignedDegrees(offsetY.getRaDegrees() - center.getRaDegrees()) * cosDec;
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

    private static double[] computeTrackMeanPixelCenter(Track track) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return null;
        }

        double sumX = 0.0d;
        double sumY = 0.0d;
        int count = 0;
        for (DetectedObject point : track.points) {
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
        html.append("<a href='").append(ReportFormat.escapeHtml(fileName)).append("' target='_blank'");
        if (overlay != null) {
            html.append(" title='").append(ReportFormat.escapeHtml("FOV "
                    + formatFieldOfViewDimensions(overlay.fovWidthDegrees, overlay.fovHeightDegrees)
                    + " · Center RA " + overlay.centerRaText + " · Dec " + overlay.centerDecText)).append("'");
        }
        html.append(">");
        html.append("<img loading='lazy' decoding='async' src='").append(ReportFormat.escapeHtml(fileName)).append("' alt='").append(ReportFormat.escapeHtml(altText)).append("'");
        if (imageStyle != null && !imageStyle.isBlank()) {
            html.append(" style='").append(ReportFormat.escapeHtml(imageStyle)).append("'");
        }
        html.append(" /></a>");
        if (overlay != null) {
            html.append("<div class='sky-orientation-overlay'>");
            html.append(orientationSvg(overlay.northAngleFromUpDegrees, overlay.eastAngleFromUpDegrees));
            html.append("</div>");
        }
        html.append("</div>");
        html.append("<br/><center><small>").append(ReportFormat.escapeHtml(captionText)).append("</small></center>");
        html.append("</div>");
        return html.toString();
    }

    /** Thin north/east axes, with upright labels even when the frame is rotated or mirrored. */
    static String orientationSvg(double northAngle, double eastAngle) {
        StringBuilder svg = new StringBuilder("<svg viewBox='0 0 80 80' role='img' aria-label='North and east directions'>");
        double[] angles = {northAngle, eastAngle};
        String[] labels = {"N", "E"};
        for (int i = 0; i < angles.length; i++) {
            double angle = Math.toRadians(angles[i]);
            double dx = Math.sin(angle);
            double dy = -Math.cos(angle);
            svg.append(String.format(Locale.US, "<line x1='40' y1='40' x2='%.2f' y2='%.2f'/><text x='%.2f' y='%.2f'>%s</text>",
                    40 + 24 * dx, 40 + 24 * dy, 40 + 34 * dx, 40 + 34 * dy, labels[i]));
        }
        return svg.append("</svg>").toString();
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

    private static long resolveFrameTimestampMillis(DetectedObject detection,
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

    private static long resolveFrameExposureMillis(DetectedObject detection,
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

    /**
     * Computes the wide source field: twice the narrow crop, at least 400 pixels per side when the frame permits.
     * The output resolution limit is applied after cropping, so it cannot remove either end of a long track.
     */
    static int[] computeWideSourceDimensions(int narrowWidth, int narrowHeight, int frameWidth, int frameHeight) {
        int targetWidth = narrowWidth * 2;
        int targetHeight = narrowHeight * 2;
        int wideWidth = Math.max(400, targetWidth);
        int wideHeight = Math.max(400, targetHeight);
        if (frameWidth > 0) {
            wideWidth = Math.min(wideWidth, frameWidth);
        }
        if (frameHeight > 0) {
            wideHeight = Math.min(wideHeight, frameHeight);
        }
        return new int[]{wideWidth, wideHeight};
    }

    /** Crops the wide download field kept inside the frame. */
    private static short[][] cropWideDownloadSource(short[][] pixels, TrackCropGeometry.CropBounds bounds, int[] sourceDimensions) {
        int sourceWidth = sourceDimensions[0];
        int sourceHeight = sourceDimensions[1];
        int startX = TrackCropGeometry.insideFrame(bounds.fixedCenterX - sourceWidth / 2, sourceWidth, pixels[0].length);
        int startY = TrackCropGeometry.insideFrame(bounds.fixedCenterY - sourceHeight / 2, sourceHeight, pixels.length);
        return TrackVisualizationRenderer.robustEdgeAwareCrop(pixels,
                startX + sourceWidth / 2, startY + sourceHeight / 2, sourceWidth, sourceHeight);
    }

    /** Scales a rendered wide field to at most 1000 pixels per side. */
    private static BufferedImage scaleWideDownloadFrame(BufferedImage field) {
        double scale = Math.min(1.0, 1000.0 / Math.max(field.getWidth(), field.getHeight()));
        if (scale == 1.0) {
            return field;
        }
        int width = Math.max(1, (int) Math.round(field.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(field.getHeight() * scale));
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(field, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
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
