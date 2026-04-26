package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes the residual-review report sections for local rescue candidates and broader residual activity clusters.
 */
final class ResidualReviewSectionWriter {

    private static final Color LOCAL_RESCUE_COLOR = new Color(64, 224, 208);
    private static final Color LOCAL_ACTIVITY_COLOR = new Color(255, 170, 80);

    private ResidualReviewSectionWriter() {
    }

    static void writeSection(PrintWriter report, DetectionReportContext context) throws IOException {
        if (!context.localRescueCandidates.isEmpty()) {
            short[][] background = context.masterStackData != null
                    ? context.masterStackData
                    : (!context.rawFrames.isEmpty() ? context.rawFrames.get(0) : null);
            report.println("<h2>Local Rescue Candidates (Residual Heuristic Analysis)</h2>");
            report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>Engine-side JTransient rescue analysis over <strong>unclassifiedTransients</strong>. These candidates highlight faint local motion spanning just a few pixels, sparse coherent local drifts across a handful of frames, plus short same-location repeats that are worth manual inspection.</p>");
            report.println("<div class='astro-note' style='margin-bottom: 15px;'>These detections are no longer a SpacePixels-side post-pass. They come directly from JTransient residual analysis after the normal track, anomaly, and suspected-streak branches have already finished.</div>");

            int counter = 1;
            for (ResidualTransientAnalysis.LocalRescueCandidate candidate : context.localRescueCandidates) {
                exportLocalRescueCandidateCard(report, context, background, candidate, counter);
                counter++;
            }
        }

        if (!context.localActivityClusters.isEmpty()) {
            short[][] background = context.masterStackData != null
                    ? context.masterStackData
                    : (!context.rawFrames.isEmpty() ? context.rawFrames.get(0) : null);
            report.println("<h2>Local Activity Clusters (Residual Review Buckets)</h2>");
            report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>Broad engine-side spatial clusters built from leftover detections after local rescue candidates have already consumed their points. These are not object confirmations; they are review buckets for persistent same-area activity.</p>");

            int counter = 1;
            for (ResidualTransientAnalysis.LocalActivityCluster cluster : context.localActivityClusters) {
                exportLocalActivityClusterCard(report, context, background, cluster, counter);
                counter++;
            }
        }
    }

    static TrackLinker.Track buildResidualTrack(List<SourceExtractor.DetectedObject> points) {
        TrackLinker.Track track = new TrackLinker.Track();
        if (points == null || points.isEmpty()) {
            return track;
        }

        List<SourceExtractor.DetectedObject> orderedPoints = new ArrayList<>(points);
        orderedPoints.sort(Comparator.comparingInt((SourceExtractor.DetectedObject point) -> point.sourceFrameIndex)
                .thenComparingDouble(point -> point.x)
                .thenComparingDouble(point -> point.y));
        for (SourceExtractor.DetectedObject point : orderedPoints) {
            track.addPoint(point);
        }
        return track;
    }

