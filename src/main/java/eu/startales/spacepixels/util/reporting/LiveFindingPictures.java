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

import eu.startales.spacepixels.config.VariableStarReportOptions;
import eu.startales.spacepixels.util.DisplayImageRenderer;
import io.github.ppissias.jtransient.model.DetectedObject;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Pictures of findings for the progress dialog's live view: crops of the run's frames around a detection, stretched
 * and cropped as the report does, with a ring around the detection. They are small, detached images; the frames are
 * only read.
 */
public final class LiveFindingPictures {

    /** Ring colours by kind, matching the report's markers loosely. */
    public static final Color MOVING_OBJECT = new Color(100, 180, 255);
    public static final Color STREAK = new Color(255, 120, 120);
    public static final Color ANOMALY = new Color(255, 210, 90);
    public static final Color SLOW_MOVER = new Color(255, 160, 60);
    public static final Color VARIABLE_STAR = new Color(140, 230, 140);
    /** The tint of masked stars over the reference. */
    public static final Color STAR_MASK = new Color(90, 200, 255);

    /** The smallest and the largest crop side, in image pixels. */
    private static final int MIN_CROP = 96;
    private static final int MAX_CROP = 256;
    private static final int PADDING = 24;
    private static final int GAP = 4;
    private static final int CHART_WIDTH = 160;

    private LiveFindingPictures() {
    }

    /**
     * Where a detection is, copied from the engine's object so the picture keeps no reference to it.
     *
     * @param frame the frame's sequence index, the position in the run's frame list
     */
    public record Spot(double x, double y, double radius, int frame) {
        public static Spot of(DetectedObject detection) {
            return new Spot(detection.x, detection.y, TrackCropGeometry.computeBoundingFootprintRadius(detection),
                    detection.sourceFrameIndex);
        }
    }

    /** A crop of {@code image} around the spot with a ring, and the frame number when {@code label} is not null. */
    public static BufferedImage crop(short[][] image, Spot spot, Color ring, String label) {
        return crop(image, spot, ring, label, DetectionReportGenerator.captureExportSettings(
                VariableStarReportOptions.DEFAULT));
    }

