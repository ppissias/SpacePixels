/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */

package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.util.DebugOutput;
import eu.startales.spacepixels.util.DisplayImageRenderer;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ReportInclusion;
import eu.startales.spacepixels.util.ImageProcessing;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverCandidateResult;
import io.github.ppissias.jtransient.core.SlowMoverSummaryTelemetry;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import io.github.ppissias.jtransient.telemetry.TrackerTelemetry;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Main entry point for exporting detection reports, their companion visualization assets, and the iterative index
 * pages that link multiple report runs together.
 */
public class DetectionReportGenerator {

    // =================================================================
    // CONFIGURATION PARAMETERS
    // =================================================================

    // --- Export & Cropping Parameters ---
    public static int gifBlinkSpeedMs = 300;
    public static int trackCropPadding = 200;
    public static int trackObjectCentricCropSize = 200;
    public static boolean includeAiCreativeReportSections = false;

    // --- Annotation Tools (For GIFs) ---
    public static int targetCircleRadius = 15;
    public static float targetCircleStrokeWidth = 2.0f;

    public static final String detectionReportName = "detection_report.html";

    public static ExportVisualizationSettings captureExportSettings(eu.startales.spacepixels.config.VariableStarReportOptions options) {
        return snapshotCurrentExportVisualizationSettings().withVariableStarReportOptions(options);
    }

    private static ExportVisualizationSettings snapshotCurrentExportVisualizationSettings() {
        return new ExportVisualizationSettings(
                DisplayImageRenderer.autoStretchBlackSigma,
                DisplayImageRenderer.autoStretchWhiteSigma,
                gifBlinkSpeedMs,
                trackCropPadding,
                trackObjectCentricCropSize,
                includeAiCreativeReportSections,
                targetCircleRadius,
                targetCircleStrokeWidth
        );
    }

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

    static final class MaskOverlapStats {
        public final int overlappingPixels;
        public final int totalPixels;
        public final double fraction;

        public MaskOverlapStats(int overlappingPixels, int totalPixels) {
            this.overlappingPixels = overlappingPixels;
            this.totalPixels = totalPixels;
            this.fraction = totalPixels > 0 ? (double) overlappingPixels / totalPixels : 0.0;
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
    // IMAGE RENDERING & CROPPING
    // =================================================================

    public static BufferedImage createDisplayImage(short[][] imageData) {
        return DisplayImageRenderer.createDisplayImage(imageData);
    }

    private static BufferedImage createDisplayImage(short[][] imageData,
                                                    ExportVisualizationSettings settings) {
        return TrackVisualizationRenderer.createDisplayImage(imageData, settings);
    }

    // --- DIAGNOSTIC MASK RENDERER ---
    static BufferedImage createMasterMaskOverlay(short[][] masterStackData, boolean[][] masterMask) {
        int height = masterStackData.length;
        int width = masterStackData[0].length;

        if (masterMask != null) {
            int maskY = masterMask.length;
            int maskX = maskY > 0 && masterMask[0] != null ? masterMask[0].length : 0;
            if (maskY != height || maskX != width) {
                System.err.println("CRITICAL: masterMask dimensions " + maskX + "x" + maskY
                        + " do NOT match masterStackData dimensions " + width + "x" + height + "!");
            }
        }

        // Create an RGB image so we can paint the mask in color
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        if (masterMask == null || masterMask.length == 0) return image;

        // The BufferedImage is entirely black by default (RGB 0,0,0)
        // We only need to paint the exact veto pixels

        // PAINT THE MASTER MASK IN BRIGHT RED
        int redColor = new Color(255, 0, 0).getRGB(); // Solid red

        // Safety check: Detect if the mask was accidentally transposed in the library [width][height]
        boolean transposed = (masterMask.length == width && masterMask[0].length == height && width != height);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean isMasked = false;

                // Safely extract the mask value without crashing if the array dimensions don't perfectly match
                if (transposed) {
                    if (x < masterMask.length && y < masterMask[x].length) isMasked = masterMask[x][y];
                } else {
                    if (y < masterMask.length && x < masterMask[y].length) isMasked = masterMask[y][x];
                }

                if (isMasked) {
                    image.setRGB(x, y, redColor);
                }
            }
        }

        return image;
    }

    private static BufferedImage createCroppedMaskOverlay(short[][] backgroundData,
                                                          boolean[][] mask,
                                                          int cx,
                                                          int cy,
                                                          int cropWidth,
                                                          int cropHeight,
                                                          SourceExtractor.DetectedObject highlightDetection,
                                                          Color maskColor) {
        return createCroppedMaskOverlay(
                backgroundData,
                mask,
                cx,
                cy,
                cropWidth,
                cropHeight,
                highlightDetection,
                maskColor,
                snapshotCurrentExportVisualizationSettings());
    }