    private static void exportLocalRescueCandidateCard(PrintWriter report,
                                                       DetectionReportContext context,
                                                       short[][] referenceBackground,
                                                       ResidualTransientAnalysis.LocalRescueCandidate candidate,
                                                       int counter) throws IOException {
        TrackLinker.Track track = buildResidualTrack(candidate.points);
        TrackCropGeometry.CropBounds cropBounds = new TrackCropGeometry.CropBounds(track, Math.max(140, context.settings.getTrackCropPadding() / 2));

        String prefix = "micro_drift_" + counter;
        String backgroundFileName = prefix + "_background.png";
        String trailFileName = prefix + "_trail.png";
        String shapeFileName = prefix + "_shape.png";
        String contextGifFileName = prefix + "_context.gif";

        short[][] backgroundSource = resolveBackgroundSource(referenceBackground, context.rawFrames, track);

        if (backgroundSource != null) {
            short[][] croppedBackground = TrackVisualizationRenderer.robustEdgeAwareCrop(
                    backgroundSource,
                    cropBounds.fixedCenterX,
                    cropBounds.fixedCenterY,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight);
            TrackVisualizationRenderer.saveLosslessPng(
                    TrackVisualizationRenderer.createDisplayImage(croppedBackground, context.settings),
                    new File(context.exportDir, backgroundFileName));

            BufferedImage trailImage = createMicroDriftTrailImage(
                    track,
                    backgroundSource,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight,
                    cropBounds.startX,
                    cropBounds.startY);
            TrackVisualizationRenderer.saveLosslessPng(trailImage, new File(context.exportDir, trailFileName));
        }

        BufferedImage shapeImage = TrackVisualizationRenderer.createTrackShapeImage(
                track,
                cropBounds.trackBoxWidth,
                cropBounds.trackBoxHeight,
                cropBounds.startX,
                cropBounds.startY);
        TrackVisualizationRenderer.saveLosslessPng(shapeImage, new File(context.exportDir, shapeFileName));

        if (!context.rawFrames.isEmpty()) {
            Set<Integer> mandatoryFrames = collectResidualFrameIndices(track);
            Map<Integer, SourceExtractor.DetectedObject> pointByFrame = buildPointByFrameMap(track);
            List<Integer> sampledIndices = DetectionReportGenerator.getRepresentativeSequence(context.rawFrames.size(), mandatoryFrames, 12);
            List<BufferedImage> contextFrames = new ArrayList<>();
            for (int frameIndex : sampledIndices) {
                SourceExtractor.DetectedObject detectedPoint = pointByFrame.get(frameIndex);
                String pointLabel = detectedPoint != null ? "P" + (track.points.indexOf(detectedPoint) + 1) : null;
                BufferedImage frameImage = TrackVisualizationRenderer.createStarCentricHighlightedFrame(
                        context.rawFrames.get(frameIndex),
                        cropBounds,
                        detectedPoint,
                        context.settings,
                        LOCAL_RESCUE_COLOR,
                        pointLabel,
                        "Frame " + (frameIndex + 1),
                        true);
                contextFrames.add(frameImage);
            }
            TrackVisualizationRenderer.saveAnimatedGif(contextFrames, new File(context.exportDir, contextGifFileName), context.settings);
        }

        String candidateBadge = buildLocalRescueBadge(candidate.kind);
        String candidateNote = buildLocalRescueNote(candidate.kind);
        ResidualTransientAnalysis.LocalTransientMetrics metrics = candidate.metrics;
        report.println("<div class='detection-card' style='border-left-color: #40e0d0;'>");
        report.println("<div class='detection-title' style='color: #40e0d0;'>Local Rescue Candidate LR" + counter + candidateBadge + "</div>");
        report.println("<div class='astro-note' style='margin-top: -5px; margin-bottom: 12px;'>" + candidateNote + "</div>");
        report.println("<div class='image-container'>");
        if (backgroundSource != null) {
            report.println("<div><a href='" + backgroundFileName + "' target='_blank'><img src='" + backgroundFileName + "' alt='Reference Crop' /></a><br/><center><small>Reference Crop</small></center></div>");
            report.println("<div><a href='" + trailFileName + "' target='_blank'><img src='" + trailFileName + "' alt='Local Trail Map' /></a><br/><center><small>Local Trail Map</small></center></div>");
        }
        if (!context.rawFrames.isEmpty()) {
            report.println("<div><a href='" + contextGifFileName + "' target='_blank'><img src='" + contextGifFileName + "' alt='Micro-Drift Animation' /></a><br/><center><small>Animation (Star-Centric)</small></center></div>");
        }
        report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Shape Footprint Evolution' /></a><br/><center><small>Shape Evolution</small></center></div>");
        report.println("</div>");

        report.println("<div style='font-family: monospace; font-size: 12px; color: #aaa; margin-bottom: 10px;'>"
                + "Type: <span style='color:#fff;'>" + DetectionReportGenerator.escapeHtml(formatLocalRescueKind(candidate.kind)) + "</span>"
                + " | "
                + "Support: <span style='color:#fff;'>" + metrics.pointCount + " detections</span>"
                + " across <span style='color:#fff;'>" + metrics.frameSpan + "</span> frame(s)"
                + " | Gap Frames: <span style='color:#fff;'>" + metrics.totalGapFrames + "</span>"
                + " | Total Motion: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f px", metrics.totalDisplacementPixels) + "</span>"
                + " | Mean Step: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f px", metrics.averageStepPixels) + "</span>"
                + " | Cluster Radius: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f px", metrics.clusterRadiusPixels) + "</span>"
                + " | Fit RMSE: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f px", metrics.linearityRmsePixels) + "</span>"
                + " | Score: <span style='color:#fff;'>" + String.format(Locale.US, "%.1f", candidate.score) + "</span>"
                + "</div>");
        report.print(DetectionReportGenerator.buildTrackTimingSummaryHtml(track, context.astrometryContext));

        report.println("<strong>Detection Coordinates & Frames:</strong><ul class='source-list'>");
        for (int i = 0; i < track.points.size(); i++) {
            SourceExtractor.DetectedObject point = track.points.get(i);
            String pointMetrics = buildMicroDriftMetricsText(point, i + 1);
            report.println(DetectionReportAstrometry.buildSourceCoordinateListEntry(
                    "[" + (i + 1) + "] " + point.sourceFilename,
                    context.astrometryContext,
                    point.x,
                    point.y,
                    pointMetrics));
        }
        report.println("</ul>");
        report.print(DetectionReportAstrometry.buildTrackSolarSystemIdentificationHtml(
                context.astrometryContext,
                track,
                "local-rescue-jpl-" + counter,
                String.format(Locale.US, "jpl_local_rescue_%02d", counter)));
        report.println("</div>");
    }

    private static void exportLocalActivityClusterCard(PrintWriter report,
                                                       DetectionReportContext context,
                                                       short[][] referenceBackground,
                                                       ResidualTransientAnalysis.LocalActivityCluster cluster,
                                                       int counter) throws IOException {
        TrackLinker.Track track = buildResidualTrack(cluster.points);
        TrackCropGeometry.CropBounds cropBounds = new TrackCropGeometry.CropBounds(track, Math.max(180, context.settings.getTrackCropPadding() / 2));

        String prefix = "local_activity_" + counter;
        String backgroundFileName = prefix + "_background.png";
        String trailFileName = prefix + "_trail.png";
        String shapeFileName = prefix + "_shape.png";
        String contextGifFileName = prefix + "_context.gif";

        short[][] backgroundSource = resolveBackgroundSource(referenceBackground, context.rawFrames, track);

        if (backgroundSource != null) {
            short[][] croppedBackground = TrackVisualizationRenderer.robustEdgeAwareCrop(
                    backgroundSource,
                    cropBounds.fixedCenterX,
                    cropBounds.fixedCenterY,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight);
            TrackVisualizationRenderer.saveLosslessPng(
                    TrackVisualizationRenderer.createDisplayImage(croppedBackground, context.settings),
                    new File(context.exportDir, backgroundFileName));

            BufferedImage trailImage = createMicroDriftTrailImage(
                    track,
                    backgroundSource,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight,
                    cropBounds.startX,
                    cropBounds.startY);
            TrackVisualizationRenderer.saveLosslessPng(trailImage, new File(context.exportDir, trailFileName));
        }

        BufferedImage shapeImage = TrackVisualizationRenderer.createTrackShapeImage(
                track,
                cropBounds.trackBoxWidth,
                cropBounds.trackBoxHeight,
                cropBounds.startX,
                cropBounds.startY);
        TrackVisualizationRenderer.saveLosslessPng(shapeImage, new File(context.exportDir, shapeFileName));

        if (!context.rawFrames.isEmpty()) {
            Set<Integer> mandatoryFrames = collectResidualFrameIndices(track);
            Map<Integer, SourceExtractor.DetectedObject> pointByFrame = buildPointByFrameMap(track);
            List<Integer> sampledIndices = DetectionReportGenerator.getRepresentativeSequence(context.rawFrames.size(), mandatoryFrames, 12);
            List<BufferedImage> contextFrames = new ArrayList<>();
            for (int frameIndex : sampledIndices) {
                SourceExtractor.DetectedObject detectedPoint = pointByFrame.get(frameIndex);
                String pointLabel = detectedPoint != null ? "P" + (track.points.indexOf(detectedPoint) + 1) : null;
                BufferedImage frameImage = TrackVisualizationRenderer.createStarCentricHighlightedFrame(
                        context.rawFrames.get(frameIndex),
                        cropBounds,
                        detectedPoint,
                        context.settings,
                        LOCAL_ACTIVITY_COLOR,
                        pointLabel,
                        "Frame " + (frameIndex + 1),
                        true);
                contextFrames.add(frameImage);
            }
            TrackVisualizationRenderer.saveAnimatedGif(contextFrames, new File(context.exportDir, contextGifFileName), context.settings);
        }

        ResidualTransientAnalysis.LocalTransientMetrics metrics = cluster.metrics;
        report.println("<div class='detection-card' style='border-left-color: #ffb347;'>");
        report.println("<div class='detection-title' style='color: #ffb347;'>Local Activity Cluster LC" + counter
                + " <span style='background: #7a4b14; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>"
                + String.format(Locale.US, "%.0f px Linkage", cluster.linkageRadiusPixels)
                + "</span></div>");
        report.println("<div class='astro-note' style='margin-top: -5px; margin-bottom: 12px;'>Broad engine-side residual review cluster built by JTransient after rescue-consumed points are removed. This is not a confirmed object category; it highlights persistent same-area activity worth manual inspection.</div>");
        report.println("<div class='image-container'>");
        if (backgroundSource != null) {
            report.println("<div><a href='" + backgroundFileName + "' target='_blank'><img src='" + backgroundFileName + "' alt='Reference Crop' /></a><br/><center><small>Reference Crop</small></center></div>");
            report.println("<div><a href='" + trailFileName + "' target='_blank'><img src='" + trailFileName + "' alt='Local Cluster Trail Map' /></a><br/><center><small>Cluster Trail Map</small></center></div>");
        }
        if (!context.rawFrames.isEmpty()) {
            report.println("<div><a href='" + contextGifFileName + "' target='_blank'><img src='" + contextGifFileName + "' alt='Local Activity Animation' /></a><br/><center><small>Animation (Star-Centric)</small></center></div>");
        }
        report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Cluster Shape Evolution' /></a><br/><center><small>Shape Evolution</small></center></div>");
        report.println("</div>");

        report.println("<div style='font-family: monospace; font-size: 12px; color: #aaa; margin-bottom: 10px;'>"
                + "Support: <span style='color:#fff;'>" + metrics.pointCount + " detections</span>"
                + " across <span style='color:#fff;'>" + metrics.uniqueFrameCount + "</span> unique frame(s)"
                + " | Frame Span: <span style='color:#fff;'>" + metrics.frameSpan + "</span>"
                + " | Gap Frames: <span style='color:#fff;'>" + metrics.totalGapFrames + "</span>"
                + " | Total Motion: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f px", metrics.totalDisplacementPixels) + "</span>"
                + " | Mean Step: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f px", metrics.averageStepPixels) + "</span>"
                + " | Cluster Radius: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f px", metrics.clusterRadiusPixels) + "</span>"
                + " | Fit RMSE: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f px", metrics.linearityRmsePixels) + "</span>"
                + " | Linkage Radius: <span style='color:#fff;'>" + String.format(Locale.US, "%.1f px", cluster.linkageRadiusPixels) + "</span>"
                + "</div>");

        report.println("<strong>Cluster Coordinates & Frames:</strong><ul class='source-list'>");
        for (int i = 0; i < track.points.size(); i++) {
            SourceExtractor.DetectedObject point = track.points.get(i);
            String metricsText = buildMicroDriftMetricsText(point, i + 1);
            report.println(DetectionReportAstrometry.buildSourceCoordinateListEntry(
                    "[" + (i + 1) + "] " + point.sourceFilename,
                    context.astrometryContext,
                    point.x,
                    point.y,
                    metricsText));
        }
        report.println("</ul>");
        report.println("</div>");
    }

    private static short[][] resolveBackgroundSource(short[][] referenceBackground,
                                                     List<short[][]> rawFrames,
                                                     TrackLinker.Track track) {
        if (referenceBackground != null) {
            return referenceBackground;
        }
        if (rawFrames == null || rawFrames.isEmpty() || track == null || track.points == null || track.points.isEmpty()) {
            return null;
        }
        int fallbackIndex = Math.max(0, Math.min(rawFrames.size() - 1, track.points.get(0).sourceFrameIndex));
        return rawFrames.get(fallbackIndex);
    }

    private static Map<Integer, SourceExtractor.DetectedObject> buildPointByFrameMap(TrackLinker.Track track) {
        Map<Integer, SourceExtractor.DetectedObject> pointByFrame = new HashMap<>();
        for (SourceExtractor.DetectedObject point : track.points) {
            pointByFrame.put(point.sourceFrameIndex, point);
        }
        return pointByFrame;
    }

    private static Set<Integer> collectResidualFrameIndices(TrackLinker.Track track) {
        Set<Integer> frameIndices = new HashSet<>();
        if (track == null || track.points == null) {
            return frameIndices;
        }
        for (SourceExtractor.DetectedObject point : track.points) {
            frameIndices.add(point.sourceFrameIndex);
        }
        return frameIndices;
    }

    private static String formatLocalRescueKind(ResidualTransientAnalysis.LocalRescueKind kind) {
        if (kind == null) {
            return "Local Rescue";
        }
        switch (kind) {
            case LOCAL_REPEAT:
                return "Same-Location Repeat";
            case SPARSE_LOCAL_DRIFT:
                return "Sparse Local Drift";
            case MICRO_DRIFT:
            default:
                return "Micro-Drift";
        }
    }

    private static String buildLocalRescueBadge(ResidualTransientAnalysis.LocalRescueKind kind) {
        if (kind == ResidualTransientAnalysis.LocalRescueKind.LOCAL_REPEAT) {
            return " <span style='background: #19595a; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>2+ Same-Location Rescue</span>";
        }
        if (kind == ResidualTransientAnalysis.LocalRescueKind.SPARSE_LOCAL_DRIFT) {
            return " <span style='background: #2d5a88; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>Sparse Local-Drift Rescue</span>";
        }
        return "";
    }

    private static String buildLocalRescueNote(ResidualTransientAnalysis.LocalRescueKind kind) {
        if (kind == ResidualTransientAnalysis.LocalRescueKind.LOCAL_REPEAT) {
            return "Engine-side residual rescue built by JTransient from unclassified transients after normal track, anomaly, and suspected-streak classification. This candidate is a tight local repeater rather than a strong kinematic track.";
        }
        if (kind == ResidualTransientAnalysis.LocalRescueKind.SPARSE_LOCAL_DRIFT) {
            return "Engine-side residual rescue built by JTransient from unclassified transients after normal track, anomaly, and suspected-streak classification. This candidate shows coherent local drift across a sparse set of frames, so it would be missed by stricter contiguous-frame linking.";
        }
        return "Engine-side residual rescue built by JTransient from unclassified transients after normal track, anomaly, and suspected-streak classification. These are not confirmed tracks; manual verification is recommended.";
    }

    private static BufferedImage createMicroDriftTrailImage(TrackLinker.Track track,
                                                            short[][] backgroundData,
                                                            int cropWidth,
                                                            int cropHeight,
                                                            int startX,
                                                            int startY) {
        short[][] croppedBackground = TrackVisualizationRenderer.robustEdgeAwareCrop(
                backgroundData,
                startX + (cropWidth / 2),
                startY + (cropHeight / 2),
                cropWidth,
                cropHeight);
        BufferedImage grayBackground = DetectionReportGenerator.createDisplayImage(croppedBackground);
        BufferedImage output = new BufferedImage(cropWidth, cropHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = output.createGraphics();
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, cropWidth, cropHeight);
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
        g2d.drawImage(grayBackground, 0, 0, null);
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int pointCount = track == null || track.points == null ? 0 : track.points.size();
        for (int i = 0; i < pointCount; i++) {
            SourceExtractor.DetectedObject point = track.points.get(i);
            float ratio = pointCount > 1 ? (float) i / (pointCount - 1) : 0f;
            Color timeColor = Color.getHSBColor(0.66f - (0.66f * ratio), 1.0f, 1.0f);
            int localX = (int) Math.round(point.x - startX);
            int localY = (int) Math.round(point.y - startY);

            if (point.rawPixels != null && !point.rawPixels.isEmpty()) {
                for (SourceExtractor.Pixel pixel : point.rawPixels) {
                    int px = pixel.x - startX;
                    int py = pixel.y - startY;
                    if (px >= 0 && px < cropWidth && py >= 0 && py < cropHeight) {
                        output.setRGB(px, py, timeColor.getRGB());
                    }
                }
            }

            g2d.setColor(new Color(timeColor.getRed(), timeColor.getGreen(), timeColor.getBlue(), 120));
            g2d.fillOval(localX - 7, localY - 7, 14, 14);
            g2d.setColor(timeColor);
            g2d.fillOval(localX - 3, localY - 3, 6, 6);
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("Segoe UI", Font.BOLD, 12));
            g2d.drawString(String.valueOf(i + 1), localX + 8, localY - 8);

            if (i > 0) {
                SourceExtractor.DetectedObject previous = track.points.get(i - 1);
                int prevX = (int) Math.round(previous.x - startX);
                int prevY = (int) Math.round(previous.y - startY);
                g2d.setColor(new Color(timeColor.getRed(), timeColor.getGreen(), timeColor.getBlue(), 180));
                g2d.setStroke(new BasicStroke(2.0f));
                g2d.drawLine(prevX, prevY, localX, localY);
            }
        }

        g2d.dispose();
        return output;
    }

    private static String buildMicroDriftMetricsText(SourceExtractor.DetectedObject detection, int sequenceNumber) {
        return String.format(
                Locale.US,
                "Point %d, Flux: %.1f, Pixels: %d, Elongation: %.2f, FWHM: %.2f, Peak Sigma: %.2f, Integrated Sigma: %.2f, UTC: %s",
                sequenceNumber,
                detection.totalFlux,
                (int) detection.pixelArea,
                detection.elongation,
                detection.fwhm,
                detection.peakSigma,
                detection.integratedSigma,
                formatUtcTimestamp(detection.timestamp));
    }

    private static String formatUtcTimestamp(long timestampMillis) {
        if (timestampMillis <= 0L) {
            return "Unknown";
        }

        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(timestampMillis));
    }
}