    /** Renders a crop using the run's captured stretch settings rather than live desktop defaults. */
    public static BufferedImage crop(short[][] image, Spot spot, Color ring, String label, ExportVisualizationSettings settings) {
        int size = Math.min(MAX_CROP, Math.max(MIN_CROP, (int) Math.round(spot.radius() * 2) + PADDING));
        int centerX = (int) Math.round(spot.x());
        int centerY = (int) Math.round(spot.y());
        short[][] crop = TrackVisualizationRenderer.robustEdgeAwareCrop(image, centerX, centerY, size, size);
        BufferedImage picture = DisplayImageRenderer.createDisplayImage(crop,
                settings.getAutoStretchBlackSigma(), settings.getAutoStretchWhiteSigma());
        Graphics2D graphics = picture.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int ringRadius = (int) Math.max(8, Math.min(size / 2.0 - 2, spot.radius() + 6));
            int localX = (int) Math.round(spot.x() - (centerX - size / 2));
            int localY = (int) Math.round(spot.y() - (centerY - size / 2));
            graphics.setColor(ring);
            graphics.setStroke(new BasicStroke(2f));
            graphics.drawOval(localX - ringRadius, localY - ringRadius, ringRadius * 2, ringRadius * 2);
            if (label != null) {
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
                graphics.setColor(new Color(255, 255, 255, 230));
                graphics.drawString(label, 4, 13);
            }
        } finally {
            graphics.dispose();
        }
        return picture;
    }

    /**
     * A copy of a thumbnail of the reference, tinted where the mask covers it: each thumbnail pixel in proportion to
     * the share of its block of reference pixels that is masked, so scattered small stars do not tint the whole field.
     *
     * @param mask the mask at the reference's full size, read only
     */
    public static BufferedImage withStarMask(BufferedImage thumbnail, boolean[][] mask) {
        int width = thumbnail.getWidth();
        int height = thumbnail.getHeight();
        int[][] masked = new int[height][width];
        int[][] total = new int[height][width];
        double scaleX = width / (double) mask[0].length;
        double scaleY = height / (double) mask.length;
        int[] column = new int[mask[0].length];
        for (int x = 0; x < column.length; x++) {
            column[x] = Math.min(width - 1, (int) (x * scaleX));
        }
        for (int y = 0; y < mask.length; y++) {
            boolean[] row = mask[y];
            int[] maskedRow = masked[Math.min(height - 1, (int) (y * scaleY))];
            int[] totalRow = total[Math.min(height - 1, (int) (y * scaleY))];
            for (int x = 0; x < row.length; x++) {
                totalRow[column[x]]++;
                if (row[x]) {
                    maskedRow[column[x]]++;
                }
            }
        }
        // Drawn, not read with getRGB: getRGB would convert a grey thumbnail's levels and lighten the sky.
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = picture.createGraphics();
        try {
            graphics.drawImage(thumbnail, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = picture.getRGB(x, y);
                if (masked[y][x] > 0) {
                    // Stronger than the share, so a small star still shows, but never hiding the sky entirely.
                    rgb = blend(rgb, STAR_MASK.getRGB(), Math.min(0.75, 1.5 * masked[y][x] / total[y][x]));
                }
                picture.setRGB(x, y, rgb);
            }
        }
        return picture;
    }

    private static int blend(int base, int tint, double weight) {
        int red = (int) Math.round(((base >> 16) & 0xFF) * (1 - weight) + ((tint >> 16) & 0xFF) * weight);
        int green = (int) Math.round(((base >> 8) & 0xFF) * (1 - weight) + ((tint >> 8) & 0xFF) * weight);
        int blue = (int) Math.round((base & 0xFF) * (1 - weight) + (tint & 0xFF) * weight);
        return (red << 16) | (green << 8) | blue;
    }

    /** Crops of the first, a middle and the last spot of a track side by side, each in its own frame. */
    public static BufferedImage strip(List<short[][]> frames, List<Spot> spots, Color ring) {
        return strip(frames, spots, ring, DetectionReportGenerator.captureExportSettings(
                eu.startales.spacepixels.config.VariableStarReportOptions.DEFAULT));
    }

    /** Uses one captured stretch for every frame in a moving-object preview strip. */
    public static BufferedImage strip(List<short[][]> frames, List<Spot> spots, Color ring, ExportVisualizationSettings settings) {
        BufferedImage[] crops = new BufferedImage[spots.size()];
        int width = 0;
        int height = 0;
        for (int index = 0; index < spots.size(); index++) {
            Spot spot = spots.get(index);
            crops[index] = crop(frames.get(spot.frame()), spot, ring, "Frame " + (spot.frame() + 1), settings);
            width += crops[index].getWidth() + (index == 0 ? 0 : GAP);
            height = Math.max(height, crops[index].getHeight());
        }
        BufferedImage strip = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = strip.createGraphics();
        try {
            int x = 0;
            for (BufferedImage crop : crops) {
                graphics.drawImage(crop, x, 0, null);
                x += crop.getWidth() + GAP;
            }
        } finally {
            graphics.dispose();
        }
        return strip;
    }

    /**
     * A crop beside a small light curve of {@code deltaMag} by frame, brighter at the top; frames without a
     * measurement are gaps.
     */
    public static BufferedImage withLightCurve(BufferedImage crop, double[] deltaMag) {
        int height = crop.getHeight();
        BufferedImage picture = new BufferedImage(crop.getWidth() + GAP + CHART_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = picture.createGraphics();
        try {
            graphics.drawImage(crop, 0, 0, null);
            int left = crop.getWidth() + GAP;
            graphics.setColor(new Color(24, 26, 30));
            graphics.fillRect(left, 0, CHART_WIDTH, height);
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (double value : deltaMag) {
                if (Double.isFinite(value)) {
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            }
            if (deltaMag.length < 2 || !Double.isFinite(min)) {
                return picture;
            }
            double range = Math.max(max - min, 0.02);
            int margin = 8;
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(VARIABLE_STAR);
            graphics.setStroke(new BasicStroke(1.2f));
            int previousX = -1;
            int previousY = -1;
            for (int index = 0; index < deltaMag.length; index++) {
                if (!Double.isFinite(deltaMag[index])) {
                    previousX = -1;
                    continue;
                }
                int x = left + margin + (int) Math.round((CHART_WIDTH - 2.0 * margin) * index / (deltaMag.length - 1));
                // A smaller magnitude is brighter, so it is drawn higher.
                int y = margin + (int) Math.round((height - 2.0 * margin) * (deltaMag[index] - min) / range);
                if (previousX >= 0) {
                    graphics.drawLine(previousX, previousY, x, y);
                }
                graphics.fillOval(x - 2, y - 2, 4, 4);
                previousX = x;
                previousY = y;
            }
        } finally {
            graphics.dispose();
        }
        return picture;
    }
}
