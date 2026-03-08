package eu.startales.spacepixels.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

public class SourceExtractor {

    // =================================================================
    // CONFIGURATION PARAMETERS
    // =================================================================

    /** The primary strict threshold to start detecting a new object.
     * Lowering this catches fainter objects but increases noise. */
    public static double detectionSigmaMultiplier = 7.0;

    /** The absolute minimum number of pixels a blob must have to even be evaluated. */
    public static int minDetectionPixels = 10;

    /** The "dead zone" in pixels around the edge of the sensor.
     * Objects with their center in this zone are ignored to prevent edge artifacts. */
    public static int edgeMarginPixels = 15;

    /** Dual-Thresholding (Hysteresis): The lower threshold used to trace the faint
     * edges of a blob once a bright seed pixel is found. */
    public static double growSigmaMultiplier = 1.2;

    /** If a pixel is darker than this fraction of the background median
     * (e.g., 0.5 means 50% darker), it is assumed to be artificial registration padding. */
    public static double voidThresholdFraction = 0.5;

    // --- NEW: Void Proximity Killer ---
    /** The distance (in pixels) to look ahead for a void edge.
     * Defeats interpolation gradients left by image alignment algorithms. */
    public static int voidProximityRadius = 3;

    // --- Shape Classification Parameters ---

    /** The minimum elongation ratio (length/width) required to classify a blob as a fast-moving streak. */
    public static double streakMinElongation = 5.0;

    /** The minimum number of pixels required to classify an elongated blob as a streak. */
    public static int streakMinPixels = 10;

    /** The minimum number of pixels to classify a blob as a point source (star/asteroid). */
    public static int pointSourceMinPixels = 4;

    // --- Background Statistics Parameters ---

    /** Number of passes used to exclude bright stars when calculating the background sky noise. */
    public static int bgClippingIterations = 3;

    /** The threshold (in standard deviations) used to chop off stars during background calculation. */
    public static double bgClippingFactor = 3.0;


    // =================================================================
    // HELPER CLASSES
    // =================================================================

    public static class DetectedObject {
        public double x, y;
        public double totalFlux;
        public int pixelCount;

        // Size metric for TrackLinker Morphological Filtering
        public double pixelArea;

        // FWHM Metric
        public double fwhm;

        // Shape Fields
        public double elongation;
        public double angle; // The angle of the streak in radians
        public boolean isStreak;
        public boolean isNoise;

        public int sourceFrameIndex; // Points back to rawFrames.get(i)
        public String sourceFilename;

        public DetectedObject(double x, double y, double flux, int count) {
            this.x = x;
            this.y = y;
            this.totalFlux = flux;
            this.pixelCount = count;
        }
    }

    public static class Pixel {
        int x, y, value;

        public Pixel(int x, int y, int value) {
            this.x = x;
            this.y = y;
            this.value = value;
        }
    }

    public static class BackgroundMetrics {
        public double median;
        public double sigma;
        public double threshold;
    }

    // =================================================================
    // CORE EXTRACTION PIPELINE
    // =================================================================

