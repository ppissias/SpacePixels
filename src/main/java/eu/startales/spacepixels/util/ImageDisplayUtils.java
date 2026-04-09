/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */

package eu.startales.spacepixels.util;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverCandidateDiagnostics;
import io.github.ppissias.jtransient.core.SlowMoverCandidateResult;
import io.github.ppissias.jtransient.core.SlowMoverSummaryTelemetry;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import io.github.ppissias.jtransient.telemetry.TrackerTelemetry;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
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

public class ImageDisplayUtils {

    private enum CreativeLegendGlyph {
        TRACK,
        STREAK,
        PULSE,
        DIAMOND,
        DUST
    }

    // =================================================================
    // CONFIGURATION PARAMETERS
    // =================================================================

    // --- Auto-Stretch Parameters (Standard) ---
    public static double autoStretchBlackSigma = 0.5;
    public static double autoStretchWhiteSigma = 5.0;

    // --- Export & Cropping Parameters ---
    public static int gifBlinkSpeedMs = 300;
    public static int trackCropPadding = 200;
    public static int trackObjectCentricCropSize = 200;
    public static boolean includeAiCreativeReportSections = false;

    // --- Annotation Tools (For GIFs) ---
    public static int targetCircleRadius = 15;
    public static float targetCircleStrokeWidth = 2.0f;

    public static final String detectionReportName = "detection_report.html";

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

    public static class CropBounds {
        public final int trackBoxWidth, trackBoxHeight, fixedCenterX, fixedCenterY, startX, startY;

        public CropBounds(TrackLinker.Track track, int padding) {
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

            for (SourceExtractor.DetectedObject pt : track.points) {
                double objectRadius = 0;
                if (pt.pixelArea > 0) {
                    if (pt.isStreak) objectRadius = Math.sqrt((pt.pixelArea * pt.elongation) / Math.PI);
                    else objectRadius = Math.sqrt(pt.pixelArea / Math.PI);
                }
                if (pt.x - objectRadius < minX) minX = pt.x - objectRadius;
                if (pt.x + objectRadius > maxX) maxX = pt.x + objectRadius;
                if (pt.y - objectRadius < minY) minY = pt.y - objectRadius;
                if (pt.y + objectRadius > maxY) maxY = pt.y + objectRadius;
            }

            if (minX == Double.MAX_VALUE) minX = 0; if (maxX == -Double.MAX_VALUE) maxX = 0;
            if (minY == Double.MAX_VALUE) minY = 0; if (maxY == -Double.MAX_VALUE) maxY = 0;

            trackBoxWidth = (int) Math.round(maxX - minX) + padding;
            trackBoxHeight = (int) Math.round(maxY - minY) + padding;
            fixedCenterX = (int) Math.round((minX + maxX) / 2.0);
            fixedCenterY = (int) Math.round((minY + maxY) / 2.0);

            startX = fixedCenterX - (trackBoxWidth / 2);
            startY = fixedCenterY - (trackBoxHeight / 2);
        }

        public CropBounds(SourceExtractor.DetectedObject detection, int padding) {
            double objectRadius = 0;
            if (detection != null && detection.pixelArea > 0) {
                if (detection.isStreak) {
                    objectRadius = Math.sqrt((detection.pixelArea * detection.elongation) / Math.PI);
                } else {
                    objectRadius = Math.sqrt(detection.pixelArea / Math.PI);
                }
            }

            double minX = detection == null ? 0 : detection.x - objectRadius;
            double maxX = detection == null ? 0 : detection.x + objectRadius;
            double minY = detection == null ? 0 : detection.y - objectRadius;
            double maxY = detection == null ? 0 : detection.y + objectRadius;

            trackBoxWidth = (int) Math.round(maxX - minX) + padding;
            trackBoxHeight = (int) Math.round(maxY - minY) + padding;
            fixedCenterX = (int) Math.round((minX + maxX) / 2.0);
            fixedCenterY = (int) Math.round((minY + maxY) / 2.0);

            startX = fixedCenterX - (trackBoxWidth / 2);
            startY = fixedCenterY - (trackBoxHeight / 2);
        }
    }

    private static class CreativeTributeLayout {
        public final int cropX;
        public final int cropY;
        public final int cropWidth;
        public final int cropHeight;
        public final int outputWidth;
        public final int outputHeight;
        public final int headerHeight;
        public final int canvasHeight;
        public final int plotOffsetY;
        public final double scale;

        public CreativeTributeLayout(int cropX, int cropY, int cropWidth, int cropHeight, int outputWidth, int outputHeight, int headerHeight, int canvasHeight, int plotOffsetY, double scale) {
            this.cropX = cropX;
            this.cropY = cropY;
            this.cropWidth = cropWidth;
            this.cropHeight = cropHeight;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            this.headerHeight = headerHeight;
            this.canvasHeight = canvasHeight;
            this.plotOffsetY = plotOffsetY;
            this.scale = scale;
        }
    }

    private static class MaskOverlapStats {
        public final int overlappingPixels;
        public final int totalPixels;
        public final double fraction;

        public MaskOverlapStats(int overlappingPixels, int totalPixels) {
            this.overlappingPixels = overlappingPixels;
            this.totalPixels = totalPixels;
            this.fraction = totalPixels > 0 ? (double) overlappingPixels / totalPixels : 0.0;
        }
    }

    private static class ReportTrackReference {
        public final TrackLinker.Track track;
        public final String label;
        public final String categoryLabel;

        public ReportTrackReference(TrackLinker.Track track, String label, String categoryLabel) {
            this.track = track;
            this.label = label;
            this.categoryLabel = categoryLabel;
        }
    }

    private static class SlowMoverTrackMatch {
        public final String label;
        public final String categoryLabel;
        public final int overlappingTrackPoints;
        public final double bestDistancePixels;

        public SlowMoverTrackMatch(String label,
                                   String categoryLabel,
                                   int overlappingTrackPoints,
                                   double bestDistancePixels) {
            this.label = label;
            this.categoryLabel = categoryLabel;
            this.overlappingTrackPoints = overlappingTrackPoints;
            this.bestDistancePixels = bestDistancePixels;
        }
    }

    /**
     * Returns an evenly spaced sample of chronological frame indices while guaranteeing that
     * mandatory frames (where actual detections occur) are included to never miss a transient.
     */
    private static List<Integer> getRepresentativeSequence(int totalFrames, java.util.Set<Integer> mandatoryFrames, int maxFrames) {
        java.util.TreeSet<Integer> selected = new java.util.TreeSet<>();

        if (totalFrames <= maxFrames) {
            for (int i = 0; i < totalFrames; i++) selected.add(i);
            return new ArrayList<>(selected);
        }

        List<Integer> mandatoryList = new ArrayList<>(mandatoryFrames);
        java.util.Collections.sort(mandatoryList);

        if (mandatoryList.size() >= maxFrames) {
            if (maxFrames == 1) {
                selected.add(mandatoryList.get(0));
            } else {
                for (int i = 0; i < maxFrames; i++) {
                    int idx = (int) Math.round(i * (mandatoryList.size() - 1) / (double) (maxFrames - 1));
                    selected.add(mandatoryList.get(idx));
                }
            }
            return new ArrayList<>(selected);
        }

        selected.addAll(mandatoryList);

        List<Integer> available = new ArrayList<>();
        for (int i = 0; i < totalFrames; i++) {
            if (!mandatoryFrames.contains(i)) available.add(i);
        }

        int needed = maxFrames - selected.size();
        if (needed > 0 && !available.isEmpty()) {
            if (needed == 1) {
                selected.add(available.get(available.size() / 2));
            } else {
                for (int i = 0; i < needed; i++) {
                    int idx = (int) Math.round(i * (available.size() - 1) / (double) (needed - 1));
                    selected.add(available.get(idx));
                }
            }
        }

        return new ArrayList<>(selected);
    }

    // =================================================================
    // IMAGE RENDERING & CROPPING
    // =================================================================