    static BufferedImage createCroppedMaskOverlay(short[][] backgroundData,
                                                  boolean[][] mask,
                                                  int cx,
                                                  int cy,
                                                  int cropWidth,
                                                  int cropHeight,
                                                  SourceExtractor.DetectedObject highlightDetection,
                                                  Color maskColor,
                                                  ExportVisualizationSettings settings) {
        BufferedImage grayImage = TrackVisualizationRenderer.createCroppedDisplayImage(
                backgroundData,
                cx,
                cy,
                cropWidth,
                cropHeight,
                settings);
        BufferedImage image = new BufferedImage(cropWidth, cropHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        g2d.drawImage(grayImage, 0, 0, null);

        boolean transposed = isMasterMaskTransposed(mask, backgroundData[0].length, backgroundData.length);
        int halfWidth = cropWidth / 2;
        int halfHeight = cropHeight / 2;
        int maskRgb = maskColor.getRGB();

        for (int y = 0; y < cropHeight; y++) {
            int sourceY = cy - halfHeight + y;
            for (int x = 0; x < cropWidth; x++) {
                int sourceX = cx - halfWidth + x;
                if (isMasterMaskSet(mask, sourceX, sourceY, transposed)) {
                    image.setRGB(x, y, maskRgb);
                }
            }
        }

        if (highlightDetection != null) {
            int localX = (int) Math.round(highlightDetection.x) - (cx - halfWidth);
            int localY = (int) Math.round(highlightDetection.y) - (cy - halfHeight);
            g2d.setColor(Color.WHITE);
            g2d.setStroke(new BasicStroke(settings.getTargetCircleStrokeWidth()));
            g2d.drawOval(
                    localX - settings.getTargetCircleRadius(),
                    localY - settings.getTargetCircleRadius(),
                    settings.getTargetCircleRadius() * 2,
                    settings.getTargetCircleRadius() * 2);
        }

        g2d.dispose();
        return image;
    }

    private static BufferedImage createCroppedMasterMaskOverlay(short[][] masterStackData,
                                                                boolean[][] masterMask,
                                                                int cx,
                                                                int cy,
                                                                int cropWidth,
                                                                int cropHeight,
                                                                SourceExtractor.DetectedObject highlightDetection) {
        return createCroppedMasterMaskOverlay(
                masterStackData,
                masterMask,
                cx,
                cy,
                cropWidth,
                cropHeight,
                highlightDetection,
                snapshotCurrentExportVisualizationSettings());
    }

    private static BufferedImage createCroppedMasterMaskOverlay(short[][] masterStackData,
                                                                boolean[][] masterMask,
                                                                int cx,
                                                                int cy,
                                                                int cropWidth,
                                                                int cropHeight,
                                                                SourceExtractor.DetectedObject highlightDetection,
                                                                ExportVisualizationSettings settings) {
        return createCroppedMaskOverlay(
                masterStackData,
                masterMask,
                cx,
                cy,
                cropWidth,
                cropHeight,
                highlightDetection,
                new Color(255, 32, 32),
                settings);
    }

    private static boolean isMasterMaskTransposed(boolean[][] masterMask, int refWidth, int refHeight) {
        return masterMask != null
                && masterMask.length > 0
                && masterMask[0] != null
                && masterMask.length == refWidth
                && masterMask[0].length == refHeight
                && refWidth != refHeight;
    }

    private static boolean isMasterMaskSet(boolean[][] masterMask, int x, int y, boolean transposed) {
        if (masterMask == null || x < 0 || y < 0) {
            return false;
        }

        if (transposed) {
            return x < masterMask.length && y < masterMask[x].length && masterMask[x][y];
        }
        return y < masterMask.length && x < masterMask[y].length && masterMask[y][x];
    }

    static MaskOverlapStats computeMaskOverlapStats(SourceExtractor.DetectedObject detection,
                                                    boolean[][] masterMask,
                                                    int refWidth,
                                                    int refHeight) {
        if (detection == null || masterMask == null || refWidth <= 0 || refHeight <= 0) {
            return new MaskOverlapStats(0, 0);
        }

        boolean transposed = isMasterMaskTransposed(masterMask, refWidth, refHeight);
        int overlappingPixels = 0;
        int totalPixels = 0;

        if (detection.rawPixels != null && !detection.rawPixels.isEmpty()) {
            for (SourceExtractor.Pixel pixel : detection.rawPixels) {
                totalPixels++;
                if (isMasterMaskSet(masterMask, pixel.x, pixel.y, transposed)) {
                    overlappingPixels++;
                }
            }
        } else {
            int x = (int) Math.round(detection.x);
            int y = (int) Math.round(detection.y);
            totalPixels = 1;
            if (isMasterMaskSet(masterMask, x, y, transposed)) {
                overlappingPixels = 1;
            }
        }

        return new MaskOverlapStats(overlappingPixels, totalPixels);
    }

    static String formatPercent(double fraction) {
        return String.format(Locale.US, "%.1f%%", fraction * 100.0);
    }

    static String formatOptionalMetric(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.2f", value) : "n/a";
    }

    private static void saveTrackImageLossless(BufferedImage image, File outputFile) throws IOException {
        TrackVisualizationRenderer.saveLosslessPng(image, outputFile);
    }

    private static short[][] robustEdgeAwareCrop(short[][] fullImage, int cx, int cy, int cropWidth, int cropHeight) {
        return TrackVisualizationRenderer.robustEdgeAwareCrop(fullImage, cx, cy, cropWidth, cropHeight);
    }

    private static BufferedImage createSingleStreakShapeImage(List<SourceExtractor.DetectedObject> points,
                                                              int cropWidth,
                                                              int cropHeight,
                                                              int startX,
                                                              int startY,
                                                              boolean drawCentroid) {
        return TrackVisualizationRenderer.createSingleStreakShapeImage(points, cropWidth, cropHeight, startX, startY, drawCentroid);
    }

    private static BufferedImage createTrackShapeImage(TrackLinker.Track track,
                                                       int cropWidth,
                                                       int cropHeight,
                                                       int startX,
                                                       int startY) {
        return TrackVisualizationRenderer.createTrackShapeImage(track, cropWidth, cropHeight, startX, startY);
    }

    private static void saveAnimatedGif(List<BufferedImage> frames, File outputFile, ExportVisualizationSettings settings) throws IOException {
        TrackVisualizationRenderer.saveAnimatedGif(frames, outputFile, settings);
    }

    // --- REFACTORED: GLOBAL TRACK MAP RENDERER ---
    static final Color GLOBAL_MAP_ANOMALY_COLOR = FindingCategory.ANOMALY.color();
    static final Color GLOBAL_MAP_SINGLE_STREAK_COLOR = FindingCategory.SINGLE_STREAK.color();
    static final Color GLOBAL_MAP_STREAK_TRACK_COLOR = FindingCategory.STREAK_TRACK.color();
    static final Color GLOBAL_MAP_MOVING_TARGET_COLOR = FindingCategory.MOVING_TARGET.color();
    static final Color GLOBAL_MAP_LOCAL_RESCUE_COLOR = FindingCategory.LOCAL_RESCUE.color();
    static final Color GLOBAL_MAP_LOCAL_ACTIVITY_COLOR = FindingCategory.LOCAL_ACTIVITY.color();
    static final Color GLOBAL_MAP_DEEP_STACK_COLOR = FindingCategory.SLOW_MOVER.color();

    static BufferedImage createGlobalTrackMap(short[][] backgroundData,
                                              List<TrackLinker.AnomalyDetection> anomalies,
                                              List<TrackLinker.Track> singleStreaks,
                                              List<TrackLinker.Track> streakTracks,
                                              List<TrackLinker.Track> movingTargets,
                                              List<TrackLinker.Track> localRescueTracks,
                                              List<ResidualTransientAnalysis.LocalActivityCluster> localActivityClusters,
                                              List<SourceExtractor.DetectedObject> deepStackCandidates, ExportVisualizationSettings settings) {
        BufferedImage grayBg = createDisplayImage(backgroundData, settings);
        BufferedImage rgbMap = new BufferedImage(grayBg.getWidth(), grayBg.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = rgbMap.createGraphics();
        g2d.drawImage(grayBg, 0, 0, null);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int stCounter = 1;
        for (TrackLinker.Track t : streakTracks) {
            drawMultiFrameTrack(g2d, t, GLOBAL_MAP_STREAK_TRACK_COLOR, "ST" + stCounter);
            stCounter++;
        }
        int tCounter = 1;
        for (TrackLinker.Track t : movingTargets) {
            drawMultiFrameTrack(g2d, t, GLOBAL_MAP_MOVING_TARGET_COLOR, "T" + tCounter);
            tCounter++;
        }
        int lrCounter = 1;
        for (TrackLinker.Track t : localRescueTracks) {
            drawMultiFrameTrack(g2d, t, GLOBAL_MAP_LOCAL_RESCUE_COLOR, "LR" + lrCounter);
            lrCounter++;
        }

        int lcCounter = 1;
        for (ResidualTransientAnalysis.LocalActivityCluster cluster : localActivityClusters) {
            drawLocalActivityCluster(g2d, cluster, GLOBAL_MAP_LOCAL_ACTIVITY_COLOR, "LC" + lcCounter);
            lcCounter++;
        }

        int dsCounter = 1;
        for (SourceExtractor.DetectedObject candidate : deepStackCandidates) {
            drawDeepStackCandidate(g2d, candidate, GLOBAL_MAP_DEEP_STACK_COLOR, "DS" + dsCounter);
            dsCounter++;
        }

        int aCounter = 1;
        for (TrackLinker.AnomalyDetection anomaly : anomalies) {
            drawSingleFindingMarker(g2d, anomaly.object, GLOBAL_MAP_ANOMALY_COLOR, "A" + aCounter, 20);
            aCounter++;
        }

        int sCounter = 1;
        for (TrackLinker.Track t : singleStreaks) {
            drawSingleFindingMarker(g2d, t.points.get(0), GLOBAL_MAP_SINGLE_STREAK_COLOR, "S" + sCounter, 20);
            sCounter++;
        }
        g2d.dispose();
        return rgbMap;
    }

    private static void drawSingleFindingMarker(Graphics2D g2d, SourceExtractor.DetectedObject pt, Color color, String label, int radius) {
        if (pt == null) {
            return;
        }
        int cx = (int) Math.round(pt.x);
        int cy = (int) Math.round(pt.y);
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(2.5f));
        g2d.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
        if (pt.rawPixels != null) {
            for (SourceExtractor.Pixel pixel : pt.rawPixels) {
                g2d.fillRect(pixel.x - 1, pixel.y - 1, 3, 3);
            }
        }
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Segoe UI", Font.BOLD, 18));
        g2d.drawString(label, cx + radius + 5, cy - radius - 5);
    }

    private static void drawDeepStackCandidate(Graphics2D g2d, SourceExtractor.DetectedObject candidate, Color color, String label) {
        if (candidate == null) {
            return;
        }
        int radius = Math.max(20, (int) Math.round(Math.sqrt(Math.max(1.0, candidate.pixelArea * Math.max(1.0, candidate.elongation)) / Math.PI)) + 12);
        drawSingleFindingMarker(g2d, candidate, color, label, radius);
    }

    private static void drawLocalActivityCluster(Graphics2D g2d,
                                                 ResidualTransientAnalysis.LocalActivityCluster cluster,
                                                 Color color,
                                                 String label) {
        if (cluster == null || cluster.points == null || cluster.points.isEmpty()) {
            return;
        }

        double centroidX = cluster.metrics != null ? cluster.metrics.centroidX : cluster.points.get(0).x;
        double centroidY = cluster.metrics != null ? cluster.metrics.centroidY : cluster.points.get(0).y;
        double clusterRadius = cluster.metrics != null ? cluster.metrics.clusterRadiusPixels : 0.0;
        int radius = Math.max(18, (int) Math.ceil(clusterRadius + 12.0));
        int cx = (int) Math.round(centroidX);
        int cy = (int) Math.round(centroidY);

        Stroke previousStroke = g2d.getStroke();
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, new float[]{8.0f, 6.0f}, 0.0f));
        g2d.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
        g2d.setStroke(previousStroke);

