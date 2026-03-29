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
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.telemetry.TrackerTelemetry;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    // --- Soft Stretch Parameters ---
    public static double softStretchBlackRatio = 0.9;
    public static double softStretchWhiteRatio = 0.8;

    // --- Export & Cropping Parameters ---
    public static int gifBlinkSpeedMs = 300;
    public static int trackCropPadding = 200;
    public static int trackObjectCentricCropSize = 200;
    public static int singleStreakExportPadding = 50;
    public static double streakElongationCropMultiplier = 10.0;
    public static boolean includeAiCreativeReportSections = false;

    // --- Annotation Tools (For GIFs) ---
    public static int targetCircleRadius = 15;
    public static float targetCircleStrokeWidth = 2.0f;

    public static final String detectionReportName = "detection_report.html";

    // =================================================================
    // DATA MODELS
    // =================================================================

    public static class FitsDataAnalysis {
        public int width;
        public int height;
        public long totalPixels;

        public short minRaw;
        public short maxRaw;
        public int countRawZero;
        public int countRawMin;
        public int countRawMax;

        public int minUnsigned;
        public int maxUnsigned;
        public int countUnsignedZero;
        public int countUnsignedMax;

        public double meanUnsigned;
        public double sigmaUnsigned;

        public double autoBlackPoint;
        public double autoWhitePoint;
    }

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

    private static class ObserverSite {
        private final double latitudeDeg;
        private final double longitudeDeg;
        private final double altitudeMeters;
        private final String sourceLabel;

        private ObserverSite(double latitudeDeg, double longitudeDeg, double altitudeMeters, String sourceLabel) {
            this.latitudeDeg = latitudeDeg;
            this.longitudeDeg = longitudeDeg;
            this.altitudeMeters = altitudeMeters;
            this.sourceLabel = sourceLabel;
        }

        private String toSkybotObserverUriSpec() {
            return formatSignedCoordinate(latitudeDeg, 6) + "," + formatSignedCoordinate(longitudeDeg, 6) + "," + formatDecimal(altitudeMeters, 1);
        }

        private String toSkybotObserverFreeFormatSpec() {
            return formatSignedCoordinate(longitudeDeg, 6) + " " + formatSignedCoordinate(latitudeDeg, 6) + " " + formatDecimal(altitudeMeters, 1);
        }
    }

    private static class ReportAstrometryContext {
        private final FitsFileInformation[] fitsFiles;
        private final WcsSolutionResolver.ResolvedWcsSolution wcsSolution;
        private final ObserverSite observerSite;
        private final String skybotObserverCode;
        private final String skybotObserverSource;
        private final long sessionMidpointTimestampMillis;

        private ReportAstrometryContext(FitsFileInformation[] fitsFiles,
                                        WcsSolutionResolver.ResolvedWcsSolution wcsSolution,
                                        ObserverSite observerSite,
                                        String skybotObserverCode,
                                        String skybotObserverSource,
                                        long sessionMidpointTimestampMillis) {
            this.fitsFiles = fitsFiles;
            this.wcsSolution = wcsSolution;
            this.observerSite = observerSite;
            this.skybotObserverCode = skybotObserverCode;
            this.skybotObserverSource = skybotObserverSource;
            this.sessionMidpointTimestampMillis = sessionMidpointTimestampMillis;
        }

        private WcsCoordinateTransformer getTransformer() {
            return wcsSolution != null ? wcsSolution.getTransformer() : null;
        }

        private boolean hasAstrometricSolution() {
            return getTransformer() != null;
        }

        private String getWcsSummary() {
            if (wcsSolution == null) {
                return "No aligned-frame WCS solution available";
            }
            String scope = wcsSolution.isSharedAcrossAlignedSet() ? "shared aligned WCS" : "native frame WCS";
            return scope + " from " + escapeHtml(wcsSolution.getSourceFileName()) + " (" + escapeHtml(wcsSolution.getSourceType()) + ")";
        }

        private long getFrameTimestampMillis(int frameIndex) {
            if (fitsFiles == null || frameIndex < 0 || frameIndex >= fitsFiles.length || fitsFiles[frameIndex] == null) {
                return -1L;
            }
            return fitsFiles[frameIndex].getObservationTimestamp();
        }

        private boolean hasSkybotObserverCode() {
            return skybotObserverCode != null && !skybotObserverCode.isEmpty();
        }

        private String getPreferredSkybotObserver() {
            return hasSkybotObserverCode() ? skybotObserverCode : "500";
        }
    }

    private static class SolarSystemQueryTarget {
        private final double pixelX;
        private final double pixelY;
        private final long timestampMillis;
        private final double searchRadiusDegrees;
        private final String summaryLabel;

        private SolarSystemQueryTarget(double pixelX, double pixelY, long timestampMillis, double searchRadiusDegrees, String summaryLabel) {
            this.pixelX = pixelX;
            this.pixelY = pixelY;
            this.timestampMillis = timestampMillis;
            this.searchRadiusDegrees = searchRadiusDegrees;
            this.summaryLabel = summaryLabel;
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

    private static BufferedImage createCroppedMasterMaskOverlay(short[][] masterStackData,
                                                                boolean[][] masterMask,
                                                                int cx,
                                                                int cy,
                                                                int cropWidth,
                                                                int cropHeight,
                                                                SourceExtractor.DetectedObject highlightDetection) {
        short[][] croppedMasterData = robustEdgeAwareCrop(masterStackData, cx, cy, cropWidth, cropHeight);
        BufferedImage grayImage = createDisplayImage(croppedMasterData);
        BufferedImage image = new BufferedImage(cropWidth, cropHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        g2d.drawImage(grayImage, 0, 0, null);

        boolean transposed = isMasterMaskTransposed(masterMask, masterStackData[0].length, masterStackData.length);
        int halfWidth = cropWidth / 2;
        int halfHeight = cropHeight / 2;

        for (int y = 0; y < cropHeight; y++) {
            int sourceY = cy - halfHeight + y;
            for (int x = 0; x < cropWidth; x++) {
                int sourceX = cx - halfWidth + x;
                if (isMasterMaskSet(masterMask, sourceX, sourceY, transposed)) {
                    image.setRGB(x, y, new Color(255, 32, 32).getRGB());
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

    private static BufferedImage createDisplayImageSoft(short[][] imageData) {
        int height = imageData.length;
        int width = imageData[0].length;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = image.getRaster();

        long sum = 0;
        int min = 65535;
        int max = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int val = imageData[y][x] + 32768;
                if (val < min) min = val;
                if (val > max) max = val;
                sum += val;
            }
        }

        double mean = (double) sum / (width * height);

        double blackPoint = min + ((mean - min) * softStretchBlackRatio);
        double whitePoint = min + ((max - min) * softStretchWhiteRatio);

        double range = whitePoint - blackPoint;
        if (range <= 0) range = 1;

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

    private static BufferedImage cropRegionToImage(short[][] fullImage, int cx, int cy, int cropWidth, int cropHeight) {
        int halfWidth = cropWidth / 2;
        int halfHeight = cropHeight / 2;

        short[][] croppedData = new short[cropHeight][cropWidth];
        int imgHeight = fullImage.length;
        int imgWidth = fullImage[0].length;

        for (int y = 0; y < cropHeight; y++) {
            for (int x = 0; x < cropWidth; x++) {
                int sourceY = cy - halfHeight + y;
                int sourceX = cx - halfWidth + x;

                if (sourceY >= 0 && sourceY < imgHeight && sourceX >= 0 && sourceX < imgWidth) {
                    croppedData[y][x] = fullImage[sourceY][sourceX];
                } else {
                    croppedData[y][x] = 0;
                }
            }
        }

        return createDisplayImage(croppedData);
    }

    private static BufferedImage exportSingleStreak(short[][] rawImage, SourceExtractor.DetectedObject streak) {
        int estimatedLength = (int) (streak.elongation * streakElongationCropMultiplier) + singleStreakExportPadding;
        int cropSize = Math.min(estimatedLength, Math.max(rawImage.length, rawImage[0].length));

        int cx = (int) Math.round(streak.x);
        int cy = (int) Math.round(streak.y);

        return cropRegionToImage(rawImage, cx, cy, cropSize, cropSize);
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
    private static BufferedImage createGlobalTrackMap(short[][] backgroundData,
                                                     List<TrackLinker.Track> anomalies,
                                                     List<TrackLinker.Track> singleStreaks,
                                                     List<TrackLinker.Track> streakTracks,
                                                     List<TrackLinker.Track> movingTargets) {
        BufferedImage grayBg = createDisplayImage(backgroundData);
        BufferedImage rgbMap = new BufferedImage(grayBg.getWidth(), grayBg.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = rgbMap.createGraphics();
        g2d.drawImage(grayBg, 0, 0, null);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int aCounter = 1;
        for (TrackLinker.Track t : anomalies) {
            SourceExtractor.DetectedObject pt = t.points.get(0);
            int cx = (int) Math.round(pt.x), cy = (int) Math.round(pt.y);
            g2d.setColor(new Color(255, 51, 255)); g2d.setStroke(new BasicStroke(2.5f));
            g2d.drawOval(cx - 20, cy - 20, 40, 40);
            g2d.setFont(new Font("Segoe UI", Font.BOLD, 18)); g2d.drawString("A" + aCounter, cx + 25, cy - 25);
            aCounter++;
        }

        int sCounter = 1;
        for (TrackLinker.Track t : singleStreaks) {
            SourceExtractor.DetectedObject pt = t.points.get(0);
            int cx = (int) Math.round(pt.x), cy = (int) Math.round(pt.y);
            g2d.setColor(new Color(255, 153, 51)); g2d.setStroke(new BasicStroke(2.5f));
            g2d.drawOval(cx - 20, cy - 20, 40, 40);
            g2d.setFont(new Font("Segoe UI", Font.BOLD, 18)); g2d.drawString("S" + sCounter, cx + 25, cy - 25);
            sCounter++;
        }
        int stCounter = 1;
        for (TrackLinker.Track t : streakTracks) {
            drawMultiFrameTrack(g2d, t, new Color(255, 204, 51), "ST" + stCounter);
            stCounter++;
        }
        int tCounter = 1;
        for (TrackLinker.Track t : movingTargets) {
            drawMultiFrameTrack(g2d, t, new Color(77, 166, 255), "T" + tCounter);
            tCounter++;
        }
        g2d.dispose();
        return rgbMap;
    }

    private static void drawMultiFrameTrack(Graphics2D g2d, TrackLinker.Track track, Color lineColor, String label) {
        g2d.setColor(lineColor); g2d.setStroke(new BasicStroke(2.0f));
        for (int i = 0; i < track.points.size() - 1; i++) {
            SourceExtractor.DetectedObject p1 = track.points.get(i);
            SourceExtractor.DetectedObject p2 = track.points.get(i + 1);
            g2d.drawLine((int) Math.round(p1.x), (int) Math.round(p1.y), (int) Math.round(p2.x), (int) Math.round(p2.y));
        }
        g2d.setColor(new Color(255, 80, 80));
        for (SourceExtractor.DetectedObject pt : track.points) {
            g2d.fillOval((int) Math.round(pt.x) - 4, (int) Math.round(pt.y) - 4, 8, 8);
        }
        g2d.setColor(Color.WHITE); g2d.setFont(new Font("Segoe UI", Font.BOLD, 18));
        g2d.drawString(label, (int) Math.round(track.points.get(0).x) + 15, (int) Math.round(track.points.get(0).y) - 15);
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
        g2d.drawString("Point Targets", legendX + 30, legendY + 4);
        
        // Streak legend
        g2d.setColor(new Color(255, 204, 102));
        g2d.drawLine(legendX, legendY + 20, legendX + 20, legendY + 20);
        g2d.fillOval(legendX + 17, legendY + 17, 6, 6);
        g2d.setColor(Color.WHITE);
        g2d.drawString("Streak Targets", legendX + 30, legendY + 24);

        g2d.dispose();
        return img;
    }

    private static BufferedImage createCreativeTributeImage(short[][] backgroundData,
                                                            List<List<SourceExtractor.DetectedObject>> allTransients,
                                                            List<TrackLinker.Track> anomalies,
                                                            List<TrackLinker.Track> singleStreaks,
                                                            List<TrackLinker.Track> streakTracks,
                                                            List<TrackLinker.Track> movingTargets,
                                                            List<SourceExtractor.DetectedObject> slowMoverCandidates,
                                                            List<SourceExtractor.DetectedObject> masterMaximumStackTransientStreaks,
                                                            PipelineTelemetry pipelineTelemetry) {
        CreativeTributeLayout layout = createCreativeTributeLayout(
                backgroundData,
                allTransients,
                anomalies,
                singleStreaks,
                streakTracks,
                movingTargets,
                slowMoverCandidates,
                masterMaximumStackTransientStreaks
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
        for (TrackLinker.Track track : anomalies) {
            if (track.points != null && !track.points.isEmpty()) {
                drawCreativePulse(g2d, track.points.get(0), layout, new Color(255, 102, 204));
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
        if (masterMaximumStackTransientStreaks != null) {
            for (SourceExtractor.DetectedObject streak : masterMaximumStackTransientStreaks) {
                drawCreativeMeasuredStreak(g2d, streak, layout, new Color(255, 214, 112), 1.35);
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
        int deepStackHintCount = (slowMoverCandidates == null ? 0 : slowMoverCandidates.size())
                + (masterMaximumStackTransientStreaks == null ? 0 : masterMaximumStackTransientStreaks.size());
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
        g2d.drawString("Flashes: " + anomalies.size() + " | Single streaks: " + singleStreaks.size() + " | Deep-stack hints: " + deepStackHintCount, textX, y);
        y += 24;
        g2d.drawString("Dominant motion: " + dominantMotion + " | Longest linked path: " + String.format(Locale.US, "%.1f px", longestPath), textX, y);

        int legendHeight = Math.min(layout.headerHeight - (panelPadding * 2), 186);
        int legendX = panelPadding + leftPanelWidth + interPanelGap;
        int legendY = panelPadding;
        g2d.setColor(new Color(10, 10, 16, 175));
        g2d.fillRoundRect(legendX, legendY, legendWidth, legendHeight, 22, 22);
        g2d.setColor(new Color(77, 166, 255, 120));
        g2d.drawRoundRect(legendX, legendY, legendWidth, legendHeight, 22, 22);

        g2d.setFont(new Font("Segoe UI", Font.BOLD, Math.max(14, Math.min(18, layout.outputWidth / 85))));
        g2d.setColor(Color.WHITE);
        g2d.drawString("What the colors and symbols mean", legendX + 18, legendY + 28);

        int legendRowY = legendY + 58;
        Font legendFont = new Font("Segoe UI", Font.PLAIN, Math.max(12, Math.min(15, layout.outputWidth / 95)));
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(66, 210, 255), "Linked mover track (line + nodes)", legendFont, CreativeLegendGlyph.TRACK);
        legendRowY += 26;
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(255, 204, 102), "Streak paths / streak markers", legendFont, CreativeLegendGlyph.STREAK);
        legendRowY += 26;
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(255, 102, 204), "Anomaly pulse (circle + crosshair)", legendFont, CreativeLegendGlyph.PULSE);
        legendRowY += 26;
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(186, 122, 255), "Deep-stack hint (diamond)", legendFont, CreativeLegendGlyph.DIAMOND);
        legendRowY += 26;
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
                                                                     List<TrackLinker.Track> anomalies,
                                                                     List<TrackLinker.Track> singleStreaks,
                                                                     List<TrackLinker.Track> streakTracks,
                                                                     List<TrackLinker.Track> movingTargets,
                                                                     List<SourceExtractor.DetectedObject> slowMoverCandidates,
                                                                     List<SourceExtractor.DetectedObject> masterMaximumStackTransientStreaks) {
        int imageWidth = backgroundData[0].length;
        int imageHeight = backgroundData.length;

        double[] bounds = new double[]{Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        includeCreativeTrackBounds(bounds, anomalies);
        includeCreativeTrackBounds(bounds, singleStreaks);
        includeCreativeTrackBounds(bounds, streakTracks);
        includeCreativeTrackBounds(bounds, movingTargets);
        includeCreativeDetectionBounds(bounds, slowMoverCandidates);
        includeCreativeDetectionBounds(bounds, masterMaximumStackTransientStreaks);

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
        int headerHeight = Math.max(230, Math.min(280, outputWidth / 4));
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
                                                     ReportAstrometryContext astrometryContext,
                                                     short[][] primaryStackData,
                                                     String primaryStackLabel,
                                                     String primaryStackFileName,
                                                     short[][] masterStackData,
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
        report.println("<div class='image-container' style='margin-bottom: 10px;'>");
        if (masterFileName != null) {
            report.println("<div><a href='" + masterFileName + "' target='_blank'><img src='" + masterFileName + "' style='max-width: 150px;' alt='Median Stack Crop' /></a><br/><center><small>Median Stack</small></center></div>");
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
        report.println("<div style='font-family: monospace; font-size: 12px; color: #aaa;'>" + escapeHtml(formatPixelCoordinateWithSky(astrometryContext, detection.x, detection.y)) + "<br>Elongation: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f", detection.elongation) + "</span><br>Pixels: <span style='color:#fff;'>" + (int) detection.pixelArea + "</span></div>");
        report.print(buildDeepStackIdentificationHtml(astrometryContext, detection));
        report.println("</div>");
    }

    private static ReportAstrometryContext buildReportAstrometryContext(FitsFileInformation[] fitsFiles, AppConfig appConfig) {
        WcsSolutionResolver.ResolvedWcsSolution wcsSolution = WcsSolutionResolver.resolve(null, fitsFiles);
        ObserverSite observerSite = resolveObserverSite(fitsFiles, appConfig);
        String observerCode = resolveSkybotObserverCode(appConfig);
        String observerCodeSource = observerCode != null ? "Configuration panel" : null;
        long sessionMidpointTimestampMillis = resolveSessionMidpointTimestamp(fitsFiles);
        return new ReportAstrometryContext(fitsFiles, wcsSolution, observerSite, observerCode, observerCodeSource, sessionMidpointTimestampMillis);
    }

    private static String resolveSkybotObserverCode(AppConfig appConfig) {
        if (appConfig == null || appConfig.observatoryCode == null) {
            return null;
        }
        String code = appConfig.observatoryCode.trim();
        if (code.isEmpty()) {
            return null;
        }
        return code.toUpperCase(Locale.US);
    }

    private static ObserverSite resolveObserverSite(FitsFileInformation[] fitsFiles, AppConfig appConfig) {
        if (fitsFiles != null) {
            for (FitsFileInformation file : fitsFiles) {
                if (file == null) {
                    continue;
                }
                Double latitude = parseCoordinateValue(getFirstHeaderValue(file, "SITELAT", "OBSGEO-B", "LAT-OBS"));
                Double longitude = parseCoordinateValue(getFirstHeaderValue(file, "SITELONG", "SITELON", "OBSGEO-L", "LONG-OBS", "LON-OBS"));
                if (latitude != null && longitude != null) {
                    Double altitude = parseDoubleValue(getFirstHeaderValue(file, "SITEELEV", "OBSALT", "ALT-OBS", "ELEVATIO"));
                    return new ObserverSite(latitude, longitude, altitude != null ? altitude : 0.0, "FITS header");
                }
            }
        }

        if (appConfig != null) {
            Double latitude = parseCoordinateValue(appConfig.siteLat);
            Double longitude = parseCoordinateValue(appConfig.siteLong);
            if (latitude != null && longitude != null) {
                return new ObserverSite(latitude, longitude, 0.0, "Configuration panel");
            }
        }

        return null;
    }

    private static long resolveSessionMidpointTimestamp(FitsFileInformation[] fitsFiles) {
        if (fitsFiles == null || fitsFiles.length == 0) {
            return -1L;
        }

        List<Long> timestamps = new ArrayList<>();
        for (FitsFileInformation file : fitsFiles) {
            if (file == null) {
                continue;
            }
            long timestamp = file.getObservationTimestamp();
            if (timestamp > 0L) {
                timestamps.add(timestamp);
            }
        }

        if (timestamps.isEmpty()) {
            return -1L;
        }

        return timestamps.get(timestamps.size() / 2);
    }

    private static String buildDeepStackIdentificationHtml(ReportAstrometryContext astrometryContext,
                                                           SourceExtractor.DetectedObject detection) {
        SolarSystemQueryTarget queryTarget = buildSingleDetectionQueryTarget(astrometryContext, detection);
        return buildSolarSystemIdentificationHtml(astrometryContext, queryTarget,
                "Reference epoch for stack lookup",
                "Search SkyBoT",
                "SkyBoT search radius");
    }

    private static String buildTrackSolarSystemIdentificationHtml(ReportAstrometryContext astrometryContext,
                                                                  TrackLinker.Track track) {
        SolarSystemQueryTarget queryTarget = buildTrackQueryTarget(astrometryContext, track);
        return buildSolarSystemIdentificationHtml(astrometryContext, queryTarget,
                "Reference epoch for track lookup",
                "Search SkyBoT (Track Midpoint)",
                "SkyBoT search radius");
    }

    private static String buildSingleFrameSkyViewerHtml(ReportAstrometryContext astrometryContext,
                                                        SourceExtractor.DetectedObject detection,
                                                        String epochLabel) {
        SolarSystemQueryTarget queryTarget = buildFrameDetectionQueryTarget(astrometryContext, detection);
        return buildSkyViewerLinksHtml(astrometryContext, queryTarget, epochLabel);
    }

    private static String buildTrackSkyViewerHtml(ReportAstrometryContext astrometryContext,
                                                  TrackLinker.Track track,
                                                  String epochLabel) {
        SolarSystemQueryTarget queryTarget = buildTrackQueryTarget(astrometryContext, track);
        return buildSkyViewerLinksHtml(astrometryContext, queryTarget, epochLabel);
    }

    private static String buildSolarSystemIdentificationHtml(ReportAstrometryContext astrometryContext,
                                                             SolarSystemQueryTarget queryTarget,
                                                             String epochLabel,
                                                             String buttonLabel,
                                                             String radiusLabel) {
        StringBuilder html = new StringBuilder();
        html.append("<div class='astro-note'>");

        if (astrometryContext == null || !astrometryContext.hasAstrometricSolution()) {
            html.append("Solar-system identification link unavailable: no reusable aligned-frame WCS solution was found for this session.");
            html.append("</div>");
            return html.toString();
        }

        if (queryTarget == null) {
            html.append("Solar-system identification link unavailable: no valid timestamp could be resolved for this detection.");
            html.append("</div>");
            return html.toString();
        }

        WcsCoordinateTransformer.SkyCoordinate skyCoordinate = astrometryContext.getTransformer().pixelToSky(queryTarget.pixelX, queryTarget.pixelY);
        html.append("Sky position: <span class='coord-highlight'>RA ")
                .append(escapeHtml(WcsCoordinateTransformer.formatRa(skyCoordinate.getRaDegrees())))
                .append(" | Dec ")
                .append(escapeHtml(WcsCoordinateTransformer.formatDec(skyCoordinate.getDecDegrees())))
                .append("</span>");
        if (queryTarget.summaryLabel != null && !queryTarget.summaryLabel.isEmpty()) {
            html.append("<br>").append(escapeHtml(queryTarget.summaryLabel)).append(".");
        }
        html.append("<br>WCS source: ").append(astrometryContext.getWcsSummary()).append(".");

        String epochText = formatUtcTimestamp(queryTarget.timestampMillis);
        if (queryTarget.timestampMillis > 0L) {
            html.append("<br>").append(escapeHtml(epochLabel)).append(": ").append(escapeHtml(epochText)).append(".");
        } else {
            html.append("<br>Reference epoch unavailable: no valid frame timestamps were found in the FITS headers.");
        }

        if (astrometryContext.hasSkybotObserverCode()) {
            html.append("<br>Observer used for lookup: IAU code ")
                    .append(escapeHtml(astrometryContext.skybotObserverCode))
                    .append(" (")
                    .append(escapeHtml(astrometryContext.skybotObserverSource))
                    .append(").");
        } else {
            html.append("<br>Observer used for lookup: geocenter (500).");
        }
        if (astrometryContext.observerSite != null) {
            html.append("<br>Known site coordinates: ")
                    .append(escapeHtml(String.format(Locale.US, "%.5f, %.5f (%s)",
                            astrometryContext.observerSite.latitudeDeg,
                            astrometryContext.observerSite.longitudeDeg,
                            astrometryContext.observerSite.sourceLabel)))
                    .append(".");
        }

        String nominalSkybotUrl = buildSkybotUrl(astrometryContext, queryTarget, queryTarget.searchRadiusDegrees, false);
        if (nominalSkybotUrl != null) {
            double nominalRadiusArcmin = queryTarget.searchRadiusDegrees * 60.0;
            double widerRadiusDegrees = Math.min(queryTarget.searchRadiusDegrees * 2.5, 2.0);
            double muchWiderRadiusDegrees = Math.min(queryTarget.searchRadiusDegrees * 6.0, 5.0);
            html.append("<div class='id-links'>");
            html.append("<a class='id-link' href='").append(nominalSkybotUrl).append("' target='_blank' rel='noopener noreferrer'>").append(escapeHtml(buttonLabel)).append("</a>");
            html.append("<a class='id-link' href='").append(buildSkybotUrl(astrometryContext, queryTarget, widerRadiusDegrees, false)).append("' target='_blank' rel='noopener noreferrer'>SkyBoT Wide Cone</a>");
            html.append("<a class='id-link' href='").append(buildSkybotUrl(astrometryContext, queryTarget, muchWiderRadiusDegrees, false)).append("' target='_blank' rel='noopener noreferrer'>SkyBoT Much Wider Cone</a>");
            if (astrometryContext.hasSkybotObserverCode()) {
                html.append("<a class='id-link' href='").append(buildSkybotUrl(astrometryContext, queryTarget, queryTarget.searchRadiusDegrees, true)).append("' target='_blank' rel='noopener noreferrer'>SkyBoT Geocenter</a>");
            }
            html.append("</div>");
            html.append("<div class='astro-note' style='margin-top: 6px;'>")
                    .append(escapeHtml(radiusLabel)).append(": ")
                    .append(escapeHtml(String.format(Locale.US, "%.2f arcmin", nominalRadiusArcmin)))
                    .append(" | Wide: ")
                    .append(escapeHtml(String.format(Locale.US, "%.2f arcmin", widerRadiusDegrees * 60.0)))
                    .append(" | Much wider: ")
                    .append(escapeHtml(String.format(Locale.US, "%.2f arcmin", muchWiderRadiusDegrees * 60.0)))
                    .append(" (asteroids + comets).</div>");
        }

        html.append(buildSkyViewerLinksHtml(astrometryContext, queryTarget, epochLabel));

        html.append("</div>");
        return html.toString();
    }

    private static SolarSystemQueryTarget buildSingleDetectionQueryTarget(ReportAstrometryContext astrometryContext,
                                                                          SourceExtractor.DetectedObject detection) {
        if (astrometryContext == null || detection == null) {
            return null;
        }

        long timestampMillis = astrometryContext.sessionMidpointTimestampMillis;
        double radiusDegrees = estimateSkybotSearchRadiusDegrees(astrometryContext, detection.x, detection.y, detection.pixelArea, detection.elongation);
        return new SolarSystemQueryTarget(detection.x, detection.y, timestampMillis, radiusDegrees, null);
    }

    private static SolarSystemQueryTarget buildFrameDetectionQueryTarget(ReportAstrometryContext astrometryContext,
                                                                         SourceExtractor.DetectedObject detection) {
        if (astrometryContext == null || detection == null) {
            return null;
        }

        long timestampMillis = astrometryContext.getFrameTimestampMillis(detection.sourceFrameIndex);
        if (timestampMillis <= 0L) {
            timestampMillis = astrometryContext.sessionMidpointTimestampMillis;
        }

        double radiusDegrees = estimateSkybotSearchRadiusDegrees(astrometryContext, detection.x, detection.y, detection.pixelArea, detection.elongation);
        return new SolarSystemQueryTarget(detection.x, detection.y, timestampMillis, radiusDegrees, "Single-frame detection");
    }

    private static SolarSystemQueryTarget buildTrackQueryTarget(ReportAstrometryContext astrometryContext,
                                                                TrackLinker.Track track) {
        if (astrometryContext == null || track == null || track.points == null || track.points.isEmpty()) {
            return null;
        }

        double sumX = 0.0;
        double sumY = 0.0;
        double sumArea = 0.0;
        double sumElongation = 0.0;
        int count = 0;
        long timestampAccumulator = 0L;
        int timestampCount = 0;

        for (SourceExtractor.DetectedObject point : track.points) {
            if (point == null) {
                continue;
            }
            sumX += point.x;
            sumY += point.y;
            sumArea += Math.max(1.0, point.pixelArea);
            sumElongation += Math.max(1.0, point.elongation);
            count++;

            long timestampMillis = astrometryContext.getFrameTimestampMillis(point.sourceFrameIndex);
            if (timestampMillis > 0L) {
                timestampAccumulator += timestampMillis;
                timestampCount++;
            }
        }

        if (count == 0) {
            return null;
        }

        long timestampMillis = timestampCount > 0 ? Math.round((double) timestampAccumulator / timestampCount) : -1L;
        double pixelX = sumX / count;
        double pixelY = sumY / count;
        double avgArea = sumArea / count;
        double avgElongation = sumElongation / count;
        double radiusDegrees = estimateSkybotSearchRadiusDegrees(astrometryContext, pixelX, pixelY, avgArea, avgElongation);
        return new SolarSystemQueryTarget(pixelX, pixelY, timestampMillis, radiusDegrees,
                "Track midpoint from " + count + " detections");
    }

    private static String buildSkybotUrl(ReportAstrometryContext astrometryContext,
                                         SolarSystemQueryTarget queryTarget,
                                         double searchRadiusDegrees,
                                         boolean forceGeocenterObserver) {
        if (astrometryContext == null || !astrometryContext.hasAstrometricSolution() || queryTarget == null || queryTarget.timestampMillis <= 0L) {
            return null;
        }

        WcsCoordinateTransformer.SkyCoordinate skyCoordinate = astrometryContext.getTransformer().pixelToSky(queryTarget.pixelX, queryTarget.pixelY);
        String observer = forceGeocenterObserver ? "500" : astrometryContext.getPreferredSkybotObserver();

        StringBuilder url = new StringBuilder("https://ssp.imcce.fr/webservices/skybot/api/conesearch.php?");
        url.append("-ep=").append(urlEncode(formatSkybotTimestamp(queryTarget.timestampMillis)));
        url.append("&-ra=").append(urlEncode(formatDecimal(skyCoordinate.getRaDegrees(), 6)));
        url.append("&-dec=").append(urlEncode(formatDecimal(skyCoordinate.getDecDegrees(), 6)));
        url.append("&-rd=").append(urlEncode(formatDecimal(searchRadiusDegrees, 6)));
        url.append("&-mime=html");
        url.append("&-output=all");
        url.append("&-observer=").append(urlEncode(observer));
        url.append("&-objFilter=101");
        url.append("&-from=SpacePixels");
        return url.toString();
    }

    private static String buildSkyViewerLinksHtml(ReportAstrometryContext astrometryContext,
                                                  SolarSystemQueryTarget queryTarget,
                                                  String epochLabel) {
        StringBuilder html = new StringBuilder();
        html.append("<div class='astro-note' style='margin-top: 8px;'>");

        if (astrometryContext == null || !astrometryContext.hasAstrometricSolution()) {
            html.append("Interactive sky-viewer links unavailable: no reusable aligned-frame WCS solution was found for this session.");
            html.append("</div>");
            return html.toString();
        }

        if (queryTarget == null || queryTarget.timestampMillis <= 0L) {
            html.append("Interactive sky-viewer links unavailable: no valid timestamp could be resolved for this detection.");
            html.append("</div>");
            return html.toString();
        }

        WcsCoordinateTransformer.SkyCoordinate skyCoordinate = astrometryContext.getTransformer().pixelToSky(queryTarget.pixelX, queryTarget.pixelY);
        double tightFovDegrees = clampFieldOfViewDegrees(queryTarget.searchRadiusDegrees * 8.0, 0.5, 12.0);
        double wideFovDegrees = clampFieldOfViewDegrees(queryTarget.searchRadiusDegrees * 22.0, 4.0, 60.0);

        html.append("Interactive sky viewers:");
        html.append("<div class='id-links'>");
        html.append("<a class='id-link' href='")
                .append(buildStellariumWebUrl(astrometryContext, queryTarget, skyCoordinate, tightFovDegrees))
                .append("' target='_blank' rel='noopener noreferrer'>Stellarium Web (RA/Dec)</a>");
        html.append("<a class='id-link' href='")
                .append(buildStellariumWebUrl(astrometryContext, queryTarget, skyCoordinate, wideFovDegrees))
                .append("' target='_blank' rel='noopener noreferrer'>Stellarium Web Wide</a>");
        html.append("</div>");

        html.append("<div class='astro-note' style='margin-top: 6px;'>")
                .append("Stellarium links use best-effort RA/Dec URL parameters at ")
                .append(escapeHtml(formatUtcTimestamp(queryTarget.timestampMillis)))
                .append(" with FOV ")
                .append(escapeHtml(String.format(Locale.US, "%.2f° / %.2f°", tightFovDegrees, wideFovDegrees)))
                .append(".");
        if (epochLabel != null && !epochLabel.isEmpty()) {
            html.append(" ").append(escapeHtml(epochLabel)).append(": ")
                    .append(escapeHtml(formatUtcTimestamp(queryTarget.timestampMillis)))
                    .append(".");
        }
        html.append("</div>");
        html.append("</div>");
        return html.toString();
    }

    private static String buildStellariumWebUrl(ReportAstrometryContext astrometryContext,
                                                SolarSystemQueryTarget queryTarget,
                                                WcsCoordinateTransformer.SkyCoordinate skyCoordinate,
                                                double fovDegrees) {
        StringBuilder url = new StringBuilder("https://stellarium-web.org/?");
        url.append("date=").append(urlEncode(formatStellariumTimestamp(queryTarget.timestampMillis)));
        url.append("&ra=").append(urlEncode(formatDecimal(skyCoordinate.getRaDegrees(), 6)));
        url.append("&dec=").append(urlEncode(formatDecimal(skyCoordinate.getDecDegrees(), 6)));
        url.append("&fov=").append(urlEncode(formatDecimal(fovDegrees, 4)));
        if (astrometryContext != null && astrometryContext.observerSite != null) {
            url.append("&lat=").append(urlEncode(formatDecimal(astrometryContext.observerSite.latitudeDeg, 5)));
            url.append("&lng=").append(urlEncode(formatDecimal(astrometryContext.observerSite.longitudeDeg, 5)));
            url.append("&elev=").append(urlEncode(formatDecimal(astrometryContext.observerSite.altitudeMeters, 1)));
        }
        return url.toString();
    }

    private static double estimateSkybotSearchRadiusDegrees(ReportAstrometryContext astrometryContext,
                                                            double pixelX,
                                                            double pixelY,
                                                            double pixelArea,
                                                            double elongation) {
        double pixelScaleArcsec = estimateLocalPixelScaleArcsec(astrometryContext.getTransformer(), pixelX, pixelY);
        if (!Double.isFinite(pixelScaleArcsec) || pixelScaleArcsec <= 0.0) {
            pixelScaleArcsec = 2.0;
        }

        double objectRadiusPixels = pixelArea > 0
                ? Math.sqrt((pixelArea * Math.max(elongation, 1.0)) / Math.PI)
                : 15.0;
        double radiusArcsec = Math.max(90.0, objectRadiusPixels * pixelScaleArcsec * 6.0);
        radiusArcsec = Math.min(radiusArcsec, 900.0);
        return radiusArcsec / 3600.0;
    }

    private static double estimateLocalPixelScaleArcsec(WcsCoordinateTransformer transformer, double pixelX, double pixelY) {
        if (transformer == null) {
            return Double.NaN;
        }

        WcsCoordinateTransformer.SkyCoordinate center = transformer.pixelToSky(pixelX, pixelY);
        WcsCoordinateTransformer.SkyCoordinate offsetX = transformer.pixelToSky(pixelX + 1.0, pixelY);
        WcsCoordinateTransformer.SkyCoordinate offsetY = transformer.pixelToSky(pixelX, pixelY + 1.0);
        double stepX = angularSeparationArcsec(center.getRaDegrees(), center.getDecDegrees(), offsetX.getRaDegrees(), offsetX.getDecDegrees());
        double stepY = angularSeparationArcsec(center.getRaDegrees(), center.getDecDegrees(), offsetY.getRaDegrees(), offsetY.getDecDegrees());
        return (stepX + stepY) / 2.0;
    }

    private static double angularSeparationArcsec(double ra1Deg, double dec1Deg, double ra2Deg, double dec2Deg) {
        double ra1 = Math.toRadians(ra1Deg);
        double dec1 = Math.toRadians(dec1Deg);
        double ra2 = Math.toRadians(ra2Deg);
        double dec2 = Math.toRadians(dec2Deg);
        double sinHalfDec = Math.sin((dec2 - dec1) / 2.0);
        double sinHalfRa = Math.sin((ra2 - ra1) / 2.0);
        double a = (sinHalfDec * sinHalfDec) + (Math.cos(dec1) * Math.cos(dec2) * sinHalfRa * sinHalfRa);
        double c = 2.0 * Math.asin(Math.min(1.0, Math.sqrt(Math.max(0.0, a))));
        return Math.toDegrees(c) * 3600.0;
    }

    private static String getFirstHeaderValue(FitsFileInformation file, String... keys) {
        if (file == null || file.getFitsHeader() == null || keys == null) {
            return null;
        }

        for (String key : keys) {
            String value = file.getFitsHeader().get(key);
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static Double parseCoordinateValue(String rawValue) {
        if (rawValue == null) {
            return null;
        }

        String cleaned = rawValue.replace("'", "").trim().toUpperCase(Locale.US);
        if (cleaned.isEmpty()) {
            return null;
        }

        boolean forceNegative = cleaned.endsWith("S") || cleaned.endsWith("W");
        boolean forcePositive = cleaned.endsWith("N") || cleaned.endsWith("E");
        if (forceNegative || forcePositive) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }

        cleaned = cleaned.replace(",", ".");
        Double parsed = parseDoubleValue(cleaned);
        if (parsed == null) {
            return null;
        }
        if (forceNegative) {
            return -Math.abs(parsed);
        }
        if (forcePositive) {
            return Math.abs(parsed);
        }
        return parsed;
    }

    private static Double parseDoubleValue(String rawValue) {
        if (rawValue == null) {
            return null;
        }

        String cleaned = rawValue.replace("'", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }

        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String formatSkybotTimestamp(long timestampMillis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(timestampMillis));
    }

    private static String formatStellariumTimestamp(long timestampMillis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(timestampMillis));
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

    private static String buildAnomalyMetricsText(SourceExtractor.DetectedObject detection) {
        return String.format(
                Locale.US,
                "Peak Sigma: %.2f, Flux: %.1f, Pixels: %d, Elongation: %.2f",
                detection.peakSigma,
                detection.totalFlux,
                (int) detection.pixelArea,
                detection.elongation);
    }

    private static String buildTrackTimingSummaryHtml(TrackLinker.Track track) {
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
            if (point.timestamp <= 0L) {
                continue;
            }
            long startTimestamp = point.timestamp;
            long endTimestamp = point.timestamp + Math.max(point.exposureDuration, 0L);
            if (firstStartTimestamp < 0L) {
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

    private static String formatSignedCoordinate(double value, int decimals) {
        if (value > 0.0) {
            return "+" + formatDecimal(value, decimals);
        }
        if (value == 0.0d) {
            return "+0";
        }
        return formatDecimal(value, decimals);
    }

    private static double clampFieldOfViewDegrees(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String formatPixelCoordinateWithSky(ReportAstrometryContext astrometryContext, double pixelX, double pixelY) {
        String pixel = String.format(Locale.US, "X: %.1f, Y: %.1f", pixelX, pixelY);
        String sky = formatSkyCoordinateInline(astrometryContext, pixelX, pixelY);
        return sky == null ? pixel : pixel + " | " + sky;
    }

    private static String formatPixelCoordinateWithSky(ReportAstrometryContext astrometryContext, int pixelX, int pixelY) {
        String pixel = String.format(Locale.US, "[%d, %d]", pixelX, pixelY);
        String sky = formatSkyCoordinateInline(astrometryContext, pixelX, pixelY);
        return sky == null ? pixel : pixel + " | " + sky;
    }

    private static String formatPixelCoordinateOnly(int pixelX, int pixelY) {
        return String.format(Locale.US, "[%d, %d]", pixelX, pixelY);
    }

    private static String formatPixelCoordinateBlockHtml(ReportAstrometryContext astrometryContext, double pixelX, double pixelY) {
        StringBuilder html = new StringBuilder();
        html.append("<span class='coord-highlight coord-stack'>");
        html.append("<span class='coord-line'>")
                .append(escapeHtml(String.format(Locale.US, "X: %.1f, Y: %.1f", pixelX, pixelY)))
                .append("</span>");
        if (astrometryContext != null && astrometryContext.hasAstrometricSolution()) {
            WcsCoordinateTransformer.SkyCoordinate skyCoordinate = astrometryContext.getTransformer().pixelToSky(pixelX, pixelY);
            html.append("<span class='coord-line'>RA ")
                    .append(escapeHtml(WcsCoordinateTransformer.formatRa(skyCoordinate.getRaDegrees())))
                    .append(" | Dec ")
                    .append(escapeHtml(WcsCoordinateTransformer.formatDec(skyCoordinate.getDecDegrees())))
                    .append("</span>");
        }
        html.append("</span>");
        return html.toString();
    }

    private static String buildSourceCoordinateListEntry(String fileLabel,
                                                         ReportAstrometryContext astrometryContext,
                                                         double pixelX,
                                                         double pixelY,
                                                         String metricsText) {
        StringBuilder html = new StringBuilder();
        html.append("<li>");
        html.append("<div class='source-file'>").append(escapeHtml(fileLabel)).append("</div>");
        html.append(formatPixelCoordinateBlockHtml(astrometryContext, pixelX, pixelY));
        if (metricsText != null && !metricsText.isEmpty()) {
            html.append("<div class='source-metrics'>").append(escapeHtml(metricsText)).append("</div>");
        }
        html.append("</li>");
        return html.toString();
    }

    private static String formatSkyCoordinateInline(ReportAstrometryContext astrometryContext, double pixelX, double pixelY) {
        if (astrometryContext == null || !astrometryContext.hasAstrometricSolution()) {
            return null;
        }

        WcsCoordinateTransformer.SkyCoordinate skyCoordinate = astrometryContext.getTransformer().pixelToSky(pixelX, pixelY);
        return "RA " + WcsCoordinateTransformer.formatRa(skyCoordinate.getRaDegrees())
                + " | Dec " + WcsCoordinateTransformer.formatDec(skyCoordinate.getDecDegrees());
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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

        // Extract variables from the PipelineResult to keep the rendering logic unchanged
        List<TrackLinker.Track> tracks = result.tracks;
        TrackerTelemetry linkerTelemetry = result.telemetry != null ? result.telemetry.trackerTelemetry : null;
        short[][] masterStackData = result.masterStackData;
        short[][] masterMaximumStackData = result.masterMaximumStackData;
        boolean[][] masterMask = result.masterMask;
        List<SourceExtractor.DetectedObject> masterStars = result.masterStars;
        short[][] slowMoverStackData = result.slowMoverStackData;
        List<SourceExtractor.DetectedObject> slowMoverCandidates = result.slowMoverCandidates;
        List<SourceExtractor.DetectedObject> masterMaximumStackTransientStreaks = result.masterMaximumStackTransientStreaks;
        PipelineTelemetry pipelineTelemetry = result.telemetry;
        List<List<SourceExtractor.DetectedObject>> allTransients = result.allTransients;
        ReportAstrometryContext astrometryContext = buildReportAstrometryContext(fitsFiles, appConfig);
        List<TrackLinker.Track> anomalies = new ArrayList<>();
        List<TrackLinker.Track> singleStreaks = new ArrayList<>();
        List<TrackLinker.Track> streakTracks = new ArrayList<>();
        List<TrackLinker.Track> movingTargets = new ArrayList<>();

        for (TrackLinker.Track track : tracks) {
            if (track.points == null || track.points.isEmpty()) continue;
            java.util.Set<Integer> uniqueFrames = new java.util.HashSet<>();
            for (SourceExtractor.DetectedObject pt : track.points) uniqueFrames.add(pt.sourceFrameIndex);

            if (uniqueFrames.size() == 1) {
                if (track.isAnomaly) anomalies.add(track);
                else singleStreaks.add(track);
            } else {
                if (track.isStreakTrack) streakTracks.add(track);
                else movingTargets.add(track);
            }
        }

        int slowMoverCandidateCount = slowMoverCandidates == null ? 0 : slowMoverCandidates.size();
        int maximumStackTransientStreakCount = masterMaximumStackTransientStreaks == null ? 0 : masterMaximumStackTransientStreaks.size();
        int potentialSlowMoverCount = slowMoverCandidateCount + maximumStackTransientStreakCount;
        int linkedTrackCount = movingTargets.size() + streakTracks.size();

        if (!exportDir.exists()) exportDir.mkdirs();

        // --- EXPORT MASTER DIAGNOSTICS ---
        if (masterStackData != null && masterMask != null) {
            BufferedImage masterImg = createDisplayImage(masterStackData);
            saveTrackImageLossless(masterImg, new File(exportDir, "master_stack.png"));

            BufferedImage masterMaskImg = createMasterMaskOverlay(masterStackData, masterMask);
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
                report.println("<div class='metric-box'><span class='metric-value'>" + linkedTrackCount + "</span><span class='metric-label'>Linked Tracks</span></div>");
                report.println("</div>");
                report.println("</div>");

                report.println("<div class='panel'>");
                report.println("<h2>Detection Breakdown</h2>");
                report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>Final report sections summarized up front so linked targets, anomalies, and potential slow movers are visible immediately.</p>");
                report.println("<div class='flex-container'>");
                report.println("<div class='metric-box'><span class='metric-value'>" + singleStreaks.size() + "</span><span class='metric-label'>Streaks</span></div>");
                report.println("<div class='metric-box'><span class='metric-value'>" + streakTracks.size() + "</span><span class='metric-label'>Streak Tracks</span></div>");
                report.println("<div class='metric-box'><span class='metric-value'>" + movingTargets.size() + "</span><span class='metric-label'>Moving Object Tracks</span></div>");
                report.println("<div class='metric-box'><span class='metric-value'>" + anomalies.size() + "</span><span class='metric-label'>High-Energy Anomalies</span></div>");
                String potentialSlowMoverMetric = config.enableSlowMoverDetection ? String.valueOf(potentialSlowMoverCount) : "Off";
                report.println("<div class='metric-box'><span class='metric-value'>" + potentialSlowMoverMetric + "</span><span class='metric-label'>Potential Slow Movers</span></div>");
                report.println("</div>");
                if (config.enableSlowMoverDetection) {
                    report.println("<div class='astro-note'>Potential slow movers include " + slowMoverCandidateCount + " deep-stack candidates and " + maximumStackTransientStreakCount + " unmatched maximum-stack streaks.</div>");
                } else {
                    report.println("<div class='astro-note'>Potential slow mover analysis was disabled for this session.</div>");
                }
                report.println("</div>");

                report.println("<div class='panel'>");
                report.println("<h2>Astrometric Context</h2>");
                if (astrometryContext.hasAstrometricSolution()) {
                    report.println("<div class='flex-container'>");
                    report.println("<div class='metric-box'><span class='metric-value'>Available</span><span class='metric-label'>Aligned WCS</span></div>");
                    report.println("<div class='metric-box'><span class='metric-value'>" + escapeHtml(formatUtcTimestamp(astrometryContext.sessionMidpointTimestampMillis)) + "</span><span class='metric-label'>Session Midpoint (UTC)</span></div>");
                    String observerMetric = astrometryContext.hasSkybotObserverCode()
                            ? escapeHtml(astrometryContext.skybotObserverCode)
                            : "500";
                    report.println("<div class='metric-box'><span class='metric-value'>" + observerMetric + "</span><span class='metric-label'>SkyBoT Observer</span></div>");
                    report.println("</div>");
                    report.println("<div class='astro-note'>WCS source: " + astrometryContext.getWcsSummary() + ".");
                    if (astrometryContext.hasSkybotObserverCode()) {
                        report.println("<br>SkyBoT observer code source: " + escapeHtml(astrometryContext.skybotObserverSource) + ".");
                    } else {
                        report.println("<br>No IAU observatory code configured. SkyBoT links will use geocenter (500).");
                    }
                    if (astrometryContext.observerSite != null) {
                        report.println("<br>Known site coordinates source: " + escapeHtml(astrometryContext.observerSite.sourceLabel) + ".");
                    } else {
                        report.println("<br>No observer site coordinates found.");
                    }
                    report.println("</div>");
                } else {
                    report.println("<p>No reusable WCS solution was found in the aligned set. Sky-coordinate report links are disabled for this session.</p>");
                }
                report.println("</div>");

                // --- Rejected Frames Table ---
                if (!pipelineTelemetry.rejectedFrames.isEmpty()) {
                    report.println("<div class='panel'>");
                    report.println("<h2>Quality Control: Rejected Frames</h2>");
                    report.println("<table><tr><th>Frame Index</th><th>Filename</th><th>Rejection Reason</th></tr>");
                    for (PipelineTelemetry.FrameRejectionStat rej : pipelineTelemetry.rejectedFrames) {
                        report.println("<tr><td>" + (rej.frameIndex + 1) + "</td>");
                        report.println("<td>" + rej.filename + "</td>");
                        report.println("<td class='alert'>" + rej.reason + "</td></tr>");
                    }
                    report.println("</table>");
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
                report.println("<div class='panel'>");
                report.println("<h2>Frame Extraction Statistics</h2>");
                report.println("<table><tr><th>Frame Index</th><th>Filename</th><th>Objects Extracted</th><th>Bg Median</th><th>Bg Sigma</th><th>Seed Threshold</th><th>Grow Threshold</th></tr>");
                for (PipelineTelemetry.FrameExtractionStat stat : pipelineTelemetry.frameExtractionStats) {
                    report.println("<tr><td>" + (stat.frameIndex + 1) + "</td>");
                    report.println("<td>" + stat.filename + "</td>");
                    report.println("<td>" + stat.objectCount + "</td>");
                    report.println("<td>" + String.format(Locale.US, "%.2f", stat.bgMedian) + "</td>");
                    report.println("<td>" + String.format(Locale.US, "%.2f", stat.bgSigma) + "</td>");
                    report.println("<td>" + String.format(Locale.US, "%.2f", stat.seedThreshold) + "</td>");
                    report.println("<td>" + String.format(Locale.US, "%.2f", stat.growThreshold) + "</td></tr>");
                }
                report.println("</table>");
                report.println("</div>");

                // --- Star Map Purification Table ---
                report.println("<div class='panel'>");
                report.println("<h2>Phase 3: Stationary Star Purification</h2>");
                report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>");
                report.println("Total Stationary Stars Purged Across Sequence: <span style='color: #4da6ff; font-weight: bold;'>" + linkerTelemetry.totalStationaryStarsPurged + "</span>");
                report.println("</p>");
                report.println("<table><tr><th>Frame Index</th><th>Filename</th><th>Initial Point Sources</th><th>Stars Purged</th><th>Surviving Transients</th></tr>");

                for (TrackerTelemetry.FrameStarMapStat starStat : linkerTelemetry.frameStarMapStats) {
                    String fName = "Unknown";
                    if (starStat.frameIndex < pipelineTelemetry.frameExtractionStats.size()) {
                        fName = pipelineTelemetry.frameExtractionStats.get(starStat.frameIndex).filename;
                    }
                    report.println("<tr><td>" + (starStat.frameIndex + 1) + "</td>");
                    report.println("<td>" + fName + "</td>");
                    report.println("<td>" + starStat.initialPointSources + "</td>");
                    report.println("<td style='color: #ff9933;'>" + starStat.purgedStars + "</td>");
                    report.println("<td style='color: #44ff44; font-weight: bold;'>" + starStat.survivingTransients + "</td></tr>");
                }
                report.println("</table>");
                report.println("</div>");
            }

            // =================================================================
            // 2. TRACKER TELEMETRY
            // =================================================================
            if (linkerTelemetry != null) {
                report.println("<div class='panel'>");
                report.println("<h2>Track Linking Diagnostics</h2>");

                report.println("<table>");
                report.println("<tr><th>Filter Phase</th><th>Rejection Reason</th><th>Points Rejected</th></tr>");

                report.println("<tr><td>1. Baseline (p1 &rarr; p2)</td><td>Stationary / Jitter</td><td>" + linkerTelemetry.countBaselineJitter + "</td></tr>");
                report.println("<tr><td>1. Baseline (p1 &rarr; p2)</td><td>Exceeded Max Jump Velocity</td><td>" + linkerTelemetry.countBaselineJump + "</td></tr>");
                report.println("<tr><td>1. Baseline (p1 &rarr; p2)</td><td>Morphological Size Mismatch</td><td>" + linkerTelemetry.countBaselineSize + "</td></tr>");

                report.println("<tr><td>2. Track Search (p3)</td><td>Off Predicted Trajectory Line</td><td>" + linkerTelemetry.countP3NotLine + "</td></tr>");
                report.println("<tr><td>2. Track Search (p3)</td><td>Wrong Direction / Angle</td><td>" + linkerTelemetry.countP3WrongDirection + "</td></tr>");
                report.println("<tr><td>2. Track Search (p3)</td><td>Exceeded Max Jump Velocity</td><td>" + linkerTelemetry.countP3Jump + "</td></tr>");
                report.println("<tr><td>2. Track Search (p3)</td><td>Morphological Size Mismatch</td><td>" + linkerTelemetry.countP3Size + "</td></tr>");

                report.println("<tr><td>3. Final Track</td><td>Insufficient Track Length</td><td>" + linkerTelemetry.countTrackTooShort + "</td></tr>");
                report.println("<tr><td>3. Final Track</td><td>Erratic Kinematic Rhythm</td><td>" + linkerTelemetry.countTrackErraticRhythm + "</td></tr>");
                report.println("<tr><td>3. Final Track</td><td>Duplicate Track (Ignored)</td><td>" + linkerTelemetry.countTrackDuplicate + "</td></tr>");
                report.println("</table>");
                report.println("<p class='astro-note' style='margin-top: 12px;'>");
                report.println("Phase outputs: <strong>" + linkerTelemetry.streakTracksFound + "</strong> accepted streak tracks and <strong>" + linkerTelemetry.pointTracksFound + "</strong> accepted point/anomaly tracks. ");
                report.println("The point/anomaly total includes anomaly-rescue outputs and is not identical to the final moving-target bucket.");
                report.println("</p>");

                report.println("</div>");
            }

            // =================================================================
            // 3. TARGET VISUALIZATIONS
            // =================================================================
            report.println("<h2>Target Visualizations</h2>");

            if (tracks.isEmpty()) {
                report.println("<div class='panel'><p>No moving targets were detected in this session.</p></div>");
            }


            if (!singleStreaks.isEmpty()) {
                report.println("<h3 style='color: #ff9933; margin-top: 30px; border-bottom: 1px solid #444; padding-bottom: 5px;'>Single Streaks</h3>");
                int counter = 1;
                for (TrackLinker.Track track : singleStreaks) {
                    CropBounds cb = new CropBounds(track, trackCropPadding);
                    SourceExtractor.DetectedObject pt = track.points.get(0);
                    int frameIndex = pt.sourceFrameIndex;

                    report.println("<div class='detection-card streak-title'>");
                    report.println("<div class='detection-title'>Single Streak Event S" + counter + "</div>");

                    short[][] croppedData = robustEdgeAwareCrop(rawFrames.get(frameIndex), cb.fixedCenterX, cb.fixedCenterY, cb.trackBoxWidth, cb.trackBoxHeight);
                    BufferedImage streakImg = createDisplayImage(croppedData);
                    String streakFileName = "single_streak_" + counter + ".png";
                    saveTrackImageLossless(streakImg, new File(exportDir, streakFileName));

                    String shapeFileName = "single_streak_" + counter + "_shape.png";
                    BufferedImage streakShapeImg = createSingleStreakShapeImage(track.points, cb.trackBoxWidth, cb.trackBoxHeight, cb.startX, cb.startY, true);
                    saveTrackImageLossless(streakShapeImg, new File(exportDir, shapeFileName));

                    report.println("<div class='image-container'>");
                    report.println("<div><a href='" + streakFileName + "' target='_blank'><img src='" + streakFileName + "' alt='Detection Image' /></a><br/><center><small>Detection Image</small></center></div>");
                    report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Shape Footprint' /></a><br/><center><small>Shape Footprint Map</small></center></div>");
                    report.println("</div>");
                    String metricsStr = buildStreakMetricsText(pt);
                    report.println("<strong>Detection Coordinate:</strong><ul class='source-list'>" + buildSourceCoordinateListEntry(pt.sourceFilename, astrometryContext, pt.x, pt.y, metricsStr) + "</ul>");
                    report.print(buildSingleFrameSkyViewerHtml(astrometryContext, pt, "Reference epoch for streak lookup"));
                    report.println("</div>");

                    counter++;
                }
            }

            if (!streakTracks.isEmpty()) {
                report.println("<h3 style='color: #ffcc33; margin-top: 30px; border-bottom: 1px solid #444; padding-bottom: 5px;'>Multi-Frame Streak Tracks</h3>");
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
                    report.println("<div class='detection-title' style='color: #ffcc33;'>Multi-Frame Streak Track ST" + counter + timeBadge + "</div>");

                    report.println("<div class='image-container'>");
                    report.println("<div><a href='" + starFileName + "' target='_blank'><img src='" + starFileName + "' alt='Star Centric Animation' /></a><br/><center><small>Star Centric</small></center></div>");
                    report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Track Shape Map' /></a><br/><center><small>Track Shape Map</small></center></div>");
                    report.println("</div>");

                    report.println("<strong>Detection Coordinates & Frames:</strong><ul class='source-list'>");
                    for (int i = 0; i < track.points.size(); i++) {
                        SourceExtractor.DetectedObject pt = track.points.get(i);
                        String metricsStr = buildStreakMetricsText(pt);
                        report.println(buildSourceCoordinateListEntry("[" + (i + 1) + "] " + pt.sourceFilename, astrometryContext, pt.x, pt.y, metricsStr));
                    }
                    report.println("</ul>");
                    report.print(buildTrackSkyViewerHtml(astrometryContext, track, "Reference epoch for streak-track lookup"));
                    report.println("</div>");
                    counter++;
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
                    report.print(buildTrackTimingSummaryHtml(track));

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
                        report.println(buildSourceCoordinateListEntry("[" + (i + 1) + "] " + pt.sourceFilename, astrometryContext, pt.x, pt.y, metricsStr));
                    }
                    report.println("</ul>");
                    report.print(buildTrackSolarSystemIdentificationHtml(astrometryContext, track));
                    report.println("</div>");
                    counter++;
                }
            }

            if (!anomalies.isEmpty()) {
                report.println("<h3 style='color: #ff3333; margin-top: 30px; border-bottom: 1px solid #444; padding-bottom: 5px;'>High-Energy Anomalies (Optical Flashes)</h3>");
                int counter = 1;
                for (TrackLinker.Track track : anomalies) {
                    CropBounds cb = new CropBounds(track, trackCropPadding);
                    SourceExtractor.DetectedObject pt = track.points.get(0);
                    int frameIndex = pt.sourceFrameIndex;

                    report.println("<div class='detection-card streak-title' style='border-left-color: #ff3333; color: #ff3333;'>");
                    report.println("<div class='detection-title' style='color: #ff3333;'>Anomaly Event A" + counter + "</div>");

                    short[][] croppedData = robustEdgeAwareCrop(rawFrames.get(frameIndex), cb.fixedCenterX, cb.fixedCenterY, cb.trackBoxWidth, cb.trackBoxHeight);
                    BufferedImage detectionImg = createDisplayImage(croppedData);
                    String detectionFileName = "anomaly_" + counter + "_detection.png";
                    saveTrackImageLossless(detectionImg, new File(exportDir, detectionFileName));

                    String shapeFileName = "anomaly_" + counter + "_shape.png";
                    BufferedImage shapeImg = createSingleStreakShapeImage(track.points, cb.trackBoxWidth, cb.trackBoxHeight, cb.startX, cb.startY, false);
                    saveTrackImageLossless(shapeImg, new File(exportDir, shapeFileName));

                    String maskFileName = null;
                    MaskOverlapStats maskOverlapStats = new MaskOverlapStats(0, 0);
                    if (masterStackData != null && masterMask != null) {
                        BufferedImage maskOverlayImg = createCroppedMasterMaskOverlay(
                                masterStackData,
                                masterMask,
                                cb.fixedCenterX,
                                cb.fixedCenterY,
                                cb.trackBoxWidth,
                                cb.trackBoxHeight,
                                pt
                        );
                        maskFileName = "anomaly_" + counter + "_master_mask.png";
                        saveTrackImageLossless(maskOverlayImg, new File(exportDir, maskFileName));
                        maskOverlapStats = computeMaskOverlapStats(pt, masterMask, masterStackData[0].length, masterStackData.length);
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
                    String metricsStr = buildAnomalyMetricsText(pt);
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
                    report.println("<strong>Detection Coordinate:</strong><ul class='source-list'>" + buildSourceCoordinateListEntry(pt.sourceFilename, astrometryContext, pt.x, pt.y, metricsStr) + "</ul>");
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
                boolean hasMaximumStackTransientStreaks = masterMaximumStackTransientStreaks != null && !masterMaximumStackTransientStreaks.isEmpty();
                boolean hasTelemetry = result.slowMoverTelemetry != null;

                if (hasCandidates || hasTelemetry || hasMaximumStackTransientStreaks) {
                    report.println("<h2>Deep Stack Anomalies (Ultra-Slow Mover Candidates)</h2>");
                    report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>Objects in the master median stack that are significantly elongated compared to the rest of the star field. These may be ultra-slow moving targets that moved just enough to form a short streak, but too slowly to be rejected by the median filter.</p>");

                    if (hasTelemetry) {
                        report.println("<div class='flex-container' style='margin-bottom: 25px;'>");
                        report.println("<div class='metric-box'><span class='metric-value'>" + result.slowMoverTelemetry.candidatesDetected + "</span><span class='metric-label'>Raw Candidates</span></div>");
                        report.println("<div class='metric-box'><span class='metric-value'>" + String.format(Locale.US, "%.2f", result.slowMoverTelemetry.medianElongation) + "</span><span class='metric-label'>Median Background Elongation</span></div>");
                        report.println("<div class='metric-box'><span class='metric-value'>" + String.format(Locale.US, "%.2f", result.slowMoverTelemetry.dynamicElongationThreshold) + "</span><span class='metric-label'>Dynamic Threshold</span></div>");
                        report.println("</div>");
                    }

                    if (hasCandidates && slowMoverStackData != null) {
                        report.println("<div class='flex-container'>");

                        int smCounter = 1;
                        for (SourceExtractor.DetectedObject sm : slowMoverCandidates) {
                            exportDeepStackDetectionCard(
                                    report,
                                    exportDir,
                                    rawFrames,
                                    sm,
                                    astrometryContext,
                                    slowMoverStackData,
                                    "Slow Mover Stack",
                                    "slow_mover_" + smCounter + "_sm_stack.png",
                                    masterStackData,
                                    masterMaximumStackData,
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
                    if (hasMaximumStackTransientStreaks) {
                        report.println("<h3 style='color: #ffcc66; margin-top: 30px; border-bottom: 1px solid #444; padding-bottom: 5px;'>Master Maximum Stack Transient Streaks</h3>");
                        report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>Streaks detected in the master maximum stack that were not matched to previously identified single-frame streaks. These may represent very slow movers, stack-enhanced movers, or artifacts worth manual inspection.</p>");
                        report.println("<div class='flex-container'>");

                        int streakCounter = 1;
                        for (SourceExtractor.DetectedObject streak : masterMaximumStackTransientStreaks) {
                            exportDeepStackDetectionCard(
                                    report,
                                    exportDir,
                                    rawFrames,
                                    streak,
                                    astrometryContext,
                                    masterMaximumStackData,
                                    "Maximum Stack",
                                    "master_max_streak_" + streakCounter + "_maximum_stack.png",
                                    masterStackData,
                                    null,
                                    null,
                                    null,
                                    "Maximum Stack Diff",
                                    "master_max_streak_" + streakCounter + "_diff.png",
                                    "master_max_streak_" + streakCounter + "_anim.gif",
                                    "master_max_streak_" + streakCounter + "_shape.png",
                                    "Streak #" + streakCounter,
                                    "#ffcc66"
                            );
                            streakCounter++;
                        }
                        report.println("</div>");
                    }
                }
            }

            // =================================================================
            // 4.5 GLOBAL TRAJECTORY MAP
            // =================================================================
            if (!tracks.isEmpty()) {
                short[][] bgData = masterStackData != null ? masterStackData : (!rawFrames.isEmpty() ? rawFrames.get(0) : null);
                if (bgData != null) {
                    BufferedImage globalMap = createGlobalTrackMap(bgData, anomalies, singleStreaks, streakTracks, movingTargets);
                    saveTrackImageLossless(globalMap, new File(exportDir, "global_track_map.png"));
                    report.println("<div class='panel'>");
                    report.println("<h3 style='color: #ffffff; margin-top: 0;'>Global Trajectory Map</h3>");
                    report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>");
                    report.println("An overview of all detected transients and moving targets plotted over the master background. " +
                            "Multi-frame tracks are connected with lines (<strong>T#</strong> for targets, <strong>ST#</strong> for streaks), while single-frame anomalies and single streaks are circled (<strong>A#</strong> and <strong>S#</strong>).</p>");
                    report.println("<a href='global_track_map.png' target='_blank'><img src='global_track_map.png' style='width: 100%; border: 1px solid #555; border-radius: 4px;' alt='Global Track Map' /></a>");
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
                    report.println("Shows all raw transients detected across the entire session. Colors map to time (Blue = Start, Red = End). This helps visualize noise floors, hot columns, and unlinked moving targets.</p>");

                    report.println("<div class='image-container' style='flex-wrap: wrap;'>");

                    report.println("<div style='flex: 1; min-width: 400px;'>");
                    report.println("<h4 style='color: #ccc; margin-bottom: 5px;'>Exact Footprint Map</h4>");
                    report.println("<p style='font-size: 12px; color: #888; margin-top: 0;'>Plots the exact raw pixels at a 1:1 scale. Both objects and streaks</p>");
                    report.println("<a href='global_transient_map.png' target='_blank'><img src='global_transient_map.png' style='width: 100%; border: 1px solid #555; border-radius: 4px;' alt='Global Transient Map' /></a></div>");

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
                        movingTargets,
                        slowMoverCandidates,
                        masterMaximumStackTransientStreaks,
                        pipelineTelemetry
                );
                saveTrackImageLossless(creativeTributeImage, new File(exportDir, creativeFileName));

                int rawTransientCount = countTotalTransientDetections(allTransients);
                int confirmedTrackCount = movingTargets.size() + streakTracks.size();
                int deepStackHintCount = (slowMoverCandidates == null ? 0 : slowMoverCandidates.size())
                        + (masterMaximumStackTransientStreaks == null ? 0 : masterMaximumStackTransientStreaks.size());
                double longestPath = computeLongestTrackPathPx(streakTracks, movingTargets);
                String dominantMotion = computeDominantMotionLabel(movingTargets, streakTracks);

                report.println("<div class='panel' style='background: linear-gradient(180deg, #453049 0%, #2b2b2b 100%); border: 1px solid #5f536a;'>");
                report.println("<h2>The AI's Perspective: Skyprint of the Session</h2>");
                report.println("<p style='color: #c7bfd6; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>A creative tribute by Codex. This poster compresses the whole run into one image: faint time-mapped transient dust for everything that flashed through the extractor, brighter paths for linked movers, and separate markers for deep-stack hints that deserve a second look.</p>");
                report.println("<a href='" + creativeFileName + "' target='_blank'><img src='" + creativeFileName + "' class='native-size-image' style='border: 1px solid #666; border-radius: 6px;' alt='Creative Tribute Skyprint' /></a>");
                report.println("<p style='font-size: 13px; color: #b8b0c7; margin-bottom: 0;'>This session stitched together <strong style='color:#ffffff;'>" + rawTransientCount + "</strong> raw transients, produced <strong style='color:#ffffff;'>" + confirmedTrackCount + "</strong> linked tracks, surfaced <strong style='color:#ffffff;'>" + anomalies.size() + "</strong> optical flashes, and left <strong style='color:#ffffff;'>" + deepStackHintCount + "</strong> deep-stack hints on the table. The dominant linked motion trends toward <strong style='color:#ffffff;'>" + dominantMotion + "</strong>, and the longest confirmed path spans <strong style='color:#ffffff;'>" + String.format(Locale.US, "%.1f px", longestPath) + "</strong>.</p>");
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
                report.println("<p style='font-size: 12px; color: #888; margin-top: 0;'>A radar chart mapping the velocity and heading of moving targets using a logarithmic scale to highlight both slow asteroids and fast satellites. Orbital constellations often clump together into distinct vectors, revealing satellite swarms or shared orbital planes. See the legend in the top right to distinguish between point-source targets and streaks.</p>");
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

    // =================================================================
    // ANALYTICS & DIAGNOSTICS (Unchanged)
    // =================================================================

    private static FitsDataAnalysis analyzeFitsData(short[][] imageData) {
        FitsDataAnalysis data = new FitsDataAnalysis();

        data.height = imageData.length;
        data.width = imageData[0].length;
        data.totalPixels = (long) data.width * data.height;

        data.minRaw = Short.MAX_VALUE;
        data.maxRaw = Short.MIN_VALUE;
        data.minUnsigned = 65535;
        data.maxUnsigned = 0;

        long sumUnsigned = 0;

        for (int y = 0; y < data.height; y++) {
            for (int x = 0; x < data.width; x++) {
                short rawVal = imageData[y][x];
                int unsignedVal = rawVal + 32768;

                if (rawVal < data.minRaw) data.minRaw = rawVal;
                if (rawVal > data.maxRaw) data.maxRaw = rawVal;

                if (unsignedVal < data.minUnsigned) data.minUnsigned = unsignedVal;
                if (unsignedVal > data.maxUnsigned) data.maxUnsigned = unsignedVal;

                sumUnsigned += unsignedVal;

                if (rawVal == 0) data.countRawZero++;
                if (rawVal == Short.MIN_VALUE) data.countRawMin++;
                if (rawVal == Short.MAX_VALUE) data.countRawMax++;
                if (unsignedVal == 0) data.countUnsignedZero++;
                if (unsignedVal == 65535) data.countUnsignedMax++;
            }
        }

        data.meanUnsigned = (double) sumUnsigned / data.totalPixels;

        double sumSqDiff = 0;
        for (int y = 0; y < data.height; y++) {
            for (int x = 0; x < data.width; x++) {
                int unsignedVal = imageData[y][x] + 32768;
                double diff = unsignedVal - data.meanUnsigned;
                sumSqDiff += (diff * diff);
            }
        }

        double variance = sumSqDiff / data.totalPixels;
        data.sigmaUnsigned = Math.sqrt(variance);

        data.autoBlackPoint = data.meanUnsigned - (autoStretchBlackSigma * data.sigmaUnsigned);
        data.autoWhitePoint = data.meanUnsigned + (autoStretchWhiteSigma * data.sigmaUnsigned);

        return data;
    }
}
