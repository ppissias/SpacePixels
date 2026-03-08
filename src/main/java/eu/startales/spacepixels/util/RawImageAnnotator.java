package eu.startales.spacepixels.util;

import java.util.List;

public class RawImageAnnotator {

    // =================================================================
    // CONFIGURATION PARAMETERS
    // =================================================================

    /** * The color value used to draw annotations.
     * In a 16-bit unsigned FITS image, pure white is exactly 65535.
     * In Java's signed short, this is represented as -1.
     */
    public static short highlightValue = (short) 65535;

    /** * The scale factor used to determine how long the drawn streak line should be.
     * It multiplies the calculated elongation ratio to make the line visible.
     */
    public static double streakLineScaleFactor = 5.0;

    /** * The radius (in pixels) of the small, tight box drawn exactly at the
     * calculated centroid of a fast-moving streak.
     */
    public static int streakCentroidBoxRadius = 3;

    /** * The absolute minimum radius (in pixels) for a bounding box drawn around
     * standard point sources (stars, slow asteroids).
     */
    public static int pointSourceMinBoxRadius = 10;

    /** * The number of extra padding pixels added to the dynamically calculated
     * radius of an object. This ensures the box is drawn outside the glowing core,
     * resting clearly on the dark sky background.
     */
    public static int dynamicBoxPadding = 5;


    // =================================================================
    // ANNOTATION LOGIC
    // =================================================================

    /**
     * Overwrites the raw FITS data to draw boxes around stars and lines over streaks.
     */
    public static void drawDetections(short[][] imageData, List<SourceExtractor.DetectedObject> objects) {
        if (objects == null || objects.isEmpty()) return;

        for (SourceExtractor.DetectedObject obj : objects) {
            int cx = (int) Math.round(obj.x);
            int cy = (int) Math.round(obj.y);

            if (obj.isStreak) {
                // Draw a line representing the streak's angle and length (Parameterized scale)
                double lineLength = obj.elongation * streakLineScaleFactor;
                int x1 = (int) Math.round(cx - (Math.cos(obj.angle) * lineLength));
                int y1 = (int) Math.round(cy - (Math.sin(obj.angle) * lineLength));
                int x2 = (int) Math.round(cx + (Math.cos(obj.angle) * lineLength));
                int y2 = (int) Math.round(cy + (Math.sin(obj.angle) * lineLength));

                drawLine(imageData, x1, y1, x2, y2, highlightValue);

                // Draw a small tight box exactly at the centroid (Parameterized radius)
                drawBox(imageData, cx, cy, streakCentroidBoxRadius, highlightValue);
            } else {
                // DYNAMIC BOX SIZE
                int boxRadius = pointSourceMinBoxRadius; // Parameterized default minimum

                if (obj.pixelArea > 0) {
                    // Calculate the rough radius of the object and add parameterized padding
                    int dynamicRadius = (int) Math.round(Math.sqrt(obj.pixelArea / Math.PI)) + dynamicBoxPadding;

                    // Use whichever is larger: the default minimum, or the new dynamic size
                    boxRadius = Math.max(pointSourceMinBoxRadius, dynamicRadius);
                }

                drawBox(imageData, cx, cy, boxRadius, highlightValue);
            }
        }
    }

    /**
     * Draws a hollow square bounding box centered at (cx, cy).
     */
    private static void drawBox(short[][] image, int cx, int cy, int radius, short value) {
        // Top and Bottom edges
        for (int x = cx - radius; x <= cx + radius; x++) {
            setPixelSafe(image, x, cy - radius, value);
            setPixelSafe(image, x, cy + radius, value);
        }
        // Left and Right edges
        for (int y = cy - radius; y <= cy + radius; y++) {
            setPixelSafe(image, cx - radius, y, value);
            setPixelSafe(image, cx + radius, y, value);
        }
    }

    /**
     * Implementation of Bresenham's Line Algorithm to draw angled lines on a 2D grid.
     */
    private static void drawLine(short[][] image, int x1, int y1, int x2, int y2, short value) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;

        while (true) {
            setPixelSafe(image, x1, y1, value);

            if (x1 == x2 && y1 == y2) break;

            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x1 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y1 += sy;
            }
        }
    }

    /**
     * Safely sets a pixel value, preventing OutOfBoundsExceptions if a detection
     * is right on the edge of the image.
     */
    private static void setPixelSafe(short[][] image, int x, int y, short value) {
        // Because image is row-major [height][width], image.length is the Height (Y axis)
        // and image[0].length is the Width (X axis).
        if (y >= 0 && y < image.length && x >= 0 && x < image[0].length) {
            // Write to the array in the proper [y][x] format
            image[y][x] = value;
        }
    }
}