        for (SourceExtractor.DetectedObject point : cluster.points) {
            int px = (int) Math.round(point.x);
            int py = (int) Math.round(point.y);
            g2d.setColor(color);
            g2d.fillOval(px - 4, py - 4, 8, 8);
            g2d.setColor(Color.WHITE);
            g2d.drawOval(px - 4, py - 4, 8, 8);
        }

        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Segoe UI", Font.BOLD, 18));
        g2d.drawString(label, cx + radius + 6, cy - radius - 6);
    }

    private static void drawMultiFrameTrack(Graphics2D g2d, TrackLinker.Track track, Color lineColor, String label) {
        g2d.setColor(lineColor); g2d.setStroke(new BasicStroke(2.0f));
        for (int i = 0; i < track.points.size() - 1; i++) {
            SourceExtractor.DetectedObject p1 = track.points.get(i);
            SourceExtractor.DetectedObject p2 = track.points.get(i + 1);
            g2d.drawLine((int) Math.round(p1.x), (int) Math.round(p1.y), (int) Math.round(p2.x), (int) Math.round(p2.y));
        }
        g2d.setColor(lineColor);
        for (SourceExtractor.DetectedObject pt : track.points) {
            g2d.fillOval((int) Math.round(pt.x) - 4, (int) Math.round(pt.y) - 4, 8, 8);
            g2d.setColor(Color.WHITE);
            g2d.drawOval((int) Math.round(pt.x) - 4, (int) Math.round(pt.y) - 4, 8, 8);
            g2d.setColor(lineColor);
        }
        g2d.setColor(Color.WHITE); g2d.setFont(new Font("Segoe UI", Font.BOLD, 18));
        g2d.drawString(label, (int) Math.round(track.points.get(0).x) + 15, (int) Math.round(track.points.get(0).y) - 15);
    }

    static BufferedImage createDriftMap(List<SourceExtractor.Pixel> path, int outSize) {
        BufferedImage img = new BufferedImage(outSize, outSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = img.createGraphics();
        g2d.setColor(new Color(35, 35, 35));
        g2d.fillRect(0, 0, outSize, outSize);

        if (path == null || path.isEmpty()) {
            g2d.dispose();
            return img;
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (SourceExtractor.Pixel p : path) {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.y > maxY) maxY = p.y;
        }

        int rangeX = maxX - minX;
        int rangeY = maxY - minY;
        int maxRange = Math.max(Math.max(rangeX, rangeY), 15); // Ensure map scale doesn't explode on 0 drift

        int pad = 40;
        double scale = (double) (outSize - 2 * pad) / maxRange;

        int centerX = (maxX + minX) / 2;
        int centerY = (maxY + minY) / 2;

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw Grid Outline
        g2d.setColor(new Color(70, 70, 70));
        g2d.setStroke(new BasicStroke(1));
        g2d.drawRect(pad, pad, outSize - 2 * pad, outSize - 2 * pad);

        // Draw Trajectory Path
        g2d.setStroke(new BasicStroke(2.0f));
        for (int i = 0; i < path.size() - 1; i++) {
            SourceExtractor.Pixel p1 = path.get(i);
            SourceExtractor.Pixel p2 = path.get(i + 1);

            int x1 = (outSize / 2) + (int) Math.round((p1.x - centerX) * scale);
            int y1 = (outSize / 2) + (int) Math.round((p1.y - centerY) * scale);
            int x2 = (outSize / 2) + (int) Math.round((p2.x - centerX) * scale);
            int y2 = (outSize / 2) + (int) Math.round((p2.y - centerY) * scale);

            // Draw color gradient to denote time (Blue -> Red)
            float ratio = (float) i / (path.size() - 1);
            g2d.setColor(new Color(ratio, 0.4f, 1.0f - ratio));
            g2d.drawLine(x1, y1, x2, y2);
            g2d.fillOval(x1 - 2, y1 - 2, 4, 4);
        }

        // Render Map Points
        SourceExtractor.Pixel pFirst = path.get(0);
        int xf = (outSize / 2) + (int) Math.round((pFirst.x - centerX) * scale);
        int yf = (outSize / 2) + (int) Math.round((pFirst.y - centerY) * scale);
        g2d.setColor(new Color(80, 150, 255)); // Blue = Start
        g2d.fillOval(xf - 5, yf - 5, 10, 10);

        SourceExtractor.Pixel pLast = path.get(path.size() - 1);
        int xl = (outSize / 2) + (int) Math.round((pLast.x - centerX) * scale);
        int yl = (outSize / 2) + (int) Math.round((pLast.y - centerY) * scale);
        g2d.setColor(new Color(255, 80, 80)); // Red = End
        g2d.fillOval(xl - 5, yl - 5, 10, 10);

        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Segoe UI", Font.BOLD, 12));
        g2d.drawString("Max Drift: " + rangeX + "x" + rangeY + " px", 10, 20);
        g2d.drawString("Points on path: " + path.size(), 10, 35);

        g2d.dispose();
        return img;
    }

    static BufferedImage createFourCornerMosaic(short[][] frame, int targetCropSize) {
        int height = frame.length;
        int width = frame[0].length;
        int cropX = Math.min(targetCropSize, width / 2);
        int cropY = Math.min(targetCropSize, height / 2);

        short[][] tl = robustEdgeAwareCrop(frame, cropX / 2, cropY / 2, cropX, cropY);
        short[][] tr = robustEdgeAwareCrop(frame, width - (cropX / 2), cropY / 2, cropX, cropY);
        short[][] bl = robustEdgeAwareCrop(frame, cropX / 2, height - (cropY / 2), cropX, cropY);
        short[][] br = robustEdgeAwareCrop(frame, width - (cropX / 2), height - (cropY / 2), cropX, cropY);

        BufferedImage imgTL = createDisplayImage(tl);
        BufferedImage imgTR = createDisplayImage(tr);
        BufferedImage imgBL = createDisplayImage(bl);
        BufferedImage imgBR = createDisplayImage(br);

        BufferedImage out = new BufferedImage(cropX * 2, cropY * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(imgTL, 0, 0, null);
        g.drawImage(imgTR, cropX, 0, null);
        g.drawImage(imgBL, 0, cropY, null);
        g.drawImage(imgBR, cropX, cropY, null);

        // Draw central crosshairs to separate the corners visually
        g.setColor(new Color(255, 80, 80, 200));
        g.setStroke(new BasicStroke(2));
        g.drawLine(cropX, 0, cropX, cropY * 2);
        g.drawLine(0, cropY, cropX * 2, cropY);
        g.dispose();

        return out;
    }

    // --- GLOBAL TRANSIENT MAP RENDERER ---
    static BufferedImage createGlobalTransientMap(short[][] backgroundData, List<List<SourceExtractor.DetectedObject>> allTransients, ExportVisualizationSettings settings) {
        int width = backgroundData[0].length;
        int height = backgroundData.length;

        BufferedImage grayBg = createDisplayImage(backgroundData, settings);
        BufferedImage rgbMap = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = rgbMap.createGraphics();

        // Draw a dark, "ghostly" background (20% opacity) so the colorful transients pop
        // while still giving the user spatial context of the star field!
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, width, height);
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.2f));
        g2d.drawImage(grayBg, 0, 0, null);
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int totalFrames = allTransients.size();
        for (int i = 0; i < totalFrames; i++) {
            List<SourceExtractor.DetectedObject> frameTransients = allTransients.get(i);
            if (frameTransients == null || frameTransients.isEmpty()) continue;

            // Map time to a color gradient: Blue (Start) -> Green -> Yellow -> Red (End)
            float ratio = totalFrames > 1 ? (float) i / (totalFrames - 1) : 0f;
            // Hue ranges from 0.66 (Blue) down to 0.0 (Red)
            Color timeColor = Color.getHSBColor(0.66f - (0.66f * ratio), 1.0f, 1.0f);
            int timeRgb = timeColor.getRGB();

            for (SourceExtractor.DetectedObject pt : frameTransients) {
                if (pt.rawPixels != null && !pt.rawPixels.isEmpty()) {
                    // Draw the exact pixel footprint to accurately represent size and shape
                    for (SourceExtractor.Pixel p : pt.rawPixels) {
                        if (p.x >= 0 && p.x < width && p.y >= 0 && p.y < height) {
                            rgbMap.setRGB(p.x, p.y, timeRgb);
                        }
                    }
                } else {
                    // Fallback to a single pixel just in case
                    int cx = (int) Math.round(pt.x);
                    int cy = (int) Math.round(pt.y);
                    if (cx >= 0 && cx < width && cy >= 0 && cy < height) {
                        rgbMap.setRGB(cx, cy, timeRgb);
                    }
                }
            }
        }

        // The time legend is drawn by the report as HTML, legible at any display size.
        g2d.dispose();
        return rgbMap;
    }

    // --- NEW: RAINBOW CLUSTER MAP RENDERER ---
    static BufferedImage createRainbowClusterMap(short[][] backgroundData, List<List<SourceExtractor.DetectedObject>> allTransients) {
        int imgWidth = backgroundData[0].length;
        int imgHeight = backgroundData.length;

        int minX = imgWidth, maxX = 0;
        int minY = imgHeight, maxY = 0;
        int count = 0;

        // 1. Find the exact bounding box of the activity
        for (List<SourceExtractor.DetectedObject> frameTransients : allTransients) {
            if (frameTransients == null) continue;
            for (SourceExtractor.DetectedObject pt : frameTransients) {
                int cx = (int) Math.round(pt.x);
                int cy = (int) Math.round(pt.y);
                if (cx < minX) minX = cx;
                if (cx > maxX) maxX = cx;
                if (cy < minY) minY = cy;
                if (cy > maxY) maxY = cy;
                count++;
            }
        }

        if (count == 0) return new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);

        // Add padding and calculate dimensions
        int pad = 100;
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(imgWidth - 1, maxX + pad);
        maxY = Math.min(imgHeight - 1, maxY + pad);

        int cropW = maxX - minX + 1;
        int cropH = maxY - minY + 1;

        // 2. Downscale the map so it fits nicely on a screen without immense scrolling
        double scale = 1.0;
        int maxDim = Math.max(cropW, cropH);
        if (maxDim > 1200) {
            scale = 1200.0 / maxDim;
        }

        int scaledW = (int) Math.round(cropW * scale);
        int scaledH = (int) Math.round(cropH * scale);

        // Extract and softly scale the background
        short[][] croppedBg = robustEdgeAwareCrop(backgroundData, minX + (cropW / 2), minY + (cropH / 2), cropW, cropH);
        BufferedImage grayBg = createDisplayImage(croppedBg);

        BufferedImage rgbMap = new BufferedImage(scaledW, scaledH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = rgbMap.createGraphics();

        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, scaledW, scaledH);
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.2f));
        g2d.drawImage(grayBg, 0, 0, scaledW, scaledH, null); // Natively downscales the background
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int totalFrames = allTransients.size();
        for (int i = 0; i < totalFrames; i++) {
            List<SourceExtractor.DetectedObject> frameTransients = allTransients.get(i);
            if (frameTransients == null || frameTransients.isEmpty()) continue;

            float ratio = totalFrames > 1 ? (float) i / (totalFrames - 1) : 0f;
            Color timeColor = Color.getHSBColor(0.66f - (0.66f * ratio), 1.0f, 1.0f);

            // Create a glowing halo to thicken the line for maximum visibility
            Color haloColor = new Color(timeColor.getRed(), timeColor.getGreen(), timeColor.getBlue(), 80);

            for (SourceExtractor.DetectedObject pt : frameTransients) {
                int x = (int) Math.round((pt.x - minX) * scale);
                int y = (int) Math.round((pt.y - minY) * scale);

                g2d.setColor(haloColor);
                g2d.fillOval(x - 6, y - 6, 12, 12);
                g2d.setColor(timeColor);
                g2d.fillOval(x - 3, y - 3, 6, 6);
            }
        }

        // --- Draw Legend ---
        int legendWidth = 200;
        int legendHeight = 12;
        int lx = 15;
        int ly = scaledH - 35;

        g2d.setColor(new Color(0, 0, 0, 180));
        g2d.fillRect(lx - 10, ly - 25, legendWidth + 20, legendHeight + 35);

        for (int x = 0; x < legendWidth; x++) {
            float ratio = (float) x / legendWidth;
            Color timeColor = Color.getHSBColor(0.66f - (0.66f * ratio), 1.0f, 1.0f);
            g2d.setColor(timeColor);
            g2d.drawLine(lx + x, ly, lx + x, ly + legendHeight);
        }

        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Segoe UI", Font.BOLD, 11));
        g2d.drawString("Start", lx, ly - 4);
        g2d.drawString("End", lx + legendWidth - 20, ly - 4);
        g2d.drawString("Rainbow Cluster Map", lx, ly - 15);

        g2d.dispose();
        return rgbMap;
    }

    static String formatUtcTimestamp(long timestampMillis) {
        if (timestampMillis <= 0L) {
            return "Unknown";
        }

        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(timestampMillis));
    }

    private static String formatDuration(long durationMillis) {
        if (durationMillis < 0L) {
            return "Unknown";
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
        double degrees = Math.toDegrees(angleRadians);
        while (degrees < 0.0d) {
            degrees += 180.0d;
        }
        while (degrees >= 180.0d) {
            degrees -= 180.0d;
        }
        return formatDecimal(degrees, 1) + "°";
    }

    private static String formatDirectionDegrees(double dx, double dy) {
        double degrees = Math.toDegrees(Math.atan2(dy, dx));
        while (degrees < 0.0d) {
            degrees += 360.0d;
        }
        while (degrees >= 360.0d) {
            degrees -= 360.0d;
        }
        return formatDecimal(degrees, 1) + "°";
    }

    private static String formatTrackSpeed(double distancePixels, long durationMillis) {
        if (durationMillis <= 0L) {
            return "n/a";
        }

        double pixelsPerSecond = distancePixels / (durationMillis / 1000.0d);
        if (durationMillis >= 60_000L) {
            return formatDecimal(pixelsPerSecond * 60.0d, 2) + " px/min";
        }
        return formatDecimal(pixelsPerSecond, 2) + " px/s";
    }

    private static int getAnomalyDisplayOrder(TrackLinker.AnomalyDetection anomaly) {
        if (anomaly == null || anomaly.type == null) {
            return 2;
        }
        switch (anomaly.type) {
            case PEAK_SIGMA:
                return 0;
            case INTEGRATED_SIGMA:
                return 1;
            default:
                return 2;
        }
    }

    private static int getTrackDisplayFrameIndex(TrackLinker.Track track) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return Integer.MAX_VALUE;
        }

        int earliestFrameIndex = Integer.MAX_VALUE;
        for (SourceExtractor.DetectedObject point : track.points) {
            if (point == null) {
                continue;
            }
            earliestFrameIndex = Math.min(earliestFrameIndex, point.sourceFrameIndex);
        }
        return earliestFrameIndex;
    }

    private static int getAnomalyDisplayFrameIndex(TrackLinker.AnomalyDetection anomaly) {
        if (anomaly == null || anomaly.object == null) {
            return Integer.MAX_VALUE;
        }
        return anomaly.object.sourceFrameIndex;
    }

    private static String getTrackDisplayFileName(TrackLinker.Track track) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return "";
        }
        SourceExtractor.DetectedObject firstPoint = track.points.get(0);
        return firstPoint != null && firstPoint.sourceFilename != null ? firstPoint.sourceFilename : "";
    }

    private static List<TrackLinker.Track> sortTracksForFrameDisplay(List<TrackLinker.Track> tracks) {
        List<TrackLinker.Track> ordered = new ArrayList<>();
        if (tracks != null) {
            ordered.addAll(tracks);
        }
        ordered.sort(Comparator
                .comparingInt(DetectionReportGenerator::getTrackDisplayFrameIndex)
                .thenComparing(DetectionReportGenerator::getTrackDisplayFileName)
                .thenComparingDouble(track -> track != null && track.points != null && !track.points.isEmpty() && track.points.get(0) != null
                        ? track.points.get(0).x
                        : Double.POSITIVE_INFINITY)
                .thenComparingDouble(track -> track != null && track.points != null && !track.points.isEmpty() && track.points.get(0) != null
                        ? track.points.get(0).y
                        : Double.POSITIVE_INFINITY));
        return ordered;
    }

    private static List<TrackLinker.AnomalyDetection> sortAnomaliesForDisplay(List<TrackLinker.AnomalyDetection> anomalies) {
        List<TrackLinker.AnomalyDetection> ordered = new ArrayList<>();
        if (anomalies != null) {
            ordered.addAll(anomalies);
        }
        ordered.sort(Comparator
                .comparingInt(DetectionReportGenerator::getAnomalyDisplayFrameIndex)
                .thenComparingInt(DetectionReportGenerator::getAnomalyDisplayOrder)
                .thenComparing(anomaly -> anomaly != null && anomaly.object != null && anomaly.object.sourceFilename != null
                        ? anomaly.object.sourceFilename
                        : "")
                .thenComparingDouble(anomaly -> anomaly != null && anomaly.object != null
                        ? anomaly.object.x
                        : Double.POSITIVE_INFINITY)
                .thenComparingDouble(anomaly -> anomaly != null && anomaly.object != null
                        ? anomaly.object.y
                        : Double.POSITIVE_INFINITY));
        return ordered;
    }

    private static int countAnomaliesOfType(List<TrackLinker.AnomalyDetection> anomalies, TrackLinker.AnomalyType type) {
        if (anomalies == null || anomalies.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (TrackLinker.AnomalyDetection anomaly : anomalies) {
            if (anomaly != null && anomaly.type == type) {
                count++;
            }
        }
        return count;
    }

    static String buildTrackTimingSummaryHtml(TrackLinker.Track track,
                                              DetectionReportAstrometry.Context astrometryContext) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return "";
        }

        SourceExtractor.DetectedObject firstPoint = track.points.get(0);
        SourceExtractor.DetectedObject lastPoint = track.points.get(track.points.size() - 1);
        double dx = lastPoint.x - firstPoint.x;
        double dy = lastPoint.y - firstPoint.y;
        double displacementPixels = Math.hypot(dx, dy);

        long firstStartTimestamp = -1L;
        long lastEndTimestamp = -1L;
        long firstMidpoint = Long.MAX_VALUE;
        long lastMidpoint = Long.MIN_VALUE;
        SourceExtractor.DetectedObject motionFirst = null;
        SourceExtractor.DetectedObject motionLast = null;
        for (SourceExtractor.DetectedObject point : track.points) {
            long startTimestamp = DetectionReportAstrometry.resolveTrackPointStartTimestamp(astrometryContext, point);
            if (startTimestamp <= 0L) {
                continue;
            }
            long endTimestamp = startTimestamp + DetectionReportAstrometry.resolveTrackPointExposureMillis(point);
            if (firstStartTimestamp < 0L || startTimestamp < firstStartTimestamp) {
                firstStartTimestamp = startTimestamp;
            }
            lastEndTimestamp = Math.max(lastEndTimestamp, endTimestamp);
            long midpoint = SatCheckerQueryTargetFactory.resolveTrackPointMidpointTimestamp(astrometryContext, point);
            if (midpoint < firstMidpoint) { firstMidpoint = midpoint; motionFirst = point; }
            if (midpoint > lastMidpoint) { lastMidpoint = midpoint; motionLast = point; }
        }

        long durationMillis = (firstStartTimestamp > 0L && lastEndTimestamp >= firstStartTimestamp)
                ? lastEndTimestamp - firstStartTimestamp
                : -1L;
        long motionMillis = motionFirst != null && motionLast != null && lastMidpoint > firstMidpoint
                ? lastMidpoint - firstMidpoint : -1;
        if (motionMillis > 0) {
            dx = motionLast.x - motionFirst.x;
            dy = motionLast.y - motionFirst.y;
            displacementPixels = Math.hypot(dx, dy);
        }

        StringBuilder html = new StringBuilder();
        html.append("<div style='font-family: monospace; font-size: 12px; color: #aaa; margin-bottom: 15px;'>");
        html.append("First UTC: <span style='color:#fff;'>").append(escapeHtml(formatUtcTimestamp(firstStartTimestamp))).append("</span><br>");
        html.append("Last UTC: <span style='color:#fff;'>").append(escapeHtml(formatUtcTimestamp(lastEndTimestamp))).append("</span><br>");
        html.append("Track Duration: <span style='color:#fff;'>").append(escapeHtml(formatDuration(durationMillis))).append("</span><br>");
        html.append("Mean Speed: <span style='color:#fff;'>").append(escapeHtml(formatTrackSpeed(displacementPixels, motionMillis))).append("</span> (exposure midpoints)<br>");
        html.append(DetectionReportAstrometry.buildTrackSkyRateSummaryHtml(astrometryContext, track));
        html.append("Start-End Motion: <span style='color:#fff;'>")
                .append(escapeHtml(formatDecimal(displacementPixels, 1)))
                .append(" px @ ")
                .append(escapeHtml(formatDirectionDegrees(dx, dy)))
                .append("</span></div>");
        return html.toString();
    }

    private static String formatDecimal(double value, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", value);
    }

    static String formatPixelCoordinateOnly(int pixelX, int pixelY) {
        return String.format(Locale.US, "[%d, %d]", pixelX, pixelY);
    }

    /** Sticky-navigation entries for the sections this report contains, with their counts. */
    private static List<PipelineDiagnosticsSectionWriter.NavItem> buildNavigation(DetectionReportContext context,
                                                                                 PipelineTelemetry pipelineTelemetry,
                                                                                 DetectionReportSummary summary,
                                                                                 boolean aiSections,
                                                                                 int commonMotionGroupCount,
                                                                                 ReportInclusion inclusion) {
        List<PipelineDiagnosticsSectionWriter.NavItem> items = new ArrayList<>();
        items.add(new PipelineDiagnosticsSectionWriter.NavItem("overview", "Overview", null, false));
        int movingObjects = summary.movingTargetCount + summary.streakTrackCount + summary.singleStreakCount
                + summary.anomalyCount;
        items.add(new PipelineDiagnosticsSectionWriter.NavItem("moving-objects", "Moving objects & events",
                String.valueOf(movingObjects), movingObjects == 0));
if (FindingCategory.SLOW_MOVER.included(inclusion) && context.config != null && context.config.enableSlowMoverDetection) {
            items.add(new PipelineDiagnosticsSectionWriter.NavItem("slow-movers", "Slow movers",
                    String.valueOf(summary.slowMoverCandidateCount), summary.slowMoverCandidateCount == 0));
        }
        int residual = summary.localRescueCandidateCount + summary.localActivityClusterCount;
        if ((FindingCategory.LOCAL_RESCUE.included(inclusion) || FindingCategory.LOCAL_ACTIVITY.included(inclusion)) && residual > 0) {
            items.add(new PipelineDiagnosticsSectionWriter.NavItem("residual-review", "Local rescue", String.valueOf(residual), false));
        }
        items.add(new PipelineDiagnosticsSectionWriter.NavItem("maps", "Maps", null, false));
        items.add(new PipelineDiagnosticsSectionWriter.NavItem("inspector", "Unclassified", null, false));
        PipelineTelemetry.PhotometryTelemetry photometry = pipelineTelemetry != null ? pipelineTelemetry.photometryTelemetry : null;
        if (FindingCategory.VARIABLE_STAR.included(inclusion) && context.config != null && context.config.enableVariableStarDetection && photometry != null) {
            boolean notReady = "NOT_READY".equals(photometry.verdict);
            int candidates = photometry.highConfidence + photometry.possible;
            items.add(new PipelineDiagnosticsSectionWriter.NavItem("variables", "Variable stars",
                    notReady ? "not ready" : String.valueOf(candidates), notReady || candidates == 0));
        } else if (FindingCategory.VARIABLE_STAR.included(inclusion)) {
            items.add(new PipelineDiagnosticsSectionWriter.NavItem("variables", "Variable stars", "off", true));
        }
        int likelyArtifacts = (FindingCategory.COMMON_MOTION.included(inclusion) ? commonMotionGroupCount : 0) + context.cameraFixedGroups.size();
        if (likelyArtifacts > 0) {
            items.add(new PipelineDiagnosticsSectionWriter.NavItem("likely-artifacts", "Likely artifacts",
                    String.valueOf(likelyArtifacts), true));
        }
        items.add(new PipelineDiagnosticsSectionWriter.NavItem("night-equipment", "Night & equipment", null, false));
        items.add(new PipelineDiagnosticsSectionWriter.NavItem("diagnostics", "Diagnostics", null, true));
        if (aiSections) {
            items.add(new PipelineDiagnosticsSectionWriter.NavItem("ai-perspective", "AI perspectives", null, true));
        }
        return items;
    }

    private static DetectionReportSummary buildDetectionReportSummary(PipelineTelemetry pipelineTelemetry,
                                                                      ReportInclusion inclusion,
                                                                      List<TrackLinker.Track> singleStreaks,
                                                                      List<TrackLinker.Track> streakTracks,
                                                                      List<TrackLinker.Track> movingTargets,
                                                                      List<TrackLinker.AnomalyDetection> anomalies,
                                                                      List<SourceExtractor.DetectedObject> slowMoverCandidates,
                                                                      List<ResidualTransientAnalysis.LocalRescueCandidate> localRescueCandidates,
                                                                      List<ResidualTransientAnalysis.LocalActivityCluster> localActivityClusters,
                                                                      List<SourceExtractor.DetectedObject> masterStars,
                                                                      List<List<SourceExtractor.DetectedObject>> unclassifiedTransients) {
        int slowMoverCandidateCount = slowMoverCandidates == null ? 0 : slowMoverCandidates.size();
        int localRescueCandidateCount = localRescueCandidates.size();
        int localActivityClusterCount = localActivityClusters.size();
        int singleStreakCount = singleStreaks.size();
        int streakTrackCount = streakTracks.size();
        int movingTargetCount = movingTargets.size();
        int confirmedLinkedTrackCount = movingTargetCount + streakTrackCount;
        boolean insufficientFramesAfterQuality = pipelineTelemetry != null
                && pipelineTelemetry.totalFramesKept < ImageProcessing.MIN_USABLE_FRAMES_FOR_MULTI_FRAME_ANALYSIS;
        int returnedTrackCount = pipelineTelemetry != null
                ? pipelineTelemetry.totalTracksFound
                : singleStreakCount + confirmedLinkedTrackCount;
        int masterStarCount = pipelineTelemetry != null
                ? pipelineTelemetry.totalMasterStarsIdentified
                : (masterStars == null ? 0 : masterStars.size());
        int masterMapObjectCount = masterStars == null ? 0 : masterStars.size();
        int anomalyCount = !FindingCategory.ANOMALY.included(inclusion) ? 0
                : pipelineTelemetry != null ? pipelineTelemetry.totalAnomaliesFound : anomalies.size();
        int peakSigmaAnomalyCount = countAnomaliesOfType(anomalies, TrackLinker.AnomalyType.PEAK_SIGMA);
        int integratedSigmaAnomalyCount = countAnomaliesOfType(anomalies, TrackLinker.AnomalyType.INTEGRATED_SIGMA);
        int otherAnomalyCount = Math.max(0, anomalyCount - peakSigmaAnomalyCount - integratedSigmaAnomalyCount);
        int unclassifiedTransientCount = CreativeTributeRenderer.countTotalTransientDetections(unclassifiedTransients);

        return new DetectionReportSummary(
                slowMoverCandidateCount,
                localRescueCandidateCount,
                localActivityClusterCount,
                singleStreakCount,
                streakTrackCount,
                movingTargetCount,
                confirmedLinkedTrackCount,
                insufficientFramesAfterQuality,
                returnedTrackCount,
                masterStarCount,
                masterMapObjectCount,
                anomalyCount,
                peakSigmaAnomalyCount,
                integratedSigmaAnomalyCount,
                otherAnomalyCount,
                unclassifiedTransientCount
        );
    }

    static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static boolean hasMeaningfulSlowMoverTelemetry(SlowMoverSummaryTelemetry telemetry) {
        if (telemetry == null) {
            return false;
        }
        return telemetry.rawCandidatesExtracted > 0
                || telemetry.evaluatedAgainstMedianMask > 0
                || telemetry.candidatesDetected > 0
                || telemetry.rejectedLowMedianSupport > 0
                || telemetry.rejectedHighMedianSupport > 0
                || telemetry.rejectedBelowMinPixels > 0
                || telemetry.rejectedBelowMinAxisRatio > 0
                || telemetry.rejectedAboveMaxAxisRatio > 0
                || telemetry.rejectedLowFillFactor > 0
                || telemetry.evaluatedAgainstFrames > 0
                || telemetry.frameEvidenceUnavailable > 0
                || telemetry.rejectedLowFrameSupport > 0
                || telemetry.rejectedHighStationaryLikelihood > 0
                || telemetry.minAxisRatioThreshold > 0.0
                || telemetry.minFrameSupportThreshold > 0.0
                || telemetry.maxStationaryLikelihoodThreshold > 0.0;
    }

    // =================================================================
    // HTML EXPORT
    // =================================================================

    /**
     * The master HTML generator.
     * Consumes the full engine result, config settings, and raw FITS frames to crop targets,
     * render animated GIFs, and layout the final diagnostic dashboard.
     */
    public static void exportTrackVisualizations(PipelineResult result,
                                                 List<short[][]> rawFrames,
                                                 FitsFileInformation[] fitsFiles,
                                                 File exportDir,
                                                 DetectionConfig config,
                                                 AppConfig appConfig) throws IOException {
        exportTrackVisualizations(result, rawFrames, fitsFiles, exportDir, config, appConfig, ReportInclusion.all());
    }

    public static void exportTrackVisualizations(PipelineResult result,
                                                 List<short[][]> rawFrames,
                                                 FitsFileInformation[] fitsFiles,
                                                 File exportDir,
                                                 DetectionConfig config,
                                                 AppConfig appConfig,
                                                 ReportInclusion inclusion) throws IOException {
        exportTrackVisualizations(result, rawFrames, fitsFiles, exportDir, config, appConfig, inclusion, null);
    }

    /**
     * Writes the report, telling {@code progress} (which may be null) how far it is, from 0 to 99 %. The listener may
     * throw to stop the report, for example when its job is cancelled; the HTML page is then not written.
     */
    public static void exportTrackVisualizations(PipelineResult result,
                                                 List<short[][]> rawFrames,
                                                 FitsFileInformation[] fitsFiles,
                                                 File exportDir,
                                                 DetectionConfig config,
                                                 AppConfig appConfig,
                                                 ReportInclusion inclusion,
                                                 TransientEngineProgressListener progress) throws IOException {
        exportTrackVisualizationsInternal(result, rawFrames, fitsFiles, exportDir, config, appConfig,
                inclusion == null ? ReportInclusion.all() : inclusion, new ReportProgress(progress));
    }

    public static void exportTrackVisualizations(PipelineResult result, List<short[][]> rawFrames,
                                                 FitsFileInformation[] fitsFiles, File exportDir,
                                                 DetectionConfig config, AppConfig appConfig, ReportInclusion inclusion,
                                                 TransientEngineProgressListener progress, ExportVisualizationSettings settings) throws IOException {
        exportTrackVisualizationsInternal(result, rawFrames, fitsFiles, exportDir, config, appConfig,
                inclusion == null ? ReportInclusion.all() : inclusion, new ReportProgress(progress),
                settings == null ? captureExportSettings(appConfig != null && appConfig.variableStarReportOptions != null
                        ? appConfig.variableStarReportOptions : eu.startales.spacepixels.config.VariableStarReportOptions.DEFAULT) : settings);
    }

    static void exportTrackVisualizationsInternal(PipelineResult result, List<short[][]> rawFrames,
                                                  FitsFileInformation[] fitsFiles, File exportDir,
                                                  DetectionConfig config, AppConfig appConfig, ReportInclusion inclusion,
                                                  ReportProgress progress) throws IOException {
        exportTrackVisualizationsInternal(result, rawFrames, fitsFiles, exportDir, config, appConfig, inclusion, progress,
                captureExportSettings(appConfig != null && appConfig.variableStarReportOptions != null
                        ? appConfig.variableStarReportOptions : eu.startales.spacepixels.config.VariableStarReportOptions.DEFAULT));
    }

    private static void exportTrackVisualizationsInternal(PipelineResult result, List<short[][]> rawFrames,
                                                  FitsFileInformation[] fitsFiles, File exportDir,
                                                  DetectionConfig config, AppConfig appConfig, ReportInclusion inclusion,
                                                  ReportProgress progress, ExportVisualizationSettings settings) throws IOException {
        progress.step(0, 2, "preparing");

        // Extract variables from the PipelineResult and normalize newer track categories for rendering.
        List<TrackLinker.Track> tracks = result.tracks != null ? result.tracks : new ArrayList<>();
        List<TrackLinker.AnomalyDetection> anomalies = FindingCategory.ANOMALY.included(inclusion)
                ? sortAnomaliesForDisplay(result.anomalies)
                : new ArrayList<>();
        PipelineTelemetry pipelineTelemetry = result.telemetry;
        TrackerTelemetry linkerTelemetry = pipelineTelemetry != null ? pipelineTelemetry.trackerTelemetry : null;
        short[][] masterStackData = result.masterStackData;
        short[][] maximumStackData = result.maximumStackData;
        boolean[][] masterVetoMask = result.masterVetoMask;
        List<SourceExtractor.DetectedObject> masterStars = result.masterStars;
        SlowMoverAnalysis slowMoverAnalysis = result.slowMoverAnalysis != null
                ? result.slowMoverAnalysis
                : SlowMoverAnalysis.empty();
        short[][] slowMoverStackData = slowMoverAnalysis.maximumStackData != null
                ? slowMoverAnalysis.maximumStackData
                : result.maximumStackData;
        boolean[][] slowMoverMedianVetoMask = slowMoverAnalysis.medianMask != null
                ? slowMoverAnalysis.medianMask
                : result.slowMoverMedianVetoMask;
        List<SlowMoverCandidateResult> slowMoverCandidateResults = !FindingCategory.SLOW_MOVER.included(inclusion)
                ? java.util.Collections.emptyList()
                : slowMoverAnalysis.candidates != null
                ? slowMoverAnalysis.candidates
                : java.util.Collections.emptyList();
        List<SourceExtractor.DetectedObject> slowMoverCandidates = new ArrayList<>();
        if (FindingCategory.SLOW_MOVER.included(inclusion) && !slowMoverCandidateResults.isEmpty()) {
            for (SlowMoverCandidateResult candidateResult : slowMoverCandidateResults) {
                if (candidateResult != null && candidateResult.object != null) {
                    slowMoverCandidates.add(candidateResult.object);
                }
            }
        } else if (FindingCategory.SLOW_MOVER.included(inclusion) && result.slowMoverCandidates != null) {
            slowMoverCandidates.addAll(result.slowMoverCandidates);
        }
        SlowMoverSummaryTelemetry slowMoverTelemetry = !FindingCategory.SLOW_MOVER.included(inclusion) ? null
                : hasMeaningfulSlowMoverTelemetry(slowMoverAnalysis.telemetry)
                ? slowMoverAnalysis.telemetry
                : (pipelineTelemetry != null && pipelineTelemetry.slowMoverTelemetry != null
                ? new SlowMoverSummaryTelemetry(pipelineTelemetry.slowMoverTelemetry)
                : null);
        List<List<SourceExtractor.DetectedObject>> allTransients = result.allTransients;
        List<List<SourceExtractor.DetectedObject>> unclassifiedTransients = result.unclassifiedTransients;
        ResidualTransientAnalysis residualTransientAnalysis = result.residualTransientAnalysis != null
                ? result.residualTransientAnalysis
                : ResidualTransientAnalysis.empty();
        List<ResidualTransientAnalysis.LocalRescueCandidate> localRescueCandidates = FindingCategory.LOCAL_RESCUE.included(inclusion)
                ? residualTransientAnalysis.localRescueCandidates
                : java.util.Collections.emptyList();
        List<ResidualTransientAnalysis.LocalActivityCluster> localActivityClusters = FindingCategory.LOCAL_ACTIVITY.included(inclusion)
                ? residualTransientAnalysis.localActivityClusters
                : java.util.Collections.emptyList();
        List<ResidualTransientAnalysis.CommonMotionGroup> commonMotionGroups = FindingCategory.COMMON_MOTION.included(inclusion)
                ? residualTransientAnalysis.commonMotionGroups
                : java.util.Collections.emptyList();
        DetectionReportAstrometry.Context astrometryContext = DetectionReportAstrometry.buildContext(fitsFiles, appConfig);
        List<TrackLinker.Track> singleStreaks = new ArrayList<>();
        List<TrackLinker.Track> streakTracks = new ArrayList<>();
        List<TrackLinker.Track> movingTargets = new ArrayList<>();
        List<TrackLinker.Track> localRescueTracks = new ArrayList<>();

        for (TrackLinker.Track track : tracks) {
            if (track.points == null || track.points.isEmpty()) continue;
            java.util.Set<Integer> uniqueFrames = new java.util.HashSet<>();
            for (SourceExtractor.DetectedObject pt : track.points) uniqueFrames.add(pt.sourceFrameIndex);

            if (uniqueFrames.size() == 1) {
                singleStreaks.add(track);
            } else {
                if (track.isStreakTrack) streakTracks.add(track);
                else movingTargets.add(track);
            }
        }
        singleStreaks = sortTracksForFrameDisplay(singleStreaks);
        for (ResidualTransientAnalysis.LocalRescueCandidate candidate : localRescueCandidates) {
            TrackLinker.Track residualTrack = ResidualReviewSectionWriter.buildResidualTrack(candidate.points);
            if (residualTrack.points != null && !residualTrack.points.isEmpty()) {
                localRescueTracks.add(residualTrack);
            }
        }

        DetectionReportSummary summary = buildDetectionReportSummary(
                pipelineTelemetry,
                inclusion,
                singleStreaks,
                streakTracks,
                movingTargets,
                anomalies,
                slowMoverCandidates,
                localRescueCandidates,
                localActivityClusters,
                masterStars,
                unclassifiedTransients);
        DetectionReportContext reportContext = new DetectionReportContext(
                settings,
                exportDir,
                rawFrames,
                fitsFiles,
                config,
                astrometryContext,
                masterStackData,
                maximumStackData,
                masterVetoMask,
                slowMoverStackData,
                slowMoverMedianVetoMask,
                slowMoverCandidateResults,
                slowMoverCandidates,
                slowMoverTelemetry,
                localRescueCandidates,
                localActivityClusters,
                anomalies,
                singleStreaks,
                streakTracks,
                movingTargets);

        reportContext.progress = progress;
        reportContext.dataArea = result.dataArea;
        reportContext.cameraFixedGroups = FindingCategory.CAMERA_FIXED.included(inclusion) ? result.cameraFixedGroups : java.util.Collections.emptyList();
        reportContext.dataAreaText = DataAreaPresentation.describe(result.dataArea, pipelineTelemetry);
        reportContext.data = ReportData.create(reportContext, pipelineTelemetry, result.variableStarAnalysis, commonMotionGroups, inclusion);
        java.nio.file.Files.createDirectories(exportDir.toPath());
        reportContext.data.writeJson(exportDir);

        File reportFile = new File(exportDir, detectionReportName);

        long reportStarted = System.nanoTime();
        try (AtomicReportFile output = new AtomicReportFile(reportFile)) {
            java.io.PrintWriter report = output.writer();
            DetectionReportDocumentWriter.appendDetectionReportStart(report);
            PipelineDiagnosticsSectionWriter.writeSessionHeader(report, reportContext, pipelineTelemetry);
            report.println("<div class='report-toolbar'>");
            PipelineDiagnosticsSectionWriter.writeNavigation(report, buildNavigation(
                    reportContext, pipelineTelemetry, summary, settings.isIncludeAiCreativeReportSections(),
                    commonMotionGroups.size(), inclusion));
            ReportPresentation.controls(report, reportContext);
            report.println("</div>");

            // Results first: overview, moving objects, slow movers, residual review, maps, inspector, variable stars,
            // then the likely artifacts, folded. The shares of the bar follow the time each step took on a 33-frame
            // 61-megapixel session.
            progress.step(2, 16, "measuring the field depth and the night");
            FieldDepth fieldDepth = FieldDepth.measure(reportContext, pipelineTelemetry);
            NightEquipment nightEquipment = NightEquipment.measure(reportContext, pipelineTelemetry, masterStars);
            DebugOutput.printf(Locale.US, "Night & Equipment measured in %.1f s%n", nightEquipment.millis / 1000.0);
            progress.step(16, 20, "overview");
            PipelineDiagnosticsSectionWriter.writeOverview(report, reportContext, pipelineTelemetry, summary, localRescueTracks, fieldDepth, nightEquipment,
                    commonMotionGroups.size(), inclusion);

            progress.step(20, 26, "moving objects and streaks");
            report.println("<section id='moving-objects'>");
            TargetVisualizationSectionWriter.writeSection(report, reportContext);
            report.println("</section>");

            if (FindingCategory.SLOW_MOVER.included(inclusion)) {
                progress.step(26, 27, "slow movers");
                report.println("<section id='slow-movers'>");
                DeepStackReportSectionWriter.writeSection(report, reportContext);
                report.println("</section>");
            }

            if (FindingCategory.LOCAL_RESCUE.included(inclusion) || FindingCategory.LOCAL_ACTIVITY.included(inclusion)) {
                progress.step(27, 28, "residual review");
                report.println("<section id='residual-review'>");
                ResidualReviewSectionWriter.writeSection(report, reportContext);
                report.println("</section>");
            }

            progress.step(28, 36, "maps");
            report.println("<section id='maps'>");
            GlobalMapsSectionWriter.writeSections(report, reportContext, localRescueTracks, allTransients);
            report.println("</section>");

            progress.step(36, 66, "unclassified transients");
            report.println("<section id='inspector'>");
            UnclassifiedTransientSectionWriter.writeSection(report, reportContext, allTransients, unclassifiedTransients,
                    UnclassifiedTransientSectionWriter.rejectedFrameIndices(pipelineTelemetry),
                    residualTransientAnalysis, result.cameraFixedGroups, inclusion);
            report.println("</section>");

            if (FindingCategory.VARIABLE_STAR.included(inclusion)) {
                progress.step(66, 68, "variable stars");
                report.println("<section id='variables'>");
                PhotometryReportSectionWriter.writeSection(report, reportContext, result.variableStarAnalysis);
                report.println("</section>");
            }

            progress.step(68, 96, "likely artifacts");
            ResidualReviewSectionWriter.writeLikelyArtifactsSection(report, reportContext, reportContext.cameraFixedGroups, commonMotionGroups);

            progress.step(96, 99, "night & equipment and diagnostics");
            report.println("<section id='night-equipment'>");
            NightEquipmentSectionWriter.writeSection(report, nightEquipment, fieldDepth);
            report.println("</section>");

            // Processing diagnostics, collapsed.
            PipelineDiagnosticsSectionWriter.writeDiagnostics(
                    report,
                    reportContext,
                    pipelineTelemetry,
                    linkerTelemetry,
                    summary,
                    result.driftPoints,
                    fieldDepth);

            // =================================================================
            // AI PERSPECTIVES: Codex's Signal Weave, then Claude's The Night, Retold
            // =================================================================
            if (settings.isIncludeAiCreativeReportSections()) {
                report.println("<section id='ai-perspective'>");
            }
            if (settings.isIncludeAiCreativeReportSections()) {
                SignalWeaveSectionWriter.write(report, reportContext, pipelineTelemetry, allTransients, unclassifiedTransients);
            }

            if (settings.isIncludeAiCreativeReportSections()) {
                ClaudePerspectiveSectionWriter.writeSection(report, reportContext, pipelineTelemetry, summary,
                        FindingCategory.VARIABLE_STAR.included(inclusion) ? result.variableStarAnalysis : null);
                report.println("</section>");
            }

            ReportClientScriptWriter.appendLiveReportRenderingScript(report);
            ReportPresentation.appendScript(report);
            report.printf(Locale.US, "<p class='astro-note'>SpacePixels %s · JTransient %s · Generated %s · Report preparation %.2f s</p>%n",
                    ReportPresentation.version("spacepixels"), ReportPresentation.version("jtransient"), reportContext.data.generatedAtUtc,
                    (System.nanoTime() - reportStarted) / 1e9);
            DetectionReportDocumentWriter.appendHtmlDocumentEnd(report);
            output.commit();
        }
        DebugOutput.println("\nFinished exporting visualizations and generated HTML report at: " + reportFile.getAbsolutePath());
    }



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
                report.println("<p class='alert'>" + escapeHtml(note) + "</p>");
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
                    report.println("<td class='alert'>Report not written: " + escapeHtml(summary.reportFailure) + "</td>");
                } else {
                    String reportLink = summary.folderName + "/" + detectionReportName;
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
