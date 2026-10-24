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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A correction of a session's plate solution, fitted to the Gaia stars detected in its frames: a smooth displacement
 * in pixels from where the header solution puts a sky position to where the star really is, for the whole session,
 * plus a small one per frame. Kept next to the frames as {@value #FILE_NAME}; the FITS headers are never changed.
 * It is used only while {@link #enabled}: {@link WcsSolutionResolver} then applies it, so the viewers, the cursor,
 * the report and its lookups all use the corrected coordinates.
 */
public final class PlateSolutionCorrection {

    public static final String FILE_NAME = "plate_correction.json";
    static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    /** Loaded corrections by folder, with the file time they were read at. */

    public int version = CURRENT_VERSION;
    /** Whether the correction is used. */
    public boolean enabled;
    /** Explicit viewer choice, or null until the user chooses; kept across fits of the same solution. */
    public Boolean userEnabled;
    public String fittedAtUtc;
    /** The header solution it corrects (see {@link WcsCoordinateTransformer#solutionKey()}). */
    public String solutionKey;
    public int width;
    public int height;
    /** The correction of the whole session; null when the check found the header solution needs none. */
    public Polynomial global;
    /** The extra correction of each frame, by file name; a frame without one uses the global correction only. */
    public Map<String, Polynomial> frames = new LinkedHashMap<>();
    /** Distance from the Gaia stars to the detected stars, in pixels: median and 90th percentile, before and after. */
    public double beforeMedianPx;
    public double beforeP90Px;
    public double afterMedianPx;
    public double afterP90Px;
    /** Matched stars in the median frame, and the frames fitted. */
    public int starsPerFrame;
    public int framesFitted;
    public List<String> notes = new ArrayList<>();

    /** A displacement (dx, dy) in pixels that is a polynomial of the pixel position, in coordinates scaled to ±1. */
    public static final class Polynomial {
        public int order;
        public double cx;
        public double cy;
        public double scale;
        public double[] ax;
        public double[] ay;

        public static int terms(int order) {
            return (order + 1) * (order + 2) / 2;
        }

        /** The basis values u^(n-j) v^j for n up to the order, in that order. */
        public static double[] basis(int order, double u, double v) {
            double[] values = new double[terms(order)];
            int k = 0;
            for (int n = 0; n <= order; n++) {
                for (int j = 0; j <= n; j++) {
                    values[k++] = Math.pow(u, n - j) * Math.pow(v, j);
                }
            }
            return values;
        }

        public double[] delta(double x, double y) {
            double[] values = basis(order, (x - cx) / scale, (y - cy) / scale);
            double dx = 0;
            double dy = 0;
            for (int k = 0; k < values.length; k++) {
                dx += ax[k] * values[k];
                dy += ay[k] * values[k];
            }
            return new double[]{dx, dy};
        }

        boolean isValid() {
            return order >= 0 && scale > 0 && ax != null && ay != null && ax.length == terms(order) && ay.length == terms(order);
        }
    }

    /**
     * The header solution with this correction for one frame ({@code fileName}, or null for the session as a whole),
     * or the solution unchanged when the correction was fitted to another solution or frame size.
     */
    public WcsCoordinateTransformer apply(WcsCoordinateTransformer header, String fileName, int frameWidth, int frameHeight) {
        if (header == null || global == null || !global.isValid() || !header.solutionKey().equals(solutionKey)
                || frameWidth != width || frameHeight != height) {
            return header;
        }
        Polynomial frame = fileName == null ? null : frames.get(fileName);
        Polynomial frameCorrection = frame != null && frame.isValid() ? frame : null;
        return header.withDisplacement((x, y) -> {
            double[] first = global.delta(x, y);
            if (frameCorrection == null) {
                return first;
            }
            double[] second = frameCorrection.delta(x + first[0], y + first[1]);
            return new double[]{first[0] + second[0], first[1] + second[1]};
        });
    }

    /** Whether the check found a correction that matches the stars better than the header solution. */
    public boolean isNeeded() {
        return global != null;
    }

    public PlateSolutionCorrection copy() {
        return GSON.fromJson(GSON.toJson(this), PlateSolutionCorrection.class);
    }

    /** Select the initial state of a new fit without overriding a saved, explicit viewer choice. */
    public void initializeEnabled(PlateSolutionCorrection previous) {
        boolean sameSolution = previous != null && solutionKey != null && solutionKey.equals(previous.solutionKey)
                && width == previous.width && height == previous.height;
        userEnabled = sameSolution ? previous.userEnabled : null;
        enabled = isNeeded() && (userEnabled != null ? userEnabled
                : (sameSolution && previous.enabled) || hasSignificantImprovement());
    }

    /** More than one pixel gained in median accuracy, with the outer residuals also improving. */
    public boolean hasSignificantImprovement() {
        return isNeeded() && Double.isFinite(beforeMedianPx) && Double.isFinite(afterMedianPx)
                && beforeMedianPx - afterMedianPx > 1.0
                && Double.isFinite(beforeP90Px) && Double.isFinite(afterP90Px) && afterP90Px < beforeP90Px;
    }

    /** "4.86 → 0.11 px". */
    public String improvementText() {
        return String.format(Locale.US, "%.2f → %.2f px", beforeMedianPx, afterMedianPx);
    }

    // ==========================================
    // FILE
    // ==========================================

    public static File fileIn(File sessionFolder) {
        return new File(sessionFolder, FILE_NAME);
    }

    public static void save(File sessionFolder, PlateSolutionCorrection correction) throws IOException {
        try (StagedFile staged = new StagedFile(fileIn(sessionFolder))) {
            try (Writer writer = staged.newWriter()) {
                GSON.toJson(correction, writer);
            }
            staged.publish();
        }
    }

    /** The correction kept in the session folder, or null when there is none or it cannot be read. */
    public static PlateSolutionCorrection load(File sessionFolder) {
        File file = fileIn(sessionFolder);
        if (!file.isFile()) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            PlateSolutionCorrection correction = GSON.fromJson(reader, PlateSolutionCorrection.class);
            if (correction == null || correction.version > CURRENT_VERSION
                    || (correction.global != null && !correction.global.isValid()) || correction.solutionKey == null) {
                return null;
            }
            if (correction.frames == null) {
                correction.frames = new LinkedHashMap<>();
            }
            if (correction.notes == null) {
                correction.notes = new ArrayList<>();
            }
            correction.enabled &= correction.isNeeded();
            return correction;
        } catch (IOException | JsonParseException e) {
            return null;
        }
    }

}
