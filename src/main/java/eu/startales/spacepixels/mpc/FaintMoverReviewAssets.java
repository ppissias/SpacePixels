/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.mpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads and renders a faint mover's saved report review assets (whole-session shift stack PNG and synchronized
 * PSF-filtered static-reference-subtracted residual cutouts from {@code fm<n>_view.js}) for interactive inspection
 * in the Asteroids &amp; Comets window.
 */
public final class FaintMoverReviewAssets {

    /** Default symmetric signed residual display bound, matching the HTML report's review controls. */
    public static final double DEFAULT_RESIDUAL_BOUND = 3.0;
    private static final double ASINH_3 = Math.log(3.0 + Math.sqrt(10.0));
    /** Maximum cutout side accepted from review assets, matching {@code faint-mover-review.js}. */
    private static final int MAX_SIDE = 256;

    /** One frame's geometry inside the saved faint-mover residual cube. */
    public record ResidualFrame(int index, int frameNumber, double x, double y, int originX, int originY, boolean unmasked) { }

    /** Decoded synchronized residual cutouts for a faint mover. */
    public static final class ResidualCube {
        private final int width;
        private final int height;
        private final boolean followsPath;
        private final List<ResidualFrame> frames;
        private final Map<Integer, ResidualFrame> byFrameNumber;
        private final float[] pixels;

        ResidualCube(int width, int height, boolean followsPath, List<ResidualFrame> frames, float[] pixels) {
            this.width = width;
            this.height = height;
            this.followsPath = followsPath;
            this.frames = List.copyOf(frames);
            this.pixels = pixels;
            Map<Integer, ResidualFrame> map = new HashMap<>(frames.size() * 2);
            for (ResidualFrame frame : frames) {
                map.put(frame.frameNumber(), frame);
            }
            this.byFrameNumber = Collections.unmodifiableMap(map);
        }

        public int width() { return width; }

        public int height() { return height; }

        public boolean followsPath() { return followsPath; }

        public List<ResidualFrame> frames() { return frames; }

        /** Returns the residual frame matching a 1-based session frame number, or {@code null} if absent. */
        public ResidualFrame frameForNumber(int frameNumber) {
            return byFrameNumber.get(frameNumber);
        }

