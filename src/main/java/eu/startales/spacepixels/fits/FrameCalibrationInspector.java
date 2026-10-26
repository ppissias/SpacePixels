package eu.startales.spacepixels.fits;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import io.github.ppissias.jtransient.execution.CancellationScope;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.io.IOException;
import java.util.concurrent.CancellationException;

/**
 * Inspects sample frames of a sequence for calibration defects (uncalibrated sensor hot pixels)
 * and registration consistency (inter-frame star alignment shift).
 */
public final class FrameCalibrationInspector {

    public static final double DEFAULT_HOT_PIXEL_RATIO_MAX = 0.25;
    public static final double DEFAULT_HOT_PIXEL_DENSITY_THRESHOLD = 5.0; // spikes per Megapixel
    public static final int DEFAULT_HOT_PIXEL_MIN_COUNT = 30;
    public static final double DEFAULT_MISALIGNMENT_SHIFT_THRESHOLD = 2.5; // pixels
    public static final double DEFAULT_PEAK_SIGMA_MULTIPLIER = 10.0;

    /** Prevents construction of this stateless sample inspector. */
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
        /** Reports that no sampled warning was found; this is not a calibration certificate. */
        public static InspectionResult clean() {
            return new InspectionResult(false, false, 0.0, 0, 0.0, null);
        }

        /** Whether a sampled calibration or alignment warning needs a caller decision. */
        public boolean hasWarning() {
            return isUncalibrated || isMisaligned;
        }

