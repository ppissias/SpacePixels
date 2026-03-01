package spv.util;

import java.util.List;

public class RawImageAnnotator {

    // In a 16-bit FITS image, pure white is exactly 65535.
    // In Java's signed short, this is represented as -1.
    private static final short HIGHLIGHT_VALUE = (short) 65535;

    /**
     * Overwrites the raw FITS data to draw boxes around stars and lines over streaks.
     */
    public static void drawDetections(short[][] imageData, List<SourceExtractor.DetectedObject> objects) {
        if (objects == null || objects.isEmpty()) return;

        for (SourceExtractor.DetectedObject obj : objects) {
            int cx = (int) Math.round(obj.x);
            int cy = (int) Math.round(obj.y);

            if (obj.isStreak) {
                // Draw a line representing the streak's angle and length
                double lineLength = obj.elongation * 5.0; // Scale factor for visibility
                int x1 = (int) Math.round(cx - (Math.cos(obj.angle) * lineLength));
                int y1 = (int) Math.round(cy - (Math.sin(obj.angle) * lineLength));
                int x2 = (int) Math.round(cx + (Math.cos(obj.angle) * lineLength));
                int y2 = (int) Math.round(cy + (Math.sin(obj.angle) * lineLength));

                drawLine(imageData, x1, y1, x2, y2, HIGHLIGHT_VALUE);

                // Draw a small tight box exactly at the centroid
                drawBox(imageData, cx, cy, 3, HIGHLIGHT_VALUE);
            } else {
                // Draw a standard 10-pixel radius target box around point sources
                drawBox(imageData, cx, cy, 10, HIGHLIGHT_VALUE);
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
        if (x >= 0 && x < image.length && y >= 0 && y < image[0].length) {
            image[x][y] = value;
        }
    }
}