        /**
         * Renders one residual frame into an 8-bit grayscale image with the same signed asinh mapping and optional
         * motion-centred bilinear resampling used by the HTML report's interactive faint-mover review.
         */
        public BufferedImage render(ResidualFrame frame, boolean objectCentred, double bound) {
            double safeBound = Double.isFinite(bound) && bound > 0.0 ? bound : DEFAULT_RESIDUAL_BOUND;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            WritableRaster raster = image.getRaster();
            int offset = frame.index() * width * height;
            double dx = objectCentred ? frame.x() - (width - 1) / 2.0 : 0.0;
            double dy = objectCentred ? frame.y() - (height - 1) / 2.0 : 0.0;
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double value = sample(pixels, offset, width, height, x + dx, y + dy);
                    if (Double.isFinite(value)) {
                        double signed = Math.max(-1.0, Math.min(1.0, value / safeBound));
                        double mapped = 0.5 + 0.5 * asinh(3.0 * signed) / ASINH_3;
                        row[x] = (int) Math.round(255.0 * Math.max(0.0, Math.min(1.0, mapped)));
                    } else {
                        row[x] = 0;
                    }
                }
                raster.setSamples(0, y, width, 1, 0, row);
            }
            return image;
        }
    }

    private final BufferedImage shiftStack;
    private final ResidualCube residuals;

    private FaintMoverReviewAssets(BufferedImage shiftStack, ResidualCube residuals) {
        this.shiftStack = shiftStack;
        this.residuals = residuals;
    }

    public BufferedImage shiftStack() { return shiftStack; }

    public ResidualCube residuals() { return residuals; }

    /** True when either the shift-stack image or the synchronized residual cube is available. */
    public boolean hasAny() {
        return shiftStack != null || residuals != null;
    }

    /**
     * Loads any available faint-mover review assets from {@code reportFolder} without throwing on missing or
     * unreadable files; returns {@code null} when neither asset is present.
     */
    public static FaintMoverReviewAssets load(File reportFolder, String id, ReportedObjects.FaintMoverAssetPaths paths) {
        if (reportFolder == null || id == null || id.isBlank()) {
            return null;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        String stackPath = paths != null && paths.cutout() != null && !paths.cutout().isBlank()
                ? paths.cutout() : "faint_movers/" + lower + "_stack.png";
        String dataPath = paths != null && paths.reviewData() != null && !paths.reviewData().isBlank()
                ? paths.reviewData() : "faint_movers/" + lower + "_view.js";

        BufferedImage stack = loadImage(new File(reportFolder, stackPath));
        ResidualCube residuals = loadResiduals(new File(reportFolder, dataPath));
        FaintMoverReviewAssets assets = new FaintMoverReviewAssets(stack, residuals);
        return assets.hasAny() ? assets : null;
    }

    private static BufferedImage loadImage(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            return ImageIO.read(file);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    /** Parses the JSON wrapper in {@code fm<n>_view.js} and decodes its base64 little-endian float32 residuals. */
    static ResidualCube loadResiduals(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonObject data = JsonParser.parseString(content.substring(start, end + 1)).getAsJsonObject();
            if (!data.has("version") || data.get("version").getAsInt() != 1) {
                return null;
            }
            if (!data.has("residual") || !data.get("residual").isJsonPrimitive()) {
                return null;
            }
            int width = data.has("width") ? data.get("width").getAsInt() : 0;
            int height = data.has("height") ? data.get("height").getAsInt() : 0;
            if (width <= 0 || width > MAX_SIDE || height <= 0 || height > MAX_SIDE) {
                return null;
            }
            if (!data.has("frames") || !data.get("frames").isJsonArray()) {
                return null;
            }
            boolean followsPath = data.has("followsPath") && data.get("followsPath").getAsBoolean();
            List<ResidualFrame> frames = new ArrayList<>();
            int idx = 0;
            for (JsonElement el : data.getAsJsonArray("frames")) {
                if (!el.isJsonObject()) {
                    return null;
                }
                JsonObject f = el.getAsJsonObject();
                int number = f.has("number") ? f.get("number").getAsInt() : 0;
                double x = f.has("x") ? f.get("x").getAsDouble() : 0.0;
                double y = f.has("y") ? f.get("y").getAsDouble() : 0.0;
                int originX = f.has("originX") ? f.get("originX").getAsInt() : 0;
                int originY = f.has("originY") ? f.get("originY").getAsInt() : 0;
                boolean unmasked = !f.has("unmasked") || f.get("unmasked").getAsBoolean();
                frames.add(new ResidualFrame(idx++, number, x, y, originX, originY, unmasked));
            }
            if (frames.isEmpty()) {
                return null;
            }
            byte[] decoded = Base64.getDecoder().decode(data.get("residual").getAsString());
            int expectedSamples = width * height * frames.size();
            if (decoded.length != expectedSamples * 4) {
                return null;
            }
            float[] pixels = new float[expectedSamples];
            ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(pixels);
            return new ResidualCube(width, height, followsPath, frames, pixels);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    /** Bilinear sample matching {@code faint-mover-review.js}; returns {@code Double.NaN} outside bounds or on non-finite input. */
    private static double sample(float[] pixels, int offset, int width, int height, double x, double y) {
        if (x < 0.0 || y < 0.0 || x > width - 1 || y > height - 1) {
            return Double.NaN;
        }
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(width - 1, x0 + 1);
        int y1 = Math.min(height - 1, y0 + 1);
        if (x == x0 && y == y0) {
            float v = pixels[offset + y0 * width + x0];
            return Float.isFinite(v) ? v : Double.NaN;
        }
        float v00 = pixels[offset + y0 * width + x0];
        float v01 = pixels[offset + y0 * width + x1];
        float v10 = pixels[offset + y1 * width + x0];
        float v11 = pixels[offset + y1 * width + x1];
        double dx = x - x0;
        double dy = y - y0;
        double w00 = (1.0 - dx) * (1.0 - dy);
        double w01 = dx * (1.0 - dy);
        double w10 = (1.0 - dx) * dy;
        double w11 = dx * dy;
        double sum = 0.0;
        if (w00 > 0.0) { if (!Float.isFinite(v00)) return Double.NaN; sum += w00 * v00; }
        if (w01 > 0.0) { if (!Float.isFinite(v01)) return Double.NaN; sum += w01 * v01; }
        if (w10 > 0.0) { if (!Float.isFinite(v10)) return Double.NaN; sum += w10 * v10; }
        if (w11 > 0.0) { if (!Float.isFinite(v11)) return Double.NaN; sum += w11 * v11; }
        return sum;
    }

    private static double asinh(double value) {
        return Math.log(value + Math.sqrt(value * value + 1.0));
    }
}