    /**
     * Main method to run the detection pipeline on a single frame.
     */
    public static List<DetectedObject> extractSources(short[][] image, double sigmaMultiplier, int minPixels) {
        int height = image.length;
        int width = image[0].length;

        // 1. Calculate Background
        BackgroundMetrics bg = calculateBackgroundSigmaClipped(image, width, height, sigmaMultiplier);

        // --- DUAL THRESHOLDS (HYSTERESIS) ---
        double seedThreshold = bg.median + (bg.sigma * sigmaMultiplier);
        double growThreshold = bg.median + (bg.sigma * growSigmaMultiplier);

        // Pre-calculate the void numeric threshold
        double voidValueThreshold = bg.median * voidThresholdFraction;

        // 2. Setup for BFS Blob Detection
        List<DetectedObject> detectedObjects = new ArrayList<>();
        boolean[][] visited = new boolean[height][width];

        int[] dx = {-1, -1, -1, 0, 0, 1, 1, 1};
        int[] dy = {-1, 0, 1, -1, 1, -1, 0, 1};

        // 3. Scan the image
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {

                int pixelValue = image[y][x] + 32768;

                // --- Start a blob ONLY if it beats the strict SEED threshold ---
                if (pixelValue > seedThreshold && !visited[y][x]) {
                    List<Pixel> currentBlob = new ArrayList<>();

                    // Optimized to use ArrayDeque instead of LinkedList for faster BFS
                    Queue<Pixel> queue = new java.util.ArrayDeque<>();

                    Pixel startPixel = new Pixel(x, y, pixelValue);
                    queue.add(startPixel);
                    visited[y][x] = true;

                    // --- FAST BFS LOOP (No complex proximity logic here) ---
                    while (!queue.isEmpty()) {
                        Pixel p = queue.poll();
                        currentBlob.add(p);

                        for (int i = 0; i < 8; i++) {
                            int nx = p.x + dx[i];
                            int ny = p.y + dy[i];

                            if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                                if (!visited[ny][nx]) {
                                    int nValue = image[ny][nx] + 32768;
                                    if (nValue > growThreshold) {
                                        visited[ny][nx] = true;
                                        queue.add(new Pixel(nx, ny, nValue));
                                    }
                                }
                            }
                        }
                    }

                    // 4. Centroiding and Filtering
                    if (currentBlob.size() >= minPixels) {

                        DetectedObject obj = analyzeShape(currentBlob, bg.median);

                        // SENSOR EDGE FILTER
                        if (obj.x < edgeMarginPixels || obj.x >= (width - edgeMarginPixels) ||
                                obj.y < edgeMarginPixels || obj.y >= (height - edgeMarginPixels)) {
                            continue;
                        }

                        if (obj.isNoise) {
                            continue;
                        }

// ==========================================================
                        // 5. POST-CLASSIFICATION VOID PROXIMITY CHECK (Streaks Only)
                        // ==========================================================
                        if (obj.isStreak) {
                            int voidTouchingPixels = 0;

                            // Check every pixel in the streak against the void radius
                            for (Pixel p : currentBlob) {
                                boolean pixelTouchesVoid = false;

                                for (int vy = -voidProximityRadius; vy <= voidProximityRadius; vy++) {
                                    for (int vx = -voidProximityRadius; vx <= voidProximityRadius; vx++) {
                                        int checkX = p.x + vx;
                                        int checkY = p.y + vy;

                                        // If the radius hits the absolute array boundary
                                        if (checkX < 0 || checkX >= width || checkY < 0 || checkY >= height) {
                                            pixelTouchesVoid = true;
                                            break;
                                        }

                                        // If the radius hits the black registration padding
                                        int vValue = image[checkY][checkX] + 32768;
                                        if (vValue < voidValueThreshold) {
                                            pixelTouchesVoid = true;
                                            break;
                                        }
                                    }
                                    if (pixelTouchesVoid) break;
                                }

                                if (pixelTouchesVoid) {
                                    voidTouchingPixels++;
                                }
                            }

                            // Calculate what percentage of the streak is touching the void
                            double voidTouchRatio = (double) voidTouchingPixels / currentBlob.size();

                            // If more than 30% of the streak is hugging the edge, it is a registration artifact.
                            // A real satellite entering the frame will have a very low ratio (e.g., 5%).
                            if (voidTouchRatio > 0.30) {
                                continue; // Discard this fake streak artifact!
                            }
                        }

                        // RECORD THE PIXEL AREA
                        obj.pixelArea = currentBlob.size();

                        detectedObjects.add(obj);
                    }
                }
            }
        }
        return detectedObjects;
    }

    /**
     * Fast Histogram-based background estimation using Iterative Sigma Clipping.
     */
    public static BackgroundMetrics calculateBackgroundSigmaClipped(short[][] image, int width, int height, double sigmaMultiplier) {
        BackgroundMetrics metrics = new BackgroundMetrics();
        int[] histogram = new int[65536];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int val = image[y][x] + 32768;
                histogram[val]++;
            }
        }

        int currentMinBin = 0;
        int currentMaxBin = 65535;
        double currentMedian = 0;
        double currentSigma = 0;

        for (int iter = 0; iter < bgClippingIterations; iter++) { // Parameterized!
            long count = 0;
            long validPixelCount = 0;

            for (int i = currentMinBin; i <= currentMaxBin; i++) {
                validPixelCount += histogram[i];
            }

            if (validPixelCount == 0) break;

            for (int i = currentMinBin; i <= currentMaxBin; i++) {
                count += histogram[i];
                if (count >= validPixelCount / 2) {
                    currentMedian = i;
                    break;
                }
            }

            double sumSqDiff = 0;
            for (int i = currentMinBin; i <= currentMaxBin; i++) {
                if (histogram[i] > 0) {
                    double diff = i - currentMedian;
                    sumSqDiff += (diff * diff) * histogram[i];
                }
            }
            currentSigma = Math.sqrt(sumSqDiff / validPixelCount);

            currentMinBin = (int) Math.max(0, Math.floor(currentMedian - (bgClippingFactor * currentSigma))); // Parameterized!
            currentMaxBin = (int) Math.min(65535, Math.ceil(currentMedian + (bgClippingFactor * currentSigma))); // Parameterized!
        }

        metrics.median = currentMedian;
        metrics.sigma = currentSigma;
        metrics.threshold = currentMedian + (currentSigma * sigmaMultiplier);

        return metrics;
    }

    /**
     * Calculates the centroid, elongation, and angle of a pixel blob using Image Moments.
     */
    public static DetectedObject analyzeShape(List<Pixel> blob, double bgMedian) {
        DetectedObject obj = new DetectedObject(0,0,0,0);

        double m00 = 0;
        double m10 = 0;
        double m01 = 0;

        for (Pixel p : blob) {
            double intensity = p.value - bgMedian;
            if (intensity <= 0) intensity = 1;

            m00 += intensity;
            m10 += p.x * intensity;
            m01 += p.y * intensity;
        }

        obj.x = m10 / m00;
        obj.y = m01 / m00;
        obj.totalFlux = m00;

        double mu20 = 0, mu02 = 0, mu11 = 0;
        for (Pixel p : blob) {
            double intensity = p.value - bgMedian;
            if (intensity <= 0) intensity = 1;

            double dx = p.x - obj.x;
            double dy = p.y - obj.y;

            mu20 += (dx * dx) * intensity;
            mu02 += (dy * dy) * intensity;
            mu11 += (dx * dy) * intensity;
        }

        mu20 /= m00;
        mu02 /= m00;
        mu11 /= m00;

        double delta = Math.sqrt((mu20 - mu02) * (mu20 - mu02) + 4 * mu11 * mu11);
        double lambda1 = (mu20 + mu02 + delta) / 2.0;
        double lambda2 = (mu20 + mu02 - delta) / 2.0;

        if (lambda2 < 0.001) lambda2 = 0.001;
        if (lambda1 < 0.001) lambda1 = 0.001;

        obj.elongation = Math.sqrt(lambda1 / lambda2);
        obj.angle = 0.5 * Math.atan2(2 * mu11, mu20 - mu02);

        // =================================================================
        // STEP 4: The Shape Filter Classification (Strict Thin Line Logic)
        // =================================================================

        // 1. Is it a definitive, thin, long line? (Streak)
        // Parameterized elongation and pixel size!
        if (obj.elongation > streakMinElongation && blob.size() > streakMinPixels) {
            obj.isStreak = true;
            obj.isNoise = false;
        }
        // 2. Is it a Star, Asteroid, or a merged "Peanut"? (Point Source Fallback)
        // Parameterized point source minimum pixel size!
        else if (obj.elongation <= streakMinElongation && blob.size() >= pointSourceMinPixels) {
            obj.isStreak = false;
            obj.isNoise = false;
        }
        // 3. Everything else is microscopic noise
        else {
            obj.isStreak = false;
            obj.isNoise = true;
        }

        return obj;
    }
}