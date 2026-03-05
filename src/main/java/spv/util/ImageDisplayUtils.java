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


    public static BufferedImage createDisplayImage(short[][] imageData) {
        int height = imageData.length;
        int width = imageData[0].length;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = image.getRaster();

        // =================================================================
        // STEP 1: Find the Mean (Background Level)
        // =================================================================
        long sum = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // THE FIX: Add 32768 to reverse the signed FITS shift
                sum += (imageData[y][x] + 32768);
            }
        }
        double mean = (double) sum / (width * height);

        // =================================================================
        // STEP 2: Find the Standard Deviation (Sigma)
        // =================================================================
        double sumSqDiff = 0;
        int actualMax = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // THE FIX: Add 32768
                int val = imageData[y][x] + 32768;
                if (val > actualMax) actualMax = val;

                double diff = val - mean;
                sumSqDiff += (diff * diff);
            }
        }
        double variance = sumSqDiff / (width * height);
        double sigma = Math.sqrt(variance);

        // =================================================================
        // STEP 3: The Dynamic Sigma Auto-Stretch
        // =================================================================
        // BLACK POINT: Set just slightly below the mean.
        // This ensures the sky is near zero/black, but we don't clip the noise completely.
        double blackPoint = mean - (0.5 * sigma);

        // WHITE POINT: The magic number. We cap brightness at 5 standard deviations
        // above the background. Faint streaks will hit this ceiling and become pure white!
        double whitePoint = mean + (5.0 * sigma);

        // Safety catches
        if (blackPoint < 0) blackPoint = 0;
        if (whitePoint > actualMax) whitePoint = actualMax; // Don't overshoot the real max

        double range = whitePoint - blackPoint;
        if (range <= 0) range = 1.0;

        // =================================================================
        // STEP 4: Map and Render
        // =================================================================
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // THE FIX: Add 32768
                int val = imageData[y][x] + 32768;

                // REMOVED: The old "Black Sun" sensor overflow fix was deleted here!

                // Apply the new contrast bounds
                double adjustedVal = val - blackPoint;
                if (adjustedVal < 0) adjustedVal = 0;
                if (adjustedVal > range) adjustedVal = range;

                // Normalize between 0.0 and 1.0
                double normalized = adjustedVal / range;

                // Apply the Square Root stretch to further boost faint midtones
                double stretched = Math.sqrt(normalized);

                // Scale to 8-bit visual bounds (0 to 255)
                int displayValue = (int) (stretched * 255.0);

                // Final safety clamp
                if (displayValue > 255) displayValue = 255;
                if (displayValue < 0) displayValue = 0;

                raster.setSample(x, y, 0, displayValue);
            }
        }

        return image;
    }

    public static BufferedImage createDisplayImageSoft(short[][] imageData) {
        // FITS data is row-major: the first dimension is Height (Y), the second is Width (X)
        int height = imageData.length;
        int width = imageData[0].length;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = image.getRaster();

        // 1. Find Min, Max, and the Total Sum (to find the background average)
        long sum = 0;
        int min = 65535;
        int max = 0;

        // Iterate over Y first, then X for row-major arrays
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Access data correctly as [y][x]
                int val = imageData[y][x] & 0xFFFF;
                if (val < min) min = val;
                if (val > max) max = val;
                sum += val;
            }
        }

        // Calculate the average pixel value (this accurately represents the sky background)
        double mean = (double) sum / (width * height);

        // --- THE CONTRAST TUNING ---

        // BLACK POINT: Set the floor near the mean to crush the gray background.
        double blackPoint = min + ((mean - min) * 0.9);

        // WHITE POINT: Lower the ceiling so fainter stars become pure white faster.
        double whitePoint = min + ((max - min) * 0.8);

        double range = whitePoint - blackPoint;
        if (range <= 0) range = 1; // Safety catch

        // 2. Map the data using the new contrast bounds
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Access data correctly as [y][x]
                int val = imageData[y][x] & 0xFFFF;

                // Subtract the black point. If it's darker than the black point, clamp it to 0.
                double adjustedVal = val - blackPoint;
                if (adjustedVal < 0) adjustedVal = 0;

                // If it's brighter than the white point, clamp it to the max range.
                if (adjustedVal > range) adjustedVal = range;

                // Normalize between 0.0 and 1.0 based on our new tighter range
                double normalized = adjustedVal / range;

                // Apply the Square Root stretch to boost the midtones
                double stretched = Math.sqrt(normalized);

                // Scale up to standard 8-bit visual bounds (0 to 255)
                int displayValue = (int) (stretched * 255.0);

                // Final safety clamp
                if (displayValue > 255) displayValue = 255;
                if (displayValue < 0) displayValue = 0;

                raster.setSample(x, y, 0, displayValue);
            }
        }

        return image;
    }


    /**
     * Extracts a region from the image, automatically handling edges with black padding.
     */
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
                    croppedData[y][x] = 0; // Pad out-of-bounds areas with black
                }
            }
        }

        // Convert the 2D short array directly into a visual image using your display method
        return createDisplayImage(croppedData);
    }

    public static BufferedImage exportSingleStreak(short[][] rawImage, SourceExtractor.DetectedObject streak) {
        // Assume elongation roughly maps to pixel length based on your earlier math.
        // We multiply by a factor (e.g., 10) and add 50 pixels of padding.
        int estimatedLength = (int) (streak.elongation * 10) + 50;

        // Cap the maximum size so it doesn't crash if the streak is somehow infinite
        int cropSize = Math.min(estimatedLength, Math.max(rawImage.length, rawImage[0].length));

        int cx = (int) Math.round(streak.x);
        int cy = (int) Math.round(streak.y);

        return cropRegionToImage(rawImage, cx, cy, cropSize, cropSize);
    }

    /**
     * Saves a BufferedImage to disk in a mathematically lossless PNG format.
     * * @param image The BufferedImage to save (e.g., your cropped target).
     * @param outputFile The destination file (should end with .png).
     */
    public static void saveTrackImageLossless(BufferedImage image, File outputFile) throws IOException {
        if (image == null) {
            System.err.println("Warning: Attempted to save a null image. Skipping.");
            return;
        }

        // ImageIO handles the PNG encoding perfectly.
        // We use "png" because it compresses file size without losing a single pixel of data.
        boolean success = ImageIO.write(image, "png", outputFile);

        if (!success) {
            throw new IOException("No appropriate PNG writer found in ImageIO.");
        }
    }

    /**
     * Exports visualizations for all detected tracks and generates an HTML visual report.
     * Single-frame streaks (even dashed ones) are saved as lossless PNGs.
     * Multi-frame tracks are saved as looping GIFs.
     */
    public static void exportTrackVisualizations(List<TrackLinker.Track> tracks, List<short[][]> rawFrames, File exportDir) throws IOException {

        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }

        int blinkSpeedMs = 300;
        int trackCounter = 1;
        int streakCounter = 1;

        // Initialize the HTML report writer
        File reportFile = new File(exportDir, "detection_report.html");

        try (java.io.PrintWriter report = new java.io.PrintWriter(new java.io.FileWriter(reportFile))) {

            // --- WRITE HTML HEADER & CSS ---
            report.println("<!DOCTYPE html>");
            report.println("<html>");
            report.println("<head>");
            report.println("<title>Detection Report</title>");
            report.println("<style>");
            report.println("body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #1e1e1e; color: #e0e0e0; margin: 40px; }");
            report.println("h1 { color: #ffffff; border-bottom: 2px solid #444; padding-bottom: 10px; }");
            report.println(".detection-card { background: #2d2d2d; padding: 20px; margin-bottom: 30px; border-radius: 8px; box-shadow: 0 4px 8px rgba(0,0,0,0.5); }");

            // Added a specific color class for streak titles to make them pop!
            report.println(".detection-title { font-size: 1.2em; font-weight: bold; color: #4da6ff; margin-bottom: 15px; }");
            report.println(".streak-title { color: #ff9933; }");

            report.println(".image-container { display: flex; gap: 20px; margin-bottom: 15px; align-items: flex-start; }");
            report.println("img { border: 1px solid #555; border-radius: 4px; max-width: 100%; height: auto; }");
            report.println(".source-list { list-style-type: none; padding: 0; display: flex; flex-wrap: wrap; gap: 8px; }");
            report.println(".source-list li { background: #3d3d3d; padding: 5px 10px; border-radius: 4px; font-size: 0.9em; font-family: monospace; }");
            report.println("</style>");
            report.println("</head>");
            report.println("<body>");
            report.println("<h1>Detection Report</h1>");

            for (TrackLinker.Track track : tracks) {
                if (track.points == null || track.points.isEmpty()) continue;

                java.util.Set<Integer> uniqueFrames = new java.util.HashSet<>();
                for (SourceExtractor.DetectedObject pt : track.points) {
                    uniqueFrames.add(pt.sourceFrameIndex);
                }

                // =================================================================
                // CASE 1: SINGLE FRAME EVENT (Solid Streak or Dashed Airplane)
                // =================================================================
                if (uniqueFrames.size() == 1) {

                    double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE;
                    double minY = Double.MAX_VALUE, maxY = Double.MIN_VALUE;

                    for (SourceExtractor.DetectedObject pt : track.points) {

                        double objectRadius = 0;
                        if (pt.pixelArea > 0) {
                            if (pt.isStreak) {
                                objectRadius = Math.sqrt((pt.pixelArea * pt.elongation) / Math.PI);
                            } else {
                                objectRadius = Math.sqrt(pt.pixelArea / Math.PI);
                            }
                        }

                        if (pt.x - objectRadius < minX) minX = pt.x - objectRadius;
                        if (pt.x + objectRadius > maxX) maxX = pt.x + objectRadius;
                        if (pt.y - objectRadius < minY) minY = pt.y - objectRadius;
                        if (pt.y + objectRadius > maxY) maxY = pt.y + objectRadius;
                    }

                    int padding = 100;
                    int trackBoxWidth = (int) Math.round(maxX - minX) + padding;
                    int trackBoxHeight = (int) Math.round(maxY - minY) + padding;
                    int fixedCenterX = (int) Math.round((minX + maxX) / 2.0);
                    int fixedCenterY = (int) Math.round((minY + maxY) / 2.0);

                    int frameIndex = track.points.get(0).sourceFrameIndex;
                    short[][] rawImage = rawFrames.get(frameIndex);

                    short[][] croppedData = robustEdgeAwareCrop(
                            rawImage, fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                    BufferedImage streakImg = createDisplayImage(croppedData);

                    String streakFileName = "streak_" + streakCounter + ".png";
                    File streakFile = new File(exportDir, streakFileName);
                    saveTrackImageLossless(streakImg, streakFile);

                    System.out.println("Exported single-frame streak event to: " + streakFileName);

                    // --- WRITE HTML CARD FOR STREAK ---
                    report.println("<div class='detection-card'>");
                    report.println("<div class='detection-title streak-title'>Single Streak Event #" + streakCounter + "</div>");
                    report.println("<div class='image-container'>");
                    report.println("<img src='" + streakFileName + "' alt='Streak Image' />");
                    report.println("</div>");
                    report.println("<strong>Source Frame:</strong>");
                    report.println("<ul class='source-list'><li>" + track.points.get(0).sourceFilename + "</li></ul>");
                    report.println("</div>");

                    streakCounter++;
                }
                // =================================================================
                // CASE 2: MULTI-FRAME TRACK (Animations)
                // =================================================================
                else {
                    List<BufferedImage> objectCentricFrames = new ArrayList<>();
                    List<BufferedImage> starCentricFrames = new ArrayList<>();

                    double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE;
                    double minY = Double.MAX_VALUE, maxY = Double.MIN_VALUE;

                    for (SourceExtractor.DetectedObject pt : track.points) {

                        double objectRadius = 0;
                        if (pt.pixelArea > 0) {
                            if (pt.isStreak) {
                                objectRadius = Math.sqrt((pt.pixelArea * pt.elongation) / Math.PI);
                            } else {
                                objectRadius = Math.sqrt(pt.pixelArea / Math.PI);
                            }
                        }

                        if (pt.x - objectRadius < minX) minX = pt.x - objectRadius;
                        if (pt.x + objectRadius > maxX) maxX = pt.x + objectRadius;
                        if (pt.y - objectRadius < minY) minY = pt.y - objectRadius;
                        if (pt.y + objectRadius > maxY) maxY = pt.y + objectRadius;
                    }

                    int padding = 100;
                    int trackBoxWidth = (int) Math.round(maxX - minX) + padding;
                    int trackBoxHeight = (int) Math.round(maxY - minY) + padding;
                    int fixedCenterX = (int) Math.round((minX + maxX) / 2.0);
                    int fixedCenterY = (int) Math.round((minY + maxY) / 2.0);

                    int objectCentricSize = 150;

                    java.util.Set<Integer> processedFrames = new java.util.HashSet<>();
                    java.util.Set<String> chronologicalSourceFiles = new java.util.LinkedHashSet<>();

                    for (SourceExtractor.DetectedObject pt : track.points) {

                        chronologicalSourceFiles.add(pt.sourceFilename);

                        if (processedFrames.contains(pt.sourceFrameIndex)) continue;
                        processedFrames.add(pt.sourceFrameIndex);

                        short[][] rawImage = rawFrames.get(pt.sourceFrameIndex);

                        // 1. Object-Centric Render (SKIP IF IT IS A STREAK TRACK)
                        if (!track.isStreakTrack) {
                            short[][] croppedObjData = robustEdgeAwareCrop(
                                    rawImage, (int) pt.x, (int) pt.y, objectCentricSize, objectCentricSize);
                            BufferedImage objFrame = createDisplayImage(croppedObjData);
                            objectCentricFrames.add(objFrame);
                        }

                        // 2. Star-Centric Render (ALWAYS DO THIS)
                        short[][] croppedStarData = robustEdgeAwareCrop(
                                rawImage, fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                        BufferedImage starFrameGray = createDisplayImage(croppedStarData);

                        Graphics2D g2d = starFrameGray.createGraphics();

                        int startX = fixedCenterX - (trackBoxWidth / 2);
                        int startY = fixedCenterY - (trackBoxHeight / 2);
                        int localObjX = (int) Math.round(pt.x - startX);
                        int localObjY = (int) Math.round(pt.y - startY);

                        g2d.setColor(java.awt.Color.WHITE);
                        g2d.setStroke(new java.awt.BasicStroke(2.0f));

                        int radius = 15;
                        g2d.drawOval(localObjX - radius, localObjY - radius, radius * 2, radius * 2);

                        g2d.dispose();
                        starCentricFrames.add(starFrameGray);
                    }

                    // --- EXPORT AND HTML GENERATION ---
                    report.println("<div class='detection-card'>");

                    if (track.isStreakTrack) {
                        // MULTI-FRAME STREAK TRACK
                        String starFileName = "streak_track_" + trackCounter + "_star_centric.gif";
                        File starFile = new File(exportDir, starFileName);
                        GifSequenceWriter.saveAnimatedGif(starCentricFrames, starFile, blinkSpeedMs);

                        System.out.println("Exported animation for streak track " + trackCounter + ".");

                        report.println("<div class='detection-title streak-title'>Multi-Frame Streak Track #" + trackCounter + "</div>");
                        report.println("<div class='image-container'>");
                        report.println("<img src='" + starFileName + "' alt='Star Centric Animation' title='Star-Centric' />");
                        report.println("</div>");
                    } else {
                        // STANDARD POINT TRACK (Asteroid/Comet)
                        String objFileName = "track_" + trackCounter + "_object_centric.gif";
                        String starFileName = "track_" + trackCounter + "_star_centric.gif";

                        File objFile = new File(exportDir, objFileName);
                        GifSequenceWriter.saveAnimatedGif(objectCentricFrames, objFile, blinkSpeedMs);

                        File starFile = new File(exportDir, starFileName);
                        GifSequenceWriter.saveAnimatedGif(starCentricFrames, starFile, blinkSpeedMs);

                        System.out.println("Exported animations for point track " + trackCounter + ".");

                        report.println("<div class='detection-title'>Moving Target Track #" + trackCounter + "</div>");
                        report.println("<div class='image-container'>");
                        report.println("<img src='" + objFileName + "' alt='Object Centric Animation' title='Object-Centric' />");
                        report.println("<img src='" + starFileName + "' alt='Star Centric Animation' title='Star-Centric' />");
                        report.println("</div>");
                    }

                    // Write chronological sources (applies to both)
                    report.println("<strong>Sequence Frames (Chronological):</strong>");
                    report.println("<ul class='source-list'>");
                    for (String fileName : chronologicalSourceFiles) {
                        report.println("<li>" + fileName + "</li>");
                    }
                    report.println("</ul>");
                    report.println("</div>");

                    trackCounter++;
                }
            }

            // --- CLOSE HTML TAGS ---
            report.println("</body>");
            report.println("</html>");

        }

        System.out.println("\nFinished exporting visualizations and generated HTML report at: " + reportFile.getAbsolutePath());
    }
    /**
     * Exports visualizations for all detected tracks and generates a source report.
     * Single-frame streaks (even dashed ones) are saved as lossless PNGs.
     * Multi-frame tracks are saved as looping GIFs.
     */
    public static void exportTrackVisualizationsTxtReport(List<TrackLinker.Track> tracks, List<short[][]> rawFrames, File exportDir) throws IOException {

        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }

        int blinkSpeedMs = 300;
        int trackCounter = 1;
        int streakCounter = 1;

        // Initialize the report writer
        File reportFile = new File(exportDir, "detection_report.txt");

        try (java.io.PrintWriter report = new java.io.PrintWriter(new java.io.FileWriter(reportFile))) {

            report.println("==================================================");
            report.println("               DETECTION REPORT                   ");
            report.println("==================================================");
            report.println();

            for (TrackLinker.Track track : tracks) {
                if (track.points == null || track.points.isEmpty()) continue;

                // =================================================================
                // How many unique FITS frames does this track span?
                // =================================================================
                java.util.Set<Integer> uniqueFrames = new java.util.HashSet<>();
                for (SourceExtractor.DetectedObject pt : track.points) {
                    uniqueFrames.add(pt.sourceFrameIndex);
                }

                // =================================================================
                // CASE 1: SINGLE FRAME EVENT (Solid Streak or Dashed Airplane)
                // =================================================================
                if (uniqueFrames.size() == 1) {

                    double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE;
                    double minY = Double.MAX_VALUE, maxY = Double.MIN_VALUE;

                    for (SourceExtractor.DetectedObject pt : track.points) {
                        if (pt.x < minX) minX = pt.x;
                        if (pt.x > maxX) maxX = pt.x;
                        if (pt.y < minY) minY = pt.y;
                        if (pt.y > maxY) maxY = pt.y;
                    }

                    int padding = 100;
                    int trackBoxWidth = (int) Math.round(maxX - minX) + padding;
                    int trackBoxHeight = (int) Math.round(maxY - minY) + padding;
                    int fixedCenterX = (int) Math.round((minX + maxX) / 2.0);
                    int fixedCenterY = (int) Math.round((minY + maxY) / 2.0);

                    int frameIndex = track.points.get(0).sourceFrameIndex;
                    short[][] rawImage = rawFrames.get(frameIndex);

                    // TWO-STEP RENDER: Crop raw data -> Auto-Stretch Display Image
                    short[][] croppedData = robustEdgeAwareCrop(
                            rawImage, fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                    BufferedImage streakImg = createDisplayImage(croppedData);

                    File streakFile = new File(exportDir, "streak_" + streakCounter + ".png");
                    saveTrackImageLossless(streakImg, streakFile);

                    System.out.println("Exported single-frame streak event to: " + streakFile.getName());

                    // --- WRITE TO REPORT ---
                    report.println("Export: " + streakFile.getName());
                    report.println("Source: " + track.points.get(0).sourceFilename);
                    report.println();

                    streakCounter++;
                }
                // =================================================================
                // CASE 2: MULTI-FRAME TRACK (Animations)
                // =================================================================
                else {
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

                    int padding = 100;
                    int trackBoxWidth = (int) Math.round(maxX - minX) + padding;
                    int trackBoxHeight = (int) Math.round(maxY - minY) + padding;
                    int fixedCenterX = (int) Math.round((minX + maxX) / 2.0);
                    int fixedCenterY = (int) Math.round((minY + maxY) / 2.0);

                    int objectCentricSize = 150;

                    java.util.Set<Integer> processedFrames = new java.util.HashSet<>();
                    // Use a LinkedHashSet to maintain the chronological order of the filenames
                    java.util.Set<String> chronologicalSourceFiles = new java.util.LinkedHashSet<>();

                    for (SourceExtractor.DetectedObject pt : track.points) {

                        // Collect the filename for the report
                        chronologicalSourceFiles.add(pt.sourceFilename);

                        if (processedFrames.contains(pt.sourceFrameIndex)) continue;
                        processedFrames.add(pt.sourceFrameIndex);

                        short[][] rawImage = rawFrames.get(pt.sourceFrameIndex);

                        // 1. Object-Centric Render
                        short[][] croppedObjData = robustEdgeAwareCrop(
                                rawImage, (int) pt.x, (int) pt.y, objectCentricSize, objectCentricSize);
                        BufferedImage objFrame = createDisplayImage(croppedObjData);
                        objectCentricFrames.add(objFrame);

                        // 2. Star-Centric Render
                        short[][] croppedStarData = robustEdgeAwareCrop(
                                rawImage, fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                        BufferedImage starFrameGray = createDisplayImage(croppedStarData);

                        // --- NEW: DRAW THE WHITE TARGETING SHAPE ---
                        Graphics2D g2d = starFrameGray.createGraphics();

                        int startX = fixedCenterX - (trackBoxWidth / 2);
                        int startY = fixedCenterY - (trackBoxHeight / 2);
                        int localObjX = (int) Math.round(pt.x - startX);
                        int localObjY = (int) Math.round(pt.y - startY);

                        g2d.setColor(java.awt.Color.WHITE);
                        g2d.setStroke(new java.awt.BasicStroke(2.0f));

                        int radius = 15;
                        g2d.drawOval(localObjX - radius, localObjY - radius, radius * 2, radius * 2);

                        g2d.dispose();
                        starCentricFrames.add(starFrameGray);
                    }

                    File objFile = new File(exportDir, "track_" + trackCounter + "_object_centric.gif");
                    GifSequenceWriter.saveAnimatedGif(objectCentricFrames, objFile, blinkSpeedMs);

                    File starFile = new File(exportDir, "track_" + trackCounter + "_star_centric.gif");
                    GifSequenceWriter.saveAnimatedGif(starCentricFrames, starFile, blinkSpeedMs);

                    System.out.println("Exported animations for track " + trackCounter + ".");

                    // --- WRITE TO REPORT ---
                    report.println("Export: " + objFile.getName() + " | " + starFile.getName());
                    report.println("Sources: ");
                    for (String fileName : chronologicalSourceFiles) {
                        report.println("  - " + fileName);
                    }
                    report.println();

                    trackCounter++;
                }
            }
        } // The try-with-resources block automatically closes the PrintWriter here

        System.out.println("\nFinished exporting all track visualizations and report to: " + exportDir.getAbsolutePath());
    }

    /**
     * Exports visualizations for all detected tracks.
     * Single-frame streaks (even dashed ones) are saved as lossless PNGs.
     * Multi-frame tracks are saved as looping GIFs.
     */
    public static void exportTrackVisualizationsSimple(List<TrackLinker.Track> tracks, List<short[][]> rawFrames, File exportDir) throws IOException {

        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }

        int blinkSpeedMs = 300;
        int trackCounter = 1;
        int streakCounter = 1;

        for (TrackLinker.Track track : tracks) {
            if (track.points == null || track.points.isEmpty()) continue;

            // =================================================================
            // How many unique FITS frames does this track span?
            // =================================================================
            java.util.Set<Integer> uniqueFrames = new java.util.HashSet<>();
            for (SourceExtractor.DetectedObject pt : track.points) {
                uniqueFrames.add(pt.sourceFrameIndex);
            }

            // =================================================================
            // CASE 1: SINGLE FRAME EVENT (Solid Streak or Dashed Airplane)
            // =================================================================
            if (uniqueFrames.size() == 1) {

                double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE;
                double minY = Double.MAX_VALUE, maxY = Double.MIN_VALUE;

                for (SourceExtractor.DetectedObject pt : track.points) {
                    if (pt.x < minX) minX = pt.x;
                    if (pt.x > maxX) maxX = pt.x;
                    if (pt.y < minY) minY = pt.y;
                    if (pt.y > maxY) maxY = pt.y;
                }

                int padding = 100;
                int trackBoxWidth = (int) Math.round(maxX - minX) + padding;
                int trackBoxHeight = (int) Math.round(maxY - minY) + padding;
                int fixedCenterX = (int) Math.round((minX + maxX) / 2.0);
                int fixedCenterY = (int) Math.round((minY + maxY) / 2.0);

                int frameIndex = track.points.get(0).sourceFrameIndex;
                short[][] rawImage = rawFrames.get(frameIndex);

                // TWO-STEP RENDER: Crop raw data -> Auto-Stretch Display Image
                short[][] croppedData = robustEdgeAwareCrop(
                        rawImage, fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                BufferedImage streakImg = createDisplayImage(croppedData);

                File streakFile = new File(exportDir, "streak_" + streakCounter + ".png");
                saveTrackImageLossless(streakImg, streakFile);

                System.out.println("Exported single-frame streak event to: " + streakFile.getName());
                streakCounter++;
            }
// =================================================================
            // CASE 2: MULTI-FRAME TRACK (Animations)
            // =================================================================
            else {
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

                int padding = 100;
                int trackBoxWidth = (int) Math.round(maxX - minX) + padding;
                int trackBoxHeight = (int) Math.round(maxY - minY) + padding;
                int fixedCenterX = (int) Math.round((minX + maxX) / 2.0);
                int fixedCenterY = (int) Math.round((minY + maxY) / 2.0);

                int objectCentricSize = 150;

                java.util.Set<Integer> processedFrames = new java.util.HashSet<>();

                for (SourceExtractor.DetectedObject pt : track.points) {

                    if (processedFrames.contains(pt.sourceFrameIndex)) continue;
                    processedFrames.add(pt.sourceFrameIndex);

                    short[][] rawImage = rawFrames.get(pt.sourceFrameIndex);

                    // 1. Object-Centric Render
                    short[][] croppedObjData = robustEdgeAwareCrop(
                            rawImage, (int) pt.x, (int) pt.y, objectCentricSize, objectCentricSize);
                    BufferedImage objFrame = createDisplayImage(croppedObjData);
                    objectCentricFrames.add(objFrame);

                    // 2. Star-Centric Render
                    short[][] croppedStarData = robustEdgeAwareCrop(
                            rawImage, fixedCenterX, fixedCenterY, trackBoxWidth, trackBoxHeight);
                    BufferedImage starFrameGray = createDisplayImage(croppedStarData);

                    // --- NEW: DRAW THE WHITE TARGETING SHAPE ---
                    // Draw directly on the existing grayscale image!
                    Graphics2D g2d = starFrameGray.createGraphics();

                    // Calculate where the object is inside the local cropped image coordinates
                    int startX = fixedCenterX - (trackBoxWidth / 2);
                    int startY = fixedCenterY - (trackBoxHeight / 2);
                    int localObjX = (int) Math.round(pt.x - startX);
                    int localObjY = (int) Math.round(pt.y - startY);

                    g2d.setColor(Color.WHITE);
                    g2d.setStroke(new BasicStroke(2.0f)); // 2-pixel thick line

                    // OPTION A: Draw a Circle
                    int radius = 15;
                    g2d.drawOval(localObjX - radius, localObjY - radius, radius * 2, radius * 2);

                    // OPTION B: Draw a Rectangle (Uncomment to use instead)
                    // int size = 30;
                    // g2d.drawRect(localObjX - (size / 2), localObjY - (size / 2), size, size);

                    g2d.dispose(); // Clean up graphics object

                    // Add the modified grayscale frame directly to the list
                    starCentricFrames.add(starFrameGray);
                }

                File objFile = new File(exportDir, "track_" + trackCounter + "_object_centric.gif");
                GifSequenceWriter.saveAnimatedGif(objectCentricFrames, objFile, blinkSpeedMs);

                File starFile = new File(exportDir, "track_" + trackCounter + "_star_centric.gif");
                GifSequenceWriter.saveAnimatedGif(starCentricFrames, starFile, blinkSpeedMs);

                System.out.println("Exported animations for track " + trackCounter + ".");
                trackCounter++;
            }
        }

        System.out.println("\nFinished exporting all track visualizations to: " + exportDir.getAbsolutePath());
    }

    /**
     * Extracts a region from the image, safely handling edges with absolute black padding.
     * The padding uses -32768 to align perfectly with the unsigned FITS shift.
     */
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

                // Explicitly check boundaries. If inside the image, grab the real data.
                if (sourceY >= 0 && sourceY < height && sourceX >= 0 && sourceX < width) {
                    cropped[y][x] = fullImage[sourceY][sourceX];
                } else {
                    // THE FIX: Pad with -32768 (Java's Short.MIN_VALUE).
                    // When the display method adds 32768, this becomes exactly 0 (pure black).
                    cropped[y][x] = -32768;
                }
            }
        }
        return cropped;
    }

    /**
     * Scans the entire FITS image array and prints a comprehensive statistical
     * profile to diagnose contrast stretching and saturation (Black Sun) issues.
     */
    public static void analyzeFitsData(short[][] imageData) {
        int height = imageData.length;
        int width = imageData[0].length;
        long totalPixels = (long) width * height;

        // Track both the raw Java 'short' (signed) and the masked 'int' (unsigned)
        short minRaw = Short.MAX_VALUE;
        short maxRaw = Short.MIN_VALUE;
        int minUnsigned = 65535;
        int maxUnsigned = 0;

        long sumUnsigned = 0;

        // Counters for specific edge-case values
        int countRawZero = 0;
        int countRawMin = 0; // -32768
        int countRawMax = 0; // 32767
        int countUnsignedZero = 0;
        int countUnsignedMax = 0; // 65535

        System.out.println("\n==================================================");
        System.out.println("=== FITS DATA ARRAY ANALYSIS ===");
        System.out.println("==================================================");
        System.out.println("Dimensions : " + width + " x " + height + " (" + totalPixels + " pixels)");

        // --- PASS 1: Min, Max, Sum, and Outliers ---
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                short rawVal = imageData[y][x];
                int unsignedVal = rawVal & 0xFFFF;

                // Track Raw (Signed)
                if (rawVal < minRaw) minRaw = rawVal;
                if (rawVal > maxRaw) maxRaw = rawVal;

                // Track Unsigned (Masked)
                if (unsignedVal < minUnsigned) minUnsigned = unsignedVal;
                if (unsignedVal > maxUnsigned) maxUnsigned = unsignedVal;

                sumUnsigned += unsignedVal;

                // Count specific flags
                if (rawVal == 0) countRawZero++;
                if (rawVal == Short.MIN_VALUE) countRawMin++;
                if (rawVal == Short.MAX_VALUE) countRawMax++;
                if (unsignedVal == 0) countUnsignedZero++;
                if (unsignedVal == 65535) countUnsignedMax++;
            }
        }

        double meanUnsigned = (double) sumUnsigned / totalPixels;

        // --- PASS 2: Standard Deviation (Sigma) ---
        double sumSqDiff = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int unsignedVal = imageData[y][x] & 0xFFFF;
                double diff = unsignedVal - meanUnsigned;
                sumSqDiff += (diff * diff);
            }
        }
        double variance = sumSqDiff / totalPixels;
        double sigmaUnsigned = Math.sqrt(variance);

        // --- PRINT THE REPORT ---
        System.out.println("\n--- RAW SIGNED SHORTS (Java Native) ---");
        System.out.println("Min Raw Value    : " + minRaw);
        System.out.println("Max Raw Value    : " + maxRaw);
        System.out.println("Pixels @ 0       : " + countRawZero);
        System.out.println("Pixels @ -32768  : " + countRawMin);
        System.out.println("Pixels @ +32767  : " + countRawMax);

        System.out.println("\n--- MASKED UNSIGNED INTS (& 0xFFFF) ---");
        System.out.println("Min Unsigned     : " + minUnsigned);
        System.out.println("Max Unsigned     : " + maxUnsigned);
        System.out.println("Pixels @ 0       : " + countUnsignedZero);
        System.out.println("Pixels @ 65535   : " + countUnsignedMax);

        System.out.println("\n--- STATISTICAL PROFILE (Unsigned) ---");
        System.out.println("Mean (Background): " + String.format("%.2f", meanUnsigned));
        System.out.println("Sigma (Std Dev)  : " + String.format("%.2f", sigmaUnsigned));

        // Let's see what your current auto-stretch is trying to do
        double calcBlack = meanUnsigned - (0.5 * sigmaUnsigned);
        double calcWhite = meanUnsigned + (5.0 * sigmaUnsigned);
        System.out.println("Auto Black Point : " + String.format("%.2f", calcBlack));
        System.out.println("Auto White Point : " + String.format("%.2f", calcWhite));
        System.out.println("==================================================\n");
    }

}