        /** Plain text for logs, API results and command output. */
        public String summary() {
            if (!hasWarning()) {
                return "No calibration or alignment warning found in the sampled frames.";
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

        /** Explains the sampled evidence and the recommended cancellation in a desktop dialog. */
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
        CancellationScope.check();
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
        CancellationScope.check();
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
                CancellationScope.check();
                int sIdx = sampleIndices[i];
                File sampleFile = files[sIdx];
                if (sampleFile == null || !sampleFile.isFile()) throw new IOException("A sampled frame is unavailable.");

                try (Fits fits = new Fits(sampleFile)) {
                    BasicHDU<?> hdu = FitsInput.getImageHDU(fits);
                    if (hdu == null) throw new IOException("A sampled frame has no image HDU.");
                    PixelGrid grid = wrapGrid(hdu.getKernel(), hdu.getHeader());
                    if (grid == null) throw new IOException("A sampled frame has an unsupported pixel layout.");

                    int w = grid.width();
                    int h = grid.height();
                    double megapixel = (w * (double) h) / 1_000_000.0;

                    // Hot pixel analysis
                    HotPixelStats hp = analyzeHotPixels(grid, megapixel);
                    if (hp.count > maxHotPixels) {
                        maxHotPixels = hp.count;
                        maxDensity = hp.density;
                    }

                    // Match a coherent star displacement, independently of the frames' brightness.
                    FrameStats stats = computeFrameStats(grid);
                    List<ReferenceStar> stars = findReferenceStars(grid, stats);
                    if (i == 0) {
                        referenceStars = stars;
                    } else if (referenceStars != null) {
                        maxStarShift = Math.max(maxStarShift, measureStarShift(referenceStars, stars));
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

        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception e) {
            CancellationScope.check();
            throw new IllegalStateException("Frame calibration inspection failed: " + e.getMessage(), e);
        }
    }

    /** Counts isolated peaks in one frame and expresses their density per megapixel. */
    private record HotPixelStats(int count, double density) {}

    /** Measures sharp sensor spikes without modifying pixels; cancellation is checked on every row. */
    private static HotPixelStats analyzeHotPixels(PixelGrid grid, double megapixel) {
        int h = grid.height();
        int w = grid.width();
        FrameStats stats = computeFrameStats(grid);
        float threshold = stats.median + (float) (DEFAULT_PEAK_SIGMA_MULTIPLIER * stats.sigma);

        int count = 0;
        for (int y = 1; y < h - 1; y++) {
            CancellationScope.check();
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

    /** Background level and robust lower-half noise estimate, in the image's physical units. */
    private record FrameStats(float median, float sigma) {}

    /** Estimates sky and noise while preserving fractional or negative floating-point units. */
    private static FrameStats computeFrameStats(PixelGrid grid) {
        int h = grid.height();
        int w = grid.width();
        // Histogram bins are valid only for known unsigned integer storage, never normalized floating data.
        if (grid.unsigned16()) {
            int[] hist = new int[65536];
            long total = 0;
            for (int y = 0; y < h; y++) {
                CancellationScope.check();
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
                CancellationScope.check();
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
            float sigma = Math.max(Math.ulp(median), median - p16);
            return new FrameStats(median, sigma);
        }
    }

    /** A bright extended peak with a background-subtracted local centroid. */
    private record ReferenceStar(double x, double y, float peak) {}

    /** Selects up to 32 spaced optical peaks; isolated sensor spikes cannot become alignment references. */
    private static List<ReferenceStar> findReferenceStars(PixelGrid grid, FrameStats stats) {
        int h = grid.height();
        int w = grid.width();
        int margin = Math.max(1, Math.min(50, Math.min(w, h) / 6));
        if (h <= margin * 2 || w <= margin * 2) {
            return List.of();
        }

        List<ReferenceStar> candidates = new ArrayList<>();
        int step = Math.max(16, Math.min(w, h) / 30);
        float minPeak = 15.0f * stats.sigma;

        for (int y = margin; y < h - margin; y += step) {
            CancellationScope.check();
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
                if (peak > minPeak && (!grid.unsigned16() || cellMaxVal < 60000)) {
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
                            double weight = 0, weightedX = 0, weightedY = 0;
                            for (int dy = -1; dy <= 1; dy++) {
                                for (int dx = -1; dx <= 1; dx++) {
                                    double value = Math.max(0, grid.get(cellMaxX + dx, cellMaxY + dy) - stats.median);
                                    weight += value;
                                    weightedX += value * (cellMaxX + dx);
                                    weightedY += value * (cellMaxY + dy);
                                }
                            }
                            candidates.add(new ReferenceStar(weightedX / weight, weightedY / weight, peak));
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
                if (chosen.size() >= 32) {
                    break;
                }
            }
        }
        return chosen;
    }

    /** Finds a displacement supported by at least three stars and half of the smaller reference set. */
    private static double measureStarShift(List<ReferenceStar> reference, List<ReferenceStar> current) {
        int required = Math.max(3, (Math.min(reference.size(), current.size()) + 1) / 2);
        int bestCount = 0;
        double bestShift = 0;
        for (ReferenceStar origin : reference) {
            CancellationScope.check();
            for (ReferenceStar destination : current) {
                double dx = destination.x - origin.x;
                double dy = destination.y - origin.y;
                List<Double> offsetsX = new ArrayList<>();
                List<Double> offsetsY = new ArrayList<>();
                for (ReferenceStar first : reference) {
                    ReferenceStar nearest = null;
                    double distance = 2.0 * 2.0;
                    for (ReferenceStar second : current) {
                        double errorX = second.x - first.x - dx;
                        double errorY = second.y - first.y - dy;
                        double error = errorX * errorX + errorY * errorY;
                        if (error < distance) {
                            nearest = second;
                            distance = error;
                        }
                    }
                    if (nearest != null) {
                        offsetsX.add(nearest.x - first.x);
                        offsetsY.add(nearest.y - first.y);
                    }
                }
                int matches = offsetsX.size();
                if (matches < required || matches < bestCount) continue;
                double shift = Math.hypot(median(offsetsX), median(offsetsY));
                if (matches > bestCount || shift < bestShift) {
                    bestCount = matches;
                    bestShift = shift;
                }
            }
        }
        // Missing stars provide insufficient alignment evidence; they are not a measured eight-pixel shift.
        return bestCount >= required ? bestShift : 0;
    }

    /** Returns the median of a nonempty offset list in physical pixels. */
    private static double median(List<Double> values) {
        values.sort(Double::compare);
        int n = values.size();
        return n % 2 == 1 ? values.get(n / 2) : 0.5 * (values.get(n / 2 - 1) + values.get(n / 2));
    }

    /** Read-only access to one image plane, with explicit unsigned-integer histogram eligibility. */
    private interface PixelGrid {
        /** Width in pixels. */
        int width();
        /** Height in pixels. */
        int height();
        /** Physical pixel value after FITS scaling. */
        float get(int x, int y);
        /** Whether every value has the unsigned 16-bit integer storage contract. */
        default boolean unsigned16() { return false; }
    }

    /** Adapts a numeric FITS plane (or first colour plane), retaining its physical scale and no copied buffers. */
    private static PixelGrid wrapGrid(Object kernel, nom.tam.fits.Header header) {
        if (kernel == null) return null;
        if (kernel instanceof Object[] planes && planes.length > 0 && planes[0] instanceof Object[]) {
            return wrapGrid(planes[0], header);
        }
        double bzero = header == null ? 0.0 : header.getDoubleValue("BZERO", 0.0);
        double bscale = header == null ? 1.0 : header.getDoubleValue("BSCALE", 1.0);

        if (kernel instanceof short[][] sData) {
            int h = sData.length;
            int w = sData[0].length;
            return new PixelGrid() {
                @Override public int width() { return w; }
                @Override public int height() { return h; }
                @Override public float get(int x, int y) { return (float) (sData[y][x] * bscale + bzero); }
                @Override public boolean unsigned16() { return bscale == 1.0 && bzero == 32768.0; }
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
        return null;
    }
}
