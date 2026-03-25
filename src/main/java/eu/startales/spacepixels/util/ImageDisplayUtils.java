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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class ImageDisplayUtils {

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

    /**
     * Returns an evenly spaced sample of chronological frame indices while guaranteeing that
     * mandatory frames (where actual detections occur) are included to never miss a transient.
     */
    public static List<Integer> getRepresentativeSequence(int totalFrames, java.util.Set<Integer> mandatoryFrames, int maxFrames) {
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
    public static BufferedImage createMasterMaskOverlay(short[][] masterStackData, boolean[][] masterMask) {
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

    public static BufferedImage createDisplayImageSoft(short[][] imageData) {
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

    public static BufferedImage cropRegionToImage(short[][] fullImage, int cx, int cy, int cropWidth, int cropHeight) {
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

    public static BufferedImage exportSingleStreak(short[][] rawImage, SourceExtractor.DetectedObject streak) {
        int estimatedLength = (int) (streak.elongation * streakElongationCropMultiplier) + singleStreakExportPadding;
        int cropSize = Math.min(estimatedLength, Math.max(rawImage.length, rawImage[0].length));

        int cx = (int) Math.round(streak.x);
        int cy = (int) Math.round(streak.y);

        return cropRegionToImage(rawImage, cx, cy, cropSize, cropSize);
    }

    public static void saveTrackImageLossless(BufferedImage image, File outputFile) throws IOException {
        if (image == null) {
            System.err.println("Warning: Attempted to save a null image. Skipping.");
            return;
        }

        boolean success = ImageIO.write(image, "png", outputFile);

        if (!success) {
            throw new IOException("No appropriate PNG writer found in ImageIO.");
        }
    }

    public static short[][] robustEdgeAwareCrop(short[][] fullImage, int cx, int cy, int cropWidth, int cropHeight) {
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
    public static BufferedImage createSingleStreakShapeImage(List<SourceExtractor.DetectedObject> points, int cropWidth, int cropHeight, int startX, int startY, boolean drawCentroid) {
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
    public static BufferedImage createTrackShapeImage(TrackLinker.Track track, int cropWidth, int cropHeight, int startX, int startY) {
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
    public static BufferedImage createGlobalTrackMap(short[][] backgroundData,
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

    public static BufferedImage createDriftMap(List<SourceExtractor.Pixel> path, int outSize) {
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

    public static BufferedImage createFourCornerMosaic(short[][] frame, int targetCropSize) {
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
    public static BufferedImage createGlobalTransientMap(short[][] backgroundData, List<List<SourceExtractor.DetectedObject>> allTransients) {
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
    public static BufferedImage createRainbowClusterMap(short[][] backgroundData, List<List<SourceExtractor.DetectedObject>> allTransients) {
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
    public static BufferedImage createSlowMoverDifferenceMap(short[][] slowMover, short[][] master) {
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
                                                 File exportDir,
                                                 DetectionConfig config) throws IOException {

        // Extract variables from the PipelineResult to keep the rendering logic unchanged
        List<TrackLinker.Track> tracks = result.tracks;
        TrackerTelemetry linkerTelemetry = result.telemetry != null ? result.telemetry.trackerTelemetry : null;
        short[][] masterStackData = result.masterStackData;
        boolean[][] masterMask = result.masterMask;
        short[][] slowMoverStackData = result.slowMoverStackData;
        List<SourceExtractor.DetectedObject> slowMoverCandidates = result.slowMoverCandidates;
        PipelineTelemetry pipelineTelemetry = result.telemetry;
        List<List<SourceExtractor.DetectedObject>> allTransients = result.allTransients;

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
            report.println(".source-list li { background: #3d3d3d; padding: 5px 10px; border-radius: 4px; font-size: 0.9em; font-family: monospace; color: #aaa; border: 1px solid #555; }");
            report.println(".coord-highlight { color: #4da6ff; font-weight: bold; }");
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
                report.println("<div class='metric-box'><span class='metric-value'>" + pipelineTelemetry.totalMovingTargetsFound + "</span><span class='metric-label'>Confirmed Targets</span></div>");
                report.println("</div>");
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
                        Gson gson = new GsonBuilder().setPrettyPrinting().create();
                        gson.toJson(config, writer);
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
                            report.println("<div class='config-item'><span style='color: #aaa;'>Frame " + (i + 1) + "</span> <span class='val'>[" + p.x + ", " + p.y + "]</span></div>");
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

                report.println("</div>");
            }

            // =================================================================
            // 3. TARGET VISUALIZATIONS
            // =================================================================
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
                    String coordStr = String.format(Locale.US, "X: %.1f, Y: %.1f", pt.x, pt.y);
                    String metricsStr = String.format(Locale.US, "Flux: %.1f, Pixels: %d, Elongation: %.2f", pt.totalFlux, (int) pt.pixelArea, pt.elongation);
                    report.println("<strong>Detection Coordinate:</strong><ul class='source-list'><li>" + pt.sourceFilename + " | <span class='coord-highlight'>" + coordStr + "</span> | <span style='color: #999;'>" + metricsStr + "</span></li></ul></div>");

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
                        String coordStr = String.format(Locale.US, "X: %.1f, Y: %.1f", pt.x, pt.y);
                        String metricsStr = String.format(Locale.US, "Flux: %.1f, Pixels: %d, Elongation: %.2f", pt.totalFlux, (int) pt.pixelArea, pt.elongation);
                        report.println("<li>[" + (i + 1) + "] " + pt.sourceFilename + " | <span class='coord-highlight'>" + coordStr + "</span> | <span style='color: #999;'>" + metricsStr + "</span></li>");
                    }
                    report.println("</ul></div>");
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
                        String coordStr = String.format(Locale.US, "X: %.1f, Y: %.1f", pt.x, pt.y);
                        String metricsStr = String.format(Locale.US, "Flux: %.1f, Pixels: %d, Elongation: %.2f", pt.totalFlux, (int) pt.pixelArea, pt.elongation);
                        report.println("<li>[" + (i + 1) + "] " + pt.sourceFilename + " | <span class='coord-highlight'>" + coordStr + "</span> | <span style='color: #999;'>" + metricsStr + "</span></li>");
                    }
                    report.println("</ul></div>");
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
                    report.println("<div><a href='" + contextGifFileName + "' target='_blank'><img src='" + contextGifFileName + "' alt='Anomaly Context' /></a><br/><center><small>Context (Before / Flash / After)</small></center></div>");
                    report.println("</div>");
                    String coordStr = String.format(Locale.US, "X: %.1f, Y: %.1f", pt.x, pt.y);
                    String metricsStr = String.format(Locale.US, "Flux: %.1f, Pixels: %d, Elongation: %.2f", pt.totalFlux, (int) pt.pixelArea, pt.elongation);
                    report.println("<strong>Detection Coordinate:</strong><ul class='source-list'><li>" + pt.sourceFilename + " | <span class='coord-highlight'>" + coordStr + "</span> | <span style='color: #999;'>" + metricsStr + "</span></li></ul></div>");
                    counter++;
                }
            }

            // =================================================================
            // 4. DEEP STACK ANOMALIES (ULTRA-SLOW MOVERS)
            // =================================================================
            if (config.enableSlowMoverDetection && slowMoverStackData != null) {
                boolean hasCandidates = slowMoverCandidates != null && !slowMoverCandidates.isEmpty();
                boolean hasTelemetry = result.slowMoverTelemetry != null;

                if (hasCandidates || hasTelemetry) {
                    report.println("<h2>Deep Stack Anomalies (Ultra-Slow Mover Candidates)</h2>");
                    report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>Objects in the master median stack that are significantly elongated compared to the rest of the star field. These may be ultra-slow moving targets that moved just enough to form a short streak, but too slowly to be rejected by the median filter.</p>");

                    if (masterStackData != null) {
                        BufferedImage diffMap = createSlowMoverDifferenceMap(slowMoverStackData, masterStackData);
                        saveTrackImageLossless(diffMap, new File(exportDir, "slow_mover_diff_map.png"));

                        report.println("<div class='panel' style='margin-bottom: 25px; max-width: 500px;'>");
                        report.println("<h3 style='color: #ffffff; margin-top: 0;'>Global Slow Mover Difference Map</h3>");
                        report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>");
                        report.println("The mathematical subtraction of the Master Median Stack from the specialized Slow Mover Stack. Positive differences are stretched and tinted red over the background, making hidden slow-mover tracks stand out.</p>");
                        report.println("<div class='image-container'>");
                        report.println("<div><a href='slow_mover_diff_map.png' target='_blank'><img src='slow_mover_diff_map.png' style='max-width: 400px;' alt='Slow Mover Difference Map' /></a></div>");
                        report.println("</div>");
                        report.println("</div>");
                    }

                    if (hasTelemetry) {
                        report.println("<div class='flex-container' style='margin-bottom: 25px;'>");
                        report.println("<div class='metric-box'><span class='metric-value'>" + result.slowMoverTelemetry.candidatesDetected + "</span><span class='metric-label'>Raw Candidates</span></div>");
                        report.println("<div class='metric-box'><span class='metric-value'>" + String.format(Locale.US, "%.2f", result.slowMoverTelemetry.medianElongation) + "</span><span class='metric-label'>Median Background Elongation</span></div>");
                        report.println("<div class='metric-box'><span class='metric-value'>" + String.format(Locale.US, "%.2f", result.slowMoverTelemetry.dynamicElongationThreshold) + "</span><span class='metric-label'>Dynamic Threshold</span></div>");
                        report.println("</div>");
                    }

                    if (hasCandidates) {
                        report.println("<div class='flex-container'>");

                        int smCounter = 1;
                        for (SourceExtractor.DetectedObject sm : slowMoverCandidates) {
                            int cx = (int) Math.round(sm.x);
                            int cy = (int) Math.round(sm.y);

                            // Dynamically scale the crop box to ensure we capture the entire elongated footprint plus padding
                            double objectRadius = sm.pixelArea > 0 ? Math.sqrt((sm.pixelArea * sm.elongation) / Math.PI) : 0;
                            int cropSize = Math.max(150, (int) Math.round(objectRadius * 2) + 100);
                            int startX = cx - (cropSize / 2);
                            int startY = cy - (cropSize / 2);

                            // Crop from slowMoverStackData
                            short[][] croppedSlowData = robustEdgeAwareCrop(slowMoverStackData, cx, cy, cropSize, cropSize);
                            BufferedImage smImg = createDisplayImage(croppedSlowData);
                            String smFileName = "slow_mover_" + smCounter + "_sm_stack.png";
                            saveTrackImageLossless(smImg, new File(exportDir, smFileName));

                            // Crop from masterStackData (Median Stack)
                            String masterFileName = "slow_mover_" + smCounter + "_master_stack.png";
                            if (masterStackData != null) {
                                short[][] croppedMasterData = robustEdgeAwareCrop(masterStackData, cx, cy, cropSize, cropSize);
                                BufferedImage masterImg = createDisplayImage(croppedMasterData);
                                saveTrackImageLossless(masterImg, new File(exportDir, masterFileName));
                            }

                            // Generate the Shape Footprint Image
                            BufferedImage shapeImg = createSingleStreakShapeImage(java.util.Collections.singletonList(sm), cropSize, cropSize, startX, startY, false);
                            String shapeFileName = "slow_mover_" + smCounter + "_shape.png";
                            saveTrackImageLossless(shapeImg, new File(exportDir, shapeFileName));

                            // Generate Animated GIF (Max 10 Frames)
                            List<Integer> sampledIndices = getRepresentativeSequence(rawFrames.size(), new java.util.HashSet<>(), 10);
                            List<BufferedImage> gifFrames = new ArrayList<>();
                            for (int idx : sampledIndices) {
                                short[][] frameData = robustEdgeAwareCrop(rawFrames.get(idx), cx, cy, cropSize, cropSize);
                                gifFrames.add(createDisplayImage(frameData));
                            }
                            String gifFileName = "slow_mover_" + smCounter + "_anim.gif";
                            GifSequenceWriter.saveAnimatedGif(gifFrames, new File(exportDir, gifFileName), gifBlinkSpeedMs);

                            report.println("<div class='detection-card' style='border-left-color: #ff66ff; padding: 15px; margin-bottom: 0;'>");
                            report.println("<div class='detection-title' style='color: #ff66ff; font-size: 1.1em; margin-bottom: 10px;'>Candidate #" + smCounter + "</div>");
                            report.println("<div class='image-container' style='margin-bottom: 10px;'>");
                            if (masterStackData != null) {
                                report.println("<div><a href='" + masterFileName + "' target='_blank'><img src='" + masterFileName + "' style='max-width: 150px;' alt='Median Stack Crop' /></a><br/><center><small>Median Stack</small></center></div>");
                            }
                            report.println("<div><a href='" + smFileName + "' target='_blank'><img src='" + smFileName + "' style='max-width: 150px;' alt='Slow Mover Stack Crop' /></a><br/><center><small>Slow Mover Stack</small></center></div>");
                            report.println("<div><a href='" + gifFileName + "' target='_blank'><img src='" + gifFileName + "' style='max-width: 150px;' alt='Sampled Time-Lapse' /></a><br/><center><small>Animation (Sampled)</small></center></div>");
                            report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' style='max-width: 150px;' alt='Slow Mover Shape' /></a><br/><center><small>Shape</small></center></div>");
                            report.println("</div>");
                            report.println("<div style='font-family: monospace; font-size: 12px; color: #aaa;'>X: " + String.format(Locale.US, "%.1f", sm.x) + " | Y: " + String.format(Locale.US, "%.1f", sm.y) + "<br>Elongation: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f", sm.elongation) + "</span><br>Pixels: <span style='color:#fff;'>" + (int) sm.pixelArea + "</span></div></div>");

                            smCounter++;
                        }
                        report.println("</div>");
                    } else {
                        report.println("<div class='panel'><p>No ultra-slow movers were detected that exceeded the dynamic threshold.</p></div>");
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
                    report.println("<a href='rainbow_cluster_map.png' target='_blank'><img src='rainbow_cluster_map.png' style='width: 100%; border: 1px solid #555; border-radius: 4px;' alt='Rainbow Cluster Map' /></a></div>");
                    report.println("</div>");
                    report.println("</div>");
                }
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

    public static FitsDataAnalysis analyzeFitsData(short[][] imageData) {
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