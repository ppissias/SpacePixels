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
    public static int maxFullSequenceFrames = 15;

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
    public static BufferedImage createMasterMaskOverlay(short[][] masterStackData, List<SourceExtractor.DetectedObject> masterStars) {
        int height = masterStackData.length;
        int width = masterStackData[0].length;

        // Create an RGB image so we can paint the mask in color
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        // Calculate stretch for background
        long sum = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                sum += (masterStackData[y][x] + 32768);
            }
        }
        double mean = (double) sum / (width * height);

        double sumSqDiff = 0;
        int actualMax = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int val = masterStackData[y][x] + 32768;
                if (val > actualMax) actualMax = val;
                double diff = val - mean;
                sumSqDiff += (diff * diff);
            }
        }
        double sigma = Math.sqrt(sumSqDiff / (width * height));
        double blackPoint = mean - (autoStretchBlackSigma * sigma);
        double whitePoint = mean + (autoStretchWhiteSigma * sigma);

        if (blackPoint < 0) blackPoint = 0;
        if (whitePoint > actualMax) whitePoint = actualMax;
        double range = whitePoint - blackPoint;
        if (range <= 0) range = 1.0;

        // Draw the grayscale stretched background
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int val = masterStackData[y][x] + 32768;
                double adjustedVal = val - blackPoint;
                if (adjustedVal < 0) adjustedVal = 0;
                if (adjustedVal > range) adjustedVal = range;

                double normalized = adjustedVal / range;
                double stretched = Math.sqrt(normalized);
                int displayValue = (int) (stretched * 255.0);
                if (displayValue > 255) displayValue = 255;
                if (displayValue < 0) displayValue = 0;

                int rgb = (displayValue << 16) | (displayValue << 8) | displayValue;
                image.setRGB(x, y, rgb);
            }
        }

        // PAINT THE MASTER MASK IN BRIGHT RED
        int redColor = new Color(255, 0, 0, 150).getRGB(); // Semi-transparent red
        for (SourceExtractor.DetectedObject star : masterStars) {
            if (star.rawPixels != null) {
                for (SourceExtractor.Pixel p : star.rawPixels) {
                    if (p.x >= 0 && p.x < width && p.y >= 0 && p.y < height) {
                        image.setRGB(p.x, p.y, redColor);
                    }
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
    public static BufferedImage createSingleStreakShapeImage(List<SourceExtractor.DetectedObject> points, int cropWidth, int cropHeight, int startX, int startY) {
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
            }

            // 3. Mark the Centroid with a white target
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cx = (int) Math.round(pt.x - startX);
            int cy = (int) Math.round(pt.y - startY);
            g2d.setColor(Color.WHITE);
            g2d.setStroke(new BasicStroke(1.5f));
            g2d.drawOval(cx - 3, cy - 3, 6, 6);
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


    // =================================================================
    // HTML EXPORT
    // =================================================================

    // --- UPDATED SIGNATURE TO ACCEPT CONFIG ---
    public static void exportTrackVisualizations(List<TrackLinker.Track> tracks,
                                                 TrackerTelemetry linkerTelemetry,
                                                 List<short[][]> rawFrames,
                                                 short[][] masterStackData,
                                                 List<SourceExtractor.DetectedObject> masterStars,
                                                 File exportDir,
                                                 PipelineTelemetry pipelineTelemetry,
                                                 DetectionConfig config) throws IOException {

        if (!exportDir.exists()) exportDir.mkdirs();

        int trackCounter = 1;
        int streakCounter = 1;

        // --- EXPORT MASTER DIAGNOSTICS ---
        if (masterStackData != null && masterStars != null) {
            BufferedImage masterImg = createDisplayImage(masterStackData);
            saveTrackImageLossless(masterImg, new File(exportDir, "master_stack.png"));

            BufferedImage masterMaskImg = createMasterMaskOverlay(masterStackData, masterStars);
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
                    report.println("<div class='panel'>");
                    report.println("<h2>Pipeline Configuration</h2>");
                    report.println("<p style='font-size: 13px; color: #888; margin-top: -10px;'>Active tuning parameters used during this session:</p>");
                    report.println("<div class='scroll-box'><table>");
                    report.println("<tr><th>Parameter</th><th>Value</th></tr>");

                    for (Field field : config.getClass().getDeclaredFields()) {
                        try {
                            field.setAccessible(true);
                            Object value = field.get(config);
                            report.println("<tr><td>" + field.getName() + "</td><td style='font-family: monospace; color: #4da6ff;'>" + value + "</td></tr>");
                        } catch (IllegalAccessException e) {
                            // Silently ignore fields that cannot be accessed
                        }
                    }
                    report.println("</table></div></div>");
                }

                // --- DIAGNOSTIC MASK RENDER ---
                report.println("<div class='panel'>");
                report.println("<h2>Master Shield & Veto Mask</h2>");
                report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>");
                report.println("The raw median stack (Left) and the extracted Binary Veto Mask painted in red (Right). Any transient touching a red pixel is destroyed in Phase 3.</p>");
                report.println("<div class='image-container'>");
                report.println("<div><a href='master_stack.png' target='_blank'><img src='master_stack.png' style='max-width: 400px;' alt='Master Stack' /></a><br/><center><small>Deep Median Stack</small></center></div>");
                report.println("<div><a href='master_mask_overlay.png' target='_blank'><img src='master_mask_overlay.png' style='max-width: 400px;' alt='Mask Overlay' /></a><br/><center><small>Binary Footprint Mask (Red)</small></center></div>");
                report.println("</div>");
                report.println("</div>");


                // --- Extraction Stats Table ---
                report.println("<div class='panel'>");
                report.println("<h2>Frame Extraction Statistics</h2>");
                report.println("<table><tr><th>Frame Index</th><th>Filename</th><th>Objects Extracted</th></tr>");
                for (PipelineTelemetry.FrameExtractionStat stat : pipelineTelemetry.frameExtractionStats) {
                    report.println("<tr><td>" + (stat.frameIndex + 1) + "</td>");
                    report.println("<td>" + stat.filename + "</td>");
                    report.println("<td>" + stat.objectCount + "</td></tr>");
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
                report.println("<tr><td>1. Baseline (p1 &rarr; p2)</td><td>Photometric Flux Mismatch</td><td>" + linkerTelemetry.countBaselineFlux + "</td></tr>");

                report.println("<tr><td>2. Track Search (p3)</td><td>Off Predicted Trajectory Line</td><td>" + linkerTelemetry.countP3NotLine + "</td></tr>");
                report.println("<tr><td>2. Track Search (p3)</td><td>Wrong Direction / Angle</td><td>" + linkerTelemetry.countP3WrongDirection + "</td></tr>");
                report.println("<tr><td>2. Track Search (p3)</td><td>Exceeded Max Jump Velocity</td><td>" + linkerTelemetry.countP3Jump + "</td></tr>");
                report.println("<tr><td>2. Track Search (p3)</td><td>Morphological Size Mismatch</td><td>" + linkerTelemetry.countP3Size + "</td></tr>");
                report.println("<tr><td>2. Track Search (p3)</td><td>Photometric Flux Mismatch</td><td>" + linkerTelemetry.countP3Flux + "</td></tr>");

                report.println("<tr><td>3. Final Track</td><td>Insufficient Track Length</td><td>" + linkerTelemetry.countTrackTooShort + "</td></tr>");
                report.println("<tr><td>3. Final Track</td><td>Erratic Kinematic Rhythm</td><td>" + linkerTelemetry.countTrackErraticRhythm + "</td></tr>");
                report.println("<tr><td>3. Final Track</td><td>Duplicate Track (Ignored)</td><td>" + linkerTelemetry.countTrackDuplicate + "</td></tr>");
                report.println("</table>");

                report.println("</div>");
            }

            // =================================================================
            // 3. TARGET VISUALIZATIONS
            // =================================================================
            report.println("<h2>Target Visualizations</h2>");

            if (tracks.isEmpty()) {
                report.println("<div class='panel'><p>No moving targets were detected in this session.</p></div>");
            }

            for (TrackLinker.Track track : tracks) {
                if (track.points == null || track.points.isEmpty()) continue;

                java.util.Set<Integer> uniqueFrames = new java.util.HashSet<>();
                for (SourceExtractor.DetectedObject pt : track.points) {
                    uniqueFrames.add(pt.sourceFrameIndex);
                }

                if (uniqueFrames.size() == 1) {
                    double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE;
                    double minY = Double.MAX_VALUE, maxY = Double.MIN_VALUE;

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

                    int trackBoxWidth = (int) Math.round(maxX - minX) + trackCropPadding;
                    int trackBoxHeight = (int) Math.round(maxY - minY) + trackCropPadding;
                    int fixedCenterX = (int) Math.round((minX + maxX) / 2.0);
                    int fixedCenterY = (int) Math.round((minY + maxY) / 2.0);

                    // Needed for coordinate offsets when rendering shape images
                    int startX = fixedCenterX - (trackBoxWidth / 2);
                    int startY = fixedCenterY - (trackBoxHeight / 2);

                    // --- SPLIT LOGIC: ANOMALY vs. SINGLE STREAK ---
                    SourceExtractor.DetectedObject pt = track.points.get(0);
                    int frameIndex = pt.sourceFrameIndex;

                    if (track.isAnomaly) {
                        // --- 1. ANOMALY (OPTICAL FLASH) ---
                        report.println("<div class='detection-card streak-title' style='border-left-color: #ff3333; color: #ff3333;'>");
                        report.println("<div class='detection-title' style='color: #ff3333;'>High-Energy Anomaly (Optical Flash)</div>");

                        // --- Generate Detection Image (PNG) ---
                        short[][] rawImage = rawFrames.get(frameIndex);
                        short[][] croppedData = robustEdgeAwareCrop(rawImage, fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                        BufferedImage detectionImg = createDisplayImage(croppedData);
                        String detectionFileName = "anomaly_" + streakCounter + "_detection.png";
                        saveTrackImageLossless(detectionImg, new File(exportDir, detectionFileName));

                        // --- Generate Shape Map (PNG) ---
                        String shapeFileName = "anomaly_" + streakCounter + "_shape.png";
                        BufferedImage shapeImg = createSingleStreakShapeImage(track.points, trackBoxWidth, trackBoxHeight, startX, startY);
                        saveTrackImageLossless(shapeImg, new File(exportDir, shapeFileName));

                        // --- Generate Full Sequence GIF ---
                        String fullSeqFileName = "anomaly_" + streakCounter + "_sampled_sequence.gif";
                        List<BufferedImage> fullSequenceFrames = new ArrayList<>();
                        java.util.Set<Integer> mandatoryFrames = new java.util.HashSet<>();
                        mandatoryFrames.add(frameIndex);
                        List<Integer> sampledIndices = getRepresentativeSequence(rawFrames.size(), mandatoryFrames, maxFullSequenceFrames);
                        for (int idx : sampledIndices) {
                            short[][] cData = robustEdgeAwareCrop(rawFrames.get(idx), fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                            fullSequenceFrames.add(createDisplayImage(cData));
                        }
                        GifSequenceWriter.saveAnimatedGif(fullSequenceFrames, new File(exportDir, fullSeqFileName), gifBlinkSpeedMs);

                        // --- Generate 3-Frame "Context" GIF ---
                        String contextGifFileName = "anomaly_" + streakCounter + "_context.gif";
                        List<BufferedImage> contextFrames = new ArrayList<>();
                        int[] frameSequence = {frameIndex - 1, frameIndex, frameIndex + 1};
                        for (int idx : frameSequence) {
                            if (idx >= 0 && idx < rawFrames.size()) {
                                short[][] cData = robustEdgeAwareCrop(rawFrames.get(idx), fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                                BufferedImage aImg = createDisplayImage(cData);
                                Graphics2D g2d = aImg.createGraphics();
                                int localX = (int) Math.round(pt.x - startX);
                                int localY = (int) Math.round(pt.y - startY);
                                g2d.setColor(Color.WHITE);
                                g2d.setStroke(new BasicStroke(targetCircleStrokeWidth));
                                g2d.drawOval(localX - targetCircleRadius, localY - targetCircleRadius, targetCircleRadius * 2, targetCircleRadius * 2);
                                g2d.dispose();
                                contextFrames.add(aImg);
                            }
                        }
                        GifSequenceWriter.saveAnimatedGif(contextFrames, new File(exportDir, contextGifFileName), gifBlinkSpeedMs);

                        // --- Render HTML ---
                        report.println("<div class='image-container'>");
                        report.println("<div><a href='" + detectionFileName + "' target='_blank'><img src='" + detectionFileName + "' alt='Detection Image' /></a><br/><center><small>Detection Image</small></center></div>");
                        report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Shape Footprint' title='Streak Footprint' /></a><br/><center><small>Shape Footprint Map</small></center></div>");
                        report.println("<div><a href='" + fullSeqFileName + "' target='_blank'><img src='" + fullSeqFileName + "' alt='Sampled Time-Lapse' title='Sampled Time-Lapse (Unannotated)' /></a><br/><center><small>Sampled Time-Lapse (Unannotated)</small></center></div>");
                        report.println("<div><a href='" + contextGifFileName + "' target='_blank'><img src='" + contextGifFileName + "' alt='Anomaly Context Animation' title='Before / During / After' /></a><br/><center><small>Context (Before / Flash / After)</small></center></div>");
                        report.println("</div>");

                    } else {
                        // --- 2. STANDARD SINGLE STREAK ---
                        report.println("<div class='detection-card streak-title'>");
                        report.println("<div class='detection-title'>Single Streak Event #" + streakCounter + "</div>");

                        // --- Generate Detection Image (PNG) ---
                        short[][] rawImage = rawFrames.get(frameIndex);
                        short[][] croppedData = robustEdgeAwareCrop(rawImage, fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                        BufferedImage streakImg = createDisplayImage(croppedData);
                        String streakFileName = "streak_" + streakCounter + ".png";
                        saveTrackImageLossless(streakImg, new File(exportDir, streakFileName));

                        // --- Generate Shape Map (PNG) ---
                        String shapeFileName = "streak_" + streakCounter + "_shape.png";
                        BufferedImage streakShapeImg = createSingleStreakShapeImage(track.points, trackBoxWidth, trackBoxHeight, startX, startY);
                        saveTrackImageLossless(streakShapeImg, new File(exportDir, shapeFileName));

                        // --- Render HTML ---
                        report.println("<div class='image-container'>");
                        report.println("<div><a href='" + streakFileName + "' target='_blank'><img src='" + streakFileName + "' alt='Detection Image' /></a><br/><center><small>Detection Image</small></center></div>");
                        report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Shape Footprint' title='Streak Footprint' /></a><br/><center><small>Shape Footprint Map</small></center></div>");
                        report.println("</div>");
                    }

                    // --- COMMON FOOTER FOR BOTH ---
                    String coordStr = String.format(Locale.US, "X: %.1f, Y: %.1f", pt.x, pt.y);
                    report.println("<strong>Detection Coordinate:</strong><ul class='source-list'><li>" + pt.sourceFilename + " | <span class='coord-highlight'>" + coordStr + "</span></li></ul></div>");

                    streakCounter++;

                } else {
                    List<BufferedImage> objectCentricFrames = new ArrayList<>();
                    List<BufferedImage> starCentricFrames = new ArrayList<>();

                    double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE;
                    double minY = Double.MAX_VALUE, maxY = Double.MIN_VALUE;

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

                    int trackBoxWidth = (int) Math.round(maxX - minX) + trackCropPadding;
                    int trackBoxHeight = (int) Math.round(maxY - minY) + trackCropPadding;
                    int fixedCenterX = (int) Math.round((minX + maxX) / 2.0);
                    int fixedCenterY = (int) Math.round((minY + maxY) / 2.0);

                    int startX = fixedCenterX - (trackBoxWidth / 2);
                    int startY = fixedCenterY - (trackBoxHeight / 2);

                    // --- NEW: Generate Full Sequence Unannotated Star-Centric GIF ---
                    List<BufferedImage> fullSequenceStarFrames = new ArrayList<>();
                    java.util.Set<Integer> mandatoryFrames = new java.util.HashSet<>();
                    for (SourceExtractor.DetectedObject pt : track.points) {
                        mandatoryFrames.add(pt.sourceFrameIndex);
                    }
                    List<Integer> sampledIndices = getRepresentativeSequence(rawFrames.size(), mandatoryFrames, maxFullSequenceFrames);
                    for (int idx : sampledIndices) {
                        short[][] croppedFullData = robustEdgeAwareCrop(rawFrames.get(idx), fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                        fullSequenceStarFrames.add(createDisplayImage(croppedFullData));
                    }

                    java.util.Set<Integer> processedFrames = new java.util.HashSet<>();

                    for (SourceExtractor.DetectedObject pt : track.points) {
                        if (processedFrames.contains(pt.sourceFrameIndex)) continue;
                        processedFrames.add(pt.sourceFrameIndex);

                        short[][] rawImage = rawFrames.get(pt.sourceFrameIndex);

                        if (!track.isStreakTrack) {
                            short[][] croppedObjData = robustEdgeAwareCrop(rawImage, (int) pt.x, (int) pt.y, trackObjectCentricCropSize, trackObjectCentricCropSize);
                            BufferedImage objFrame = createDisplayImage(croppedObjData);
                            objectCentricFrames.add(objFrame);
                        }

                        short[][] croppedStarData = robustEdgeAwareCrop(rawImage, fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                        BufferedImage starFrameGray = createDisplayImage(croppedStarData);

                        Graphics2D g2d = starFrameGray.createGraphics();
                        int localObjX = (int) Math.round(pt.x - startX);
                        int localObjY = (int) Math.round(pt.y - startY);

                        g2d.setColor(java.awt.Color.WHITE);
                        g2d.setStroke(new java.awt.BasicStroke(targetCircleStrokeWidth));
                        g2d.drawOval(localObjX - targetCircleRadius, localObjY - targetCircleRadius, targetCircleRadius * 2, targetCircleRadius * 2);
                        g2d.dispose();
                        starCentricFrames.add(starFrameGray);
                    }

                    // --- Generate the Track Shape Image ---
                    String shapeFileName = "track_" + trackCounter + "_shape.png";
                    BufferedImage shapeImg = createTrackShapeImage(track, trackBoxWidth, trackBoxHeight, startX, startY);
                    saveTrackImageLossless(shapeImg, new File(exportDir, shapeFileName));

                    if (track.isStreakTrack) {
                        String starFileName = "streak_track_" + trackCounter + "_star_centric.gif";
                        File starFile = new File(exportDir, starFileName);
                        GifSequenceWriter.saveAnimatedGif(starCentricFrames, starFile, gifBlinkSpeedMs);

                        report.println("<div class='detection-card streak-title'>");
                        report.println("<div class='detection-title'>Multi-Frame Streak Track #" + trackCounter + "</div>");

                        // --- Include both Star Centric and Shape Images ---
                        report.println("<div class='image-container'>");
                        report.println("<a href='" + starFileName + "' target='_blank'><img src='" + starFileName + "' alt='Star Centric Animation' title='Star-Centric' /></a>");
                        report.println("<a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Track Shape Map' title='Track Shape Map' /></a>");
                        report.println("</div>");

                    } else {
                        String objFileName = "track_" + trackCounter + "_object_centric.gif";
                        String starFileName = "track_" + trackCounter + "_star_centric.gif";
                        String fullSeqFileName = "track_" + trackCounter + "_sampled_sequence.gif";

                        File objFile = new File(exportDir, objFileName);
                        GifSequenceWriter.saveAnimatedGif(objectCentricFrames, objFile, gifBlinkSpeedMs);

                        File starFile = new File(exportDir, starFileName);
                        GifSequenceWriter.saveAnimatedGif(starCentricFrames, starFile, gifBlinkSpeedMs);
                        
                        File fullSeqFile = new File(exportDir, fullSeqFileName);
                        GifSequenceWriter.saveAnimatedGif(fullSequenceStarFrames, fullSeqFile, gifBlinkSpeedMs);

                        report.println("<div class='detection-card'>");
                        report.println("<div class='detection-title'>Moving Target Track #" + trackCounter + "</div>");

                        // --- Include Object, Star, and Shape Images ---
                        report.println("<div class='image-container'>");
                        report.println("<div><a href='" + objFileName + "' target='_blank'><img src='" + objFileName + "' alt='Object Centric' title='Object-Centric' /></a><br/><center><small>Object Centric</small></center></div>");
                        report.println("<div><a href='" + starFileName + "' target='_blank'><img src='" + starFileName + "' alt='Star Centric' title='Star-Centric' /></a><br/><center><small>Star Centric</small></center></div>");
                        report.println("<div><a href='" + fullSeqFileName + "' target='_blank'><img src='" + fullSeqFileName + "' alt='Sampled Time-Lapse' title='Sampled Time-Lapse (Unannotated)' /></a><br/><center><small>Sampled Time-Lapse (Unannotated)</small></center></div>");
                        report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' alt='Track Shape Map' title='Track Shape Map' /></a><br/><center><small>Track Shape Map</small></center></div>");
                        report.println("</div>");
                    }

                    // --- Exact Coordinates for every point in the Track ---
                    report.println("<strong>Detection Coordinates & Frames:</strong><ul class='source-list'>");
                    for (int i = 0; i < track.points.size(); i++) {
                        SourceExtractor.DetectedObject pt = track.points.get(i);
                        String coordStr = String.format(Locale.US, "X: %.1f, Y: %.1f", pt.x, pt.y);
                        // We put the index number [1], [2], etc., so the user can easily map it to the red dots on the Shape Image
                        report.println("<li>[" + (i + 1) + "] " + pt.sourceFilename + " | <span class='coord-highlight'>" + coordStr + "</span></li>");
                    }
                    report.println("</ul></div>");

                    trackCounter++;
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