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

import eu.startales.spacepixels.util.DisplayImageRenderer;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Centralizes image rendering helpers used by report sections to crop frames, annotate detections, and persist PNG
 * or GIF visualizations.
 */
final class TrackVisualizationRenderer {

    private TrackVisualizationRenderer() {
    }

    static BufferedImage createDisplayImage(short[][] imageData, ExportVisualizationSettings settings) {
        return DisplayImageRenderer.createDisplayImage(
                imageData,
                settings.getAutoStretchBlackSigma(),
                settings.getAutoStretchWhiteSigma());
    }

    static void saveLosslessPng(BufferedImage image, File outputFile) throws IOException {
        if (image == null) {
            System.err.println("Warning: Attempted to save a null image. Skipping.");
            return;
        }

        boolean success = ImageIO.write(image, "png", outputFile);
        if (!success) {
            throw new IOException("No appropriate PNG writer found in ImageIO.");
        }
    }

    static void saveAnimatedGif(List<BufferedImage> frames, File outputFile, ExportVisualizationSettings settings) throws IOException {
        GifSequenceWriter.saveAnimatedGif(frames, outputFile, settings.getGifBlinkSpeedMs());
    }

    static short[][] robustEdgeAwareCrop(short[][] fullImage, int cx, int cy, int cropWidth, int cropHeight) {
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

    static BufferedImage createCroppedDisplayImage(short[][] fullImage,
                                                   int cx,
                                                   int cy,
                                                   int cropWidth,
                                                   int cropHeight,
                                                   ExportVisualizationSettings settings) {
        short[][] croppedData = robustEdgeAwareCrop(fullImage, cx, cy, cropWidth, cropHeight);
        return createDisplayImage(croppedData, settings);
    }

    static BufferedImage createStarCentricHighlightedFrame(short[][] rawImage,
                                                           TrackCropGeometry.CropBounds cropBounds,
                                                           SourceExtractor.DetectedObject highlightedPoint,
                                                           ExportVisualizationSettings settings,
                                                           Color highlightColor,
                                                           String pointLabel,
                                                           String frameLabel,
                                                           boolean antiAlias) {
        BufferedImage frameImage = createCroppedDisplayImage(
                rawImage,
                cropBounds.fixedCenterX,
                cropBounds.fixedCenterY,
                cropBounds.trackBoxWidth,
                cropBounds.trackBoxHeight,
                settings);
        Graphics2D g2d = frameImage.createGraphics();
        if (antiAlias) {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        }

        if (highlightedPoint != null) {
            int localX = (int) Math.round(highlightedPoint.x - cropBounds.startX);
            int localY = (int) Math.round(highlightedPoint.y - cropBounds.startY);
            drawDetectionHighlight(g2d, localX, localY, settings, highlightColor);
            if (pointLabel != null && !pointLabel.isEmpty()) {
                g2d.setFont(new Font("Segoe UI", Font.BOLD, 13));
                g2d.setColor(Color.WHITE);
                g2d.drawString(pointLabel, localX + 18, localY - 18);
            }
        }

        if (frameLabel != null && !frameLabel.isEmpty()) {
            g2d.setFont(new Font("Segoe UI", Font.BOLD, 12));
            g2d.setColor(new Color(255, 255, 255, 230));
            g2d.drawString(frameLabel, 10, 18);
        }

        g2d.dispose();
        return frameImage;
    }

    static BufferedImage createSingleStreakShapeImage(List<SourceExtractor.DetectedObject> points,
                                                      int cropWidth,
                                                      int cropHeight,
                                                      int startX,
                                                      int startY,
                                                      boolean drawCentroid) {
        BufferedImage image = new BufferedImage(cropWidth, cropHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, cropWidth, cropHeight);

        for (SourceExtractor.DetectedObject pt : points) {
            if (pt.rawPixels != null) {
                for (SourceExtractor.Pixel p : pt.rawPixels) {
                    int x = p.x - startX;
                    int y = p.y - startY;
                    if (x >= 0 && x < cropWidth && y >= 0 && y < cropHeight) {
                        image.setRGB(x, y, new Color(255, 80, 80).getRGB());
                    }
                }
                if (drawCentroid) {
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

    static BufferedImage createTrackShapeImage(TrackLinker.Track track,
                                               int cropWidth,
                                               int cropHeight,
                                               int startX,
                                               int startY) {
        BufferedImage image = new BufferedImage(cropWidth, cropHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, cropWidth, cropHeight);

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(new Color(100, 150, 255));
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

        for (int i = 0; i < track.points.size(); i++) {
            SourceExtractor.DetectedObject p = track.points.get(i);
            int x = (int) Math.round(p.x - startX);
            int y = (int) Math.round(p.y - startY);

            g2d.setColor(Color.RED);
            g2d.fillOval(x - 4, y - 4, 8, 8);

            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("Monospaced", Font.BOLD, 12));
            g2d.drawString(String.valueOf(i + 1), x + 6, y - 6);
        }

        g2d.dispose();
        return image;
    }

    private static void drawDetectionHighlight(Graphics2D g2d,
                                               int localX,
                                               int localY,
                                               ExportVisualizationSettings settings,
                                               Color highlightColor) {
        g2d.setColor(highlightColor != null ? highlightColor : Color.WHITE);
        g2d.setStroke(new BasicStroke(settings.getTargetCircleStrokeWidth()));
        g2d.drawOval(
                localX - settings.getTargetCircleRadius(),
                localY - settings.getTargetCircleRadius(),
                settings.getTargetCircleRadius() * 2,
                settings.getTargetCircleRadius() * 2);
    }
}
