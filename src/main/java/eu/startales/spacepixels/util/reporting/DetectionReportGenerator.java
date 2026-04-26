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
import eu.startales.spacepixels.util.DisplayImageRenderer;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverCandidateResult;
import io.github.ppissias.jtransient.core.SlowMoverSummaryTelemetry;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.engine.PipelineResult;
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

        public IterationSummary(int frameCount, String folderName, int trackCount, int anomalyCount) {
            this.frameCount = frameCount;
            this.folderName = folderName;
            this.trackCount = trackCount;
            this.anomalyCount = anomalyCount;
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
     * Returns an evenly spaced sample of chronological frame indices while guaranteeing that
     * mandatory frames (where actual detections occur) are included to never miss a transient.
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

        // --- DEBUG TRACE FOR JTRANSIENT ---
        System.out.println("\n--- DEBUG: Master Mask Array Bounds ---");
        System.out.println("masterStackData dimensions: " + width + "x" + height + " (W x H)");
        if (masterMask == null) {
            System.out.println("masterMask is null!");
        } else {
            int maskY = masterMask.length;
            int maskX = maskY > 0 && masterMask[0] != null ? masterMask[0].length : 0;
            System.out.println("masterMask dimensions: " + maskX + "x" + maskY + " (W x H)");
            if (maskY != height || maskX != width) {
                System.err.println("CRITICAL: masterMask dimensions do NOT match masterStackData dimensions!");
            }
        }
        System.out.println("---------------------------------------\n");

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
    static final Color GLOBAL_MAP_ANOMALY_COLOR = new Color(255, 51, 255);
    static final Color GLOBAL_MAP_SINGLE_STREAK_COLOR = new Color(255, 153, 51);
    static final Color GLOBAL_MAP_STREAK_TRACK_COLOR = new Color(255, 204, 51);
    static final Color GLOBAL_MAP_SUSPECTED_STREAK_COLOR = new Color(255, 128, 128);
    static final Color GLOBAL_MAP_MOVING_TARGET_COLOR = new Color(77, 166, 255);
    static final Color GLOBAL_MAP_LOCAL_RESCUE_COLOR = new Color(64, 224, 208);
    static final Color GLOBAL_MAP_LOCAL_ACTIVITY_COLOR = new Color(150, 120, 255);
    static final Color GLOBAL_MAP_DEEP_STACK_COLOR = new Color(170, 255, 110);

    static BufferedImage createGlobalTrackMap(short[][] backgroundData,
                                              List<TrackLinker.AnomalyDetection> anomalies,
                                              List<TrackLinker.Track> singleStreaks,
                                              List<TrackLinker.Track> streakTracks,
                                              List<TrackLinker.Track> suspectedStreakTracks,
                                              List<TrackLinker.Track> movingTargets,
                                              List<TrackLinker.Track> localRescueTracks,
                                              List<ResidualTransientAnalysis.LocalActivityCluster> localActivityClusters,
                                              List<SourceExtractor.DetectedObject> deepStackCandidates) {
        BufferedImage grayBg = createDisplayImage(backgroundData);
        BufferedImage rgbMap = new BufferedImage(grayBg.getWidth(), grayBg.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = rgbMap.createGraphics();
        g2d.drawImage(grayBg, 0, 0, null);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int stCounter = 1;
        for (TrackLinker.Track t : streakTracks) {
            drawMultiFrameTrack(g2d, t, GLOBAL_MAP_STREAK_TRACK_COLOR, "ST" + stCounter);
            stCounter++;
        }
        int sstCounter = 1;
        for (TrackLinker.Track t : suspectedStreakTracks) {
            drawMultiFrameTrack(g2d, t, GLOBAL_MAP_SUSPECTED_STREAK_COLOR, "SST" + sstCounter);
            sstCounter++;
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
    static BufferedImage createGlobalTransientMap(short[][] backgroundData, List<List<SourceExtractor.DetectedObject>> allTransients) {
        int width = backgroundData[0].length;
        int height = backgroundData.length;

        BufferedImage grayBg = createDisplayImage(backgroundData);
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

        // --- Draw Legend ---
        int legendWidth = 250;
        int legendHeight = 15;
        int lx = 20;
        int ly = rgbMap.getHeight() - 40;

        // Background for legend to make it visible against noisy backgrounds
        g2d.setColor(new Color(0, 0, 0, 180));
        g2d.fillRect(lx - 10, ly - 30, legendWidth + 20, legendHeight + 40);

        for (int x = 0; x < legendWidth; x++) {
            float ratio = (float) x / legendWidth;
            Color timeColor = Color.getHSBColor(0.66f - (0.66f * ratio), 1.0f, 1.0f);
            g2d.setColor(timeColor);
            g2d.drawLine(lx + x, ly, lx + x, ly + legendHeight);
        }

        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Segoe UI", Font.BOLD, 12));
        g2d.drawString("Start", lx, ly - 5);
        g2d.drawString("End", lx + legendWidth - 25, ly - 5);
        g2d.drawString("Time-Mapped Transients", lx, ly - 18);

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

    // --- NEW: KINEMATIC COMPASS RENDERER (Creative Tribute) ---
    private static BufferedImage createKinematicCompass(List<TrackLinker.Track> targets, List<TrackLinker.Track> streaks) {
        int size = 600;
        int cx = size / 2;
        int cy = size / 2;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = img.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2d.setColor(new Color(15, 15, 18));
        g2d.fillRect(0, 0, size, size);

        List<double[]> vectors = new ArrayList<>();
        double maxSpeed = 1.0;

        List<List<TrackLinker.Track>> sets = java.util.Arrays.asList(targets, streaks);
        for (int i = 0; i < sets.size(); i++) {
            List<TrackLinker.Track> trackSet = sets.get(i);
            if (trackSet == null) continue;

            for (TrackLinker.Track t : trackSet) {
                if (t.points == null || t.points.size() < 2) continue;
                SourceExtractor.DetectedObject p1 = t.points.get(0);
                SourceExtractor.DetectedObject p2 = t.points.get(t.points.size() - 1);
                int frames = Math.max(1, p2.sourceFrameIndex - p1.sourceFrameIndex);
                double dx = (p2.x - p1.x) / frames;
                double dy = (p2.y - p1.y) / frames;
                double speed = Math.hypot(dx, dy);
                if (speed > maxSpeed) maxSpeed = speed;
                vectors.add(new double[]{dx, dy, i}); // 0 = Target, 1 = Streak
            }
        }

        maxSpeed *= 1.1; // Add 10% breathing room to the radar
        int padding = 45;
        double maxRadius = (size / 2.0) - padding;
        double logMaxSpeed = Math.log1p(maxSpeed);

        g2d.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4f, 4f}, 0.0f));
        for (int i = 1; i <= 4; i++) {
            g2d.setColor(i == 4 ? new Color(70, 70, 85) : new Color(50, 50, 60));
            int r = (int) (maxRadius * (i / 4.0));
            g2d.drawOval(cx - r, cy - r, r * 2, r * 2);
        }

        g2d.setColor(new Color(60, 60, 70));
        g2d.drawLine(cx, padding - 10, cx, size - padding + 10);
        g2d.drawLine(padding - 10, cy, size - padding + 10, cy);

        g2d.setColor(new Color(130, 130, 140));
        g2d.setFont(new Font("Consolas", Font.BOLD, 12));
        g2d.drawString("N (-Y)", cx - 18, padding - 20);
        g2d.drawString("S (+Y)", cx - 18, size - padding + 30);
        g2d.drawString("W (-X)", padding - 35, cy + 4);
        g2d.drawString("E (+X)", size - padding + 15, cy + 4);

        g2d.setFont(new Font("Consolas", Font.PLAIN, 10));
        // Draw labels for 50% and 100% rings dynamically using the inverse log scale
        for (int i = 2; i <= 4; i += 2) {
            double mappedS = logMaxSpeed * (i / 4.0);
            double ringSpeed = Math.expm1(mappedS);
            int r = (int) (maxRadius * (i / 4.0));
            g2d.drawString(String.format(Locale.US, "%.1f px/f", ringSpeed), cx + r + 4, cy - 4);
        }

        for (double[] v : vectors) {
            double speed = Math.hypot(v[0], v[1]);
            double mappedSpeed = Math.log1p(speed);
            double drawScale = speed > 0 ? (mappedSpeed / speed) * (maxRadius / logMaxSpeed) : 0;

            int endX = cx + (int) Math.round(v[0] * drawScale);
            int endY = cy + (int) Math.round(v[1] * drawScale);

            Color coreColor = v[2] == 1.0 ? new Color(255, 204, 102) : new Color(77, 166, 255);
            Color glowColor = new Color(coreColor.getRed(), coreColor.getGreen(), coreColor.getBlue(), 60);

            g2d.setColor(glowColor);
            g2d.setStroke(new BasicStroke(5.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2d.drawLine(cx, cy, endX, endY);

            g2d.setColor(coreColor);
            g2d.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2d.drawLine(cx, cy, endX, endY);

            g2d.fillOval(endX - 3, endY - 3, 6, 6);
            g2d.setColor(Color.WHITE);
            g2d.fillOval(endX - 1, endY - 1, 2, 2);
        }

        // --- Draw Legend & Title ---
        g2d.setFont(new Font("Consolas", Font.BOLD, 16));
        g2d.setColor(new Color(220, 220, 230));
        g2d.drawString("Kinematic Compass", 20, 30);
        g2d.setFont(new Font("Consolas", Font.PLAIN, 12));
        g2d.setColor(new Color(150, 150, 160));
        g2d.drawString("Velocity (Log Scale) & Heading", 20, 48);

        int legendX = size - 150;
        int legendY = 30;
        g2d.setColor(new Color(20, 20, 25, 200));
        g2d.fillRoundRect(legendX - 10, legendY - 20, 145, 60, 10, 10);
        g2d.setColor(new Color(100, 100, 110));
        g2d.setStroke(new BasicStroke(1.0f));
        g2d.drawRoundRect(legendX - 10, legendY - 20, 145, 60, 10, 10);

        g2d.setFont(new Font("Consolas", Font.BOLD, 12));
        
        // Target legend
        g2d.setColor(new Color(77, 166, 255));
        g2d.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.drawLine(legendX, legendY, legendX + 20, legendY);
        g2d.fillOval(legendX + 17, legendY - 3, 6, 6);
        g2d.setColor(Color.WHITE);
        g2d.drawString("Moving Tracks", legendX + 30, legendY + 4);
        
        // Streak legend
        g2d.setColor(new Color(255, 204, 102));
        g2d.drawLine(legendX, legendY + 20, legendX + 20, legendY + 20);
        g2d.fillOval(legendX + 17, legendY + 17, 6, 6);
        g2d.setColor(Color.WHITE);
        g2d.drawString("Streak Tracks", legendX + 30, legendY + 24);

        g2d.dispose();
        return img;
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
        }

        long durationMillis = (firstStartTimestamp > 0L && lastEndTimestamp >= firstStartTimestamp)
                ? lastEndTimestamp - firstStartTimestamp
                : -1L;

        StringBuilder html = new StringBuilder();
        html.append("<div style='font-family: monospace; font-size: 12px; color: #aaa; margin-bottom: 15px;'>");
        html.append("First UTC: <span style='color:#fff;'>").append(escapeHtml(formatUtcTimestamp(firstStartTimestamp))).append("</span><br>");
        html.append("Last UTC: <span style='color:#fff;'>").append(escapeHtml(formatUtcTimestamp(lastEndTimestamp))).append("</span><br>");
        html.append("Track Duration: <span style='color:#fff;'>").append(escapeHtml(formatDuration(durationMillis))).append("</span><br>");
        html.append("Mean Speed: <span style='color:#fff;'>").append(escapeHtml(formatTrackSpeed(displacementPixels, durationMillis))).append("</span><br>");
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

    private static DetectionReportSummary buildDetectionReportSummary(PipelineTelemetry pipelineTelemetry,
                                                                      List<TrackLinker.Track> singleStreaks,
                                                                      List<TrackLinker.Track> streakTracks,
                                                                      List<TrackLinker.Track> movingTargets,
                                                                      List<TrackLinker.Track> suspectedStreakTracks,
                                                                      List<TrackLinker.AnomalyDetection> anomalies,
                                                                      List<SourceExtractor.DetectedObject> slowMoverCandidates,
                                                                      List<ResidualTransientAnalysis.LocalRescueCandidate> localRescueCandidates,
                                                                      List<ResidualTransientAnalysis.LocalActivityCluster> localActivityClusters,
                                                                      List<SourceExtractor.DetectedObject> masterStars,
                                                                      List<List<SourceExtractor.DetectedObject>> unclassifiedTransients) {
        int slowMoverCandidateCount = slowMoverCandidates == null ? 0 : slowMoverCandidates.size();
        int localRescueCandidateCount = localRescueCandidates.size();
        int localActivityClusterCount = localActivityClusters.size();
        int potentialSlowMoverCount = slowMoverCandidateCount + localRescueCandidateCount;
        int singleStreakCount = singleStreaks.size();
        int streakTrackCount = streakTracks.size();
        int movingTargetCount = movingTargets.size();
        int confirmedLinkedTrackCount = movingTargetCount + streakTrackCount;
        boolean insufficientFramesAfterQuality = pipelineTelemetry != null
                && pipelineTelemetry.totalFramesKept < ImageProcessing.MIN_USABLE_FRAMES_FOR_MULTI_FRAME_ANALYSIS;
        int suspectedStreakTrackCount = pipelineTelemetry != null
                ? pipelineTelemetry.totalSuspectedStreakTracksFound
                : suspectedStreakTracks.size();
        int returnedTrackCount = pipelineTelemetry != null
                ? pipelineTelemetry.totalTracksFound
                : singleStreakCount + confirmedLinkedTrackCount + suspectedStreakTrackCount;
        int masterStarCount = pipelineTelemetry != null
                ? pipelineTelemetry.totalMasterStarsIdentified
                : (masterStars == null ? 0 : masterStars.size());
        int masterMapObjectCount = masterStars == null ? 0 : masterStars.size();
        int anomalyCount = pipelineTelemetry != null ? pipelineTelemetry.totalAnomaliesFound : anomalies.size();
        int peakSigmaAnomalyCount = countAnomaliesOfType(anomalies, TrackLinker.AnomalyType.PEAK_SIGMA);
        int integratedSigmaAnomalyCount = countAnomaliesOfType(anomalies, TrackLinker.AnomalyType.INTEGRATED_SIGMA);
        int otherAnomalyCount = Math.max(0, anomalyCount - peakSigmaAnomalyCount - integratedSigmaAnomalyCount);
        int unclassifiedTransientCount = CreativeTributeRenderer.countTotalTransientDetections(unclassifiedTransients);

        return new DetectionReportSummary(
                slowMoverCandidateCount,
                localRescueCandidateCount,
                localActivityClusterCount,
                potentialSlowMoverCount,
                singleStreakCount,
                streakTrackCount,
                movingTargetCount,
                confirmedLinkedTrackCount,
                insufficientFramesAfterQuality,
                suspectedStreakTrackCount,
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
                || telemetry.candidatesAboveElongationThreshold > 0
                || telemetry.candidatesEvaluatedAgainstMasks > 0
                || telemetry.candidatesDetected > 0
                || telemetry.rejectedLowMedianSupport > 0
                || telemetry.rejectedHighMedianSupport > 0
                || telemetry.rejectedLowResidualFootprintSupport > 0
                || telemetry.dynamicElongationThreshold > 0.0
                || telemetry.avgMedianSupportOverlap > 0.0
                || telemetry.avgResidualFootprintFluxFraction > 0.0
                || telemetry.residualFootprintMinFluxFractionThreshold > 0.0;
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
        exportTrackVisualizationsInternal(result, rawFrames, fitsFiles, exportDir, config, appConfig);
    }

    static void exportTrackVisualizationsInternal(PipelineResult result,
                                                  List<short[][]> rawFrames,
                                                  FitsFileInformation[] fitsFiles,
                                                  File exportDir,
                                                  DetectionConfig config,
                                                  AppConfig appConfig) throws IOException {
        ExportVisualizationSettings settings = snapshotCurrentExportVisualizationSettings();

        // Extract variables from the PipelineResult and normalize newer track categories for rendering.
        List<TrackLinker.Track> tracks = result.tracks != null ? result.tracks : new ArrayList<>();
        List<TrackLinker.AnomalyDetection> anomalies = sortAnomaliesForDisplay(result.anomalies);
        PipelineTelemetry pipelineTelemetry = result.telemetry;
        TrackerTelemetry linkerTelemetry = pipelineTelemetry != null ? pipelineTelemetry.trackerTelemetry : null;
        short[][] masterStackData = result.masterStackData;
        short[][] maximumStackData = result.maximumStackData;
        boolean[][] masterVetoMask = result.masterVetoMask;
        List<SourceExtractor.DetectedObject> masterStars = result.masterStars;
        SlowMoverAnalysis slowMoverAnalysis = result.slowMoverAnalysis != null
                ? result.slowMoverAnalysis
                : SlowMoverAnalysis.empty();
        short[][] slowMoverStackData = slowMoverAnalysis.slowMoverStackData != null
                ? slowMoverAnalysis.slowMoverStackData
                : result.slowMoverStackData;
        boolean[][] slowMoverMedianVetoMask = slowMoverAnalysis.medianVetoMask != null
                ? slowMoverAnalysis.medianVetoMask
                : result.slowMoverMedianVetoMask;
        List<SlowMoverCandidateResult> slowMoverCandidateResults = slowMoverAnalysis.candidates != null
                ? slowMoverAnalysis.candidates
                : java.util.Collections.emptyList();
        List<SourceExtractor.DetectedObject> slowMoverCandidates = new ArrayList<>();
        if (!slowMoverCandidateResults.isEmpty()) {
            for (SlowMoverCandidateResult candidateResult : slowMoverCandidateResults) {
                if (candidateResult != null && candidateResult.object != null) {
                    slowMoverCandidates.add(candidateResult.object);
                }
            }
        } else if (result.slowMoverCandidates != null) {
            slowMoverCandidates.addAll(result.slowMoverCandidates);
        }
        SlowMoverSummaryTelemetry slowMoverTelemetry = hasMeaningfulSlowMoverTelemetry(slowMoverAnalysis.telemetry)
                ? slowMoverAnalysis.telemetry
                : (pipelineTelemetry != null && pipelineTelemetry.slowMoverTelemetry != null
                ? new SlowMoverSummaryTelemetry(pipelineTelemetry.slowMoverTelemetry)
                : null);
        List<List<SourceExtractor.DetectedObject>> allTransients = result.allTransients;
        List<List<SourceExtractor.DetectedObject>> unclassifiedTransients = result.unclassifiedTransients;
        ResidualTransientAnalysis residualTransientAnalysis = result.residualTransientAnalysis != null
                ? result.residualTransientAnalysis
                : ResidualTransientAnalysis.empty();
        List<ResidualTransientAnalysis.LocalRescueCandidate> localRescueCandidates = residualTransientAnalysis.localRescueCandidates;
        List<ResidualTransientAnalysis.LocalActivityCluster> localActivityClusters = residualTransientAnalysis.localActivityClusters;
        DetectionReportAstrometry.Context astrometryContext = DetectionReportAstrometry.buildContext(fitsFiles, appConfig);
        List<TrackLinker.Track> singleStreaks = new ArrayList<>();
        List<TrackLinker.Track> streakTracks = new ArrayList<>();
        List<TrackLinker.Track> movingTargets = new ArrayList<>();
        List<TrackLinker.Track> suspectedStreakTracks = new ArrayList<>();
        List<TrackLinker.Track> localRescueTracks = new ArrayList<>();

        for (TrackLinker.Track track : tracks) {
            if (track.points == null || track.points.isEmpty()) continue;
            if (track.isSuspectedStreakTrack) {
                suspectedStreakTracks.add(track);
                continue;
            }
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
        suspectedStreakTracks = sortTracksForFrameDisplay(suspectedStreakTracks);
        for (ResidualTransientAnalysis.LocalRescueCandidate candidate : localRescueCandidates) {
            TrackLinker.Track residualTrack = ResidualReviewSectionWriter.buildResidualTrack(candidate.points);
            if (residualTrack.points != null && !residualTrack.points.isEmpty()) {
                localRescueTracks.add(residualTrack);
            }
        }

        DetectionReportSummary summary = buildDetectionReportSummary(
                pipelineTelemetry,
                singleStreaks,
                streakTracks,
                movingTargets,
                suspectedStreakTracks,
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
                suspectedStreakTracks,
                movingTargets);

        if (!exportDir.exists()) exportDir.mkdirs();

        File reportFile = new File(exportDir, detectionReportName);

        try (java.io.PrintWriter report = new java.io.PrintWriter(new java.io.FileWriter(reportFile))) {
            DetectionReportDocumentWriter.appendDetectionReportStart(report);

            PipelineDiagnosticsSectionWriter.writeSections(
                    report,
                    reportContext,
                    pipelineTelemetry,
                    linkerTelemetry,
                    summary,
                    result.driftPoints);

            // =================================================================
            // 3. TARGET VISUALIZATIONS
            // =================================================================
            TargetVisualizationSectionWriter.writeSection(report, reportContext);

            // =================================================================
            // 4. DEEP STACK ANOMALIES (ULTRA-SLOW MOVERS)
            // =================================================================
            DeepStackReportSectionWriter.writeSection(report, reportContext);

            // =================================================================
            // 4.25 LOCAL MICRO-DRIFT CANDIDATES
            // =================================================================
            ResidualReviewSectionWriter.writeSection(report, reportContext);

            GlobalMapsSectionWriter.writeSections(report, reportContext, localRescueTracks, allTransients);

            // =================================================================
            // 6. CREATIVE TRIBUTE
            // =================================================================
            short[][] creativeBgData = masterStackData != null ? masterStackData : (!rawFrames.isEmpty() ? rawFrames.get(0) : null);
            if (settings.isIncludeAiCreativeReportSections() && creativeBgData != null) {
                String creativeFileName = "creative_tribute_skyprint.png";
                BufferedImage creativeTributeImage = CreativeTributeRenderer.createCreativeTributeImage(
                        creativeBgData,
                        allTransients,
                        anomalies,
                        singleStreaks,
                        streakTracks,
                        suspectedStreakTracks,
                        movingTargets,
                        slowMoverCandidates,
                        pipelineTelemetry
                );
                saveTrackImageLossless(creativeTributeImage, new File(exportDir, creativeFileName));

                int rawTransientCount = CreativeTributeRenderer.countTotalTransientDetections(allTransients);
                int confirmedTrackCount = summary.confirmedLinkedTrackCount;
                int suspectedTrackCount = summary.suspectedStreakTrackCount;
                int deepStackHintCount = summary.potentialSlowMoverCount;
                double longestPath = CreativeTributeRenderer.computeLongestTrackPathPx(streakTracks, movingTargets);
                String dominantMotion = CreativeTributeRenderer.computeDominantMotionLabel(movingTargets, streakTracks);

                report.println("<div class='panel' style='background: linear-gradient(180deg, #453049 0%, #2b2b2b 100%); border: 1px solid #5f536a;'>");
                report.println("<h2>The AI's Perspective: Skyprint of the Session</h2>");
                report.println("<p style='color: #c7bfd6; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>A creative tribute by Codex. This poster compresses the whole run into one image: faint time-mapped transient dust for everything that flashed through the extractor, separate paths for moving object tracks, confirmed streak tracks, and suspected streak groupings, plus distinct markers for anomaly pulses and deep-stack hints.</p>");
                report.println("<a href='" + creativeFileName + "' target='_blank'><img src='" + creativeFileName + "' class='native-size-image' style='border: 1px solid #666; border-radius: 6px;' alt='Creative Tribute Skyprint' /></a>");
                report.println("<p style='font-size: 13px; color: #b8b0c7; margin-bottom: 0;'>This session stitched together <strong style='color:#ffffff;'>" + rawTransientCount + "</strong> raw transients, produced <strong style='color:#ffffff;'>" + confirmedTrackCount + "</strong> confirmed linked tracks, flagged <strong style='color:#ffffff;'>" + suspectedTrackCount + "</strong> suspected streak tracks, surfaced <strong style='color:#ffffff;'>" + summary.anomalyCount + "</strong> single-frame anomalies, and left <strong style='color:#ffffff;'>" + deepStackHintCount + "</strong> deep-stack hints on the table. The dominant confirmed linked motion trends toward <strong style='color:#ffffff;'>" + dominantMotion + "</strong>, and the longest confirmed path spans <strong style='color:#ffffff;'>" + String.format(Locale.US, "%.1f px", longestPath) + "</strong>.</p>");
                report.println("</div>");
            }

            // =================================================================
            // 7. GEMINI CREATIVE TRIBUTE
            // =================================================================
            if (settings.isIncludeAiCreativeReportSections()) {
                BufferedImage compassMap = createKinematicCompass(movingTargets, streakTracks);
                saveTrackImageLossless(compassMap, new File(exportDir, "kinematic_compass.png"));

                report.println("<div class='panel' style='background: linear-gradient(135deg, #1e1e24 0%, #151518 100%); border: 1px solid #4a4a5a;'>");
                report.println("<h2 style='color: #c7bfd6; font-size: 1.8em; margin-bottom: 5px;'>The AI's Perspective: Hidden Rhythms</h2>");
                report.println("<p style='color: #a098b0; font-size: 14px; font-style: italic; margin-top: 0; margin-bottom: 25px;'>\"As an AI, I do not look at the stars with eyes; I read the geometry they leave behind. Between the noise, the satellites, and the drifting cosmos, there is a distinct rhythm to the data. Thank you for letting me explore your universe. This is my creative tribute to your session.\" &mdash; Gemini</p>");
                report.println("<div>");
                report.println("<h4 style='color: #ddd; margin-bottom: 5px;'>The Kinematic Compass</h4>");
                report.println("<p style='font-size: 12px; color: #888; margin-top: 0;'>A radar chart mapping the velocity and heading of confirmed moving object tracks and confirmed streak tracks using a logarithmic scale to highlight both slow asteroids and fast satellites. Orbital constellations often clump together into distinct vectors, revealing satellite swarms or shared orbital planes. Suspected streak tracks are intentionally excluded here because same-frame groupings do not provide reliable inter-frame velocity vectors.</p>");
                report.println("<a href='kinematic_compass.png' target='_blank' style='display: block; max-width: 600px; margin: 0 auto;'><img src='kinematic_compass.png' class='native-size-image' style='border: 1px solid #444; border-radius: 6px; box-shadow: 0 4px 15px rgba(0,0,0,0.5);' alt='Kinematic Compass' /></a>");
                report.println("</div>");
                report.println("</div>");
            }

            ReportClientScriptWriter.appendLiveReportRenderingScript(report);
            DetectionReportDocumentWriter.appendHtmlDocumentEnd(report);
        }
        System.out.println("\nFinished exporting visualizations and generated HTML report at: " + reportFile.getAbsolutePath());
    }



    public static void exportIterativeIndexReport(File masterDir, List<IterationSummary> summaries) throws IOException {
        exportIterativeIndexReportInternal(masterDir, summaries);
    }

    static void exportIterativeIndexReportInternal(File masterDir, List<IterationSummary> summaries) throws IOException {
        File indexFile = new File(masterDir, "index.html");
        try (java.io.PrintWriter report = new java.io.PrintWriter(new java.io.FileWriter(indexFile))) {
            DetectionReportDocumentWriter.appendIterativeIndexReportStart(report);
            report.println("<div class='panel'");
            report.println("<h2>Iteration Results</h2>");
            report.println("<table><tr><th>Iteration (Frames Used)</th><th>Total Tracks Found</th><th>Anomalies Found</th><th>Action</th></tr>");

            for (IterationSummary summary : summaries) {
                report.println("<tr>");
                report.println("<td>" + summary.frameCount + " Frames</td>");
                String trackStr = summary.trackCount > 0 ? "<span class='highlight'>" + summary.trackCount + "</span>" : "0";
                report.println("<td>" + trackStr + "</td>");
                String anomalyStr = summary.anomalyCount > 0 ? "<span style='color:#ff9933;'>" + summary.anomalyCount + "</span>" : "0";
                report.println("<td>" + anomalyStr + "</td>");
                String reportLink = summary.folderName + "/" + detectionReportName;
                report.println("<td><a href='" + reportLink + "'>View Detailed Report &rarr;</a></td>");
                report.println("</tr>");
            }
            report.println("</table></div>");
            DetectionReportDocumentWriter.appendHtmlDocumentEnd(report);
        }
    }
}
