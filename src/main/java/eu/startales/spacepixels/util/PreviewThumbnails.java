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

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Thumbnails for the progress dialog's live view: small, detached copies that never change the pipeline's pixels.
 * Frames and stacks use the report's automatic stretch, with its levels taken from a sample of the full image.
 */
public final class PreviewThumbnails {

    /** The long side of a thumbnail in pixels, enough for the live view at 150 % display scaling. */
    static final int LONG_SIDE = 420;
    /** About this many pixels of a large image are sampled for its stretch levels. */
    private static final int LEVEL_SAMPLES = 250_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private PreviewThumbnails() {
    }

    /** A frame or stack, reduced by averaging blocks of pixels and stretched with the levels of the full image. */
    public static BufferedImage frame(short[][] pixels, double blackSigma, double whiteSigma) {
        int height = pixels.length;
        int width = pixels[0].length;
        int block = Math.max(1, (int) Math.ceil(Math.max(width, height) / (double) LONG_SIDE));
        int reducedWidth = (width + block - 1) / block;
        int reducedHeight = (height + block - 1) / block;
        short[][] reduced = new short[reducedHeight][reducedWidth];
        long[] sums = new long[reducedWidth];
        for (int reducedY = 0; reducedY < reducedHeight; reducedY++) {
            java.util.Arrays.fill(sums, 0);
            int firstRow = reducedY * block;
            int lastRow = Math.min(height, firstRow + block);
            for (int y = firstRow; y < lastRow; y++) {
                short[] row = pixels[y];
                for (int reducedX = 0; reducedX < reducedWidth; reducedX++) {
                    int lastColumn = Math.min(width, (reducedX + 1) * block);
                    long sum = 0;
                    for (int x = reducedX * block; x < lastColumn; x++) {
                        sum += row[x] + 32768;
                    }
                    sums[reducedX] += sum;
                }
            }
            for (int reducedX = 0; reducedX < reducedWidth; reducedX++) {
                long count = (long) (lastRow - firstRow) * (Math.min(width, (reducedX + 1) * block) - reducedX * block);
                reduced[reducedY][reducedX] = (short) (sums[reducedX] / count - 32768);
            }
        }
        double[] levels = DisplayImageRenderer.displayLevels(sample(pixels), blackSigma, whiteSigma);
        return DisplayImageRenderer.createDisplayImageAtLevels(reduced, levels[0], levels[1]);
    }

    /** A copy of an image, such as a report picture, no larger than a thumbnail. */
    public static BufferedImage fit(BufferedImage image) {
        double scale = Math.min(1.0, LONG_SIDE / (double) Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    /** "Frame 3 of 33", with the observation time when the frame has one. */
    static String frameCaption(int frameNumber, int frameCount, long observationTimestamp) {
        String caption = "Frame " + frameNumber + " of " + frameCount;
        return observationTimestamp > 0 ? caption + " · " + TIME.format(Instant.ofEpochMilli(observationTimestamp)) : caption;
    }

    /** Every n-th pixel of every n-th row, about {@link #LEVEL_SAMPLES} in all. */
    private static short[][] sample(short[][] pixels) {
        int height = pixels.length;
        int width = pixels[0].length;
        int step = Math.max(1, (int) Math.sqrt((double) width * height / LEVEL_SAMPLES));
        if (step == 1) {
            return pixels;
        }
        int sampledWidth = (width + step - 1) / step;
        short[][] sampled = new short[(height + step - 1) / step][sampledWidth];
        for (int y = 0, row = 0; y < height; y += step, row++) {
            for (int x = 0, column = 0; x < width; x += step, column++) {
                sampled[row][column] = pixels[y][x];
            }
        }
        return sampled;
    }
}
