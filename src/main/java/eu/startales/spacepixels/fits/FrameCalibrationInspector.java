package eu.startales.spacepixels.fits;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Inspects sample frames of a sequence for calibration defects (uncalibrated sensor hot pixels)
 * and registration consistency (inter-frame star alignment shift).
 */
public final class FrameCalibrationInspector {
    private static final Logger LOGGER = Logger.getLogger(FrameCalibrationInspector.class.getName());

    public static final double DEFAULT_HOT_PIXEL_RATIO_MAX = 0.25;
    public static final double DEFAULT_HOT_PIXEL_DENSITY_THRESHOLD = 5.0; // spikes per Megapixel
    public static final int DEFAULT_HOT_PIXEL_MIN_COUNT = 30;
    public static final double DEFAULT_MISALIGNMENT_SHIFT_THRESHOLD = 2.5; // pixels
    public static final double DEFAULT_PEAK_SIGMA_MULTIPLIER = 10.0;

    private FrameCalibrationInspector() {
    }

    /**
     * Outcome of calibration and alignment inspection across sampled sequence frames.
     */
    public record InspectionResult(
            boolean isUncalibrated,
            boolean isMisaligned,
            double hotPixelsPerMegapixel,
            int hotPixelCount,
            double starShiftPixels,
            String warningMessage
    ) {
        public static InspectionResult clean() {
            return new InspectionResult(false, false, 0.0, 0, 0.0, null);
        }

        public boolean hasWarning() {
            return isUncalibrated || isMisaligned;
        }

        public String summary() {
            if (!hasWarning()) {
                return "Sequence is calibrated and aligned.";
            }
            List<String> parts = new ArrayList<>();
            if (isUncalibrated) {
                parts.add(String.format(Locale.ROOT, "Uncalibrated hot-pixel density: %.1f/MP (%d spikes)",
                        hotPixelsPerMegapixel, hotPixelCount));
            }
            if (isMisaligned) {
                parts.add(String.format(Locale.ROOT, "Misaligned frame shift: %.1f px (threshold %.1f px)",
                        starShiftPixels, DEFAULT_MISALIGNMENT_SHIFT_THRESHOLD));
            }
            return String.join("; ", parts);
        }

        public String warningDialogText() {
            if (!hasWarning()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("<html><body style='width: 420px;'>");
            sb.append("<p><b>Dataset Quality Warning:</b></p>");
            if (isUncalibrated) {
                sb.append("<p style='margin-top: 6px;'><b>High Hot-Pixel Density:</b> The frames show a high concentration of isolated hot pixels (")
                        .append(String.format(Locale.ROOT, "%.1f", hotPixelsPerMegapixel))
                        .append(" per Megapixel, ").append(hotPixelCount).append(" spikes detected). ")
                        .append("Without dark/bias subtraction or cosmetic correction, sensor defects will mimic moving objects and can generate hundreds of false detections as the telescope drifts.</p>");
            }
            if (isMisaligned) {
                sb.append("<p style='margin-top: 6px;'><b>Frame Misalignment:</b> Star positions shift by ")
                        .append(String.format(Locale.ROOT, "%.1f", starShiftPixels))
                        .append(" pixels across frames (exceeding the ")
                        .append(String.format(Locale.ROOT, "%.1f", DEFAULT_MISALIGNMENT_SHIFT_THRESHOLD))
                        .append(" px alignment threshold). ")
                        .append("Processing unaligned frames causes star subtraction leakage across the field.</p>");
            }
            sb.append("<p style='margin-top: 10px;'><b>Recommendation:</b> Cancel and ensure frames are properly calibrated and aligned before processing.</p>");
            sb.append("</body></html>");
            return sb.toString();
        }
    }

    /**
     * Inspects sample frames from the metadata array.
     */
    public static InspectionResult inspect(FitsFileInformation[] filesInfo) {
        if (filesInfo == null || filesInfo.length == 0) {
            return InspectionResult.clean();
        }
        File[] files = new File[filesInfo.length];
        for (int i = 0; i < filesInfo.length; i++) {
            files[i] = new File(filesInfo[i].getFilePath());
        }
        return inspect(files);
    }

    /**
     * Inspects sample frames from the file array. Streams one frame at a time to keep memory bounded.
     */
    public static InspectionResult inspect(File[] files) {
        if (files == null || files.length == 0) {
            return InspectionResult.clean();
        }
        int n = files.length;
        int[] sampleIndices;
        if (n == 1) {
            sampleIndices = new int[]{0};
        } else if (n == 2) {
            sampleIndices = new int[]{0, 1};
        } else {
            sampleIndices = new int[]{0, n / 2, n - 1};
        }

        try {
            int maxHotPixels = 0;
            double maxDensity = 0.0;
            double maxStarShift = 0.0;
            List<ReferenceStar> referenceStars = null;

            for (int i = 0; i < sampleIndices.length; i++) {
                int sIdx = sampleIndices[i];
                File sampleFile = files[sIdx];
                if (sampleFile == null || !sampleFile.isFile()) continue;

                try (Fits fits = new Fits(sampleFile)) {
                    BasicHDU<?> hdu = FitsInput.getImageHDU(fits);
                    if (hdu == null) continue;
                    PixelGrid grid = wrapGrid(hdu.getKernel(), hdu.getHeader());
                    if (grid == null) continue;

                    int w = grid.width();
                    int h = grid.height();
                    double megapixel = (w * (double) h) / 1_000_000.0;

                    // Hot pixel analysis
                    HotPixelStats hp = analyzeHotPixels(grid, megapixel);
                    if (hp.count > maxHotPixels) {
                        maxHotPixels = hp.count;
                        maxDensity = hp.density;
                    }

                    // Alignment analysis
                    if (i == 0) {
                        if (sampleIndices.length >= 2) {
                            FrameStats stats0 = computeFrameStats(grid);
                            if (stats0.median > 0 || stats0.sigma > 1.0f) {
                                referenceStars = findReferenceStars(grid, stats0);
                            }
                        }
                    } else if (referenceStars != null && !referenceStars.isEmpty()) {
                        FrameStats stats = computeFrameStats(grid);
                        if (stats.median > 0 || stats.sigma > 1.0f) {
                            double shift = measureStarShift(grid, referenceStars, stats);
                            if (shift > maxStarShift) {
                                maxStarShift = shift;
                            }
                        }
                    }
                }
            }

            boolean isUncalibrated = maxDensity >= DEFAULT_HOT_PIXEL_DENSITY_THRESHOLD && maxHotPixels >= DEFAULT_HOT_PIXEL_MIN_COUNT;
            boolean isMisaligned = maxStarShift > DEFAULT_MISALIGNMENT_SHIFT_THRESHOLD;

            if (isUncalibrated || isMisaligned) {
                InspectionResult res = new InspectionResult(isUncalibrated, isMisaligned, maxDensity, maxHotPixels, maxStarShift, null);
                String warningMsg = res.summary();
                return new InspectionResult(isUncalibrated, isMisaligned, maxDensity, maxHotPixels, maxStarShift, warningMsg);
            }

            return new InspectionResult(false, false, maxDensity, maxHotPixels, maxStarShift, null);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Frame calibration inspection failed: " + e.getMessage(), e);
            return InspectionResult.clean();
        }
    }

    private record HotPixelStats(int count, double density) {}

    private static HotPixelStats analyzeHotPixels(PixelGrid grid, double megapixel) {
        int h = grid.height();
        int w = grid.width();
        FrameStats stats = computeFrameStats(grid);
        float threshold = stats.median + (float) (DEFAULT_PEAK_SIGMA_MULTIPLIER * stats.sigma);

        int count = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                float val = grid.get(x, y);
                if (val > threshold) {
                    float peak = val - stats.median;
                    if (peak <= 0) continue;
                    float ortho = (grid.get(x, y - 1) - stats.median)
                            + (grid.get(x, y + 1) - stats.median)
                            + (grid.get(x - 1, y) - stats.median)
                            + (grid.get(x + 1, y) - stats.median);
                    float ratio = ortho / peak;
                    if (ratio < DEFAULT_HOT_PIXEL_RATIO_MAX) {
                        count++;
                    }
                }
            }
        }
        double density = megapixel > 0 ? count / megapixel : 0.0;
        return new HotPixelStats(count, density);
    }

    private record FrameStats(float median, float sigma) {}

    private static FrameStats computeFrameStats(PixelGrid grid) {
        int h = grid.height();
        int w = grid.width();
        boolean is16BitRange = true;
        for (int y = 0; y < Math.min(h, 20); y++) {
            for (int x = 0; x < Math.min(w, 20); x++) {
                float v = grid.get(x, y);
                if (v < 0 || v > 65535 || Float.isNaN(v)) {
                    is16BitRange = false;
                    break;
                }
            }
            if (!is16BitRange) break;
        }

        if (is16BitRange) {
            int[] hist = new int[65536];
            long total = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int v = (int) grid.get(x, y);
                    if (v >= 0 && v < 65536) {
                        hist[v]++;
                        total++;
                    }
                }
            }
            if (total == 0) return new FrameStats(0f, 1f);

            long half = total / 2;
            long target16 = (long) (total * 0.1587);
            long count = 0;
            float median = 0;
            float p16 = 0;
            boolean found16 = false;
            boolean foundMed = false;

            for (int v = 0; v < 65536; v++) {
                count += hist[v];
                if (!found16 && count >= target16) {
                    p16 = v;
                    found16 = true;
                }
                if (!foundMed && count >= half) {
                    median = v;
                    foundMed = true;
                }
                if (found16 && foundMed) break;
            }

            float sigma = Math.max(1.0f, median - p16);
            return new FrameStats(median, sigma);
        } else {
            int step = Math.max(1, (int) Math.sqrt((w * (double) h) / 50000.0));
            List<Float> samples = new ArrayList<>(50000);
            for (int y = 0; y < h; y += step) {
                for (int x = 0; x < w; x += step) {
                    float v = grid.get(x, y);
                    if (Float.isFinite(v)) {
                        samples.add(v);
                    }
                }
            }
            if (samples.isEmpty()) return new FrameStats(0f, 1f);
            samples.sort(Float::compare);
            int n = samples.size();
            float median = samples.get(n / 2);
            float p16 = samples.get((int) (n * 0.1587));
            float sigma = Math.max(1.0f, median - p16);
            return new FrameStats(median, sigma);
        }
    }

    private record ReferenceStar(int x, int y, float peak) {}

    private static List<ReferenceStar> findReferenceStars(PixelGrid grid, FrameStats stats) {
        int h = grid.height();
        int w = grid.width();
        int margin = Math.min(50, Math.min(w, h) / 6);
        if (h <= margin * 2 || w <= margin * 2) {
            return List.of();
        }

        List<ReferenceStar> candidates = new ArrayList<>();
        int step = Math.max(16, Math.min(w, h) / 30);
        float minPeak = 15.0f * stats.sigma;

        for (int y = margin; y < h - margin; y += step) {
            for (int x = margin; x < w - margin; x += step) {
                int cellMaxY = y;
                int cellMaxX = x;
                float cellMaxVal = -Float.MAX_VALUE;
                int endY = Math.min(y + step, h - margin);
                int endX = Math.min(x + step, w - margin);
                for (int cy = y; cy < endY; cy++) {
                    for (int cx = x; cx < endX; cx++) {
                        float v = grid.get(cx, cy);
                        if (v > cellMaxVal) {
                            cellMaxVal = v;
                            cellMaxY = cy;
                            cellMaxX = cx;
                        }
                    }
                }

                float peak = cellMaxVal - stats.median;
                if (peak > minPeak && cellMaxVal < 60000) {
                    boolean isLocalMax = true;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) continue;
                            if (grid.get(cellMaxX + dx, cellMaxY + dy) > cellMaxVal) {
                                isLocalMax = false;
                                break;
                            }
                        }
                        if (!isLocalMax) break;
                    }
                    if (isLocalMax) {
                        float ortho = (grid.get(cellMaxX, cellMaxY - 1) - stats.median)
                                + (grid.get(cellMaxX, cellMaxY + 1) - stats.median)
                                + (grid.get(cellMaxX - 1, cellMaxY) - stats.median)
                                + (grid.get(cellMaxX + 1, cellMaxY) - stats.median);
                        if (ortho / peak >= 0.4f) {
                            candidates.add(new ReferenceStar(cellMaxX, cellMaxY, peak));
                        }
                    }
                }
            }
        }

        candidates.sort((a, b) -> Float.compare(b.peak, a.peak));
        List<ReferenceStar> chosen = new ArrayList<>();
        int minSpacing = Math.min(60, Math.min(w, h) / 4);
        for (ReferenceStar star : candidates) {
            boolean tooClose = false;
            for (ReferenceStar c : chosen) {
                if (Math.abs(star.x - c.x) < minSpacing && Math.abs(star.y - c.y) < minSpacing) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                chosen.add(star);
                if (chosen.size() >= 8) {
                    break;
                }
            }
        }
        return chosen;
    }

    private static double measureStarShift(PixelGrid grid, List<ReferenceStar> stars0, FrameStats stats) {
        if (stars0 == null || stars0.isEmpty()) return 0.0;
        int h = grid.height();
        int w = grid.width();
        int radius = Math.min(8, Math.max(3, Math.min(w, h) / 10));
        List<Double> shifts = new ArrayList<>();

        for (ReferenceStar star : stars0) {
            int sx = star.x;
            int sy = star.y;
            if (sy - radius < 1 || sy + radius + 2 >= h || sx - radius < 1 || sx + radius + 2 >= w) {
                continue;
            }

            int bestX = sx;
            int bestY = sy;
            float bestVal = -Float.MAX_VALUE;

            for (int cy = sy - radius; cy <= sy + radius; cy++) {
                for (int cx = sx - radius; cx <= sx + radius; cx++) {
                    float v = grid.get(cx, cy);
                    if (v > bestVal) {
                        bestVal = v;
                        bestX = cx;
                        bestY = cy;
                    }
                }
            }

            float peak = bestVal - stats.median;
            boolean isLocalMax = true;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    if (grid.get(bestX + dx, bestY + dy) > bestVal) {
                        isLocalMax = false;
                        break;
                    }
                }
                if (!isLocalMax) break;
            }

            if (isLocalMax && peak >= 0.3f * star.peak) {
                int dx = bestX - sx;
                int dy = bestY - sy;
                shifts.add(Math.sqrt(dx * dx + dy * dy));
            } else {
                shifts.add((double) radius);
            }
        }

        if (shifts.isEmpty()) return 0.0;
        shifts.sort(Double::compare);
        int n = shifts.size();
        return n % 2 == 1 ? shifts.get(n / 2) : 0.5 * (shifts.get(n / 2 - 1) + shifts.get(n / 2));
    }

    private interface PixelGrid {
        int width();
        int height();
        float get(int x, int y);
    }

    private static PixelGrid wrapGrid(Object kernel, nom.tam.fits.Header header) {
        if (kernel == null) return null;
        double bzero = header == null ? 0.0 : header.getDoubleValue("BZERO", 0.0);
        double bscale = header == null ? 1.0 : header.getDoubleValue("BSCALE", 1.0);

        if (kernel instanceof short[][] sData) {
            int h = sData.length;
            int w = sData[0].length;
            return new PixelGrid() {
                @Override public int width() { return w; }
                @Override public int height() { return h; }
                @Override public float get(int x, int y) { return (float) (sData[y][x] * bscale + bzero); }
            };
        }
        if (kernel instanceof float[][] fData) {
            int h = fData.length;
            int w = fData[0].length;
            return new PixelGrid() {
                @Override public int width() { return w; }
                @Override public int height() { return h; }
                @Override public float get(int x, int y) { return (float) (fData[y][x] * bscale + bzero); }
            };
        }
        if (kernel instanceof int[][] iData) {
            int h = iData.length;
            int w = iData[0].length;
            return new PixelGrid() {
                @Override public int width() { return w; }
                @Override public int height() { return h; }
                @Override public float get(int x, int y) { return (float) (iData[y][x] * bscale + bzero); }
            };
        }
        if (kernel instanceof double[][] dData) {
            int h = dData.length;
            int w = dData[0].length;
            return new PixelGrid() {
                @Override public int width() { return w; }
                @Override public int height() { return h; }
                @Override public float get(int x, int y) { return (float) (dData[y][x] * bscale + bzero); }
            };
        }
        if (kernel instanceof short[][][] colorData) {
            short[][] sData = colorData[0];
            int h = sData.length;
            int w = sData[0].length;
            return new PixelGrid() {
                @Override public int width() { return w; }
                @Override public int height() { return h; }
                @Override public float get(int x, int y) { return (float) (sData[y][x] * bscale + bzero); }
            };
        }
        return null;
    }
}
