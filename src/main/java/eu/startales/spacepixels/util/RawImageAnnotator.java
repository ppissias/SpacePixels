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

import io.github.ppissias.jtransient.core.SourceExtractor;

import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.BasicStroke;
import java.awt.RenderingHints;
import java.util.List;

public class RawImageAnnotator {

    // =================================================================
    // CONFIGURATION PARAMETERS
    // =================================================================

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
     * Draws anti-aliased colored boxes around stars and lines over streaks natively on the UI canvas.
     */
    public static void drawDetections(BufferedImage image, List<SourceExtractor.DetectedObject> objects) {
        if (objects == null || objects.isEmpty()) return;

        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (SourceExtractor.DetectedObject obj : objects) {
            if (obj.isNoise) continue;

            int cx = (int) Math.round(obj.x);
            int cy = (int) Math.round(obj.y);

            if (obj.isStreak) {
                g2d.setColor(new Color(255, 50, 50)); // Bright Red
                g2d.setStroke(new BasicStroke(1.5f));

                // Draw a line representing the streak's angle and length (Parameterized scale)
                double lineLength = obj.elongation * streakLineScaleFactor;
                int x1 = (int) Math.round(cx - (Math.cos(obj.angle) * lineLength));
                int y1 = (int) Math.round(cy - (Math.sin(obj.angle) * lineLength));
                int x2 = (int) Math.round(cx + (Math.cos(obj.angle) * lineLength));
                int y2 = (int) Math.round(cy + (Math.sin(obj.angle) * lineLength));

                g2d.drawLine(x1, y1, x2, y2);

                // Draw a small tight box exactly at the centroid (Parameterized radius)
                g2d.drawRect(cx - streakCentroidBoxRadius, cy - streakCentroidBoxRadius, streakCentroidBoxRadius * 2, streakCentroidBoxRadius * 2);
            } else {
                g2d.setColor(new Color(50, 255, 50)); // Bright Green
                g2d.setStroke(new BasicStroke(1.5f));

                int boxRadius = pointSourceMinBoxRadius; // Parameterized default minimum

                if (obj.pixelArea > 0) {
                    // Calculate the rough radius of the object and add parameterized padding
                    int dynamicRadius = (int) Math.round(Math.sqrt(obj.pixelArea / Math.PI)) + dynamicBoxPadding;

                    // Use whichever is larger: the default minimum, or the new dynamic size
                    boxRadius = Math.max(pointSourceMinBoxRadius, dynamicRadius);
                }

                g2d.drawRect(cx - boxRadius, cy - boxRadius, boxRadius * 2, boxRadius * 2);
            }
        }
        g2d.dispose();
    }

    /**
     * Tints the exact pixel footprint of detected objects.
     * Streaks are tinted semi-transparent Red, point sources are semi-transparent Green.
     */
    public static void drawExactBlobs(BufferedImage image, List<SourceExtractor.DetectedObject> objects) {
        // Define our semi-transparent overlay colors (ARGB)
        // Format is (Alpha << 24) | (Red << 16) | (Green << 8) | Blue
        // Increased opacity to ~70% (180 out of 255) for much more vivid visibility
        int streakColor = (180 << 24) | (255 << 16) | (0 << 8) | 0;   // Red
        int pointColor  = (180 << 24) | (0 << 16)   | (255 << 8) | 0; // Green

        for (SourceExtractor.DetectedObject obj : objects) {
            if (obj.isNoise) continue;

            int overlayColor = obj.isStreak ? streakColor : pointColor;

            for (SourceExtractor.Pixel p : obj.rawPixels) {
                // Safety bounds check
                if (p.x >= 0 && p.x < image.getWidth() && p.y >= 0 && p.y < image.getHeight()) {

                    int basePixel = image.getRGB(p.x, p.y);

                    // Blend the base image pixel with our semi-transparent mask
                    int blendedPixel = blendARGB(basePixel, overlayColor);

                    image.setRGB(p.x, p.y, blendedPixel);
                }
            }
        }
    }

    /**
     * Fast integer math to blend a semi-transparent overlay color onto a base color.
     */
    private static int blendARGB(int base, int overlay) {
        int alphaOverlay = (overlay >> 24) & 0xFF;
        int alphaBase = 255 - alphaOverlay;

        int r = (((base >> 16) & 0xFF) * alphaBase + ((overlay >> 16) & 0xFF) * alphaOverlay) / 255;
        int g = (((base >> 8)  & 0xFF) * alphaBase + ((overlay >> 8)  & 0xFF) * alphaOverlay) / 255;
        int b = ((base         & 0xFF) * alphaBase + (overlay         & 0xFF) * alphaOverlay) / 255;

        return (255 << 24) | (r << 16) | (g << 8) | b;
    }}