    public static BufferedImage createDisplayImage(short[][] imageData) {
        int height = imageData.length;
        int width = imageData[0].length;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = image.getRaster();

        long sum = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                sum += (imageData[y][x] + 32768);
            }
        }
        double mean = (double) sum / (width * height);

        double sumSqDiff = 0;
        int actualMax = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int val = imageData[y][x] + 32768;
                if (val > actualMax) actualMax = val;

                double diff = val - mean;
                sumSqDiff += (diff * diff);
            }
        }
        double variance = sumSqDiff / (width * height);
        double sigma = Math.sqrt(variance);

        double blackPoint = mean - (autoStretchBlackSigma * sigma);
        double whitePoint = mean + (autoStretchWhiteSigma * sigma);

        if (blackPoint < 0) blackPoint = 0;
        if (whitePoint > actualMax) whitePoint = actualMax;

        double range = whitePoint - blackPoint;
        if (range <= 0) range = 1.0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int val = imageData[y][x] + 32768;

                double adjustedVal = val - blackPoint;
                if (adjustedVal < 0) adjustedVal = 0;
                if (adjustedVal > range) adjustedVal = range;

                double normalized = adjustedVal / range;
                double stretched = Math.sqrt(normalized);
                int displayValue = (int) (stretched * 255.0);

                if (displayValue > 255) displayValue = 255;
                if (displayValue < 0) displayValue = 0;

                raster.setSample(x, y, 0, displayValue);
            }
        }

        return image;
    }

    // --- DIAGNOSTIC MASK RENDERER ---
    private static BufferedImage createMasterMaskOverlay(short[][] masterStackData, boolean[][] masterMask) {
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
        short[][] croppedMasterData = robustEdgeAwareCrop(backgroundData, cx, cy, cropWidth, cropHeight);
        BufferedImage grayImage = createDisplayImage(croppedMasterData);
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
            g2d.setStroke(new BasicStroke(targetCircleStrokeWidth));
            g2d.drawOval(localX - targetCircleRadius, localY - targetCircleRadius, targetCircleRadius * 2, targetCircleRadius * 2);
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
        return createCroppedMaskOverlay(
                masterStackData,
                masterMask,
                cx,
                cy,
                cropWidth,
                cropHeight,
                highlightDetection,
                new Color(255, 32, 32));
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

    private static MaskOverlapStats computeMaskOverlapStats(SourceExtractor.DetectedObject detection,
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

    private static List<ReportTrackReference> buildReportTrackReferences(List<TrackLinker.Track> streakTracks,
                                                                         List<TrackLinker.Track> suspectedStreakTracks,
                                                                         List<TrackLinker.Track> movingTargets) {
        List<ReportTrackReference> references = new ArrayList<>();
        int stCounter = 1;
        if (streakTracks != null) {
            for (TrackLinker.Track track : streakTracks) {
                references.add(new ReportTrackReference(track, "ST" + stCounter, "streak track"));
                stCounter++;
            }
        }

        int sstCounter = 1;
        if (suspectedStreakTracks != null) {
            for (TrackLinker.Track track : suspectedStreakTracks) {
                references.add(new ReportTrackReference(track, "SST" + sstCounter, "suspected streak track"));
                sstCounter++;
            }
        }

        int tCounter = 1;
        if (movingTargets != null) {
            for (TrackLinker.Track track : movingTargets) {
                references.add(new ReportTrackReference(track, "T" + tCounter, "moving object track"));
                tCounter++;
            }
        }
        return references;
    }

    private static List<SlowMoverTrackMatch> findMatchingReportedTracks(SourceExtractor.DetectedObject detection,
                                                                        List<ReportTrackReference> trackReferences) {
        List<SlowMoverTrackMatch> matches = new ArrayList<>();
        if (detection == null || trackReferences == null || trackReferences.isEmpty()) {
            return matches;
        }

        for (ReportTrackReference trackReference : trackReferences) {
            SlowMoverTrackMatch match = computeSlowMoverTrackMatch(detection, trackReference);
            if (match != null) {
                matches.add(match);
            }
        }

        matches.sort(Comparator
                .comparingInt((SlowMoverTrackMatch match) -> match.overlappingTrackPoints).reversed()
                .thenComparingDouble(match -> match.bestDistancePixels)
                .thenComparing(match -> match.label));
        return matches;
    }

    private static SlowMoverTrackMatch computeSlowMoverTrackMatch(SourceExtractor.DetectedObject detection,
                                                                  ReportTrackReference trackReference) {
        if (detection == null || trackReference == null || trackReference.track == null
                || trackReference.track.points == null || trackReference.track.points.isEmpty()) {
            return null;
        }

        double footprintRadius = computeDetectionFootprintRadius(detection);
        double matchTolerancePixels = Math.max(3.0, Math.min(10.0, footprintRadius * 0.55 + 2.0));
        int overlappingTrackPoints = 0;
        double bestDistancePixels = Double.POSITIVE_INFINITY;

        for (SourceExtractor.DetectedObject point : trackReference.track.points) {
            if (point == null) {
                continue;
            }
            double pointDistance = computePointToDetectionDistance(point, detection);
            bestDistancePixels = Math.min(bestDistancePixels, pointDistance);
            if (pointDistance <= matchTolerancePixels) {
                overlappingTrackPoints++;
            }
        }

        if (overlappingTrackPoints == 0 || !Double.isFinite(bestDistancePixels)) {
            return null;
        }

        return new SlowMoverTrackMatch(
                trackReference.label,
                trackReference.categoryLabel,
                overlappingTrackPoints,
                bestDistancePixels
        );
    }

    private static double computeDetectionFootprintRadius(SourceExtractor.DetectedObject detection) {
        if (detection == null) {
            return 0.0;
        }
        double area = detection.pixelArea > 0.0 ? detection.pixelArea : 0.0;
        if (area <= 0.0 && detection.rawPixels != null && !detection.rawPixels.isEmpty()) {
            area = detection.rawPixels.size();
        }
        if (area <= 0.0) {
            area = 1.0;
        }
        return Math.sqrt(area / Math.PI);
    }

    private static double computePointToDetectionDistance(SourceExtractor.DetectedObject point,
                                                          SourceExtractor.DetectedObject detection) {
        if (point == null || detection == null) {
            return Double.POSITIVE_INFINITY;
        }

        if (detection.rawPixels != null && !detection.rawPixels.isEmpty()) {
            double bestDistancePixels = Double.POSITIVE_INFINITY;
            for (SourceExtractor.Pixel pixel : detection.rawPixels) {
                double dx = point.x - pixel.x;
                double dy = point.y - pixel.y;
                bestDistancePixels = Math.min(bestDistancePixels, Math.hypot(dx, dy));
            }
            if (Double.isFinite(bestDistancePixels)) {
                return bestDistancePixels;
            }
        }

        return Math.hypot(point.x - detection.x, point.y - detection.y);
    }

    private static String buildSlowMoverTrackMatchSummaryHtml(List<SlowMoverTrackMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return "";
        }

        StringBuilder html = new StringBuilder();
        int displayedMatches = Math.min(matches.size(), 3);
        for (int i = 0; i < displayedMatches; i++) {
            SlowMoverTrackMatch match = matches.get(i);
            if (i > 0) {
                html.append(", ");
            }
            html.append("<span style='color:#fff;'>")
                    .append(escapeHtml(match.label))
                    .append("</span> <span style='color:#888;'>(")
                    .append(escapeHtml(match.categoryLabel))
                    .append(", ")
                    .append(match.overlappingTrackPoints)
                    .append(match.overlappingTrackPoints == 1 ? " point" : " points")
                    .append(", best ")
                    .append(String.format(Locale.US, "%.1f px", match.bestDistancePixels))
                    .append(")</span>");
        }
        if (matches.size() > displayedMatches) {
            html.append(" <span style='color:#777;'>+")
                    .append(matches.size() - displayedMatches)
                    .append(" more</span>");
        }
        return html.toString();
    }

    private static String formatPercent(double fraction) {
        return String.format(Locale.US, "%.1f%%", fraction * 100.0);
    }

    private static String formatOptionalMetric(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.2f", value) : "n/a";
    }

    private static void saveTrackImageLossless(BufferedImage image, File outputFile) throws IOException {
        if (image == null) {
            System.err.println("Warning: Attempted to save a null image. Skipping.");
            return;
        }

        boolean success = ImageIO.write(image, "png", outputFile);

        if (!success) {
            throw new IOException("No appropriate PNG writer found in ImageIO.");
        }
    }

    private static short[][] robustEdgeAwareCrop(short[][] fullImage, int cx, int cy, int cropWidth, int cropHeight) {
        int halfWidth = cropWidth / 2;
        int halfHeight = cropHeight / 2;
        short[][] cropped = new short[cropHeight][cropWidth];

        int height = fullImage.length;
        int width = fullImage[0].length;

        for (int y = 0; y < cropHeight; y++) {
            for (int x = 0; x < cropWidth; x++) {
                int sourceY = cy - halfHeight + y;
                int sourceX = cx - halfWidth + x;

                if (sourceY >= 0 && sourceY < height && sourceX >= 0 && sourceX < width) {
                    cropped[y][x] = fullImage[sourceY][sourceX];
                } else {
                    cropped[y][x] = -32768;
                }
            }
        }
        return cropped;
    }

    // --- SINGLE STREAK SHAPE RENDERER ---
    private static BufferedImage createSingleStreakShapeImage(List<SourceExtractor.DetectedObject> points, int cropWidth, int cropHeight, int startX, int startY, boolean drawCentroid) {
        BufferedImage image = new BufferedImage(cropWidth, cropHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        // 1. Fill black background
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, cropWidth, cropHeight);

        // 2. Draw Raw Pixels for the streak footprint
        for (SourceExtractor.DetectedObject pt : points) {
            if (pt.rawPixels != null) {
                for (SourceExtractor.Pixel p : pt.rawPixels) {
                    int x = p.x - startX;
                    int y = p.y - startY;
                    if (x >= 0 && x < cropWidth && y >= 0 && y < cropHeight) {
                        image.setRGB(x, y, new Color(255, 80, 80).getRGB()); // Bright red/pink footprint
                    }
                }
                if (drawCentroid) {
                    // 3. Mark the Centroid with a white target
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int cx = (int) Math.round(pt.x - startX);
                    int cy = (int) Math.round(pt.y - startY);
                    g2d.setColor(Color.WHITE);
                    g2d.setStroke(new BasicStroke(1.5f));
                    g2d.drawOval(cx - 3, cy - 3, 6, 6);
                }
            }

        }


        g2d.dispose();
        return image;
    }

    // --- TRACK SHAPE RENDERER ---
    private static BufferedImage createTrackShapeImage(TrackLinker.Track track, int cropWidth, int cropHeight, int startX, int startY) {
        BufferedImage image = new BufferedImage(cropWidth, cropHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        // 1. Fill black background
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, cropWidth, cropHeight);

        // 2. Enable Anti-aliasing for smooth lines
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 3. Draw lines connecting the track points
        g2d.setColor(new Color(100, 150, 255)); // Light Blue line
        g2d.setStroke(new BasicStroke(2.0f));

        for (int i = 0; i < track.points.size() - 1; i++) {
            SourceExtractor.DetectedObject p1 = track.points.get(i);
            SourceExtractor.DetectedObject p2 = track.points.get(i + 1);

            int x1 = (int) Math.round(p1.x - startX);
            int y1 = (int) Math.round(p1.y - startY);
            int x2 = (int) Math.round(p2.x - startX);
            int y2 = (int) Math.round(p2.y - startY);

            g2d.drawLine(x1, y1, x2, y2);
        }

        // 4. Draw the actual detection points and their frame order
        for (int i = 0; i < track.points.size(); i++) {
            SourceExtractor.DetectedObject p = track.points.get(i);
            int x = (int) Math.round(p.x - startX);
            int y = (int) Math.round(p.y - startY);

            // Draw Red Dot
            g2d.setColor(Color.RED);
            g2d.fillOval(x - 4, y - 4, 8, 8);

            // Draw Sequence Number next to the dot
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("Monospaced", Font.BOLD, 12));
            g2d.drawString(String.valueOf(i + 1), x + 6, y - 6);
        }

        g2d.dispose();
        return image;
    }

    // --- REFACTORED: GLOBAL TRACK MAP RENDERER ---
    private static final Color GLOBAL_MAP_ANOMALY_COLOR = new Color(255, 51, 255);
    private static final Color GLOBAL_MAP_SINGLE_STREAK_COLOR = new Color(255, 153, 51);
    private static final Color GLOBAL_MAP_STREAK_TRACK_COLOR = new Color(255, 204, 51);
    private static final Color GLOBAL_MAP_SUSPECTED_STREAK_COLOR = new Color(255, 128, 128);
    private static final Color GLOBAL_MAP_MOVING_TARGET_COLOR = new Color(77, 166, 255);
    private static final Color GLOBAL_MAP_LOCAL_RESCUE_COLOR = new Color(64, 224, 208);
    private static final Color GLOBAL_MAP_LOCAL_ACTIVITY_COLOR = new Color(150, 120, 255);
    private static final Color GLOBAL_MAP_DEEP_STACK_COLOR = new Color(170, 255, 110);

    private static BufferedImage createGlobalTrackMap(short[][] backgroundData,
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

    private static BufferedImage createMicroDriftTrailImage(TrackLinker.Track track,
                                                            short[][] backgroundData,
                                                            int cropWidth,
                                                            int cropHeight,
                                                            int startX,
                                                            int startY) {
        short[][] croppedBackground = robustEdgeAwareCrop(
                backgroundData,
                startX + (cropWidth / 2),
                startY + (cropHeight / 2),
                cropWidth,
                cropHeight);
        BufferedImage grayBackground = createDisplayImage(croppedBackground);
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

    private static BufferedImage createDriftMap(List<SourceExtractor.Pixel> path, int outSize) {
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

    private static BufferedImage createFourCornerMosaic(short[][] frame, int targetCropSize) {
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
    private static BufferedImage createGlobalTransientMap(short[][] backgroundData, List<List<SourceExtractor.DetectedObject>> allTransients) {
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
    private static BufferedImage createRainbowClusterMap(short[][] backgroundData, List<List<SourceExtractor.DetectedObject>> allTransients) {
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

    // --- NEW: SLOW MOVER DIFFERENCE MAP RENDERER ---
    private static BufferedImage createSlowMoverDifferenceMap(short[][] slowMover, short[][] master) {
        int width = master[0].length;
        int height = master.length;

        BufferedImage rgbMap = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        int[][] diff = new int[height][width];
        long sum = 0;
        int maxDiff = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int smVal = slowMover[y][x] + 32768;
                int mVal = master[y][x] + 32768;
                int d = smVal - mVal;
                if (d < 0) d = 0;
                diff[y][x] = d;
                sum += d;
                if (d > maxDiff) maxDiff = d;
            }
        }

        double mean = (double) sum / (width * height);
        double sumSq = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double d = diff[y][x] - mean;
                sumSq += d * d;
            }
        }
        double stdDev = Math.sqrt(sumSq / (width * height));

        // Use 2-sigma threshold to capture structurally brighter anomalies without extreme noise
        double threshold = mean + (2.0 * stdDev);
        double range = maxDiff - threshold;
        if (range <= 0) range = 1.0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (diff[y][x] > threshold) {
                    double intensity = (diff[y][x] - threshold) / range;
                    intensity = Math.sqrt(intensity); // Non-linear stretch
                    if (intensity > 1.0) intensity = 1.0;

                    // Draw directly as bright red over the purely black background
                    int r = (int) Math.min(255, intensity * 255);
                    rgbMap.setRGB(x, y, (255 << 24) | (r << 16) | (0 << 8) | 0);
                }
            }
        }

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

    private static BufferedImage createCreativeTributeImage(short[][] backgroundData,
                                                            List<List<SourceExtractor.DetectedObject>> allTransients,
                                                            List<TrackLinker.AnomalyDetection> anomalies,
                                                            List<TrackLinker.Track> singleStreaks,
                                                            List<TrackLinker.Track> streakTracks,
                                                            List<TrackLinker.Track> suspectedStreakTracks,
                                                            List<TrackLinker.Track> movingTargets,
                                                            List<SourceExtractor.DetectedObject> slowMoverCandidates,
                                                            PipelineTelemetry pipelineTelemetry) {
        CreativeTributeLayout layout = createCreativeTributeLayout(
                backgroundData,
                allTransients,
                anomalies,
                singleStreaks,
                streakTracks,
                suspectedStreakTracks,
                movingTargets,
                slowMoverCandidates
        );

        short[][] croppedBackground = robustEdgeAwareCrop(
                backgroundData,
                layout.cropX + (layout.cropWidth / 2),
                layout.cropY + (layout.cropHeight / 2),
                layout.cropWidth,
                layout.cropHeight
        );
        BufferedImage grayBg = createDisplayImage(croppedBackground);
        BufferedImage tribute = new BufferedImage(layout.outputWidth, layout.canvasHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = tribute.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2d.setColor(new Color(5, 8, 14));
        g2d.fillRect(0, 0, layout.outputWidth, layout.canvasHeight);
        g2d.setPaint(new GradientPaint(
                0, 0, new Color(12, 15, 24),
                0, layout.headerHeight, new Color(18, 22, 32)
        ));
        g2d.fillRect(0, 0, layout.outputWidth, layout.headerHeight);
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.28f));
        g2d.drawImage(grayBg, 0, layout.plotOffsetY, layout.outputWidth, layout.outputHeight, null);
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));

        g2d.setPaint(new GradientPaint(
                0, layout.plotOffsetY, new Color(32, 58, 96, 90),
                layout.outputWidth, layout.canvasHeight, new Color(96, 28, 72, 20)
        ));
        g2d.fillRect(0, layout.plotOffsetY, layout.outputWidth, layout.outputHeight);

        drawCreativeTransientDust(g2d, allTransients, layout);

        for (TrackLinker.Track track : movingTargets) {
            drawGlowingTrack(g2d, track, layout, new Color(77, 166, 255), 2.6f, 8.5f);
        }
        for (TrackLinker.Track track : streakTracks) {
            drawGlowingTrack(g2d, track, layout, new Color(255, 204, 102), 2.8f, 9.5f);
        }
        for (TrackLinker.Track track : suspectedStreakTracks) {
            drawGlowingTrack(g2d, track, layout, new Color(255, 128, 128), 2.4f, 8.2f);
        }
        for (TrackLinker.AnomalyDetection anomaly : anomalies) {
            if (anomaly != null && anomaly.object != null) {
                drawCreativePulse(g2d, anomaly.object, layout, new Color(255, 102, 204));
            }
        }
        for (TrackLinker.Track track : singleStreaks) {
            if (track.points != null && !track.points.isEmpty()) {
                drawCreativeMeasuredStreak(g2d, track.points.get(0), layout, new Color(255, 153, 51), 1.10);
            }
        }
        if (slowMoverCandidates != null) {
            for (SourceExtractor.DetectedObject candidate : slowMoverCandidates) {
                drawCreativeDiamond(g2d, candidate, layout, new Color(186, 122, 255), 16);
            }
        }

        float vignetteRadius = (float) (Math.max(layout.outputWidth, layout.outputHeight) * 0.82);
        RadialGradientPaint vignette = new RadialGradientPaint(
                new Point(layout.outputWidth / 2, layout.plotOffsetY + (layout.outputHeight / 2)),
                vignetteRadius,
                new float[]{0.0f, 0.70f, 1.0f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 55), new Color(0, 0, 0, 180)}
        );
        g2d.setPaint(vignette);
        g2d.fillRect(0, layout.plotOffsetY, layout.outputWidth, layout.outputHeight);

        g2d.setColor(new Color(76, 96, 132, 70));
        g2d.setStroke(new BasicStroke(1.2f));
        g2d.drawLine(0, layout.plotOffsetY, layout.outputWidth, layout.plotOffsetY);

        int panelPadding = Math.max(18, Math.min(34, layout.outputWidth / 35));
        int interPanelGap = Math.max(14, panelPadding / 2);
        int leftPanelWidth = Math.max(340, (int) Math.round(layout.outputWidth * 0.50));
        int maxLegendWidth = layout.outputWidth - leftPanelWidth - (panelPadding * 2) - interPanelGap;
        int legendWidth = Math.max(250, Math.min((int) Math.round(layout.outputWidth * 0.28), maxLegendWidth));
        if (legendWidth > maxLegendWidth) {
            legendWidth = maxLegendWidth;
            leftPanelWidth = layout.outputWidth - legendWidth - (panelPadding * 2) - interPanelGap;
        }
        int leftPanelHeight = layout.headerHeight - (panelPadding * 2);
        g2d.setColor(new Color(10, 10, 16, 190));
        g2d.fillRoundRect(panelPadding, panelPadding, leftPanelWidth, leftPanelHeight, 24, 24);
        g2d.setColor(new Color(150, 120, 255, 140));
        g2d.setStroke(new BasicStroke(1.6f));
        g2d.drawRoundRect(panelPadding, panelPadding, leftPanelWidth, leftPanelHeight, 24, 24);

        Font titleFont = new Font("Segoe UI", Font.BOLD, Math.max(26, Math.min(38, layout.outputWidth / 34)));
        Font subtitleFont = new Font("Segoe UI", Font.PLAIN, Math.max(13, Math.min(19, layout.outputWidth / 75)));
        Font detailFont = new Font("Consolas", Font.PLAIN, Math.max(12, Math.min(17, layout.outputWidth / 90)));

        int textX = panelPadding + 24;
        int y = panelPadding + 42;
        g2d.setFont(titleFont);
        g2d.setColor(Color.WHITE);
        g2d.drawString("Skyprint of the Session", textX, y);

        y += 28;
        g2d.setFont(subtitleFont);
        g2d.setColor(new Color(210, 190, 255));
        g2d.drawString("Creative tribute by Codex", textX, y);

        int rawTransientCount = countTotalTransientDetections(allTransients);
        int confirmedTrackCount = movingTargets.size() + streakTracks.size();
        int suspectedStreakCount = suspectedStreakTracks == null ? 0 : suspectedStreakTracks.size();
        int deepStackHintCount = slowMoverCandidates == null ? 0 : slowMoverCandidates.size();
        double longestPath = computeLongestTrackPathPx(streakTracks, movingTargets);
        String dominantMotion = computeDominantMotionLabel(movingTargets, streakTracks);

        y += 32;
        g2d.setFont(detailFont);
        g2d.setColor(new Color(220, 220, 220));
        String framesLine;
        if (pipelineTelemetry != null) {
            framesLine = "Frames kept/rejected: " + pipelineTelemetry.totalFramesKept + " / " + pipelineTelemetry.totalFramesRejected;
        } else {
            framesLine = "Frames kept/rejected: n/a";
        }
        g2d.drawString(framesLine, textX, y);
        y += 24;
        g2d.drawString("Raw transients: " + rawTransientCount + " | Confirmed tracks: " + confirmedTrackCount, textX, y);
        y += 24;
        g2d.drawString("Suspected streak tracks: " + suspectedStreakCount + " | Anomalies: " + anomalies.size(), textX, y);
        y += 24;
        g2d.drawString("Single streaks: " + singleStreaks.size() + " | Deep-stack hints: " + deepStackHintCount, textX, y);
        y += 24;
        g2d.drawString("Dominant confirmed motion: " + dominantMotion + " | Longest confirmed path: " + String.format(Locale.US, "%.1f px", longestPath), textX, y);

        int legendHeight = Math.min(layout.headerHeight - (panelPadding * 2), 204);
        int legendX = panelPadding + leftPanelWidth + interPanelGap;
        int legendY = panelPadding;
        g2d.setColor(new Color(10, 10, 16, 175));
        g2d.fillRoundRect(legendX, legendY, legendWidth, legendHeight, 22, 22);
        g2d.setColor(new Color(77, 166, 255, 120));
        g2d.drawRoundRect(legendX, legendY, legendWidth, legendHeight, 22, 22);

        g2d.setFont(new Font("Segoe UI", Font.BOLD, Math.max(14, Math.min(18, layout.outputWidth / 85))));
        g2d.setColor(Color.WHITE);
        g2d.drawString("What the colors and symbols mean", legendX + 18, legendY + 28);

        int legendRowY = legendY + 52;
        Font legendFont = new Font("Segoe UI", Font.PLAIN, Math.max(12, Math.min(15, layout.outputWidth / 95)));
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(66, 210, 255), "Moving object track (line + nodes)", legendFont, CreativeLegendGlyph.TRACK);
        legendRowY += 24;
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(255, 204, 102), "Confirmed streak track / single streak", legendFont, CreativeLegendGlyph.STREAK);
        legendRowY += 24;
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(255, 128, 128), "Suspected streak grouping", legendFont, CreativeLegendGlyph.TRACK);
        legendRowY += 24;
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(255, 102, 204), "Anomaly pulse (circle + crosshair)", legendFont, CreativeLegendGlyph.PULSE);
        legendRowY += 24;
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(186, 122, 255), "Deep-stack hint (diamond)", legendFont, CreativeLegendGlyph.DIAMOND);
        legendRowY += 24;
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(150, 220, 255), "Transient dust time map (cyan -> magenta)", legendFont, CreativeLegendGlyph.DUST);

        g2d.dispose();
        return tribute;
    }

    private static void drawCreativeTransientDust(Graphics2D g2d, List<List<SourceExtractor.DetectedObject>> allTransients, CreativeTributeLayout layout) {
        if (allTransients == null || allTransients.isEmpty()) {
            return;
        }

        int totalTransientCount = countTotalTransientDetections(allTransients);
        if (totalTransientCount == 0) {
            return;
        }

        int stride = Math.max(1, totalTransientCount / 9000);
        int sampledIndex = 0;
        int totalFrames = allTransients.size();

        for (int frameIndex = 0; frameIndex < totalFrames; frameIndex++) {
            List<SourceExtractor.DetectedObject> frameTransients = allTransients.get(frameIndex);
            if (frameTransients == null || frameTransients.isEmpty()) {
                continue;
            }

            float ratio = totalFrames > 1 ? (float) frameIndex / (float) (totalFrames - 1) : 0f;
            Color timeColor = Color.getHSBColor(0.62f - (0.55f * ratio), 0.85f, 1.0f);
            Color haloColor = new Color(timeColor.getRed(), timeColor.getGreen(), timeColor.getBlue(), 42);
            Color coreColor = new Color(timeColor.getRed(), timeColor.getGreen(), timeColor.getBlue(), 78);

            for (SourceExtractor.DetectedObject transientPoint : frameTransients) {
                if ((sampledIndex++ % stride) != 0) {
                    continue;
                }

                if (!isInsideCreativeLayout(transientPoint.x, transientPoint.y, layout)) {
                    continue;
                }

                int x = creativeX(transientPoint.x, layout);
                int y = creativeY(transientPoint.y, layout);
                g2d.setColor(haloColor);
                g2d.fillOval(x - 2, y - 2, 6, 6);
                g2d.setColor(coreColor);
                g2d.fillOval(x - 1, y - 1, 3, 3);
            }
        }
    }

    private static void drawGlowingTrack(Graphics2D g2d, TrackLinker.Track track, CreativeTributeLayout layout, Color color, float coreWidth, float glowWidth) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return;
        }

        float strokeScale = (float) Math.max(0.9, Math.min(1.8, layout.scale));
        Color glowColor = new Color(color.getRed(), color.getGreen(), color.getBlue(), 60);
        g2d.setStroke(new BasicStroke(glowWidth * strokeScale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.setColor(glowColor);
        for (int i = 0; i < track.points.size() - 1; i++) {
            SourceExtractor.DetectedObject p1 = track.points.get(i);
            SourceExtractor.DetectedObject p2 = track.points.get(i + 1);
            g2d.drawLine(creativeX(p1.x, layout), creativeY(p1.y, layout), creativeX(p2.x, layout), creativeY(p2.y, layout));
        }

        g2d.setStroke(new BasicStroke(coreWidth * strokeScale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.setColor(color);
        for (int i = 0; i < track.points.size() - 1; i++) {
            SourceExtractor.DetectedObject p1 = track.points.get(i);
            SourceExtractor.DetectedObject p2 = track.points.get(i + 1);
            g2d.drawLine(creativeX(p1.x, layout), creativeY(p1.y, layout), creativeX(p2.x, layout), creativeY(p2.y, layout));
        }

        int markerRadius = Math.max(4, (int) Math.round(5 * strokeScale));
        for (SourceExtractor.DetectedObject point : track.points) {
            int x = creativeX(point.x, layout);
            int y = creativeY(point.y, layout);
            g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 80));
            g2d.fillOval(x - markerRadius, y - markerRadius, markerRadius * 2, markerRadius * 2);
            g2d.setColor(Color.WHITE);
            g2d.fillOval(x - 2, y - 2, 4, 4);
        }
    }

    private static void drawCreativePulse(Graphics2D g2d, SourceExtractor.DetectedObject detection, CreativeTributeLayout layout, Color color) {
        int cx = creativeX(detection.x, layout);
        int cy = creativeY(detection.y, layout);
        int outerRadius = Math.max(22, (int) Math.round(30 * Math.max(0.9, Math.min(1.7, layout.scale))));
        int innerRadius = Math.max(14, (int) Math.round(18 * Math.max(0.9, Math.min(1.7, layout.scale))));

        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 42));
        g2d.fillOval(cx - outerRadius, cy - outerRadius, outerRadius * 2, outerRadius * 2);
        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 145));
        g2d.setStroke(new BasicStroke(2.0f));
        g2d.drawOval(cx - innerRadius, cy - innerRadius, innerRadius * 2, innerRadius * 2);
        g2d.drawLine(cx - outerRadius + 2, cy, cx + outerRadius - 2, cy);
        g2d.drawLine(cx, cy - outerRadius + 2, cx, cy + outerRadius - 2);
        g2d.setColor(Color.WHITE);
        g2d.fillOval(cx - 3, cy - 3, 6, 6);
    }

    private static void drawCreativeMeasuredStreak(Graphics2D g2d, SourceExtractor.DetectedObject detection, CreativeTributeLayout layout, Color color, double lengthScale) {
        if (detection == null || !isInsideCreativeLayout(detection.x, detection.y, layout)) {
            return;
        }

        int cx = creativeX(detection.x, layout);
        int cy = creativeY(detection.y, layout);
        double elongation = Math.max(1.0, detection.elongation);
        double semiMajorAxis = detection.pixelArea > 0
                ? Math.sqrt((detection.pixelArea * elongation) / Math.PI)
                : 9.0;
        double measuredLength = Math.max(18.0, semiMajorAxis * 2.0);
        double length = measuredLength * lengthScale * Math.max(0.95, Math.min(1.25, layout.scale));
        double maxLength = Math.max(42.0, Math.min(layout.outputWidth, layout.outputHeight) * 0.10);
        length = Math.min(length, maxLength);
        double angleRad = Double.isFinite(detection.angle) ? detection.angle : 0.0;
        int dx = (int) Math.round(Math.cos(angleRad) * length * 0.5);
        int dy = (int) Math.round(Math.sin(angleRad) * length * 0.5);

        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 55));
        g2d.setStroke(new BasicStroke(9.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.drawLine(cx - dx, cy - dy, cx + dx, cy + dy);

        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.drawLine(cx - dx, cy - dy, cx + dx, cy + dy);
        g2d.setColor(Color.WHITE);
        g2d.fillOval(cx - 2, cy - 2, 4, 4);
    }

    private static void drawCreativeDiamond(Graphics2D g2d, SourceExtractor.DetectedObject detection, CreativeTributeLayout layout, Color color, int radius) {
        int cx = creativeX(detection.x, layout);
        int cy = creativeY(detection.y, layout);
        int scaledRadius = Math.max(12, (int) Math.round(radius * Math.max(0.9, Math.min(1.5, layout.scale))));

        Polygon diamond = new Polygon(
                new int[]{cx, cx + scaledRadius, cx, cx - scaledRadius},
                new int[]{cy - scaledRadius, cy, cy + scaledRadius, cy},
                4
        );

        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 34));
        g2d.fillPolygon(diamond);
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(2.2f));
        g2d.drawPolygon(diamond);
        g2d.setColor(Color.WHITE);
        g2d.fillOval(cx - 2, cy - 2, 4, 4);
    }

    private static CreativeTributeLayout createCreativeTributeLayout(short[][] backgroundData,
                                                                     List<List<SourceExtractor.DetectedObject>> allTransients,
                                                                     List<TrackLinker.AnomalyDetection> anomalies,
                                                                     List<TrackLinker.Track> singleStreaks,
                                                                     List<TrackLinker.Track> streakTracks,
                                                                     List<TrackLinker.Track> suspectedStreakTracks,
                                                                     List<TrackLinker.Track> movingTargets,
                                                                     List<SourceExtractor.DetectedObject> slowMoverCandidates) {
        int imageWidth = backgroundData[0].length;
        int imageHeight = backgroundData.length;

        double[] bounds = new double[]{Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        includeCreativeAnomalyBounds(bounds, anomalies);
        includeCreativeTrackBounds(bounds, singleStreaks);
        includeCreativeTrackBounds(bounds, streakTracks);
        includeCreativeTrackBounds(bounds, suspectedStreakTracks);
        includeCreativeTrackBounds(bounds, movingTargets);
        includeCreativeDetectionBounds(bounds, slowMoverCandidates);

        if (bounds[0] == Double.MAX_VALUE) {
            includeCreativeTransientBounds(bounds, allTransients);
        }

        int cropX = 0;
        int cropY = 0;
        int cropWidth = imageWidth;
        int cropHeight = imageHeight;

        if (bounds[0] != Double.MAX_VALUE) {
            int minX = Math.max(0, (int) Math.floor(bounds[0]));
            int minY = Math.max(0, (int) Math.floor(bounds[1]));
            int maxX = Math.min(imageWidth - 1, (int) Math.ceil(bounds[2]));
            int maxY = Math.min(imageHeight - 1, (int) Math.ceil(bounds[3]));
            int spanWidth = Math.max(1, maxX - minX + 1);
            int spanHeight = Math.max(1, maxY - minY + 1);
            int padding = Math.max(140, (int) Math.round(Math.max(spanWidth, spanHeight) * 0.28));

            cropX = Math.max(0, minX - padding);
            cropY = Math.max(0, minY - padding);
            int cropMaxX = Math.min(imageWidth - 1, maxX + padding);
            int cropMaxY = Math.min(imageHeight - 1, maxY + padding);
            cropWidth = Math.max(1, cropMaxX - cropX + 1);
            cropHeight = Math.max(1, cropMaxY - cropY + 1);
        }

        int maxDim = Math.max(cropWidth, cropHeight);
        double scale = maxDim > 1600 ? (1600.0 / maxDim) : 1.0;
        if (maxDim < 1000) {
            scale = Math.min(2.1, 1000.0 / maxDim);
        }

        int outputWidth = Math.max(720, (int) Math.round(cropWidth * scale));
        int outputHeight = Math.max(480, (int) Math.round(cropHeight * scale));
        scale = Math.min((double) outputWidth / cropWidth, (double) outputHeight / cropHeight);
        outputWidth = Math.max(1, (int) Math.round(cropWidth * scale));
        outputHeight = Math.max(1, (int) Math.round(cropHeight * scale));
        int headerHeight = Math.max(270, Math.min(320, outputWidth / 3));
        int canvasHeight = outputHeight + headerHeight;
        int plotOffsetY = headerHeight;

        return new CreativeTributeLayout(cropX, cropY, cropWidth, cropHeight, outputWidth, outputHeight, headerHeight, canvasHeight, plotOffsetY, scale);
    }

    private static void includeCreativeTrackBounds(double[] bounds, List<TrackLinker.Track> tracks) {
        if (tracks == null) {
            return;
        }

        for (TrackLinker.Track track : tracks) {
            if (track == null || track.points == null) {
                continue;
            }
            for (SourceExtractor.DetectedObject point : track.points) {
                includeCreativePoint(bounds, point.x, point.y);
            }
        }
    }

    private static void includeCreativeAnomalyBounds(double[] bounds, List<TrackLinker.AnomalyDetection> anomalies) {
        if (anomalies == null) {
            return;
        }

        for (TrackLinker.AnomalyDetection anomaly : anomalies) {
            if (anomaly == null || anomaly.object == null) {
                continue;
            }
            includeCreativePoint(bounds, anomaly.object.x, anomaly.object.y);
        }
    }

    private static void includeCreativeDetectionBounds(double[] bounds, List<SourceExtractor.DetectedObject> detections) {
        if (detections == null) {
            return;
        }

        for (SourceExtractor.DetectedObject detection : detections) {
            includeCreativePoint(bounds, detection.x, detection.y);
        }
    }

    private static void includeCreativeTransientBounds(double[] bounds, List<List<SourceExtractor.DetectedObject>> allTransients) {
        if (allTransients == null) {
            return;
        }

        for (List<SourceExtractor.DetectedObject> frameTransients : allTransients) {
            if (frameTransients == null) {
                continue;
            }
            for (SourceExtractor.DetectedObject transientPoint : frameTransients) {
                includeCreativePoint(bounds, transientPoint.x, transientPoint.y);
            }
        }
    }

    private static void includeCreativePoint(double[] bounds, double x, double y) {
        if (x < bounds[0]) {
            bounds[0] = x;
        }
        if (y < bounds[1]) {
            bounds[1] = y;
        }
        if (x > bounds[2]) {
            bounds[2] = x;
        }
        if (y > bounds[3]) {
            bounds[3] = y;
        }
    }

    private static boolean isInsideCreativeLayout(double x, double y, CreativeTributeLayout layout) {
        return x >= layout.cropX
                && x < layout.cropX + layout.cropWidth
                && y >= layout.cropY
                && y < layout.cropY + layout.cropHeight;
    }

    private static int creativeX(double x, CreativeTributeLayout layout) {
        return (int) Math.round((x - layout.cropX) * layout.scale);
    }

    private static int creativeY(double y, CreativeTributeLayout layout) {
        return layout.plotOffsetY + (int) Math.round((y - layout.cropY) * layout.scale);
    }

    private static void drawCreativeLegendRow(Graphics2D g2d,
                                              int x,
                                              int y,
                                              Color color,
                                              String label,
                                              Font font,
                                              CreativeLegendGlyph glyph) {
        switch (glyph) {
            case TRACK -> drawCreativeLegendTrackGlyph(g2d, x, y, color);
            case STREAK -> drawCreativeLegendStreakGlyph(g2d, x, y, color);
            case PULSE -> drawCreativeLegendPulseGlyph(g2d, x, y, color);
            case DIAMOND -> drawCreativeLegendDiamondGlyph(g2d, x, y, color);
            case DUST -> drawCreativeLegendDustGlyph(g2d, x, y);
        }
        g2d.setFont(font);
        g2d.setColor(new Color(225, 225, 225));
        g2d.drawString(label, x + 34, y + 2);
    }

    private static void drawCreativeLegendTrackGlyph(Graphics2D g2d, int x, int y, Color color) {
        Stroke previousStroke = g2d.getStroke();
        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 70));
        g2d.setStroke(new BasicStroke(6.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.drawLine(x, y, x + 18, y - 3);
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.drawLine(x, y, x + 18, y - 3);
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x - 2, y - 2, 4, 4);
        g2d.fillOval(x + 16, y - 5, 4, 4);
        g2d.setStroke(previousStroke);
    }

    private static void drawCreativeLegendStreakGlyph(Graphics2D g2d, int x, int y, Color color) {
        Stroke previousStroke = g2d.getStroke();
        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
        g2d.setStroke(new BasicStroke(8.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.drawLine(x, y + 4, x + 18, y - 4);
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.drawLine(x, y + 4, x + 18, y - 4);
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 8, y - 2, 4, 4);
        g2d.setStroke(previousStroke);
    }

    private static void drawCreativeLegendPulseGlyph(Graphics2D g2d, int x, int y, Color color) {
        Stroke previousStroke = g2d.getStroke();
        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 38));
        g2d.fillOval(x - 2, y - 10, 20, 20);
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(1.8f));
        g2d.drawOval(x + 1, y - 7, 14, 14);
        g2d.drawLine(x - 1, y, x + 17, y);
        g2d.drawLine(x + 8, y - 9, x + 8, y + 9);
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 6, y - 2, 4, 4);
        g2d.setStroke(previousStroke);
    }

    private static void drawCreativeLegendDiamondGlyph(Graphics2D g2d, int x, int y, Color color) {
        Polygon diamond = new Polygon(
                new int[]{x + 8, x + 16, x + 8, x},
                new int[]{y - 8, y, y + 8, y},
                4
        );
        Stroke previousStroke = g2d.getStroke();
        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 34));
        g2d.fillPolygon(diamond);
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(2.0f));
        g2d.drawPolygon(diamond);
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 6, y - 2, 4, 4);
        g2d.setStroke(previousStroke);
    }

    private static void drawCreativeLegendDustGlyph(Graphics2D g2d, int x, int y) {
        Color[] dustColors = new Color[]{
                new Color(150, 220, 255),
                new Color(100, 255, 190),
                new Color(255, 214, 112),
                new Color(255, 120, 210)
        };
        int[] offsets = new int[]{0, 6, 12, 18};
        for (int i = 0; i < dustColors.length; i++) {
            Color color = dustColors[i];
            int cx = x + offsets[i];
            g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 42));
            g2d.fillOval(cx - 3, y - 3, 8, 8);
            g2d.setColor(color);
            g2d.fillOval(cx - 1, y - 1, 4, 4);
        }
    }

    private static int countTotalTransientDetections(List<List<SourceExtractor.DetectedObject>> allTransients) {
        if (allTransients == null || allTransients.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (List<SourceExtractor.DetectedObject> frameTransients : allTransients) {
            if (frameTransients != null) {
                total += frameTransients.size();
            }
        }
        return total;
    }

    private static double computeLongestTrackPathPx(List<TrackLinker.Track> streakTracks, List<TrackLinker.Track> movingTargets) {
        double longest = 0.0;

        if (movingTargets != null) {
            for (TrackLinker.Track track : movingTargets) {
                double pathLength = computeTrackPathLength(track);
                if (pathLength > longest) {
                    longest = pathLength;
                }
            }
        }
        if (streakTracks != null) {
            for (TrackLinker.Track track : streakTracks) {
                double pathLength = computeTrackPathLength(track);
                if (pathLength > longest) {
                    longest = pathLength;
                }
            }
        }

        return longest;
    }

    private static double computeTrackPathLength(TrackLinker.Track track) {
        if (track == null || track.points == null || track.points.size() < 2) {
            return 0.0;
        }

        double total = 0.0;
        for (int i = 0; i < track.points.size() - 1; i++) {
            SourceExtractor.DetectedObject p1 = track.points.get(i);
            SourceExtractor.DetectedObject p2 = track.points.get(i + 1);
            total += Math.hypot(p2.x - p1.x, p2.y - p1.y);
        }
        return total;
    }

    private static String computeDominantMotionLabel(List<TrackLinker.Track> movingTargets, List<TrackLinker.Track> streakTracks) {
        double sumDx = 0.0;
        double sumDy = 0.0;
        int contributors = 0;

        if (movingTargets != null) {
            for (TrackLinker.Track track : movingTargets) {
                if (track != null && track.points != null && track.points.size() >= 2) {
                    SourceExtractor.DetectedObject first = track.points.get(0);
                    SourceExtractor.DetectedObject last = track.points.get(track.points.size() - 1);
                    sumDx += last.x - first.x;
                    sumDy += last.y - first.y;
                    contributors++;
                }
            }
        }
        if (streakTracks != null) {
            for (TrackLinker.Track track : streakTracks) {
                if (track != null && track.points != null && track.points.size() >= 2) {
                    SourceExtractor.DetectedObject first = track.points.get(0);
                    SourceExtractor.DetectedObject last = track.points.get(track.points.size() - 1);
                    sumDx += last.x - first.x;
                    sumDy += last.y - first.y;
                    contributors++;
                }
            }
        }

        if (contributors == 0) {
            return "not established";
        }

        String vertical = "";
        String horizontal = "";

        if (sumDy < -5.0) {
            vertical = "north";
        } else if (sumDy > 5.0) {
            vertical = "south";
        }

        if (sumDx > 5.0) {
            horizontal = "east";
        } else if (sumDx < -5.0) {
            horizontal = "west";
        }

        if (!vertical.isEmpty() && !horizontal.isEmpty()) {
            return vertical + "-" + horizontal;
        }
        if (!horizontal.isEmpty()) {
            return horizontal;
        }
        if (!vertical.isEmpty()) {
            return vertical;
        }
        return "mixed / stationary";
    }

    private static void exportDeepStackDetectionCard(java.io.PrintWriter report,
                                                     File exportDir,
                                                     List<short[][]> rawFrames,
                                                     SourceExtractor.DetectedObject detection,
                                                     List<SlowMoverTrackMatch> matchedTracks,
                                                     DetectionReportAstrometry.Context astrometryContext,
                                                     short[][] primaryStackData,
                                                     String primaryStackLabel,
                                                     String primaryStackFileName,
                                                     short[][] masterStackData,
                                                     boolean[][] auxiliaryMask,
                                                     String auxiliaryMaskLabel,
                                                     String auxiliaryMaskFileName,
                                                     Double auxiliaryMaskOverlapFraction,
                                                     SlowMoverCandidateDiagnostics candidateDiagnostics,
                                                     Double residualFootprintThreshold,
                                                     short[][] secondaryStackData,
                                                     String secondaryStackLabel,
                                                     String secondaryStackFileName,
                                                     String diffLabel,
                                                     String diffFileName,
                                                     String gifFileName,
                                                     String shapeFileName,
                                                     String detectionTitle,
                                                     String accentColor) throws IOException {
        int cx = (int) Math.round(detection.x);
        int cy = (int) Math.round(detection.y);

        // Dynamically scale the crop box to ensure we capture the entire elongated footprint plus padding
        double objectRadius = detection.pixelArea > 0 ? Math.sqrt((detection.pixelArea * detection.elongation) / Math.PI) : 0;
        int cropSize = Math.max(150, (int) Math.round(objectRadius * 2) + 100);
        int startX = cx - (cropSize / 2);
        int startY = cy - (cropSize / 2);
        short[][] maskReferenceData = masterStackData != null ? masterStackData : primaryStackData;
        Double resolvedAuxiliaryMaskOverlap = auxiliaryMaskOverlapFraction;
        if (resolvedAuxiliaryMaskOverlap == null && candidateDiagnostics != null) {
            resolvedAuxiliaryMaskOverlap = candidateDiagnostics.medianSupportOverlap;
        }

        short[][] croppedPrimaryData = null;
        if (primaryStackData != null && primaryStackFileName != null) {
            croppedPrimaryData = robustEdgeAwareCrop(primaryStackData, cx, cy, cropSize, cropSize);
            BufferedImage primaryImg = createDisplayImage(croppedPrimaryData);
            saveTrackImageLossless(primaryImg, new File(exportDir, primaryStackFileName));
        }

        String masterFileName = null;
        if (masterStackData != null) {
            short[][] croppedMasterData = robustEdgeAwareCrop(masterStackData, cx, cy, cropSize, cropSize);
            BufferedImage masterImg = createDisplayImage(croppedMasterData);
            masterFileName = primaryStackFileName.replace(".png", "_master_stack.png");
            saveTrackImageLossless(masterImg, new File(exportDir, masterFileName));

            if (croppedPrimaryData != null && diffFileName != null) {
                BufferedImage diffImg = createSlowMoverDifferenceMap(croppedPrimaryData, croppedMasterData);
                saveTrackImageLossless(diffImg, new File(exportDir, diffFileName));
            }
        }

        if (auxiliaryMask != null && masterStackData != null && auxiliaryMaskFileName != null) {
            BufferedImage maskImg = createCroppedMaskOverlay(
                    masterStackData,
                    auxiliaryMask,
                    cx,
                    cy,
                    cropSize,
                    cropSize,
                    detection,
                    new Color(255, 80, 80));
            saveTrackImageLossless(maskImg, new File(exportDir, auxiliaryMaskFileName));
        }
        if (auxiliaryMask != null && maskReferenceData != null) {
            if (resolvedAuxiliaryMaskOverlap == null) {
                resolvedAuxiliaryMaskOverlap = computeMaskOverlapStats(
                        detection,
                        auxiliaryMask,
                        maskReferenceData[0].length,
                        maskReferenceData.length).fraction;
            }
        }

        if (secondaryStackData != null && secondaryStackFileName != null) {
            short[][] croppedSecondaryData = robustEdgeAwareCrop(secondaryStackData, cx, cy, cropSize, cropSize);
            BufferedImage secondaryImg = createDisplayImage(croppedSecondaryData);
            saveTrackImageLossless(secondaryImg, new File(exportDir, secondaryStackFileName));
        }

        BufferedImage shapeImg = createSingleStreakShapeImage(java.util.Collections.singletonList(detection), cropSize, cropSize, startX, startY, false);
        saveTrackImageLossless(shapeImg, new File(exportDir, shapeFileName));

        if (rawFrames != null && !rawFrames.isEmpty() && gifFileName != null) {
            List<Integer> sampledIndices = getRepresentativeSequence(rawFrames.size(), new java.util.HashSet<>(), 10);
            List<BufferedImage> gifFrames = new ArrayList<>();
            for (int idx : sampledIndices) {
                short[][] frameData = robustEdgeAwareCrop(rawFrames.get(idx), cx, cy, cropSize, cropSize);
                gifFrames.add(createDisplayImage(frameData));
            }
            GifSequenceWriter.saveAnimatedGif(gifFrames, new File(exportDir, gifFileName), gifBlinkSpeedMs);
        }

        report.println("<div class='detection-card' style='border-left-color: " + accentColor + "; padding: 15px; margin-bottom: 0;'>");
        report.println("<div class='detection-title' style='color: " + accentColor + "; font-size: 1.1em; margin-bottom: 10px;'>" + detectionTitle + "</div>");
        if (matchedTracks != null && !matchedTracks.isEmpty()) {
            report.println("<div class='astro-note' style='margin-top: 0; margin-bottom: 12px; border-left: 3px solid #66d9a3;'>"
                    + "<strong>Also identified as reported track"
                    + (matchedTracks.size() == 1 ? "" : "s")
                    + ":</strong> "
                    + buildSlowMoverTrackMatchSummaryHtml(matchedTracks)
                    + "</div>");
        }
        report.println("<div class='image-container' style='margin-bottom: 10px;'>");
        if (masterFileName != null) {
            report.println("<div><a href='" + masterFileName + "' target='_blank'><img src='" + masterFileName + "' style='max-width: 150px;' alt='Median Stack Crop' /></a><br/><center><small>Median Stack</small></center></div>");
        }
        if (auxiliaryMaskFileName != null && auxiliaryMask != null && masterStackData != null) {
            report.println("<div><a href='" + auxiliaryMaskFileName + "' target='_blank'><img src='" + auxiliaryMaskFileName + "' style='max-width: 150px;' alt='" + auxiliaryMaskLabel + " Crop' /></a><br/><center><small>" + auxiliaryMaskLabel + "</small></center></div>");
        }
        if (diffFileName != null && croppedPrimaryData != null && masterStackData != null) {
            report.println("<div><a href='" + diffFileName + "' target='_blank'><img src='" + diffFileName + "' style='max-width: 150px;' alt='" + diffLabel + " Crop' /></a><br/><center><small>" + diffLabel + "</small></center></div>");
        }
        if (secondaryStackFileName != null && secondaryStackData != null) {
            report.println("<div><a href='" + secondaryStackFileName + "' target='_blank'><img src='" + secondaryStackFileName + "' style='max-width: 150px;' alt='" + secondaryStackLabel + " Crop' /></a><br/><center><small>" + secondaryStackLabel + "</small></center></div>");
        }
        if (primaryStackFileName != null && primaryStackData != null) {
            report.println("<div><a href='" + primaryStackFileName + "' target='_blank'><img src='" + primaryStackFileName + "' style='max-width: 150px;' alt='" + primaryStackLabel + " Crop' /></a><br/><center><small>" + primaryStackLabel + "</small></center></div>");
        }
        if (gifFileName != null && rawFrames != null && !rawFrames.isEmpty()) {
            report.println("<div><a href='" + gifFileName + "' target='_blank'><img src='" + gifFileName + "' style='max-width: 150px;' alt='Sampled Time-Lapse' /></a><br/><center><small>Animation (Sampled)</small></center></div>");
        }
        report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' style='max-width: 150px;' alt='Shape Footprint' /></a><br/><center><small>Shape</small></center></div>");
        report.println("</div>");
        if ((auxiliaryMaskFileName != null && auxiliaryMask != null && masterStackData != null)
                || (diffFileName != null && croppedPrimaryData != null && masterStackData != null)) {
            report.println("<div class='astro-note' style='margin-top: 0; margin-bottom: 10px;'><strong>"
                    + escapeHtml(auxiliaryMaskLabel != null ? auxiliaryMaskLabel : "Median Mask")
                    + ":</strong> the ordinary median-stack object mask around this candidate. It shows what the static baseline already explains, and <strong>Mask Overlap</strong> measures how much of the candidate falls on that baseline.<br><strong>"
                    + escapeHtml(diffLabel != null ? diffLabel : "Diff")
                    + ":</strong> a positive-only <code>"
                    + escapeHtml(primaryStackLabel != null ? primaryStackLabel : "Primary Stack")
                    + " - Median Stack</code> map. Red marks pixels that are brighter in the slow-mover stack than in the ordinary median stack.</div>");
        }
        StringBuilder deepStackStats = new StringBuilder();
        deepStackStats.append("<div style='font-family: monospace; font-size: 12px; color: #aaa;'>")
                .append(escapeHtml(DetectionReportAstrometry.formatPixelCoordinateWithSky(astrometryContext, detection.x, detection.y)))
                .append("<br>Elongation: <span style='color:#fff;'>")
                .append(String.format(Locale.US, "%.2f", detection.elongation))
                .append("</span><br>Pixels: <span style='color:#fff;'>")
                .append((int) detection.pixelArea)
                .append("</span>");
        if (resolvedAuxiliaryMaskOverlap != null) {
            deepStackStats.append("<br>Mask Overlap: <span style='color:#fff;'>")
                    .append(formatPercent(resolvedAuxiliaryMaskOverlap))
                    .append("</span>");
        }
        if (candidateDiagnostics != null) {
            deepStackStats.append("<br>Residual Footprint Flux Fraction: <span style='color:#fff;'>")
                    .append(formatPercent(candidateDiagnostics.residualFootprintFluxFraction))
                    .append("</span>");
            if (residualFootprintThreshold != null) {
                deepStackStats.append(" <span style='color:#777;'>(threshold ")
                        .append(formatPercent(residualFootprintThreshold))
                        .append(")</span>");
            }
            deepStackStats.append("<br>Residual Footprint Flux: <span style='color:#fff;'>")
                    .append(String.format(Locale.US, "%.1f", candidateDiagnostics.residualFootprintFlux))
                    .append("</span>");
            deepStackStats.append("<br>Slow-Mover Footprint Flux: <span style='color:#fff;'>")
                    .append(String.format(Locale.US, "%.1f", candidateDiagnostics.slowMoverFootprintFlux))
                    .append("</span>");
            deepStackStats.append("<br>Median Footprint Flux: <span style='color:#fff;'>")
                    .append(String.format(Locale.US, "%.1f", candidateDiagnostics.medianFootprintFlux))
                    .append("</span>");
            deepStackStats.append("<br>Footprint Pixels: <span style='color:#fff;'>")
                    .append(candidateDiagnostics.footprintPixelCount)
                    .append("</span>");
            deepStackStats.append("<br>Residual Footprint Filter: <span style='color:#fff;'>")
                    .append(candidateDiagnostics.residualFootprintFilteringEnabled ? "enabled" : "disabled")
                    .append("</span>");
        }
        deepStackStats.append("</div>");
        report.println(deepStackStats);
        report.print(DetectionReportAstrometry.buildDeepStackIdentificationHtml(astrometryContext, detection));
        report.println("</div>");
    }

    private static TrackLinker.Track buildResidualTrack(List<SourceExtractor.DetectedObject> points) {
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

    private static void exportMicroDriftCandidateCard(java.io.PrintWriter report,
                                                      File exportDir,
                                                      List<short[][]> rawFrames,
                                                      FitsFileInformation[] fitsFiles,
                                                      DetectionReportAstrometry.Context astrometryContext,
                                                      short[][] referenceBackground,
                                                      ResidualTransientAnalysis.LocalRescueCandidate candidate,
                                                      int counter) throws IOException {
        TrackLinker.Track track = buildResidualTrack(candidate.points);
        CropBounds cropBounds = new CropBounds(track, Math.max(140, trackCropPadding / 2));

        String prefix = "micro_drift_" + counter;
        String backgroundFileName = prefix + "_background.png";
        String trailFileName = prefix + "_trail.png";
        String shapeFileName = prefix + "_shape.png";
        String contextGifFileName = prefix + "_context.gif";

        short[][] backgroundSource = referenceBackground;
        if (backgroundSource == null && rawFrames != null && !rawFrames.isEmpty()) {
            int fallbackIndex = Math.max(0, Math.min(rawFrames.size() - 1, track.points.get(0).sourceFrameIndex));
            backgroundSource = rawFrames.get(fallbackIndex);
        }

        if (backgroundSource != null) {
            short[][] croppedBackground = robustEdgeAwareCrop(
                    backgroundSource,
                    cropBounds.fixedCenterX,
                    cropBounds.fixedCenterY,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight);
            saveTrackImageLossless(createDisplayImage(croppedBackground), new File(exportDir, backgroundFileName));

            BufferedImage trailImage = createMicroDriftTrailImage(
                    track,
                    backgroundSource,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight,
                    cropBounds.startX,
                    cropBounds.startY);
            saveTrackImageLossless(trailImage, new File(exportDir, trailFileName));
        }

        BufferedImage shapeImage = createTrackShapeImage(
                track,
                cropBounds.trackBoxWidth,
                cropBounds.trackBoxHeight,
                cropBounds.startX,
                cropBounds.startY);
        saveTrackImageLossless(shapeImage, new File(exportDir, shapeFileName));

        if (rawFrames != null && !rawFrames.isEmpty()) {
            java.util.Set<Integer> mandatoryFrames = collectResidualFrameIndices(track);
            java.util.Map<Integer, SourceExtractor.DetectedObject> pointByFrame = new java.util.HashMap<>();
            for (SourceExtractor.DetectedObject point : track.points) {
                pointByFrame.put(point.sourceFrameIndex, point);
            }

            List<Integer> sampledIndices = getRepresentativeSequence(rawFrames.size(), mandatoryFrames, 12);
            List<BufferedImage> contextFrames = new ArrayList<>();
            for (int frameIndex : sampledIndices) {
                short[][] croppedData = robustEdgeAwareCrop(
                        rawFrames.get(frameIndex),
                        cropBounds.fixedCenterX,
                        cropBounds.fixedCenterY,
                        cropBounds.trackBoxWidth,
                        cropBounds.trackBoxHeight);
                BufferedImage frameImage = createDisplayImage(croppedData);
                Graphics2D g2d = frameImage.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                SourceExtractor.DetectedObject detectedPoint = pointByFrame.get(frameIndex);
                if (detectedPoint != null) {
                    int localX = (int) Math.round(detectedPoint.x - cropBounds.startX);
                    int localY = (int) Math.round(detectedPoint.y - cropBounds.startY);
                    g2d.setColor(new Color(64, 224, 208));
                    g2d.setStroke(new BasicStroke(targetCircleStrokeWidth));
                    g2d.drawOval(localX - targetCircleRadius, localY - targetCircleRadius, targetCircleRadius * 2, targetCircleRadius * 2);
                    g2d.setFont(new Font("Segoe UI", Font.BOLD, 13));
                    g2d.setColor(Color.WHITE);
                    int pointOrder = track.points.indexOf(detectedPoint) + 1;
                    g2d.drawString("P" + pointOrder, localX + 18, localY - 18);
                }

                g2d.setFont(new Font("Segoe UI", Font.BOLD, 12));
                g2d.setColor(new Color(255, 255, 255, 230));
                g2d.drawString("Frame " + (frameIndex + 1), 10, 18);
                g2d.dispose();
                contextFrames.add(frameImage);
            }
            GifSequenceWriter.saveAnimatedGif(contextFrames, new File(exportDir, contextGifFileName), gifBlinkSpeedMs);
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
        if (rawFrames != null && !rawFrames.isEmpty()) {
            report.println("<div><a href='" + contextGifFileName + "' target='_blank'><img src='" + contextGifFileName + "' alt='Micro-Drift Animation' /></a><br/><center><small>Animation (Star-Centric)</small></center></div>");
        }
        report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Shape Footprint Evolution' /></a><br/><center><small>Shape Evolution</small></center></div>");
        report.println("</div>");

        report.println("<div style='font-family: monospace; font-size: 12px; color: #aaa; margin-bottom: 10px;'>"
                + "Type: <span style='color:#fff;'>" + escapeHtml(formatLocalRescueKind(candidate.kind)) + "</span>"
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
        report.print(buildTrackTimingSummaryHtml(track, astrometryContext));

        report.println("<strong>Detection Coordinates & Frames:</strong><ul class='source-list'>");
        for (int i = 0; i < track.points.size(); i++) {
            SourceExtractor.DetectedObject point = track.points.get(i);
            String pointMetrics = buildMicroDriftMetricsText(point, i + 1);
            report.println(DetectionReportAstrometry.buildSourceCoordinateListEntry(
                    "[" + (i + 1) + "] " + point.sourceFilename,
                    astrometryContext,
                    point.x,
                    point.y,
                    pointMetrics));
        }
        report.println("</ul>");
        report.print(DetectionReportAstrometry.buildTrackSolarSystemIdentificationHtml(astrometryContext, track));
        report.println("</div>");
    }

    private static void exportLocalActivityClusterCard(java.io.PrintWriter report,
                                                       File exportDir,
                                                       List<short[][]> rawFrames,
                                                       DetectionReportAstrometry.Context astrometryContext,
                                                       short[][] referenceBackground,
                                                       ResidualTransientAnalysis.LocalActivityCluster cluster,
                                                       int counter) throws IOException {
        TrackLinker.Track track = buildResidualTrack(cluster.points);
        CropBounds cropBounds = new CropBounds(track, Math.max(180, trackCropPadding / 2));

        String prefix = "local_activity_" + counter;
        String backgroundFileName = prefix + "_background.png";
        String trailFileName = prefix + "_trail.png";
        String shapeFileName = prefix + "_shape.png";
        String contextGifFileName = prefix + "_context.gif";

        short[][] backgroundSource = referenceBackground;
        if (backgroundSource == null && rawFrames != null && !rawFrames.isEmpty()) {
            int fallbackIndex = Math.max(0, Math.min(rawFrames.size() - 1, track.points.get(0).sourceFrameIndex));
            backgroundSource = rawFrames.get(fallbackIndex);
        }

        if (backgroundSource != null) {
            short[][] croppedBackground = robustEdgeAwareCrop(
                    backgroundSource,
                    cropBounds.fixedCenterX,
                    cropBounds.fixedCenterY,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight);
            saveTrackImageLossless(createDisplayImage(croppedBackground), new File(exportDir, backgroundFileName));

            BufferedImage trailImage = createMicroDriftTrailImage(
                    track,
                    backgroundSource,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight,
                    cropBounds.startX,
                    cropBounds.startY);
            saveTrackImageLossless(trailImage, new File(exportDir, trailFileName));
        }

        BufferedImage shapeImage = createTrackShapeImage(
                track,
                cropBounds.trackBoxWidth,
                cropBounds.trackBoxHeight,
                cropBounds.startX,
                cropBounds.startY);
        saveTrackImageLossless(shapeImage, new File(exportDir, shapeFileName));

        if (rawFrames != null && !rawFrames.isEmpty()) {
            Set<Integer> mandatoryFrames = collectResidualFrameIndices(track);
            Map<Integer, SourceExtractor.DetectedObject> pointByFrame = new HashMap<>();
            for (SourceExtractor.DetectedObject point : track.points) {
                pointByFrame.put(point.sourceFrameIndex, point);
            }

            List<Integer> sampledIndices = getRepresentativeSequence(rawFrames.size(), mandatoryFrames, 12);
            List<BufferedImage> contextFrames = new ArrayList<>();
            for (int frameIndex : sampledIndices) {
                short[][] croppedData = robustEdgeAwareCrop(
                        rawFrames.get(frameIndex),
                        cropBounds.fixedCenterX,
                        cropBounds.fixedCenterY,
                        cropBounds.trackBoxWidth,
                        cropBounds.trackBoxHeight);
                BufferedImage frameImage = createDisplayImage(croppedData);
                Graphics2D g2d = frameImage.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                SourceExtractor.DetectedObject detectedPoint = pointByFrame.get(frameIndex);
                if (detectedPoint != null) {
                    int localX = (int) Math.round(detectedPoint.x - cropBounds.startX);
                    int localY = (int) Math.round(detectedPoint.y - cropBounds.startY);
                    g2d.setColor(new Color(255, 170, 80));
                    g2d.setStroke(new BasicStroke(targetCircleStrokeWidth));
                    g2d.drawOval(localX - targetCircleRadius, localY - targetCircleRadius, targetCircleRadius * 2, targetCircleRadius * 2);
                    g2d.setFont(new Font("Segoe UI", Font.BOLD, 13));
                    g2d.setColor(Color.WHITE);
                    int pointOrder = track.points.indexOf(detectedPoint) + 1;
                    g2d.drawString("P" + pointOrder, localX + 18, localY - 18);
                }

                g2d.setFont(new Font("Segoe UI", Font.BOLD, 12));
                g2d.setColor(new Color(255, 255, 255, 230));
                g2d.drawString("Frame " + (frameIndex + 1), 10, 18);
                g2d.dispose();
                contextFrames.add(frameImage);
            }
            GifSequenceWriter.saveAnimatedGif(contextFrames, new File(exportDir, contextGifFileName), gifBlinkSpeedMs);
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
        if (rawFrames != null && !rawFrames.isEmpty()) {
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
                    astrometryContext,
                    point.x,
                    point.y,
                    metricsText));
        }
        report.println("</ul>");
        report.println("</div>");
    }


    private static String formatUtcTimestamp(long timestampMillis) {
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
                .comparingInt(ImageDisplayUtils::getTrackDisplayFrameIndex)
                .thenComparing(ImageDisplayUtils::getTrackDisplayFileName)
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
                .comparingInt(ImageDisplayUtils::getAnomalyDisplayFrameIndex)
                .thenComparingInt(ImageDisplayUtils::getAnomalyDisplayOrder)
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

    private static String buildTrackTimingSummaryHtml(TrackLinker.Track track,
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
                .append(escapeHtml(formatUtcTimestamp(frameTimestamp)))
                .append("</span><br>");
        if (exposureMillis > 0L) {
            html.append("Exposure: <span style='color:#fff;'>")
                    .append(escapeHtml(formatDuration(exposureMillis)))
                    .append("</span><br>");
        }
        html.append("Streak Parts: <span style='color:#fff;'>").append(partCount).append("</span>");
        if (partCount > 1) {
            html.append(" <span style='color:#ff9933;'>(grouped same-frame components)</span>");
        }
        html.append("</div>");
        return html.toString();
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

    private static String compactMetricBox(String value, String label) {
        return "<div class='metric-box compact'>"
                + "<span class='metric-value'>" + escapeHtml(value) + "</span>"
                + "<span class='metric-label'>" + escapeHtml(label) + "</span>"
                + "</div>";
    }

    private static String formatDecimal(double value, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", value);
    }

    private static String formatPixelCoordinateOnly(int pixelX, int pixelY) {
        return String.format(Locale.US, "[%d, %d]", pixelX, pixelY);
    }

    private static String escapeHtml(String value) {
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

    private static String colorToHex(Color color) {
        if (color == null) {
            return "#ffffff";
        }
        return String.format(Locale.US, "#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static void appendGlobalTrajectoryLegendItem(StringBuilder html, String code, String label, Color color, int count) {
        if (count <= 0) {
            return;
        }
        html.append("<div class='legend-pill'>")
                .append("<span class='legend-code' style='background:")
                .append(colorToHex(color))
                .append(";'>")
                .append(escapeHtml(code))
                .append("</span>")
                .append("<span>")
                .append(escapeHtml(label))
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
        appendGlobalTrajectoryLegendItem(html, "T", "Moving object tracks", GLOBAL_MAP_MOVING_TARGET_COLOR, movingTargetCount);
        appendGlobalTrajectoryLegendItem(html, "ST", "Confirmed streak tracks", GLOBAL_MAP_STREAK_TRACK_COLOR, streakTrackCount);
        appendGlobalTrajectoryLegendItem(html, "SST", "Suspected streak tracks", GLOBAL_MAP_SUSPECTED_STREAK_COLOR, suspectedStreakCount);
        appendGlobalTrajectoryLegendItem(html, "LR", "Local rescue candidates", GLOBAL_MAP_LOCAL_RESCUE_COLOR, localRescueCount);
        appendGlobalTrajectoryLegendItem(html, "LC", "Local activity clusters", GLOBAL_MAP_LOCAL_ACTIVITY_COLOR, localActivityCount);
        appendGlobalTrajectoryLegendItem(html, "DS", "Deep-stack anomalies", GLOBAL_MAP_DEEP_STACK_COLOR, deepStackCount);
        appendGlobalTrajectoryLegendItem(html, "A", "Single-frame anomalies", GLOBAL_MAP_ANOMALY_COLOR, anomalyCount);
        appendGlobalTrajectoryLegendItem(html, "S", "Single streaks", GLOBAL_MAP_SINGLE_STREAK_COLOR, singleStreakCount);
        html.append("</div>");
        return html.toString();
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
            TrackLinker.Track residualTrack = buildResidualTrack(candidate.points);
            if (residualTrack.points != null && !residualTrack.points.isEmpty()) {
                localRescueTracks.add(residualTrack);
            }
        }
        List<ReportTrackReference> reportTrackReferences = buildReportTrackReferences(
                streakTracks,
                suspectedStreakTracks,
                movingTargets
        );

        int slowMoverCandidateCount = slowMoverCandidates == null ? 0 : slowMoverCandidates.size();
        int localRescueCandidateCount = localRescueCandidates.size();
        int localActivityClusterCount = localActivityClusters.size();
        int potentialSlowMoverCount = slowMoverCandidateCount + localRescueCandidateCount;
        int singleStreakMetric = singleStreaks.size();
        int confirmedLinkedTrackMetric = movingTargets.size() + streakTracks.size();
        int suspectedStreakTrackMetric = pipelineTelemetry != null ? pipelineTelemetry.totalSuspectedStreakTracksFound : suspectedStreakTracks.size();
        int returnedTrackMetric = pipelineTelemetry != null
                ? pipelineTelemetry.totalTracksFound
                : singleStreakMetric + confirmedLinkedTrackMetric + suspectedStreakTrackMetric;
        int masterStarMetric = pipelineTelemetry != null ? pipelineTelemetry.totalMasterStarsIdentified : (masterStars == null ? 0 : masterStars.size());
        int anomalyMetric = pipelineTelemetry != null ? pipelineTelemetry.totalAnomaliesFound : anomalies.size();
        int peakSigmaAnomalyCount = countAnomaliesOfType(anomalies, TrackLinker.AnomalyType.PEAK_SIGMA);
        int integratedSigmaAnomalyCount = countAnomaliesOfType(anomalies, TrackLinker.AnomalyType.INTEGRATED_SIGMA);
        int otherAnomalyCount = Math.max(0, anomalyMetric - peakSigmaAnomalyCount - integratedSigmaAnomalyCount);

        if (!exportDir.exists()) exportDir.mkdirs();

        // --- EXPORT MASTER DIAGNOSTICS ---
        if (masterStackData != null && masterVetoMask != null) {
            BufferedImage masterImg = createDisplayImage(masterStackData);
            saveTrackImageLossless(masterImg, new File(exportDir, "master_stack.png"));

            BufferedImage masterMaskImg = createMasterMaskOverlay(masterStackData, masterVetoMask);
            saveTrackImageLossless(masterMaskImg, new File(exportDir, "master_mask_overlay.png"));
        }

        File reportFile = new File(exportDir, detectionReportName);

        try (java.io.PrintWriter report = new java.io.PrintWriter(new java.io.FileWriter(reportFile))) {

            // --- HTML HEADER & CSS ---
            report.println("<!DOCTYPE html><html><head><title>Detection Report</title><style>");
            report.println("body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #2b2b2b; color: #cccccc; margin: 0; padding: 30px; } ");
            report.println("h1 { color: #ffffff; border-bottom: 2px solid #4da6ff; padding-bottom: 10px; margin-bottom: 30px; } ");
            report.println("h2 { color: #4da6ff; margin-top: 0; } ");
            report.println(".panel { background-color: #3c3f41; padding: 25px; margin-bottom: 30px; border-radius: 8px; box-shadow: 0 4px 8px rgba(0,0,0,0.3); } ");

            // Layout Utilities
            report.println(".flex-container { display: flex; gap: 20px; flex-wrap: wrap; margin-bottom: 15px; }");

            // Metric Boxes
            report.println(".metric-box { display: inline-block; background-color: #2b2b2b; padding: 15px 20px; border-radius: 5px; margin: 5px 15px 15px 0; border-left: 4px solid #4da6ff; min-width: 140px; } ");
            report.println(".metric-value { font-size: 26px; font-weight: bold; color: #ffffff; display: block; margin-bottom: 5px; } ");
            report.println(".metric-label { font-size: 11px; color: #999999; text-transform: uppercase; letter-spacing: 1px; } ");
            report.println(".metric-box.compact { padding: 7px 9px; margin: 3px 6px 6px 0; min-width: 92px; border-left-width: 3px; border-radius: 4px; }");
            report.println(".metric-box.compact .metric-value { font-size: 15px; margin-bottom: 2px; line-height: 1.1; }");
            report.println(".metric-box.compact .metric-label { font-size: 9px; letter-spacing: 0.4px; line-height: 1.15; }");

            // Tables
            report.println("table { border-collapse: collapse; width: 100%; margin-top: 15px; font-size: 14px; background-color: #2b2b2b; border-radius: 5px; overflow: hidden; } ");
            report.println("th, td { padding: 12px 15px; text-align: left; } ");
            report.println("th { background-color: #222; color: #4da6ff; font-weight: bold; border-bottom: 2px solid #333; position: sticky; top: 0; } ");
            report.println("tr { border-bottom: 1px solid #333; }");
            report.println("tr:nth-child(even) { background-color: #303030; } ");
            report.println("tr:hover { background-color: #3a3a3a; } ");
            report.println(".alert { color: #ff6b6b; font-weight: bold; }");

            // Configuration Grid
            report.println(".config-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 8px; font-size: 12px; }");
            report.println(".config-item { background: #333; padding: 6px 10px; border-radius: 4px; border-left: 3px solid #4da6ff; display: flex; justify-content: space-between; }");
            report.println(".config-item .val { font-family: monospace; color: #4da6ff; font-weight: bold; }");

            // Scrollable section for config
            report.println(".scroll-box { max-height: 300px; overflow-y: auto; border: 1px solid #444; border-radius: 4px; }");
            report.println(".panel.compact-diagnostics-panel { padding: 18px 20px; }");
            report.println(".compact-note { color: #999999; font-size: 12px; margin-top: -8px; margin-bottom: 12px; line-height: 1.4; }");
            report.println(".compact-threshold-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 6px; margin-bottom: 12px; }");
            report.println(".compact-threshold-grid .config-item { padding: 5px 8px; font-size: 11px; }");
            report.println(".scroll-box.compact-table-box { max-height: 360px; overflow: auto; margin-top: 10px; background-color: #2b2b2b; position: relative; }");
            report.println(".scroll-box.compact-table-box table { margin-top: 0; width: max-content; min-width: 100%; font-size: 12px; border-collapse: separate; border-spacing: 0; overflow: visible; }");
            report.println(".scroll-box.compact-table-box th, .scroll-box.compact-table-box td { padding: 7px 9px; white-space: nowrap; line-height: 1.25; }");
            report.println(".scroll-box.compact-table-box thead th { position: sticky; top: 0; z-index: 3; background-color: #222; box-shadow: 0 1px 0 #333; }");

            // Visualizations
            report.println(".detection-card { background: #2d2d2d; padding: 20px; margin-bottom: 30px; border-radius: 8px; border-left: 5px solid #4da6ff; }");
            report.println(".detection-title { font-size: 1.2em; font-weight: bold; color: #ffffff; margin-bottom: 15px; }");
            report.println(".streak-title { color: #ff9933; border-left-color: #ff9933;}");
            report.println("img { border: 1px solid #555; border-radius: 4px; background-color: black; object-fit: contain; max-width: 100%; transition: border-color 0.2s ease-in-out; }");
            report.println("a img:hover { border-color: #4da6ff; cursor: pointer; }");
            report.println(".image-container { display: flex; gap: 20px; align-items: flex-start; margin-bottom: 15px; }");
            report.println(".source-list { list-style-type: none; padding: 0; display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }");
            report.println(".source-list li { background: #3d3d3d; padding: 8px 10px; border-radius: 4px; font-size: 0.9em; font-family: monospace; color: #aaa; border: 1px solid #555; flex: 1 1 360px; max-width: 520px; line-height: 1.4; }");
            report.println(".source-file { color: #dddddd; margin-bottom: 4px; word-break: break-word; }");
            report.println(".source-metrics { color: #999999; margin-top: 4px; }");
            report.println(".coord-highlight { color: #4da6ff; font-weight: bold; }");
            report.println(".coord-stack { display: inline-flex; flex-direction: column; align-items: flex-start; gap: 2px; max-width: 100%; }");
            report.println(".coord-line { display: block; white-space: normal; }");
            report.println(".id-links { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }");
            report.println(".id-link { display: inline-block; background: #254a69; color: #d9ecff; padding: 6px 10px; border-radius: 999px; text-decoration: none; font-size: 12px; border: 1px solid #356c97; }");
            report.println(".id-link:hover { background: #2f6288; color: #ffffff; }");
            report.println(".astro-note { font-size: 12px; color: #aaaaaa; margin-top: 10px; line-height: 1.45; }");
            report.println(".native-size-image { max-width: 100%; width: auto; height: auto; display: block; margin: 0 auto; }");
            report.println(".map-legend { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 15px; }");
            report.println(".legend-pill { display: inline-flex; align-items: center; gap: 8px; background: #262626; border: 1px solid #444; border-radius: 999px; padding: 6px 10px; font-size: 12px; color: #d0d0d0; }");
            report.println(".legend-code { display: inline-flex; align-items: center; justify-content: center; min-width: 36px; padding: 4px 8px; border-radius: 999px; color: #101010; font-weight: bold; letter-spacing: 0.4px; }");
            report.println("</style></head><body>");

            report.println("<h1>SpacePixels Session Report</h1>");

            // =================================================================
            // 1. GLOBAL PIPELINE METRICS
            // =================================================================
            if (pipelineTelemetry != null) {
                report.println("<div class='panel'>");
                report.println("<h2>Pipeline Summary</h2>");
                report.println("<div class='flex-container'>");
                report.println("<div class='metric-box'><span class='metric-value'>" + String.format(Locale.US, "%.2f", pipelineTelemetry.processingTimeMs / 1000.0) + "s</span><span class='metric-label'>Processing Time</span></div>");
                report.println("<div class='metric-box'><span class='metric-value'>" + pipelineTelemetry.totalFramesLoaded + "</span><span class='metric-label'>Total Frames</span></div>");
                report.println("<div class='metric-box'><span class='metric-value'>" + pipelineTelemetry.totalFramesKept + " <span style='color:#555; font-size: 16px;'>/ " + pipelineTelemetry.totalFramesRejected + "</span></span><span class='metric-label'>Kept / Rejected</span></div>");
                report.println("<div class='metric-box'><span class='metric-value'>" + pipelineTelemetry.totalRawObjectsExtracted + "</span><span class='metric-label'>Raw Objects Extracted</span></div>");
                report.println("<div class='metric-box'><span class='metric-value'>" + masterStarMetric + "</span><span class='metric-label'>Master Stars</span></div>");
                report.println("<div class='metric-box'><span class='metric-value'>" + returnedTrackMetric + "</span><span class='metric-label'>Tracks Returned</span></div>");
                report.println("</div>");
                report.println("</div>");

                report.println("<div class='panel'>");
                report.println("<h2>Detection Breakdown</h2>");
                report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>Final report sections summarized up front so confirmed tracks, anomalies, suspected streak tracks, and potential slow movers are visible immediately.</p>");
                report.println("<div class='flex-container'>");
                report.println("<div class='metric-box'><span class='metric-value'>" + singleStreakMetric + "</span><span class='metric-label'>Streaks</span></div>");
                report.println("<div class='metric-box'><span class='metric-value'>" + streakTracks.size() + "</span><span class='metric-label'>Streak Tracks</span></div>");
                report.println("<div class='metric-box'><span class='metric-value'>" + movingTargets.size() + "</span><span class='metric-label'>Moving Object Tracks</span></div>");
                report.println("<div class='metric-box'><span class='metric-value'>" + anomalyMetric + "</span><span class='metric-label'>Single-Frame Anomalies</span></div>");
                report.println("<div class='metric-box'><span class='metric-value'>" + suspectedStreakTrackMetric + "</span><span class='metric-label'>Suspected Streak Tracks</span></div>");
                String potentialSlowMoverMetric = config.enableSlowMoverDetection ? String.valueOf(slowMoverCandidateCount) : "Off";
                report.println("<div class='metric-box'><span class='metric-value'>" + potentialSlowMoverMetric + "</span><span class='metric-label'>Potential Slow Movers</span></div>");
                report.println("<div class='metric-box'><span class='metric-value'>" + localRescueCandidateCount + "</span><span class='metric-label'>Local Rescue Candidates</span></div>");
                report.println("<div class='metric-box'><span class='metric-value'>" + localActivityClusterCount + "</span><span class='metric-label'>Local Activity Clusters</span></div>");
                report.println("</div>");
                report.println("<div class='astro-note'>JTransient returned <strong>" + returnedTrackMetric + "</strong> track-like detections overall: <strong>" + singleStreakMetric + "</strong> single-frame streaks, <strong>" + confirmedLinkedTrackMetric + "</strong> confirmed linked tracks, and <strong>" + suspectedStreakTrackMetric + "</strong> suspected streak groupings.</div>");
                if (!anomalies.isEmpty()) {
                    report.println("<div class='astro-note'>Single-frame anomaly type split shown below matches the order used in the anomaly cards and A# map labels.</div>");
                    report.println("<div class='flex-container'>");
                    report.println("<div class='metric-box compact'><span class='metric-value'>" + peakSigmaAnomalyCount + "</span><span class='metric-label'>Peak-Sigma Anomalies</span></div>");
                    report.println("<div class='metric-box compact'><span class='metric-value'>" + integratedSigmaAnomalyCount + "</span><span class='metric-label'>Integrated-Sigma Anomalies</span></div>");
                    if (otherAnomalyCount > 0) {
                        report.println("<div class='metric-box compact'><span class='metric-value'>" + otherAnomalyCount + "</span><span class='metric-label'>Other / Unknown Anomalies</span></div>");
                    }
                    report.println("</div>");
                }
                if (config.enableSlowMoverDetection) {
                    report.println("<div class='astro-note'>Top-level counts are separated by category: <strong>" + slowMoverCandidateCount + "</strong> deep-stack slow-mover candidates and <strong>" + localRescueCandidateCount + "</strong> local rescue candidates.</div>");
                } else {
                    report.println("<div class='astro-note'>Potential slow mover analysis was disabled for this session.</div>");
                }
                int unclassifiedTransientCount = countTotalTransientDetections(unclassifiedTransients);
                report.println("<div class='astro-note'>Local rescue candidates and local activity clusters are engine-side residual outputs from JTransient. Rescue candidates are mined from <strong>unclassifiedTransients</strong>, then the remaining leftovers are clustered into broader local activity groups for manual review. This run ended with <strong>" + unclassifiedTransientCount + "</strong> unclassified transient detections before residual analysis.</div>");
                report.println("</div>");

                report.println("<div class='panel'>");
                report.println("<h2>Astrometric Context</h2>");
                if (astrometryContext.hasAstrometricSolution()) {
                    report.println("<div class='flex-container'>");
                    report.println("<div class='metric-box'><span class='metric-value'>Available</span><span class='metric-label'>Aligned WCS</span></div>");
                    report.println("<div class='metric-box'><span class='metric-value'>" + escapeHtml(formatUtcTimestamp(astrometryContext.getSessionMidpointTimestampMillis())) + "</span><span class='metric-label'>Session Midpoint (UTC)</span></div>");
                    String observerMetric = astrometryContext.hasSkybotObserverCode()
                            ? escapeHtml(astrometryContext.getSkybotObserverCode())
                            : "500";
                    report.println("<div class='metric-box'><span class='metric-value'>" + observerMetric + "</span><span class='metric-label'>SkyBoT Observer</span></div>");
                    report.println("</div>");
                    report.println("<div class='astro-note'>WCS source: " + astrometryContext.getWcsSummary() + ".");
                    if (astrometryContext.hasSkybotObserverCode()) {
                        report.println("<br>SkyBoT observer code source: " + escapeHtml(astrometryContext.getSkybotObserverSource()) + ".");
                    } else {
                        report.println("<br>No IAU observatory code configured. SkyBoT links will use geocenter (500).");
                    }
                    if (astrometryContext.getObserverSiteSourceLabel() != null) {
                        report.println("<br>Known site coordinates source: " + escapeHtml(astrometryContext.getObserverSiteSourceLabel()) + ".");
                    } else {
                        report.println("<br>No observer site coordinates found.");
                    }
                    report.println("</div>");
                } else {
                    report.println("<p>No reusable WCS solution was found in the aligned set. Sky-coordinate report links are disabled for this session.</p>");
                }
                report.println("</div>");

                if (!pipelineTelemetry.frameQualityStats.isEmpty()) {
                    report.println("<div class='panel compact-diagnostics-panel'>");
                    report.println("<h2>Quality Control: Frame Quality Statistics</h2>");
                    report.println("<p class='compact-note'>Per-frame quality metrics after the session evaluator. Bright-star eccentricity is shown as <code>n/a</code> when too few bright stars qualified for that frame. For long sessions, the detailed table below is scrollable and keeps its column headers pinned.</p>");
                    report.println("<div class='compact-threshold-grid'>");
                    report.println("<div class='config-item'><span>Thresholds Available</span><span class='val'>" + (pipelineTelemetry.qualityThresholds.available ? "Yes" : "No") + "</span></div>");
                    report.println("<div class='config-item'><span>Min Allowed Star Count</span><span class='val'>" + formatOptionalMetric(pipelineTelemetry.qualityThresholds.minAllowedStarCount) + "</span></div>");
                    report.println("<div class='config-item'><span>Max Allowed FWHM</span><span class='val'>" + formatOptionalMetric(pipelineTelemetry.qualityThresholds.maxAllowedFwhm) + "</span></div>");
                    report.println("<div class='config-item'><span>Max Allowed Eccentricity</span><span class='val'>" + formatOptionalMetric(pipelineTelemetry.qualityThresholds.maxAllowedEccentricity) + "</span></div>");
                    report.println("<div class='config-item'><span>Max Bright-Star Eccentricity</span><span class='val'>" + formatOptionalMetric(pipelineTelemetry.qualityThresholds.maxAllowedBrightStarEccentricity) + "</span></div>");
                    report.println("<div class='config-item'><span>Background Median Baseline</span><span class='val'>" + formatOptionalMetric(pipelineTelemetry.qualityThresholds.backgroundMedianBaseline) + "</span></div>");
                    report.println("<div class='config-item'><span>Max Background Deviation</span><span class='val'>" + formatOptionalMetric(pipelineTelemetry.qualityThresholds.maxAllowedBackgroundDeviation) + "</span></div>");
                    report.println("<div class='config-item'><span>Min Allowed Bg Median</span><span class='val'>" + formatOptionalMetric(pipelineTelemetry.qualityThresholds.minAllowedBackgroundMedian) + "</span></div>");
                    report.println("<div class='config-item'><span>Max Allowed Bg Median</span><span class='val'>" + formatOptionalMetric(pipelineTelemetry.qualityThresholds.maxAllowedBackgroundMedian) + "</span></div>");
                    report.println("</div>");
                    report.println("<div class='scroll-box compact-table-box'>");
                    report.println("<table><thead><tr><th>Frame Index</th><th>Filename</th><th>Bg Median</th><th>Bg Sigma</th><th>Median FWHM</th><th>Median Ecc</th><th>Bright-Star Median Ecc</th><th>Stars</th><th>Shape Stars</th><th>Bright Shape Stars</th><th>FWHM Stars</th><th>Status</th><th>Rejection Reason</th></tr></thead><tbody>");
                    for (PipelineTelemetry.FrameQualityStat stat : pipelineTelemetry.frameQualityStats) {
                        String statusLabel = stat.rejected ? "Rejected" : "Kept";
                        String rejectionReason = (stat.rejectionReason == null || stat.rejectionReason.isBlank()) ? "-" : escapeHtml(stat.rejectionReason);
                        report.println("<tr><td>" + (stat.frameIndex + 1) + "</td>");
                        report.println("<td>" + escapeHtml(stat.filename) + "</td>");
                        report.println("<td>" + formatOptionalMetric(stat.backgroundMedian) + "</td>");
                        report.println("<td>" + formatOptionalMetric(stat.backgroundNoise) + "</td>");
                        report.println("<td>" + formatOptionalMetric(stat.medianFWHM) + "</td>");
                        report.println("<td>" + formatOptionalMetric(stat.medianEccentricity) + "</td>");
                        report.println("<td>" + formatOptionalMetric(stat.brightStarMedianEccentricity) + "</td>");
                        report.println("<td>" + stat.starCount + "</td>");
                        report.println("<td>" + stat.usableShapeStarCount + "</td>");
                        report.println("<td>" + stat.brightStarShapeStarCount + "</td>");
                        report.println("<td>" + stat.fwhmStarCount + "</td>");
                        report.println("<td" + (stat.rejected ? " class='alert'" : "") + ">" + statusLabel + "</td>");
                        report.println("<td" + (stat.rejected ? " class='alert'" : "") + ">" + rejectionReason + "</td></tr>");
                    }
                    report.println("</tbody></table>");
                    report.println("</div>");
                    report.println("</div>");
                }

                // --- Rejected Frames Table ---
                if (!pipelineTelemetry.rejectedFrames.isEmpty()) {
                    report.println("<div class='panel compact-diagnostics-panel'>");
                    report.println("<h2>Quality Control: Rejected Frames</h2>");
                    report.println("<div class='scroll-box compact-table-box'>");
                    report.println("<table><tr><th>Frame Index</th><th>Filename</th><th>Median Ecc</th><th>Bright-Star Median Ecc</th><th>Bright Shape Stars</th><th>Rejection Reason</th></tr>");
                    for (PipelineTelemetry.FrameRejectionStat rej : pipelineTelemetry.rejectedFrames) {
                        report.println("<tr><td>" + (rej.frameIndex + 1) + "</td>");
                        report.println("<td>" + escapeHtml(rej.filename) + "</td>");
                        report.println("<td>" + formatOptionalMetric(rej.medianEccentricity) + "</td>");
                        report.println("<td>" + formatOptionalMetric(rej.brightStarMedianEccentricity) + "</td>");
                        report.println("<td>" + rej.brightStarShapeStarCount + "</td>");
                        report.println("<td class='alert'>" + escapeHtml(rej.reason) + "</td></tr>");
                    }
                    report.println("</table>");
                    report.println("</div>");
                    report.println("</div>");
                }

                // =================================================================
                // 2. PIPELINE CONFIGURATION SECTION
                // =================================================================
                if (config != null) {
                    // Export JSON Profile File
                    File jsonConfigFile = new File(exportDir, "detection_config.json");
                    try (java.io.FileWriter writer = new java.io.FileWriter(jsonConfigFile)) {
                        SpacePixelsDetectionProfileIO.write(
                                writer,
                                config,
                                SpacePixelsDetectionProfileIO.getActiveAutoTuneMaxCandidateFrames());
                    } catch (Exception e) {
                        System.err.println("Failed to write config JSON: " + e.getMessage());
                    }

                    report.println("<div class='panel'>");
                    report.println("<h2>Pipeline Configuration</h2>");
                    report.println("<p style='font-size: 13px; color: #888; margin-top: -10px;'>Active tuning parameters used during this session. <a href='detection_config.json' target='_blank' style='color: #4da6ff; text-decoration: none;'>[View / Download JSON Profile]</a></p>");
                    report.println("<div class='scroll-box' style='padding: 10px;'><div class='config-grid'>");

                    for (Field field : config.getClass().getDeclaredFields()) {
                        try {
                            field.setAccessible(true);
                            Object value = field.get(config);
                            report.println("<div class='config-item'><span>" + field.getName() + "</span> <span class='val'>" + value + "</span></div>");
                        } catch (IllegalAccessException e) {
                            // Silently ignore fields that cannot be accessed
                        }
                    }
                    report.println("</div></div></div>");
                }

                // --- DIAGNOSTIC MASK RENDER ---
                report.println("<div class='panel'>");
                report.println("<h2>Master Shield & Veto Mask</h2>");
                report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>");
                report.println("The raw median stack (Left) and the extracted Binary Veto Mask painted in red (Right). " +
                        "These are the detected objects in the master map extended by a max star jitter of <strong>" + config.maxStarJitter + "</strong> pixels. " +
                        "During Phase 3, we calculate the overlap fraction of each transient against this mask to determine if it should be deleted (purged as a stationary star) or allowed to survive. " +
                        "The maximum allowed overlap fraction is currently set to <strong>" + config.maxMaskOverlapFraction + "</strong> (" + (int)(config.maxMaskOverlapFraction * 100) + "%).</p>");
                report.println("<div class='flex-container' style='margin-bottom: 15px;'>");
                report.println("<div class='metric-box'><span class='metric-value'>" + (masterStars != null ? masterStars.size() : 0) + "</span><span class='metric-label'>Master Map Objects</span></div>");
                report.println("</div>");
                report.println("<div class='image-container'>");
                report.println("<div><a href='master_stack.png' target='_blank'><img src='master_stack.png' style='max-width: 400px;' alt='Master Stack' /></a><br/><center><small>Deep Median Stack</small></center></div>");
                report.println("<div><a href='master_mask_overlay.png' target='_blank'><img src='master_mask_overlay.png' style='max-width: 400px;' alt='Mask Overlay' /></a><br/><center><small>Binary Footprint Mask (Red)</small></center></div>");
                report.println("</div>");
                report.println("</div>");

                // --- NEW: DITHER & DRIFT DIAGNOSTICS ---
                if (rawFrames != null && !rawFrames.isEmpty() && result.driftPoints != null && !result.driftPoints.isEmpty()) {
                    List<SourceExtractor.Pixel> driftPath = result.driftPoints;
                    List<BufferedImage> cornerFrames = new ArrayList<>();

                    boolean hasDrift = false;
                    SourceExtractor.Pixel firstP = driftPath.get(0);
                    for (SourceExtractor.Pixel p : driftPath) {
                        if (p.x != firstP.x || p.y != firstP.y) {
                            hasDrift = true;
                            break;
                        }
                    }

                    if (hasDrift) {
                        // Use the existing down-sampler to keep memory, disk I/O, and GIF size completely safe
                        List<Integer> sampledCornerIndices = getRepresentativeSequence(rawFrames.size(), new java.util.HashSet<>(), 15);

                        for (int idx : sampledCornerIndices) {
                            cornerFrames.add(createFourCornerMosaic(rawFrames.get(idx), 150));
                        }

                        BufferedImage driftImg = createDriftMap(driftPath, 300);
                        saveTrackImageLossless(driftImg, new File(exportDir, "dither_drift_map.png"));

                        String cornerGifFile = "dither_corners_sampled.gif";
                        GifSequenceWriter.saveAnimatedGif(cornerFrames, new File(exportDir, cornerGifFile), gifBlinkSpeedMs);

                        report.println("<div class='panel'>");
                        report.println("<h2>Dither & Sensor Drift Diagnostics</h2>");
                        report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>");
                        report.println("Shows how the image frame drifted across the session. Sensor dust or hot pixels move exactly along this trajectory, potentially creating false moving targets.</p>");
                        report.println("<div class='image-container'>");
                        report.println("<div><a href='dither_drift_map.png' target='_blank'><img src='dither_drift_map.png' style='max-width: 300px;' alt='Drift Trajectory' /></a><br/><center><small>Drift Trajectory Map (Blue = Start, Red = End)</small></center></div>");
                        report.println("<div><a href='" + cornerGifFile + "' target='_blank'><img src='" + cornerGifFile + "' style='max-width: 300px;' alt='Corners Sampled Time-Lapse' /></a><br/><center><small>4-Corners Sampled Time-Lapse</small></center></div>");

                        // --- Print Coordinates Table ---
                        report.println("<div style='min-width: 200px; flex-grow: 1;'><div class='scroll-box' style='max-height: 325px; border-color: #333; padding: 10px;'>");
                        report.println("<div class='config-grid' style='grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));'>");
                        for (int i = 0; i < driftPath.size(); i++) {
                            SourceExtractor.Pixel p = driftPath.get(i);
                            report.println("<div class='config-item'><span style='color: #aaa;'>Frame " + (i + 1) + "</span> <span class='val'>" + escapeHtml(formatPixelCoordinateOnly(p.x, p.y)) + "</span></div>");
                        }
                        report.println("</div></div></div>");

                        report.println("</div>");
                        report.println("</div>");
                    }
                }

                // --- Extraction Stats Table ---
                report.println("<div class='panel compact-diagnostics-panel'>");
                report.println("<h2>Frame Extraction Statistics</h2>");
                report.println("<p class='compact-note'>Detailed per-frame extraction diagnostics are shown in a scrollable compact table to keep long sequences readable without removing any rows.</p>");
                report.println("<div class='scroll-box compact-table-box'>");
                report.println("<table><thead><tr><th>Frame Index</th><th>Filename</th><th>Objects Extracted</th><th>Bg Median</th><th>Bg Sigma</th><th>Seed Threshold</th><th>Grow Threshold</th></tr></thead><tbody>");
                for (PipelineTelemetry.FrameExtractionStat stat : pipelineTelemetry.frameExtractionStats) {
                    report.println("<tr><td>" + (stat.frameIndex + 1) + "</td>");
                    report.println("<td>" + escapeHtml(stat.filename) + "</td>");
                    report.println("<td>" + stat.objectCount + "</td>");
                    report.println("<td>" + String.format(Locale.US, "%.2f", stat.bgMedian) + "</td>");
                    report.println("<td>" + String.format(Locale.US, "%.2f", stat.bgSigma) + "</td>");
                    report.println("<td>" + String.format(Locale.US, "%.2f", stat.seedThreshold) + "</td>");
                    report.println("<td>" + String.format(Locale.US, "%.2f", stat.growThreshold) + "</td></tr>");
                }
                report.println("</tbody></table>");
                report.println("</div>");
                report.println("</div>");

                // --- Star Map Purification Table ---
                report.println("<div class='panel compact-diagnostics-panel'>");
                report.println("<h2>Phase 3: Stationary Star Purification</h2>");
                report.println("<p class='compact-note'>Master-mask purification removes stationary point-like residues and same-mask stationary streaks before the moving-object linker runs. The per-frame breakdown is shown below in a scrollable compact table.</p>");
                report.println("<div class='flex-container' style='margin-bottom: 10px;'>");
                report.println("<div class='metric-box compact'><span class='metric-value'>" + linkerTelemetry.totalStationaryStarsPurged + "</span><span class='metric-label'>Stationary Stars Purged</span></div>");
                report.println("<div class='metric-box compact'><span class='metric-value'>" + linkerTelemetry.totalStationaryStreaksPurged + "</span><span class='metric-label'>Stationary Streaks Purged</span></div>");
                report.println("</div>");
                report.println("<div class='scroll-box compact-table-box'>");
                report.println("<table><thead><tr><th>Frame Index</th><th>Filename</th><th>Initial Point Sources</th><th>Stars Purged</th><th>Surviving Transients</th></tr></thead><tbody>");

                for (TrackerTelemetry.FrameStarMapStat starStat : linkerTelemetry.frameStarMapStats) {
                    String fName = "Unknown";
                    if (starStat.frameIndex < pipelineTelemetry.frameExtractionStats.size()) {
                        fName = pipelineTelemetry.frameExtractionStats.get(starStat.frameIndex).filename;
                    }
                    report.println("<tr><td>" + (starStat.frameIndex + 1) + "</td>");
                    report.println("<td>" + escapeHtml(fName) + "</td>");
                    report.println("<td>" + starStat.initialPointSources + "</td>");
                    report.println("<td style='color: #ff9933;'>" + starStat.purgedStars + "</td>");
                    report.println("<td style='color: #44ff44; font-weight: bold;'>" + starStat.survivingTransients + "</td></tr>");
                }
                report.println("</tbody></table>");
                report.println("</div>");
                report.println("</div>");
            }

            // =================================================================
            // 2. TRACKER TELEMETRY
            // =================================================================
            if (linkerTelemetry != null) {
                report.println("<div class='panel compact-diagnostics-panel'>");
                report.println("<h2>Track Linking Diagnostics</h2>");
                report.println("<p class='compact-note'>Compact scrollable view with sticky headers for the track-link rejection phases and final acceptance summary.</p>");

                report.println("<div class='scroll-box compact-table-box'>");
                report.println("<table><thead><tr><th>Filter Phase</th><th>Rejection Reason</th><th>Points Rejected</th></tr></thead><tbody>");

                report.println("<tr><td>0. Single Streak</td><td>Binary-Star-Like Shape Veto</td><td>" + linkerTelemetry.rejectedBinaryStarStreakShape + "</td></tr>");
                report.println("<tr><td>1. Baseline (p1 &rarr; p2)</td><td>Non-Positive Time Delta</td><td>" + linkerTelemetry.countBaselineNonPositiveDelta + "</td></tr>");
                report.println("<tr><td>1. Baseline (p1 &rarr; p2)</td><td>Stationary / Jitter</td><td>" + linkerTelemetry.countBaselineJitter + "</td></tr>");
                report.println("<tr><td>1. Baseline (p1 &rarr; p2)</td><td>Exceeded Max Jump Velocity</td><td>" + linkerTelemetry.countBaselineJump + "</td></tr>");
                report.println("<tr><td>1. Baseline (p1 &rarr; p2)</td><td>Morphological Size Mismatch</td><td>" + linkerTelemetry.countBaselineSize + "</td></tr>");

                report.println("<tr><td>2. Track Search (p3)</td><td>Non-Positive Time Delta</td><td>" + linkerTelemetry.countP3NonPositiveDelta + "</td></tr>");
                report.println("<tr><td>2. Track Search (p3)</td><td>Velocity Mismatch</td><td>" + linkerTelemetry.countP3VelocityMismatch + "</td></tr>");
                report.println("<tr><td>2. Track Search (p3)</td><td>Off Predicted Trajectory Line</td><td>" + linkerTelemetry.countP3NotLine + "</td></tr>");
                report.println("<tr><td>2. Track Search (p3)</td><td>Wrong Direction / Angle</td><td>" + linkerTelemetry.countP3WrongDirection + "</td></tr>");
                report.println("<tr><td>2. Track Search (p3)</td><td>Exceeded Max Jump Velocity</td><td>" + linkerTelemetry.countP3Jump + "</td></tr>");
                report.println("<tr><td>2. Track Search (p3)</td><td>Morphological Size Mismatch</td><td>" + linkerTelemetry.countP3Size + "</td></tr>");

                report.println("<tr><td>3. Final Track</td><td>Insufficient Track Length</td><td>" + linkerTelemetry.countTrackTooShort + "</td></tr>");
                report.println("<tr><td>3. Final Track</td><td>Erratic Kinematic Rhythm</td><td>" + linkerTelemetry.countTrackErraticRhythm + "</td></tr>");
                report.println("<tr><td>3. Final Track</td><td>Duplicate Track (Ignored)</td><td>" + linkerTelemetry.countTrackDuplicate + "</td></tr>");
                report.println("</tbody></table>");
                report.println("</div>");
                report.println("<p class='astro-note' style='margin-top: 12px;'>");
                report.println("Confirmed phase outputs: <strong>" + linkerTelemetry.streakTracksFound + "</strong> accepted streak tracks, <strong>" + linkerTelemetry.pointTracksFound + "</strong> accepted point tracks, and <strong>" + anomalyMetric + "</strong> rescued anomalies.");
                if (anomalyMetric > 0) {
                    report.println(" The anomaly split was <strong>" + peakSigmaAnomalyCount + "</strong> peak-sigma and <strong>" + integratedSigmaAnomalyCount + "</strong> integrated-sigma.");
                    if (otherAnomalyCount > 0) {
                        report.println(" <strong>" + otherAnomalyCount + "</strong> anomaly rescues carried other or unknown type labels.");
                    }
                }
                if (linkerTelemetry.suspectedStreakTracksFound > 0) {
                    report.println(" The tracker also flagged <strong>" + linkerTelemetry.suspectedStreakTracksFound + "</strong> suspected streak tracks.");
                }
                report.println("</p>");

                report.println("</div>");
            }

            // =================================================================
            // 3. TARGET VISUALIZATIONS
            // =================================================================
            report.println("<h2>Target Visualizations</h2>");

            if (singleStreaks.isEmpty() && streakTracks.isEmpty() && movingTargets.isEmpty() && anomalies.isEmpty() && suspectedStreakTracks.isEmpty()) {
                report.println("<div class='panel'><p>No moving tracks, single-frame streaks, anomaly rescues, or suspected streak tracks were detected in this session.</p></div>");
            }


            if (!singleStreaks.isEmpty()) {
                report.println("<h3 style='color: #ff9933; margin-top: 30px; border-bottom: 1px solid #444; padding-bottom: 5px;'>Single Streaks</h3>");
                int counter = 1;
                for (TrackLinker.Track track : singleStreaks) {
                    CropBounds cb = new CropBounds(track, trackCropPadding);
                    SourceExtractor.DetectedObject pt = track.points.get(0);
                    int frameIndex = pt.sourceFrameIndex;
                    int partCount = track.points.size();
                    String partBadge = partCount > 1
                            ? " <span style='background: #6b4a20; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>" + partCount + " Parts</span>"
                            : "";

                    report.println("<div class='detection-card streak-title'>");
                    report.println("<div class='detection-title'>Single Streak Event S" + counter + partBadge + "</div>");
                    report.print(buildSingleFrameEventSummaryHtml(track, fitsFiles));

                    short[][] croppedData = robustEdgeAwareCrop(rawFrames.get(frameIndex), cb.fixedCenterX, cb.fixedCenterY, cb.trackBoxWidth, cb.trackBoxHeight);
                    BufferedImage streakImg = createDisplayImage(croppedData);
                    String streakFileName = "single_streak_" + counter + ".png";
                    saveTrackImageLossless(streakImg, new File(exportDir, streakFileName));

                    String shapeFileName = "single_streak_" + counter + "_shape.png";
                    BufferedImage streakShapeImg = createSingleStreakShapeImage(track.points, cb.trackBoxWidth, cb.trackBoxHeight, cb.startX, cb.startY, false);
                    saveTrackImageLossless(streakShapeImg, new File(exportDir, shapeFileName));

                    report.println("<div class='image-container'>");
                    report.println("<div><a href='" + streakFileName + "' target='_blank'><img src='" + streakFileName + "' alt='Detection Image' /></a><br/><center><small>Detection Image</small></center></div>");
                    report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Shape Footprint' /></a><br/><center><small>Shape Footprint Map</small></center></div>");
                    report.println("</div>");
                    report.println("<strong>Detection Coordinates & Part Metrics:</strong><ul class='source-list'>");
                    for (int i = 0; i < track.points.size(); i++) {
                        SourceExtractor.DetectedObject part = track.points.get(i);
                        String metricsStr = buildStreakMetricsText(part);
                        String fileLabel = partCount > 1
                                ? "[Part " + (i + 1) + "] " + part.sourceFilename
                                : part.sourceFilename;
                        report.println(DetectionReportAstrometry.buildSourceCoordinateListEntry(fileLabel, astrometryContext, part.x, part.y, metricsStr));
                    }
                    report.println("</ul>");
                    if (partCount > 1) {
                        report.print(DetectionReportAstrometry.buildTrackSkyViewerHtml(astrometryContext, track, "Reference epoch for single-streak frame lookup"));
                    } else {
                        report.print(DetectionReportAstrometry.buildSingleFrameSkyViewerHtml(astrometryContext, pt, "Reference epoch for streak lookup"));
                    }
                    report.println("</div>");

                    counter++;
                }
            }

            if (!streakTracks.isEmpty() || !suspectedStreakTracks.isEmpty()) {
                report.println("<h3 style='color: #ffcc33; margin-top: 30px; border-bottom: 1px solid #444; padding-bottom: 5px;'>Streak Tracks</h3>");
                if (!suspectedStreakTracks.isEmpty()) {
                    report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>Suspected streak tracks grouped from same-frame rescued anomalies are shown here alongside the confirmed multi-frame streak tracks.</p>");
                }

                int counter = 1;
                for (TrackLinker.Track track : streakTracks) {
                    CropBounds cb = new CropBounds(track, trackCropPadding);
                    List<BufferedImage> starCentricFrames = new ArrayList<>();
                    java.util.Set<Integer> processedFrames = new java.util.HashSet<>();

                    for (SourceExtractor.DetectedObject pt : track.points) {
                        if (processedFrames.contains(pt.sourceFrameIndex)) continue;
                        processedFrames.add(pt.sourceFrameIndex);

                        short[][] rawImage = rawFrames.get(pt.sourceFrameIndex);
                        short[][] croppedStarData = robustEdgeAwareCrop(rawImage, cb.fixedCenterX, cb.fixedCenterY, cb.trackBoxWidth, cb.trackBoxHeight);
                        BufferedImage starFrameGray = createDisplayImage(croppedStarData);

                        Graphics2D g2d = starFrameGray.createGraphics();
                        int localObjX = (int) Math.round(pt.x - cb.startX);
                        int localObjY = (int) Math.round(pt.y - cb.startY);

                        g2d.setColor(java.awt.Color.WHITE); g2d.setStroke(new java.awt.BasicStroke(targetCircleStrokeWidth));
                        g2d.drawOval(localObjX - targetCircleRadius, localObjY - targetCircleRadius, targetCircleRadius * 2, targetCircleRadius * 2);
                        g2d.dispose();
                        starCentricFrames.add(starFrameGray);
                    }

                    String shapeFileName = "streak_track_" + counter + "_shape.png";
                    BufferedImage shapeImg = createTrackShapeImage(track, cb.trackBoxWidth, cb.trackBoxHeight, cb.startX, cb.startY);
                    saveTrackImageLossless(shapeImg, new File(exportDir, shapeFileName));

                    String starFileName = "streak_track_" + counter + "_star_centric.gif";
                    File starFile = new File(exportDir, starFileName);
                    GifSequenceWriter.saveAnimatedGif(starCentricFrames, starFile, gifBlinkSpeedMs);

                    report.println("<div class='detection-card streak-title' style='border-left-color: #ffcc33;'>");
                    String timeBadge = track.isTimeBasedTrack ? " <span style='background: #005c99; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>⏱ Time-Based Kinematics</span>" : "";
                    report.println("<div class='detection-title' style='color: #ffcc33;'>Confirmed Streak Track ST" + counter + timeBadge + "</div>");
                    report.print(buildTrackTimingSummaryHtml(track, astrometryContext));

                    report.println("<div class='image-container'>");
                    report.println("<div><a href='" + starFileName + "' target='_blank'><img src='" + starFileName + "' alt='Star Centric Animation' /></a><br/><center><small>Star Centric</small></center></div>");
                    report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Track Shape Map' /></a><br/><center><small>Track Shape Map</small></center></div>");
                    report.println("</div>");

                    report.println("<strong>Detection Coordinates & Frames:</strong><ul class='source-list'>");
                    for (int i = 0; i < track.points.size(); i++) {
                        SourceExtractor.DetectedObject pt = track.points.get(i);
                        String metricsStr = buildStreakMetricsText(pt);
                        report.println(DetectionReportAstrometry.buildSourceCoordinateListEntry("[" + (i + 1) + "] " + pt.sourceFilename, astrometryContext, pt.x, pt.y, metricsStr));
                    }
                    report.println("</ul>");
                    report.print(DetectionReportAstrometry.buildTrackSatCheckerHtml(astrometryContext, track));
                    report.print(DetectionReportAstrometry.buildTrackSkyViewerHtml(astrometryContext, track, "Reference epoch for streak-track lookup"));
                    report.println("</div>");
                    counter++;
                }

                int suspectedCounter = 1;
                for (TrackLinker.Track track : suspectedStreakTracks) {
                    CropBounds cb = new CropBounds(track, trackCropPadding);
                    SourceExtractor.DetectedObject pt = track.points.get(0);
                    int frameIndex = pt.sourceFrameIndex;
                    int partCount = track.points.size();
                    String partBadge = partCount > 1
                            ? " <span style='background: #6b4a20; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>" + partCount + " Parts</span>"
                            : "";
                    String groupingBadge = " <span style='background: #7a5a12; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>Same-Frame Anomaly Grouping</span>";

                    report.println("<div class='detection-card streak-title' style='border-left-color: #ff9933;'>");
                    report.println("<div class='detection-title' style='color: #ffb347;'>Suspected Streak Track SST" + suspectedCounter + partBadge + groupingBadge + "</div>");
                    report.print(buildSingleFrameEventSummaryHtml(track, fitsFiles));

                    short[][] croppedData = robustEdgeAwareCrop(rawFrames.get(frameIndex), cb.fixedCenterX, cb.fixedCenterY, cb.trackBoxWidth, cb.trackBoxHeight);
                    BufferedImage streakImg = createDisplayImage(croppedData);
                    String streakFileName = "suspected_streak_track_" + suspectedCounter + ".png";
                    saveTrackImageLossless(streakImg, new File(exportDir, streakFileName));

                    String shapeFileName = "suspected_streak_track_" + suspectedCounter + "_shape.png";
                    BufferedImage streakShapeImg = createSingleStreakShapeImage(track.points, cb.trackBoxWidth, cb.trackBoxHeight, cb.startX, cb.startY, false);
                    saveTrackImageLossless(streakShapeImg, new File(exportDir, shapeFileName));

                    report.println("<div class='image-container'>");
                    report.println("<div><a href='" + streakFileName + "' target='_blank'><img src='" + streakFileName + "' alt='Detection Image' /></a><br/><center><small>Detection Image</small></center></div>");
                    report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Shape Footprint' /></a><br/><center><small>Shape Footprint Map</small></center></div>");
                    report.println("</div>");

                    report.println("<strong>Detection Coordinates & Part Metrics:</strong><ul class='source-list'>");
                    for (int i = 0; i < track.points.size(); i++) {
                        SourceExtractor.DetectedObject part = track.points.get(i);
                        String metricsStr = buildStreakMetricsText(part);
                        String fileLabel = partCount > 1
                                ? "[Part " + (i + 1) + "] " + part.sourceFilename
                                : part.sourceFilename;
                        report.println(DetectionReportAstrometry.buildSourceCoordinateListEntry(fileLabel, astrometryContext, part.x, part.y, metricsStr));
                    }
                    report.println("</ul>");
                    if (partCount > 1) {
                        report.print(DetectionReportAstrometry.buildTrackSkyViewerHtml(astrometryContext, track, "Reference epoch for suspected streak frame lookup"));
                    } else {
                        report.print(DetectionReportAstrometry.buildSingleFrameSkyViewerHtml(astrometryContext, pt, "Reference epoch for suspected streak lookup"));
                    }
                    report.println("</div>");

                    suspectedCounter++;
                }
            }

            if (!movingTargets.isEmpty()) {
                report.println("<h3 style='color: #4da6ff; margin-top: 30px; border-bottom: 1px solid #444; padding-bottom: 5px;'>Moving Target Tracks</h3>");
                int counter = 1;
                for (TrackLinker.Track track : movingTargets) {
                    CropBounds cb = new CropBounds(track, trackCropPadding);
                    List<BufferedImage> objectCentricFrames = new ArrayList<>();
                    List<BufferedImage> starCentricFrames = new ArrayList<>();
                    java.util.Set<Integer> processedFrames = new java.util.HashSet<>();

                    for (SourceExtractor.DetectedObject pt : track.points) {
                        if (processedFrames.contains(pt.sourceFrameIndex)) continue;
                        processedFrames.add(pt.sourceFrameIndex);
                        short[][] rawImage = rawFrames.get(pt.sourceFrameIndex);

                        short[][] croppedObjData = robustEdgeAwareCrop(rawImage, (int) Math.round(pt.x), (int) Math.round(pt.y), trackObjectCentricCropSize, trackObjectCentricCropSize);
                        BufferedImage objFrame = createDisplayImage(croppedObjData);
                        objectCentricFrames.add(objFrame);

                        short[][] croppedStarData = robustEdgeAwareCrop(rawImage, cb.fixedCenterX, cb.fixedCenterY, cb.trackBoxWidth, cb.trackBoxHeight);
                        BufferedImage starFrameGray = createDisplayImage(croppedStarData);

                        Graphics2D g2d = starFrameGray.createGraphics();
                        int localObjX = (int) Math.round(pt.x - cb.startX);
                        int localObjY = (int) Math.round(pt.y - cb.startY);
                        g2d.setColor(java.awt.Color.WHITE); g2d.setStroke(new java.awt.BasicStroke(targetCircleStrokeWidth));
                        g2d.drawOval(localObjX - targetCircleRadius, localObjY - targetCircleRadius, targetCircleRadius * 2, targetCircleRadius * 2);
                        g2d.dispose();
                        starCentricFrames.add(starFrameGray);
                    }

                    String shapeFileName = "moving_track_" + counter + "_shape.png";
                    BufferedImage shapeImg = createTrackShapeImage(track, cb.trackBoxWidth, cb.trackBoxHeight, cb.startX, cb.startY);
                    saveTrackImageLossless(shapeImg, new File(exportDir, shapeFileName));

                    String objFileName = "moving_track_" + counter + "_object_centric.gif";
                    String starFileName = "moving_track_" + counter + "_star_centric.gif";
                    GifSequenceWriter.saveAnimatedGif(objectCentricFrames, new File(exportDir, objFileName), gifBlinkSpeedMs);
                    GifSequenceWriter.saveAnimatedGif(starCentricFrames, new File(exportDir, starFileName), gifBlinkSpeedMs);

                    report.println("<div class='detection-card'>");
                    String timeBadge = track.isTimeBasedTrack ? " <span style='background: #005c99; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>⏱ Time-Based Kinematics</span>" : "";
                    report.println("<div class='detection-title'>Moving Target Track T" + counter + timeBadge + "</div>");
                    report.print(buildTrackTimingSummaryHtml(track, astrometryContext));

                    report.println("<div class='image-container'>");
                    report.println("<div><a href='" + objFileName + "' target='_blank'><img src='" + objFileName + "' alt='Object Centric' /></a><br/><center><small>Object Centric</small></center></div>");
                    report.println("<div><a href='" + starFileName + "' target='_blank'><img src='" + starFileName + "' alt='Star Centric' /></a><br/><center><small>Star Centric</small></center></div>");
                    report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Track Shape Map' /></a><br/><center><small>Track Shape Map</small></center></div>");
                    report.println("</div>");

                    // --- NEW: EVOLUTION (TIGHT CROPS) ---
                    report.println("<div style='margin-bottom: 20px;'>");
                    report.println("<strong style='color: #ccc;'>Pixel Evolution (Tight Crops):</strong>");
                    report.println("<div style='display: flex; flex-wrap: wrap; gap: 10px; margin-top: 8px; align-items: flex-end;'>");

                    StringBuilder shapeEvolutionHtml = new StringBuilder();
                    shapeEvolutionHtml.append("<div style='margin-bottom: 20px;'>\n");
                    shapeEvolutionHtml.append("<strong style='color: #ccc;'>Shape Evolution (Detected Pixels):</strong>\n");
                    shapeEvolutionHtml.append("<div style='display: flex; flex-wrap: wrap; gap: 10px; margin-top: 8px; align-items: flex-end;'>\n");

                    for (int i = 0; i < track.points.size(); i++) {
                        SourceExtractor.DetectedObject pt = track.points.get(i);
                        double objectRadius = 0;
                        if (pt.pixelArea > 0) {
                            objectRadius = pt.isStreak ? Math.sqrt((pt.pixelArea * pt.elongation) / Math.PI) : Math.sqrt(pt.pixelArea / Math.PI);
                        }
                        int tightCropSize = Math.max(50, (int) Math.round(objectRadius * 2) + 24); // Diameter + 24px padding
                        short[][] tightCropData = robustEdgeAwareCrop(rawFrames.get(pt.sourceFrameIndex), (int) Math.round(pt.x), (int) Math.round(pt.y), tightCropSize, tightCropSize);
                        BufferedImage tightImg = createDisplayImage(tightCropData);
                        String tightFileName = "moving_track_" + counter + "_pt_" + (i + 1) + "_tight.png";
                        saveTrackImageLossless(tightImg, new File(exportDir, tightFileName));
                        report.println("<div><a href='" + tightFileName + "' target='_blank'><img src='" + tightFileName + "' alt='Pt " + (i + 1) + "' style='max-width: none; min-width: 50px;' /></a><br/><center><small>[" + (i + 1) + "]</small></center></div>");

                        // Generate the matching shape evolution crop
                        int startX = (int) Math.round(pt.x) - (tightCropSize / 2);
                        int startY = (int) Math.round(pt.y) - (tightCropSize / 2);
                        BufferedImage tightShapeImg = createSingleStreakShapeImage(java.util.Collections.singletonList(pt), tightCropSize, tightCropSize, startX, startY, false);
                        String tightShapeFileName = "moving_track_" + counter + "_pt_" + (i + 1) + "_shape.png";
                        saveTrackImageLossless(tightShapeImg, new File(exportDir, tightShapeFileName));

                        shapeEvolutionHtml.append("<div><a href='").append(tightShapeFileName).append("' target='_blank'><img src='").append(tightShapeFileName).append("' alt='Shape ").append(i + 1).append("' style='max-width: none; min-width: 50px;' /></a><br/><center><small>[").append(i + 1).append("]</small></center></div>\n");
                    }
                    report.println("</div></div>");

                    shapeEvolutionHtml.append("</div></div>\n");
                    report.print(shapeEvolutionHtml.toString());

                    report.println("<strong>Detection Coordinates & Frames:</strong><ul class='source-list'>");
                    for (int i = 0; i < track.points.size(); i++) {
                        SourceExtractor.DetectedObject pt = track.points.get(i);
                        String metricsStr = buildMovingTrackMetricsText(pt);
                        report.println(DetectionReportAstrometry.buildSourceCoordinateListEntry("[" + (i + 1) + "] " + pt.sourceFilename, astrometryContext, pt.x, pt.y, metricsStr));
                    }
                    report.println("</ul>");
                    report.print(DetectionReportAstrometry.buildTrackSolarSystemIdentificationHtml(astrometryContext, track));
                    report.println("</div>");
                    counter++;
                }
            }

            if (!anomalies.isEmpty()) {
                report.println("<h3 style='color: #ff3333; margin-top: 30px; border-bottom: 1px solid #444; padding-bottom: 5px;'>Single-Frame Anomalies (Optical Flashes)</h3>");
                report.println("<div class='astro-note' style='margin-bottom: 15px;'>Ordered by source frame index. If multiple anomalies land on the same frame, peak-sigma rescues are shown before integrated-sigma rescues.</div>");
                int counter = 1;
                for (TrackLinker.AnomalyDetection anomaly : anomalies) {
                    SourceExtractor.DetectedObject pt = anomaly.object;
                    CropBounds cb = new CropBounds(pt, trackCropPadding);
                    int frameIndex = pt.sourceFrameIndex;

                    report.println("<div class='detection-card streak-title' style='border-left-color: #ff3333; color: #ff3333;'>");
                    report.println("<div class='detection-title' style='color: #ff3333;'>Anomaly Event A" + counter + " <span style='background: #7a1f50; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>" + escapeHtml(formatAnomalyTypeLabel(anomaly.type).replace("Rescue Type: ", "")) + "</span></div>");

                    short[][] croppedData = robustEdgeAwareCrop(rawFrames.get(frameIndex), cb.fixedCenterX, cb.fixedCenterY, cb.trackBoxWidth, cb.trackBoxHeight);
                    BufferedImage detectionImg = createDisplayImage(croppedData);
                    String detectionFileName = "anomaly_" + counter + "_detection.png";
                    saveTrackImageLossless(detectionImg, new File(exportDir, detectionFileName));

                    String shapeFileName = "anomaly_" + counter + "_shape.png";
                    BufferedImage shapeImg = createSingleStreakShapeImage(java.util.Collections.singletonList(pt), cb.trackBoxWidth, cb.trackBoxHeight, cb.startX, cb.startY, false);
                    saveTrackImageLossless(shapeImg, new File(exportDir, shapeFileName));

                    String maskFileName = null;
                    MaskOverlapStats maskOverlapStats = new MaskOverlapStats(0, 0);
                    if (masterStackData != null && masterVetoMask != null) {
                        BufferedImage maskOverlayImg = createCroppedMasterMaskOverlay(
                                masterStackData,
                                masterVetoMask,
                                cb.fixedCenterX,
                                cb.fixedCenterY,
                                cb.trackBoxWidth,
                                cb.trackBoxHeight,
                                pt
                        );
                        maskFileName = "anomaly_" + counter + "_master_mask.png";
                        saveTrackImageLossless(maskOverlayImg, new File(exportDir, maskFileName));
                        maskOverlapStats = computeMaskOverlapStats(pt, masterVetoMask, masterStackData[0].length, masterStackData.length);
                    }

                    String contextGifFileName = "anomaly_" + counter + "_context.gif";
                    List<BufferedImage> contextFrames = new ArrayList<>();
                    int[] frameSequence = {frameIndex - 1, frameIndex, frameIndex + 1};
                    for (int idx : frameSequence) {
                        if (idx >= 0 && idx < rawFrames.size()) {
                            short[][] cData = robustEdgeAwareCrop(rawFrames.get(idx), cb.fixedCenterX, cb.fixedCenterY, cb.trackBoxWidth, cb.trackBoxHeight);
                            BufferedImage aImg = createDisplayImage(cData);
                            Graphics2D g2d = aImg.createGraphics();
                            int localX = (int) Math.round(pt.x - cb.startX);
                            int localY = (int) Math.round(pt.y - cb.startY);
                            g2d.setColor(Color.WHITE); g2d.setStroke(new BasicStroke(targetCircleStrokeWidth));
                            g2d.drawOval(localX - targetCircleRadius, localY - targetCircleRadius, targetCircleRadius * 2, targetCircleRadius * 2);
                            g2d.dispose();
                            contextFrames.add(aImg);
                        }
                    }
                    GifSequenceWriter.saveAnimatedGif(contextFrames, new File(exportDir, contextGifFileName), gifBlinkSpeedMs);

                    report.println("<div class='image-container'>");
                    report.println("<div><a href='" + detectionFileName + "' target='_blank'><img src='" + detectionFileName + "' alt='Detection Image' /></a><br/><center><small>Detection Image</small></center></div>");
                    report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Shape Footprint' /></a><br/><center><small>Shape Footprint Map</small></center></div>");
                    if (maskFileName != null) {
                        report.println("<div><a href='" + maskFileName + "' target='_blank'><img src='" + maskFileName + "' alt='Master Veto Mask' /></a><br/><center><small>Master Veto Mask</small></center></div>");
                    }
                    report.println("<div><a href='" + contextGifFileName + "' target='_blank'><img src='" + contextGifFileName + "' alt='Anomaly Context' /></a><br/><center><small>Context (Before / Flash / After)</small></center></div>");
                    report.println("</div>");
                    String metricsStr = buildAnomalyMetricsText(anomaly);
                    String overlapColor = "#aaaaaa";
                    String overlapAssessment = "n/a";
                    if (maskOverlapStats.totalPixels > 0) {
                        if (maskOverlapStats.fraction > config.maxMaskOverlapFraction) {
                            overlapColor = "#ff6b6b";
                            overlapAssessment = "above configured limit";
                        } else if (maskOverlapStats.fraction > config.maxMaskOverlapFraction * 0.8) {
                            overlapColor = "#ffcc66";
                            overlapAssessment = "near configured limit";
                        } else {
                            overlapColor = "#66d9a3";
                            overlapAssessment = "comfortably below limit";
                        }
                    }
                    report.println("<strong>Detection Coordinate:</strong><ul class='source-list'>" + DetectionReportAstrometry.buildSourceCoordinateListEntry(pt.sourceFilename, astrometryContext, pt.x, pt.y, metricsStr) + "</ul>");
                    if (maskOverlapStats.totalPixels > 0) {
                        report.println("<div style='font-family: monospace; font-size: 12px; color: #aaa;'>Veto-mask overlap: <span style='color:" + overlapColor + "; font-weight: bold;'>" + String.format(Locale.US, "%.1f%%", maskOverlapStats.fraction * 100.0) + "</span> (" + maskOverlapStats.overlappingPixels + " / " + maskOverlapStats.totalPixels + " detection pixels) | Limit: " + String.format(Locale.US, "%.1f%%", config.maxMaskOverlapFraction * 100.0) + " <span style='color:" + overlapColor + ";'>[" + overlapAssessment + "]</span></div>");
                    }
                    report.println("</div>");
                    counter++;
                }
            }

            // =================================================================
            // 4. DEEP STACK ANOMALIES (ULTRA-SLOW MOVERS)
            // =================================================================
            if (config.enableSlowMoverDetection) {
                boolean hasCandidates = slowMoverCandidates != null && !slowMoverCandidates.isEmpty();
                boolean hasTelemetry = slowMoverTelemetry != null;
                boolean hasSlowMoverStack = slowMoverStackData != null;
                boolean hasSlowMoverMask = slowMoverMedianVetoMask != null;

                if (hasCandidates || hasTelemetry || hasSlowMoverStack || hasSlowMoverMask) {
                    report.println("<h2>Deep Stack Anomalies (Ultra-Slow Mover Candidates)</h2>");
                    report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>Objects in the master median stack that are significantly elongated compared to the rest of the star field. These may be ultra-slow moving targets that moved just enough to form a short streak, but too slowly to be rejected by the median filter.</p>");
                    report.println("<div class='astro-note' style='margin-bottom: 18px;'><strong>Slow-Mover Median Mask</strong> shows the object footprints extracted from the ordinary median stack using the same strict slow-mover detection settings. It marks what the normal median stack already explains. A real ultra-slow mover should usually overlap this mask somewhat, because it still leaves some support in the median stack, but not so much that it is indistinguishable from a fully static source.<br><strong>Slow Mover Diff</strong> is the positive-only difference image <code>Slow Mover Stack - Median Stack</code>. Black means there is no extra signal in the slow-mover stack at that pixel; red highlights signal that becomes brighter in the slow-mover stack and can reveal faint ultra-slow motion.</div>");

                    if (hasTelemetry) {
                        report.println("<div class='panel'>");
                        report.println("<h3 style='color: #ffffff; margin-top: 0;'>Slow-Mover Telemetry</h3>");
                        report.println("<div class='flex-container' style='margin-bottom: 25px;'>");
                        report.println(compactMetricBox(String.valueOf(slowMoverTelemetry.rawCandidatesExtracted), "Raw Candidates"));
                        report.println(compactMetricBox(String.valueOf(slowMoverTelemetry.candidatesAboveElongationThreshold), "Above Elongation"));
                        report.println(compactMetricBox(String.valueOf(slowMoverTelemetry.candidatesEvaluatedAgainstMasks), "Mask Stage"));
                        report.println(compactMetricBox(String.valueOf(slowMoverTelemetry.candidatesDetected), "Final Candidates"));
                        report.println(compactMetricBox(String.valueOf(slowMoverTelemetry.rejectedLowMedianSupport), "Rejected Low Overlap"));
                        report.println(compactMetricBox(String.valueOf(slowMoverTelemetry.rejectedHighMedianSupport), "Rejected High Overlap"));
                        report.println(compactMetricBox(String.valueOf(slowMoverTelemetry.rejectedLowResidualFootprintSupport), "Rejected Low Residual"));
                        report.println(compactMetricBox(String.format(Locale.US, "%.2f", slowMoverTelemetry.medianElongation), "Median Elongation"));
                        report.println(compactMetricBox(String.format(Locale.US, "%.2f", slowMoverTelemetry.madElongation), "MAD Elongation"));
                        report.println(compactMetricBox(String.format(Locale.US, "%.2f", slowMoverTelemetry.dynamicElongationThreshold), "Dynamic Threshold"));
                        report.println(compactMetricBox(formatPercent(slowMoverTelemetry.medianSupportOverlapThreshold), "Min Overlap"));
                        report.println(compactMetricBox(formatPercent(slowMoverTelemetry.medianSupportMaxOverlapThreshold), "Max Overlap"));
                        report.println(compactMetricBox(formatPercent(slowMoverTelemetry.avgMedianSupportOverlap), "Average Overlap"));
                        report.println(compactMetricBox(formatPercent(slowMoverTelemetry.residualFootprintMinFluxFractionThreshold), "Residual Flux Min"));
                        report.println(compactMetricBox(formatPercent(slowMoverTelemetry.avgResidualFootprintFluxFraction), "Residual Flux Avg"));
                        report.println("</div>");
                        report.println("</div>");
                    } else if (hasSlowMoverStack || hasSlowMoverMask || hasCandidates) {
                        report.println("<div class='panel'><p>Slow-mover telemetry was not exported for this run.</p></div>");
                    }

                    if (hasCandidates) {
                        report.println("<div class='flex-container'>");

                        int smCounter = 1;
                        for (int i = 0; i < slowMoverCandidates.size(); i++) {
                            SourceExtractor.DetectedObject sm = slowMoverCandidates.get(i);
                            List<SlowMoverTrackMatch> matchedTracks = findMatchingReportedTracks(sm, reportTrackReferences);
                            SlowMoverCandidateDiagnostics candidateDiagnostics = null;
                            if (i < slowMoverCandidateResults.size()) {
                                SlowMoverCandidateResult candidateResult = slowMoverCandidateResults.get(i);
                                if (candidateResult != null && candidateResult.object == sm) {
                                    candidateDiagnostics = candidateResult.diagnostics;
                                }
                            }
                            exportDeepStackDetectionCard(
                                    report,
                                    exportDir,
                                    rawFrames,
                                    sm,
                                    matchedTracks,
                                    astrometryContext,
                                    slowMoverStackData,
                                    "Slow Mover Stack",
                                    "slow_mover_" + smCounter + "_sm_stack.png",
                                    masterStackData,
                                    slowMoverMedianVetoMask,
                                    "Slow-Mover Median Mask",
                                    "slow_mover_" + smCounter + "_median_mask.png",
                                    candidateDiagnostics != null ? candidateDiagnostics.medianSupportOverlap : null,
                                    candidateDiagnostics,
                                    slowMoverTelemetry != null ? slowMoverTelemetry.residualFootprintMinFluxFractionThreshold : config.slowMoverResidualFootprintMinFluxFraction,
                                    maximumStackData,
                                    "Maximum Stack",
                                    "slow_mover_" + smCounter + "_maximum_stack.png",
                                    "Slow Mover Diff",
                                    "slow_mover_" + smCounter + "_diff.png",
                                    "slow_mover_" + smCounter + "_anim.gif",
                                    "slow_mover_" + smCounter + "_shape.png",
                                    "Candidate #" + smCounter,
                                    "#ff66ff"
                            );
                            smCounter++;
                        }
                        report.println("</div>");
                    } else {
                        report.println("<div class='panel'><p>No ultra-slow movers were detected that exceeded the dynamic threshold.</p></div>");
                    }
                }
            }

            // =================================================================
            // 4.25 LOCAL MICRO-DRIFT CANDIDATES
            // =================================================================
            if (!localRescueCandidates.isEmpty()) {
                short[][] microDriftBackground = masterStackData != null ? masterStackData : (!rawFrames.isEmpty() ? rawFrames.get(0) : null);
                report.println("<h2>Local Rescue Candidates (Residual Heuristic Analysis)</h2>");
                report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>Engine-side JTransient rescue analysis over <strong>unclassifiedTransients</strong>. These candidates highlight faint local motion spanning just a few pixels, sparse coherent local drifts across a handful of frames, plus short same-location repeats that are worth manual inspection.</p>");
                report.println("<div class='astro-note' style='margin-bottom: 15px;'>These detections are no longer a SpacePixels-side post-pass. They come directly from JTransient residual analysis after the normal track, anomaly, and suspected-streak branches have already finished.</div>");

                int counter = 1;
                for (ResidualTransientAnalysis.LocalRescueCandidate candidate : localRescueCandidates) {
                    exportMicroDriftCandidateCard(
                            report,
                            exportDir,
                            rawFrames,
                            fitsFiles,
                            astrometryContext,
                            microDriftBackground,
                            candidate,
                            counter);
                    counter++;
                }
            }

            if (!localActivityClusters.isEmpty()) {
                short[][] clusterBackground = masterStackData != null ? masterStackData : (!rawFrames.isEmpty() ? rawFrames.get(0) : null);
                report.println("<h2>Local Activity Clusters (Residual Review Buckets)</h2>");
                report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>Broad engine-side spatial clusters built from leftover detections after local rescue candidates have already consumed their points. These are not object confirmations; they are review buckets for persistent same-area activity.</p>");

                int counter = 1;
                for (ResidualTransientAnalysis.LocalActivityCluster cluster : localActivityClusters) {
                    exportLocalActivityClusterCard(
                            report,
                            exportDir,
                            rawFrames,
                            astrometryContext,
                            clusterBackground,
                            cluster,
                            counter);
                    counter++;
                }
            }

            // =================================================================
            // 4.5 GLOBAL TRAJECTORY MAP
            // =================================================================
            if (!singleStreaks.isEmpty() || !streakTracks.isEmpty() || !suspectedStreakTracks.isEmpty() || !movingTargets.isEmpty() || !anomalies.isEmpty() || !localRescueTracks.isEmpty() || !localActivityClusters.isEmpty() || !slowMoverCandidates.isEmpty()) {
                short[][] bgData = masterStackData != null ? masterStackData : (!rawFrames.isEmpty() ? rawFrames.get(0) : null);
                if (bgData != null) {
                    BufferedImage globalMap = createGlobalTrackMap(bgData, anomalies, singleStreaks, streakTracks, suspectedStreakTracks, movingTargets, localRescueTracks, localActivityClusters, slowMoverCandidates);
                    saveTrackImageLossless(globalMap, new File(exportDir, "global_track_map.png"));
                    report.println("<div class='panel'>");
                    report.println("<h3 style='color: #ffffff; margin-top: 0;'>Global Trajectory Map</h3>");
                    report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>");
                    report.println("An overview of the classified track outputs and single-frame events plotted over the master background. " +
                            "Track paths are connected with lines (<strong>T#</strong> for moving object tracks, <strong>ST#</strong> for confirmed streak tracks, <strong>SST#</strong> for suspected streak groupings, <strong>LR#</strong> for local rescue candidates). " +
                            "Local activity clusters are ringed as <strong>LC#</strong>, while deep-stack anomalies, single-frame anomalies, and single streaks are marked as <strong>DS#</strong>, <strong>A#</strong>, and <strong>S#</strong>.</p>");
                    report.println(buildGlobalTrajectoryLegendHtml(
                            movingTargets.size(),
                            streakTracks.size(),
                            suspectedStreakTracks.size(),
                            localRescueTracks.size(),
                            localActivityClusters.size(),
                            slowMoverCandidates.size(),
                            anomalies.size(),
                            singleStreaks.size()));
                    report.println("<a href='global_track_map.png' target='_blank'><img src='global_track_map.png' class='native-size-image' style='border: 1px solid #555; border-radius: 4px;' alt='Global Track Map' /></a>");
                    report.println("</div>");
                }
            }

            // =================================================================
            // 5. GLOBAL TRANSIENT MAP (Overall Summary)
            // =================================================================
            if (allTransients != null && !allTransients.isEmpty()) {
                short[][] bgData = masterStackData != null ? masterStackData : (!rawFrames.isEmpty() ? rawFrames.get(0) : null);
                if (bgData != null) {
                    BufferedImage transientMap = createGlobalTransientMap(bgData, allTransients);
                    saveTrackImageLossless(transientMap, new File(exportDir, "global_transient_map.png"));

                    BufferedImage rainbowMap = createRainbowClusterMap(bgData, allTransients);
                    saveTrackImageLossless(rainbowMap, new File(exportDir, "rainbow_cluster_map.png"));

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
            }

            // =================================================================
            // 6. CREATIVE TRIBUTE
            // =================================================================
            short[][] creativeBgData = masterStackData != null ? masterStackData : (!rawFrames.isEmpty() ? rawFrames.get(0) : null);
            if (includeAiCreativeReportSections && creativeBgData != null) {
                String creativeFileName = "creative_tribute_skyprint.png";
                BufferedImage creativeTributeImage = createCreativeTributeImage(
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

                int rawTransientCount = countTotalTransientDetections(allTransients);
                int confirmedTrackCount = confirmedLinkedTrackMetric;
                int suspectedTrackCount = suspectedStreakTrackMetric;
                int deepStackHintCount = potentialSlowMoverCount;
                double longestPath = computeLongestTrackPathPx(streakTracks, movingTargets);
                String dominantMotion = computeDominantMotionLabel(movingTargets, streakTracks);

                report.println("<div class='panel' style='background: linear-gradient(180deg, #453049 0%, #2b2b2b 100%); border: 1px solid #5f536a;'>");
                report.println("<h2>The AI's Perspective: Skyprint of the Session</h2>");
                report.println("<p style='color: #c7bfd6; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>A creative tribute by Codex. This poster compresses the whole run into one image: faint time-mapped transient dust for everything that flashed through the extractor, separate paths for moving object tracks, confirmed streak tracks, and suspected streak groupings, plus distinct markers for anomaly pulses and deep-stack hints.</p>");
                report.println("<a href='" + creativeFileName + "' target='_blank'><img src='" + creativeFileName + "' class='native-size-image' style='border: 1px solid #666; border-radius: 6px;' alt='Creative Tribute Skyprint' /></a>");
                report.println("<p style='font-size: 13px; color: #b8b0c7; margin-bottom: 0;'>This session stitched together <strong style='color:#ffffff;'>" + rawTransientCount + "</strong> raw transients, produced <strong style='color:#ffffff;'>" + confirmedTrackCount + "</strong> confirmed linked tracks, flagged <strong style='color:#ffffff;'>" + suspectedTrackCount + "</strong> suspected streak tracks, surfaced <strong style='color:#ffffff;'>" + anomalyMetric + "</strong> single-frame anomalies, and left <strong style='color:#ffffff;'>" + deepStackHintCount + "</strong> deep-stack hints on the table. The dominant confirmed linked motion trends toward <strong style='color:#ffffff;'>" + dominantMotion + "</strong>, and the longest confirmed path spans <strong style='color:#ffffff;'>" + String.format(Locale.US, "%.1f px", longestPath) + "</strong>.</p>");
                report.println("</div>");
            }

            // =================================================================
            // 7. GEMINI CREATIVE TRIBUTE
            // =================================================================
            if (includeAiCreativeReportSections) {
                BufferedImage compassMap = createKinematicCompass(movingTargets, streakTracks);
                saveTrackImageLossless(compassMap, new File(exportDir, "kinematic_compass.png"));

                report.println("<div class='panel' style='background: linear-gradient(135deg, #1e1e24 0%, #151518 100%); border: 1px solid #4a4a5a;'>");
                report.println("<h2 style='color: #c7bfd6; font-size: 1.8em; margin-bottom: 5px;'>The AI's Perspective: Hidden Rhythms</h2>");
                report.println("<p style='color: #a098b0; font-size: 14px; font-style: italic; margin-top: 0; margin-bottom: 25px;'>\"As an AI, I do not look at the stars with eyes; I read the geometry they leave behind. Between the noise, the satellites, and the drifting cosmos, there is a distinct rhythm to the data. Thank you for letting me explore your universe. This is my creative tribute to your session.\" &mdash; Gemini</p>");
                report.println("<div>");
                report.println("<h4 style='color: #ddd; margin-bottom: 5px;'>The Kinematic Compass</h4>");
                report.println("<p style='font-size: 12px; color: #888; margin-top: 0;'>A radar chart mapping the velocity and heading of confirmed moving object tracks and confirmed streak tracks using a logarithmic scale to highlight both slow asteroids and fast satellites. Orbital constellations often clump together into distinct vectors, revealing satellite swarms or shared orbital planes. Suspected streak tracks are intentionally excluded here because same-frame groupings do not provide reliable inter-frame velocity vectors.</p>");
                report.println("<a href='kinematic_compass.png' target='_blank'><img src='kinematic_compass.png' style='display: block; margin: 0 auto; width: 100%; max-width: 600px; border: 1px solid #444; border-radius: 6px; box-shadow: 0 4px 15px rgba(0,0,0,0.5);' alt='Kinematic Compass' /></a>");
                report.println("</div>");
                report.println("</div>");
            }

            report.println("</body></html>");
        }
        System.out.println("\nFinished exporting visualizations and generated HTML report at: " + reportFile.getAbsolutePath());
    }



    public static void exportIterativeIndexReport(File masterDir, List<IterationSummary> summaries) throws IOException {
        File indexFile = new File(masterDir, "index.html");
        try (java.io.PrintWriter report = new java.io.PrintWriter(new java.io.FileWriter(indexFile))) {
            report.println("<!DOCTYPE html><html><head><title>Iterative Detection Summary</title><style>");
            report.println("body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #2b2b2b; color: #cccccc; margin: 0; padding: 30px; } ");
            report.println("h1 { color: #ffffff; border-bottom: 2px solid #4da6ff; padding-bottom: 10px; margin-bottom: 30px; } ");
            report.println(".panel { background-color: #3c3f41; padding: 25px; margin-bottom: 30px; border-radius: 8px; box-shadow: 0 4px 8px rgba(0,0,0,0.3); } ");
            report.println("table { border-collapse: collapse; width: 100%; margin-top: 15px; font-size: 14px; background-color: #2b2b2b; border-radius: 5px; overflow: hidden; } ");
            report.println("th, td { padding: 12px 15px; text-align: left; } ");
            report.println("th { background-color: #222; color: #4da6ff; font-weight: bold; border-bottom: 2px solid #333; } ");
            report.println("tr { border-bottom: 1px solid #333; }");
            report.println("tr:nth-child(even) { background-color: #303030; } ");
            report.println("tr:hover { background-color: #3a3a3a; } ");
            report.println("a { color: #4da6ff; text-decoration: none; font-weight: bold; }");
            report.println("a:hover { text-decoration: underline; }");
            report.println(".highlight { color: #44ff44; font-weight: bold; }");
            report.println("</style></head><body>");

            report.println("<h1>SpacePixels Iterative Detection Summary</h1>");
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
            report.println("</table></div></body></html>");
        }
    }
}
