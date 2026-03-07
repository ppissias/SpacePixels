package spv.util;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ImageDisplayUtils {

    // =================================================================
    // CONFIGURATION PARAMETERS
    // =================================================================

    // --- Auto-Stretch Parameters (Standard) ---
    /** Subtractions from the mean (in sigmas) to set the black point. Ensures dark sky. */
    public static double autoStretchBlackSigma = 0.5;
    /** Additions to the mean (in sigmas) to set the white point. Faint targets hit this and turn white. */
    public static double autoStretchWhiteSigma = 5.0;

    // --- Soft Stretch Parameters ---
    /** Multiplier to set the black point near the mean in the soft stretch method. */
    public static double softStretchBlackRatio = 0.9;
    /** Multiplier to set the white point in the soft stretch method. */
    public static double softStretchWhiteRatio = 0.8;

    // --- Export & Cropping Parameters ---
    /** Speed of the exported GIF animations in milliseconds per frame. */
    public static int gifBlinkSpeedMs = 300;

    /** Base padding (in pixels) added to the width and height of multi-frame track crops. */
    public static int trackCropPadding = 100;

    /** The static size (width & height in pixels) for the tight, object-centric GIF crops. */
    public static int trackObjectCentricCropSize = 150;

    /** Padding (in pixels) added when exporting a single streak image. */
    public static int singleStreakExportPadding = 50;

    /** Multiplier applied to elongation to estimate the full length of a streak for cropping. */
    public static double streakElongationCropMultiplier = 10.0;

    // --- Annotation Tools (For GIFs) ---
    /** Radius of the white targeting circle drawn on the star-centric GIFs. */
    public static int targetCircleRadius = 15;
    /** Stroke width (thickness) of the white targeting circle. */
    public static float targetCircleStrokeWidth = 2.0f;


    // =================================================================
    // DATA MODELS
    // =================================================================

    /**
     * Data object to hold the comprehensive statistical profile of a FITS image array.
     */
    public static class FitsDataAnalysis {
        public int width;
        public int height;
        public long totalPixels;

        public short minRaw;
        public short maxRaw;
        public int countRawZero;
        public int countRawMin; // -32768
        public int countRawMax; // 32767

        public int minUnsigned;
        public int maxUnsigned;
        public int countUnsignedZero;
        public int countUnsignedMax; // 65535

        public double meanUnsigned;
        public double sigmaUnsigned;

        public double autoBlackPoint;
        public double autoWhitePoint;
    }


    // =================================================================
    // IMAGE RENDERING & CROPPING
    // =================================================================

    public static BufferedImage createDisplayImage(short[][] imageData) {
        int height = imageData.length;
        int width = imageData[0].length;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = image.getRaster();

        // 1. Find the Mean (Background Level)
        long sum = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                sum += (imageData[y][x] + 32768);
            }
        }
        double mean = (double) sum / (width * height);

        // 2. Find the Standard Deviation (Sigma)
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

        // 3. The Dynamic Sigma Auto-Stretch (Parameterized)
        double blackPoint = mean - (autoStretchBlackSigma * sigma);
        double whitePoint = mean + (autoStretchWhiteSigma * sigma);

        if (blackPoint < 0) blackPoint = 0;
        if (whitePoint > actualMax) whitePoint = actualMax;

        double range = whitePoint - blackPoint;
        if (range <= 0) range = 1.0;

        // 4. Map and Render
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
                int val = imageData[y][x] & 0xFFFF;
                if (val < min) min = val;
                if (val > max) max = val;
                sum += val;
            }
        }

        double mean = (double) sum / (width * height);

        // THE CONTRAST TUNING (Parameterized)
        double blackPoint = min + ((mean - min) * softStretchBlackRatio);
        double whitePoint = min + ((max - min) * softStretchWhiteRatio);

        double range = whitePoint - blackPoint;
        if (range <= 0) range = 1;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int val = imageData[y][x] & 0xFFFF;

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
        // Parameterized estimation
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
                    // Java's Short.MIN_VALUE offsets perfectly for our background stretch
                    cropped[y][x] = -32768;
                }
            }
        }
        return cropped;
    }


    public static void exportTrackVisualizations(List<TrackLinker.Track> tracks, TrackLinker.TelemetryReport telemetry, List<short[][]> rawFrames, File exportDir) throws IOException {
        if (!exportDir.exists()) exportDir.mkdirs();

        int trackCounter = 1;
        int streakCounter = 1;

        File reportFile = new File(exportDir, "detection_report.html");

        try (java.io.PrintWriter report = new java.io.PrintWriter(new java.io.FileWriter(reportFile))) {

            // --- HTML HEADER & CSS ---
            report.println("<!DOCTYPE html><html><head><title>Detection Report</title><style>");
            report.println("body { font-family: 'Segoe UI', sans-serif; background-color: #1e1e1e; color: #e0e0e0; margin: 40px; }");
            report.println("h1 { color: #ffffff; border-bottom: 2px solid #444; padding-bottom: 10px; }");
            report.println(".detection-card { background: #2d2d2d; padding: 20px; margin-bottom: 30px; border-radius: 8px; box-shadow: 0 4px 8px rgba(0,0,0,0.5); }");
            report.println(".detection-title { font-size: 1.2em; font-weight: bold; color: #4da6ff; margin-bottom: 15px; }");
            report.println(".streak-title { color: #ff9933; }");
            report.println(".image-container { display: flex; gap: 20px; margin-bottom: 15px; align-items: flex-start; }");
            report.println("img { border: 1px solid #555; border-radius: 4px; max-width: 100%; height: auto; }");
            report.println(".source-list { list-style-type: none; padding: 0; display: flex; flex-wrap: wrap; gap: 8px; }");
            report.println(".source-list li { background: #3d3d3d; padding: 5px 10px; border-radius: 4px; font-size: 0.9em; font-family: monospace; }");

            // NEW CSS: Telemetry Table Styling
            report.println(".telemetry-table { width: 100%; border-collapse: collapse; margin-bottom: 20px; font-size: 0.95em; }");
            report.println(".telemetry-table th, .telemetry-table td { padding: 10px; text-align: left; border-bottom: 1px solid #444; }");
            report.println(".telemetry-table th { color: #4da6ff; background-color: #333; }");
            report.println(".telemetry-table tr:hover { background-color: #3a3a3a; }");
            report.println(".telemetry-summary { font-size: 1.1em; font-weight: bold; color: #44ff44; margin-top: 15px; }");

            report.println("</style></head><body><h1>Detection Report</h1>");

            // =================================================================
            // --- NEW: INJECT TELEMETRY HTML BLOCK ---
            // =================================================================
            if (telemetry != null) {
                report.println("<div class='detection-card' style='border-left: 5px solid #4da6ff;'>");
                report.println("<div class='detection-title'>Tracking Telemetry & Rejection Statistics</div>");

                report.println("<table class='telemetry-table'>");
                report.println("<tr><th>Filter Phase</th><th>Rejection Reason</th><th>Points Rejected</th></tr>");

                // Baseline Phase
                report.println("<tr><td>1. Baseline (p1 &rarr; p2)</td><td>Stationary / Jitter</td><td>" + telemetry.countBaselineJitter + "</td></tr>");
                report.println("<tr><td>1. Baseline (p1 &rarr; p2)</td><td>Exceeded Max Jump Velocity</td><td>" + telemetry.countBaselineJump + "</td></tr>");
                report.println("<tr><td>1. Baseline (p1 &rarr; p2)</td><td>Morphological Size Mismatch</td><td>" + telemetry.countBaselineSize + "</td></tr>");
                report.println("<tr><td>1. Baseline (p1 &rarr; p2)</td><td>Photometric Flux Mismatch</td><td>" + telemetry.countBaselineFlux + "</td></tr>");

                // Search Phase
                report.println("<tr><td>2. Track Search (p3)</td><td>Off Predicted Trajectory Line</td><td>" + telemetry.countP3NotLine + "</td></tr>");
                report.println("<tr><td>2. Track Search (p3)</td><td>Wrong Direction / Angle</td><td>" + telemetry.countP3WrongDirection + "</td></tr>");
                report.println("<tr><td>2. Track Search (p3)</td><td>Exceeded Max Jump Velocity</td><td>" + telemetry.countP3Jump + "</td></tr>");
                report.println("<tr><td>2. Track Search (p3)</td><td>Morphological Size Mismatch</td><td>" + telemetry.countP3Size + "</td></tr>");
                report.println("<tr><td>2. Track Search (p3)</td><td>Photometric Flux Mismatch</td><td>" + telemetry.countP3Flux + "</td></tr>");

                // Final Track Phase
                report.println("<tr><td>3. Final Track</td><td>Insufficient Track Length</td><td>" + telemetry.countTrackTooShort + "</td></tr>");
                report.println("<tr><td>3. Final Track</td><td>Erratic Kinematic Rhythm</td><td>" + telemetry.countTrackErraticRhythm + "</td></tr>");
                report.println("<tr><td>3. Final Track</td><td>Duplicate Track (Ignored)</td><td>" + telemetry.countTrackDuplicate + "</td></tr>");
                report.println("</table>");

                // Success Summary
                int totalTargets = telemetry.streakTracksFound + telemetry.pointTracksFound;
                report.println("<div class='telemetry-summary'>Total Confirmed Targets: " + totalTargets + " </div>");
                report.println("<div style='font-size:0.9em; color:#aaa; margin-top:5px;'>(" + telemetry.streakTracksFound + " Fast Streaks | " + telemetry.pointTracksFound + " Slow Point Tracks)</div>");

                report.println("</div>");
            }

            // =================================================================
            // --- EXISTING VISUALIZATION LOOP ---
            // =================================================================
            for (TrackLinker.Track track : tracks) {
                if (track.points == null || track.points.isEmpty()) continue;

                java.util.Set<Integer> uniqueFrames = new java.util.HashSet<>();
                for (SourceExtractor.DetectedObject pt : track.points) {
                    uniqueFrames.add(pt.sourceFrameIndex);
                }

                // ... [The rest of your exact Case 1 and Case 2 code remains here unchanged] ...

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

                    int frameIndex = track.points.get(0).sourceFrameIndex;
                    short[][] rawImage = rawFrames.get(frameIndex);

                    short[][] croppedData = robustEdgeAwareCrop(rawImage, fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                    BufferedImage streakImg = createDisplayImage(croppedData);

                    String streakFileName = "streak_" + streakCounter + ".png";
                    File streakFile = new File(exportDir, streakFileName);
                    saveTrackImageLossless(streakImg, streakFile);

                    report.println("<div class='detection-card'>");
                    report.println("<div class='detection-title streak-title'>Single Streak Event #" + streakCounter + "</div>");
                    report.println("<div class='image-container'><img src='" + streakFileName + "' alt='Streak Image' /></div>");
                    report.println("<strong>Source Frame:</strong><ul class='source-list'><li>" + track.points.get(0).sourceFilename + "</li></ul></div>");
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

                    java.util.Set<Integer> processedFrames = new java.util.HashSet<>();
                    java.util.Set<String> chronologicalSourceFiles = new java.util.LinkedHashSet<>();

                    for (SourceExtractor.DetectedObject pt : track.points) {
                        chronologicalSourceFiles.add(pt.sourceFilename);
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
                        int startX = fixedCenterX - (trackBoxWidth / 2);
                        int startY = fixedCenterY - (trackBoxHeight / 2);
                        int localObjX = (int) Math.round(pt.x - startX);
                        int localObjY = (int) Math.round(pt.y - startY);

                        g2d.setColor(java.awt.Color.WHITE);
                        g2d.setStroke(new java.awt.BasicStroke(targetCircleStrokeWidth));
                        g2d.drawOval(localObjX - targetCircleRadius, localObjY - targetCircleRadius, targetCircleRadius * 2, targetCircleRadius * 2);
                        g2d.dispose();
                        starCentricFrames.add(starFrameGray);
                    }

                    report.println("<div class='detection-card'>");
                    if (track.isStreakTrack) {
                        String starFileName = "streak_track_" + trackCounter + "_star_centric.gif";
                        File starFile = new File(exportDir, starFileName);
                        GifSequenceWriter.saveAnimatedGif(starCentricFrames, starFile, gifBlinkSpeedMs);

                        report.println("<div class='detection-title streak-title'>Multi-Frame Streak Track #" + trackCounter + "</div>");
                        report.println("<div class='image-container'><img src='" + starFileName + "' alt='Star Centric Animation' title='Star-Centric' /></div>");
                    } else {
                        String objFileName = "track_" + trackCounter + "_object_centric.gif";
                        String starFileName = "track_" + trackCounter + "_star_centric.gif";

                        File objFile = new File(exportDir, objFileName);
                        GifSequenceWriter.saveAnimatedGif(objectCentricFrames, objFile, gifBlinkSpeedMs);

                        File starFile = new File(exportDir, starFileName);
                        GifSequenceWriter.saveAnimatedGif(starCentricFrames, starFile, gifBlinkSpeedMs);

                        report.println("<div class='detection-title'>Moving Target Track #" + trackCounter + "</div>");
                        report.println("<div class='image-container'><img src='" + objFileName + "' alt='Object Centric Animation' title='Object-Centric' /><img src='" + starFileName + "' alt='Star Centric Animation' title='Star-Centric' /></div>");
                    }

                    report.println("<strong>Sequence Frames (Chronological):</strong><ul class='source-list'>");
                    for (String fileName : chronologicalSourceFiles) {
                        report.println("<li>" + fileName + "</li>");
                    }
                    report.println("</ul></div>");
                    trackCounter++;
                }
            }
            report.println("</body></html>");
        }
        System.out.println("\nFinished exporting visualizations and generated HTML report at: " + reportFile.getAbsolutePath());
    }

    public static void exportTrackVisualizationsTxtReport(List<TrackLinker.Track> tracks, List<short[][]> rawFrames, File exportDir) throws IOException {
        if (!exportDir.exists()) exportDir.mkdirs();

        int trackCounter = 1;
        int streakCounter = 1;

        File reportFile = new File(exportDir, "detection_report.txt");

        try (java.io.PrintWriter report = new java.io.PrintWriter(new java.io.FileWriter(reportFile))) {
            report.println("==================================================");
            report.println("               DETECTION REPORT                   ");
            report.println("==================================================");
            report.println();

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
                        if (pt.x < minX) minX = pt.x;
                        if (pt.x > maxX) maxX = pt.x;
                        if (pt.y < minY) minY = pt.y;
                        if (pt.y > maxY) maxY = pt.y;
                    }

                    int trackBoxWidth = (int) Math.round(maxX - minX) + trackCropPadding;
                    int trackBoxHeight = (int) Math.round(maxY - minY) + trackCropPadding;
                    int fixedCenterX = (int) Math.round((minX + maxX) / 2.0);
                    int fixedCenterY = (int) Math.round((minY + maxY) / 2.0);

                    int frameIndex = track.points.get(0).sourceFrameIndex;
                    short[][] rawImage = rawFrames.get(frameIndex);

                    short[][] croppedData = robustEdgeAwareCrop(rawImage, fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                    BufferedImage streakImg = createDisplayImage(croppedData);

                    File streakFile = new File(exportDir, "streak_" + streakCounter + ".png");
                    saveTrackImageLossless(streakImg, streakFile);

                    report.println("Export: " + streakFile.getName());
                    report.println("Source: " + track.points.get(0).sourceFilename);
                    report.println();

                    streakCounter++;
                } else {
                    List<BufferedImage> objectCentricFrames = new ArrayList<>();
                    List<BufferedImage> starCentricFrames = new ArrayList<>();

                    double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE;
                    double minY = Double.MAX_VALUE, maxY = Double.MIN_VALUE;

                    for (SourceExtractor.DetectedObject pt : track.points) {
                        if (pt.x < minX) minX = pt.x;
                        if (pt.x > maxX) maxX = pt.x;
                        if (pt.y < minY) minY = pt.y;
                        if (pt.y > maxY) maxY = pt.y;
                    }

                    int trackBoxWidth = (int) Math.round(maxX - minX) + trackCropPadding;
                    int trackBoxHeight = (int) Math.round(maxY - minY) + trackCropPadding;
                    int fixedCenterX = (int) Math.round((minX + maxX) / 2.0);
                    int fixedCenterY = (int) Math.round((minY + maxY) / 2.0);

                    java.util.Set<Integer> processedFrames = new java.util.HashSet<>();
                    java.util.Set<String> chronologicalSourceFiles = new java.util.LinkedHashSet<>();

                    for (SourceExtractor.DetectedObject pt : track.points) {
                        chronologicalSourceFiles.add(pt.sourceFilename);
                        if (processedFrames.contains(pt.sourceFrameIndex)) continue;
                        processedFrames.add(pt.sourceFrameIndex);

                        short[][] rawImage = rawFrames.get(pt.sourceFrameIndex);

                        short[][] croppedObjData = robustEdgeAwareCrop(rawImage, (int) pt.x, (int) pt.y, trackObjectCentricCropSize, trackObjectCentricCropSize);
                        BufferedImage objFrame = createDisplayImage(croppedObjData);
                        objectCentricFrames.add(objFrame);

                        short[][] croppedStarData = robustEdgeAwareCrop(rawImage, fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                        BufferedImage starFrameGray = createDisplayImage(croppedStarData);

                        Graphics2D g2d = starFrameGray.createGraphics();
                        int startX = fixedCenterX - (trackBoxWidth / 2);
                        int startY = fixedCenterY - (trackBoxHeight / 2);
                        int localObjX = (int) Math.round(pt.x - startX);
                        int localObjY = (int) Math.round(pt.y - startY);

                        g2d.setColor(java.awt.Color.WHITE);
                        g2d.setStroke(new java.awt.BasicStroke(targetCircleStrokeWidth));
                        g2d.drawOval(localObjX - targetCircleRadius, localObjY - targetCircleRadius, targetCircleRadius * 2, targetCircleRadius * 2);
                        g2d.dispose();
                        starCentricFrames.add(starFrameGray);
                    }

                    File objFile = new File(exportDir, "track_" + trackCounter + "_object_centric.gif");
                    GifSequenceWriter.saveAnimatedGif(objectCentricFrames, objFile, gifBlinkSpeedMs);

                    File starFile = new File(exportDir, "track_" + trackCounter + "_star_centric.gif");
                    GifSequenceWriter.saveAnimatedGif(starCentricFrames, starFile, gifBlinkSpeedMs);

                    report.println("Export: " + objFile.getName() + " | " + starFile.getName());
                    report.println("Sources: ");
                    for (String fileName : chronologicalSourceFiles) {
                        report.println("  - " + fileName);
                    }
                    report.println();

                    trackCounter++;
                }
            }
        }
        System.out.println("\nFinished exporting all track visualizations and report to: " + exportDir.getAbsolutePath());
    }

    public static void exportTrackVisualizationsSimple(List<TrackLinker.Track> tracks, List<short[][]> rawFrames, File exportDir) throws IOException {
        if (!exportDir.exists()) exportDir.mkdirs();

        int trackCounter = 1;
        int streakCounter = 1;

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
                    if (pt.x < minX) minX = pt.x;
                    if (pt.x > maxX) maxX = pt.x;
                    if (pt.y < minY) minY = pt.y;
                    if (pt.y > maxY) maxY = pt.y;
                }

                int trackBoxWidth = (int) Math.round(maxX - minX) + trackCropPadding;
                int trackBoxHeight = (int) Math.round(maxY - minY) + trackCropPadding;
                int fixedCenterX = (int) Math.round((minX + maxX) / 2.0);
                int fixedCenterY = (int) Math.round((minY + maxY) / 2.0);

                int frameIndex = track.points.get(0).sourceFrameIndex;
                short[][] rawImage = rawFrames.get(frameIndex);

                short[][] croppedData = robustEdgeAwareCrop(rawImage, fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                BufferedImage streakImg = createDisplayImage(croppedData);

                File streakFile = new File(exportDir, "streak_" + streakCounter + ".png");
                saveTrackImageLossless(streakImg, streakFile);
                streakCounter++;
            } else {
                List<BufferedImage> objectCentricFrames = new ArrayList<>();
                List<BufferedImage> starCentricFrames = new ArrayList<>();

                double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE;
                double minY = Double.MAX_VALUE, maxY = Double.MIN_VALUE;

                for (SourceExtractor.DetectedObject pt : track.points) {
                    if (pt.x < minX) minX = pt.x;
                    if (pt.x > maxX) maxX = pt.x;
                    if (pt.y < minY) minY = pt.y;
                    if (pt.y > maxY) maxY = pt.y;
                }

                int trackBoxWidth = (int) Math.round(maxX - minX) + trackCropPadding;
                int trackBoxHeight = (int) Math.round(maxY - minY) + trackCropPadding;
                int fixedCenterX = (int) Math.round((minX + maxX) / 2.0);
                int fixedCenterY = (int) Math.round((minY + maxY) / 2.0);

                java.util.Set<Integer> processedFrames = new java.util.HashSet<>();

                for (SourceExtractor.DetectedObject pt : track.points) {
                    if (processedFrames.contains(pt.sourceFrameIndex)) continue;
                    processedFrames.add(pt.sourceFrameIndex);

                    short[][] rawImage = rawFrames.get(pt.sourceFrameIndex);

                    short[][] croppedObjData = robustEdgeAwareCrop(rawImage, (int) pt.x, (int) pt.y, trackObjectCentricCropSize, trackObjectCentricCropSize);
                    BufferedImage objFrame = createDisplayImage(croppedObjData);
                    objectCentricFrames.add(objFrame);

                    short[][] croppedStarData = robustEdgeAwareCrop(rawImage, fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                    BufferedImage starFrameGray = createDisplayImage(croppedStarData);

                    Graphics2D g2d = starFrameGray.createGraphics();
                    int startX = fixedCenterX - (trackBoxWidth / 2);
                    int startY = fixedCenterY - (trackBoxHeight / 2);
                    int localObjX = (int) Math.round(pt.x - startX);
                    int localObjY = (int) Math.round(pt.y - startY);

                    g2d.setColor(Color.WHITE);
                    g2d.setStroke(new BasicStroke(targetCircleStrokeWidth));
                    g2d.drawOval(localObjX - targetCircleRadius, localObjY - targetCircleRadius, targetCircleRadius * 2, targetCircleRadius * 2);
                    g2d.dispose();

                    starCentricFrames.add(starFrameGray);
                }

                File objFile = new File(exportDir, "track_" + trackCounter + "_object_centric.gif");
                GifSequenceWriter.saveAnimatedGif(objectCentricFrames, objFile, gifBlinkSpeedMs);

                File starFile = new File(exportDir, "track_" + trackCounter + "_star_centric.gif");
                GifSequenceWriter.saveAnimatedGif(starCentricFrames, starFile, gifBlinkSpeedMs);

                trackCounter++;
            }
        }
    }

    // =================================================================
    // ANALYTICS & DIAGNOSTICS
    // =================================================================

    /**
     * Scans the entire FITS image array, prints a comprehensive statistical profile,
     * and returns the data inside a FitsDataAnalysis object.
     */
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
                int unsignedVal = rawVal & 0xFFFF;

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
                int unsignedVal = imageData[y][x] & 0xFFFF;
                double diff = unsignedVal - data.meanUnsigned;
                sumSqDiff += (diff * diff);
            }
        }

        double variance = sumSqDiff / data.totalPixels;
        data.sigmaUnsigned = Math.sqrt(variance);

        data.autoBlackPoint = data.meanUnsigned - (autoStretchBlackSigma * data.sigmaUnsigned);
        data.autoWhitePoint = data.meanUnsigned + (autoStretchWhiteSigma * data.sigmaUnsigned);

        // --- Console Output for Debugging ---
        System.out.println("\n==================================================");
        System.out.println("=== FITS DATA ARRAY ANALYSIS ===");
        System.out.println("==================================================");
        System.out.println("Dimensions : " + data.width + " x " + data.height + " (" + data.totalPixels + " pixels)");

        System.out.println("\n--- RAW SIGNED SHORTS (Java Native) ---");
        System.out.println("Min Raw Value    : " + data.minRaw);
        System.out.println("Max Raw Value    : " + data.maxRaw);
        System.out.println("Pixels @ 0       : " + data.countRawZero);
        System.out.println("Pixels @ -32768  : " + data.countRawMin);
        System.out.println("Pixels @ +32767  : " + data.countRawMax);

        System.out.println("\n--- MASKED UNSIGNED INTS (& 0xFFFF) ---");
        System.out.println("Min Unsigned     : " + data.minUnsigned);
        System.out.println("Max Unsigned     : " + data.maxUnsigned);
        System.out.println("Pixels @ 0       : " + data.countUnsignedZero);
        System.out.println("Pixels @ 65535   : " + data.countUnsignedMax);

        System.out.println("\n--- STATISTICAL PROFILE (Unsigned) ---");
        System.out.println("Mean (Background): " + String.format("%.2f", data.meanUnsigned));
        System.out.println("Sigma (Std Dev)  : " + String.format("%.2f", data.sigmaUnsigned));

        System.out.println("Auto Black Point : " + String.format("%.2f", data.autoBlackPoint));
        System.out.println("Auto White Point : " + String.format("%.2f", data.autoWhitePoint));
        System.out.println("==================================================\n");

        return data; // Return the fully populated object
    }
}