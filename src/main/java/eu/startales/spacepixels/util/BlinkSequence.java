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

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The frames of a blink, kept as 16-bit data so the display stretch can change while blinking. Each frame holds the
 * histogram of each channel (all the stretch needs) and averaged half-size copies, so a zoomed-out view reads only
 * about as many pixels as the screen shows.
 */
public final class BlinkSequence {

    /** Half-size copies are made down to this size. */
    static final int SMALLEST_LEVEL_SIDE = 512;

    private final List<Frame> frames;

    public BlinkSequence(List<Frame> frames) {
        this.frames = Collections.unmodifiableList(new ArrayList<>(frames));
    }

    public List<Frame> getFrames() {
        return frames;
    }

    /** One frame: its file, the histogram of each channel, and the data at full and reduced sizes. */
    public static final class Frame {
        private final FitsFileInformation info;
        private final int[][] histograms;
        private final List<Level> levels;

        Frame(FitsFileInformation info, short[][] channels, int width, int height) {
            this.info = info;
            this.histograms = new int[channels.length][];
            for (int channel = 0; channel < channels.length; channel++) {
                histograms[channel] = DisplayStretch.histogram(channels[channel]);
            }
            this.levels = levels(new Level(width, height, channels));
        }

        public FitsFileInformation getInfo() {
            return info;
        }

        public int getWidth() {
            return levels.get(0).width;
        }

        public int getHeight() {
            return levels.get(0).height;
        }

        public int getChannelCount() {
            return histograms.length;
        }

        public int[] getHistogram(int channel) {
            return histograms[channel];
        }

        /** Level 0 is the full frame; level k is reduced by 2^k. */
        public Level getLevel(int level) {
            return levels.get(Math.max(0, Math.min(level, levels.size() - 1)));
        }

        public int getLevelCount() {
            return levels.size();
        }

        /** The 16-bit value (0 to 65535) of the first channel at this pixel. */
        public int valueAt(int x, int y) {
            Level full = levels.get(0);
            return full.channels[0][y * full.width + x] - Short.MIN_VALUE;
        }
    }

    /** The frame data at one size, row by row, one array per channel. */
    public static final class Level {
        public final int width;
        public final int height;
        public final short[][] channels;

        Level(int width, int height, short[][] channels) {
            this.width = width;
            this.height = height;
            this.channels = channels;
        }
    }

    /** Reads a frame as SpacePixels displays it: 16-bit data as stored, other formats converted to 16 bits. */
    public static Frame load(FitsFileInformation info) throws IOException {
        try (Fits fits = new Fits(new File(info.getFilePath()))) {
            BasicHDU<?> hdu = ImageProcessing.getImageHDU(fits);
            Object kernel = hdu.getKernel();
            short[][][] planes;
            if (kernel instanceof short[][]) {
                planes = new short[][][]{(short[][]) kernel};
            } else if (kernel instanceof short[][][]) {
                planes = (short[][][]) kernel;
            } else if (kernel instanceof int[][] || kernel instanceof float[][]) {
                planes = new short[][][]{FitsPixelConverter.standardizeTo16BitMono(kernel, hdu.getHeader())};
            } else if (kernel instanceof int[][][] || kernel instanceof float[][][]) {
                planes = FitsPixelConverter.standardizeTo16BitColor(kernel, hdu.getHeader());
            } else {
                throw new IOException("Cannot show " + info.getFileName() + ": unsupported pixel format " + kernel.getClass().getSimpleName());
            }
            return fromPlanes(info, planes);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot read " + info.getFileName() + ": " + e.getMessage(), e);
        }
    }

    static Frame fromPlanes(FitsFileInformation info, short[][][] planes) {
        int height = planes[0].length;
        int width = planes[0][0].length;
        short[][] channels = new short[planes.length][];
        for (int channel = 0; channel < planes.length; channel++) {
            short[] flat = new short[width * height];
            for (int row = 0; row < height; row++) {
                System.arraycopy(planes[channel][row], 0, flat, row * width, width);
            }
            channels[channel] = flat;
            planes[channel] = null;
        }
        return new Frame(info, channels, width, height);
    }

    /** The full level and its averaged half-size copies, down to {@link #SMALLEST_LEVEL_SIDE}. */
    static List<Level> levels(Level full) {
        List<Level> levels = new ArrayList<>();
        levels.add(full);
        Level current = full;
        while (Math.max(current.width, current.height) > SMALLEST_LEVEL_SIDE) {
            current = half(current);
            levels.add(current);
        }
        return levels;
    }

    /** Each pixel is the mean of the (up to) 2 × 2 pixels it covers; an odd last row or column is kept. */
    static Level half(Level source) {
        int width = (source.width + 1) / 2;
        int height = (source.height + 1) / 2;
        short[][] channels = new short[source.channels.length][];
        for (int channel = 0; channel < channels.length; channel++) {
            short[] in = source.channels[channel];
            short[] out = new short[width * height];
            for (int y = 0; y < height; y++) {
                int y0 = 2 * y;
                int y1 = Math.min(y0 + 1, source.height - 1);
                for (int x = 0; x < width; x++) {
                    int x0 = 2 * x;
                    int x1 = Math.min(x0 + 1, source.width - 1);
                    int sum = in[y0 * source.width + x0] + in[y0 * source.width + x1]
                            + in[y1 * source.width + x0] + in[y1 * source.width + x1];
                    out[y * width + x] = (short) Math.floorDiv(sum, 4);
                }
            }
            channels[channel] = out;
        }
        return new Level(width, height, channels);
    }

    /**
     * Memory the frames need while blinking: 16-bit data plus about a third for the reduced copies, and room to read
     * the largest frame (up to 32-bit data next to its 16-bit copies).
     */
    public static long estimateBytes(FitsFileInformation[] files) {
        long bytes = 0;
        long largestFramePixels = 0;
        for (FitsFileInformation file : files) {
            long channels = file.isMonochrome() ? 1 : 3;
            long pixels = (long) file.getSizeWidth() * file.getSizeHeight() * channels;
            bytes += pixels * 2L * 4L / 3L + channels * DisplayStretch.VALUES * 4L;
            largestFramePixels = Math.max(largestFramePixels, pixels);
        }
        return bytes + largestFramePixels * 8L;
    }
}
