package spv.util;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class SourceExtractor {

    //values used for detection pipeline
    public static double detectionSigmaMultiplier = 3.5;
    public static int minDetectionPixels = 3;

    // Helper classes to hold our data
    public static class DetectedObject {
        public double x, y;
        public double totalFlux;
        public int pixelCount;

        // --- NEW: Size metric for TrackLinker Morphological Filtering ---
        public double pixelArea;

        // --- New FWHM Metric ---
        public double fwhm;

        // --- New Shape Fields ---
        public double elongation;
        public double angle; // The angle of the streak in radians
        public boolean isStreak;
        public boolean isNoise;

        public int sourceFrameIndex; // NEW: Points back to rawFrames.get(i)
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

    /**
     * Main method to run the detection pipeline on a single frame.
     */
    public static List<DetectedObject> extractSources(short[][] image, double sigmaMultiplier, int minPixels) {
        // FITS data is row-major: the first dimension is Height (Y), the second is Width (X)
        int height = image.length;
        int width = image[0].length;

        // 1. Calculate Background
        BackgroundMetrics bg = calculateBackgroundSigmaClipped(image, width, height, sigmaMultiplier);
        System.out.println("Background Median: " + bg.median + ", Sigma: " + bg.sigma + ", Threshold: " + bg.threshold);

        // 2. Setup for BFS Blob Detection
        List<DetectedObject> detectedObjects = new ArrayList<>();
        boolean[][] visited = new boolean[height][width];

        // 8-way directional arrays for finding neighbors
        int[] dx = {-1, -1, -1, 0, 0, 1, 1, 1};
        int[] dy = {-1, 0, 1, -1, 1, -1, 0, 1};

        // 3. Scan the image
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {

                int pixelValue = image[y][x] + 32768; // Convert Java signed short to unsigned 16-bit

                if (pixelValue > bg.threshold && !visited[y][x]) {
                    // Start a new BFS for a new Blob
                    List<Pixel> currentBlob = new ArrayList<>();
                    Queue<Pixel> queue = new LinkedList<>();

                    Pixel startPixel = new Pixel(x, y, pixelValue);
                    queue.add(startPixel);
                    visited[y][x] = true;

                    // BFS Loop
                    while (!queue.isEmpty()) {
                        Pixel p = queue.poll();
                        currentBlob.add(p);

                        // Check 8 neighbors
                        for (int i = 0; i < 8; i++) {
                            int nx = p.x + dx[i];
                            int ny = p.y + dy[i];

                            // Check boundaries
                            if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                                if (!visited[ny][nx]) {
                                    // FIXED TYPO HERE: Removed the double plus sign
                                    int nValue = image[ny][nx] + 32768;
                                    if (nValue > bg.threshold) {
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

                        // --- THE COMET TRIPWIRE ---
                        //if (currentBlob.size() > 150) {
                        //    System.out.println("\n[TRIPWIRE] Massive object found!");
                        //    System.out.println("  -> Size: " + currentBlob.size() + " pixels");
                        //    System.out.println("  -> Location: X:" + obj.x + ", Y:" + obj.y);
                        //    System.out.println("  -> Flagged as noise by analyzeShape? " + obj.isNoise);

                            // Force the software to keep it so we can see it in the final image!
                            // obj.isNoise = false;
                        //}

                        // The Roundness Filter Drop
                        if (obj.isNoise) {
                            continue;
                        }

                        if (obj.isStreak) {
                            System.out.println("Streak detected at X: " + obj.x + ", Y: " + obj.y + " with angle: " + obj.angle);
                        }

                        // --- NEW: RECORD THE PIXEL AREA ---
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

        int iterations = 3;
        double clippingFactor = 3.0;

        for (int iter = 0; iter < iterations; iter++) {
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

            currentMinBin = (int) Math.max(0, Math.floor(currentMedian - (clippingFactor * currentSigma)));
            currentMaxBin = (int) Math.min(65535, Math.ceil(currentMedian + (clippingFactor * currentSigma)));
        }

        metrics.median = currentMedian;
        metrics.sigma = currentSigma;
        metrics.threshold = currentMedian + (currentSigma * sigmaMultiplier);

        return metrics;
    }

    /**
     * Fast Histogram-based background estimation.
     */
    public static BackgroundMetrics calculateBackground(short[][] image, int width, int height, double sigmaMultiplier) {
        BackgroundMetrics metrics = new BackgroundMetrics();
        int[] histogram = new int[65536];
        int totalPixels = width * height;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int val = image[y][x] + 32768;
                histogram[val]++;
            }
        }

        int count = 0;
        int medianValue = 0;
        for (int i = 0; i < histogram.length; i++) {
            count += histogram[i];
            if (count >= totalPixels / 2) {
                medianValue = i;
                break;
            }
        }
        metrics.median = medianValue;

        double sumSqDiff = 0;
        int bgPixelCount = 0;
        int upperLimit = medianValue * 2;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int val = image[y][x] + 32768;
                if (val < upperLimit) {
                    double diff = val - medianValue;
                    sumSqDiff += (diff * diff);
                    bgPixelCount++;
                }
            }
        }

        metrics.sigma = Math.sqrt(sumSqDiff / Math.max(1, bgPixelCount));
        metrics.threshold = metrics.median + (metrics.sigma * sigmaMultiplier);

        return metrics;
    }

    /**
     * Intensity-weighted centroiding for sub-pixel accuracy.
     */
    private static DetectedObject calculateCentroid(List<Pixel> blob, double backgroundMedian) {
        double sumI = 0;
        double sumIX = 0;
        double sumIY = 0;

        for (Pixel p : blob) {
            double intensity = p.value - backgroundMedian;
            if (intensity < 0) intensity = 0;

            sumI += intensity;
            sumIX += p.x * intensity;
            sumIY += p.y * intensity;
        }

        double centroidX = sumI > 0 ? sumIX / sumI : blob.get(0).x;
        double centroidY = sumI > 0 ? sumIY / sumI : blob.get(0).y;

        return new DetectedObject(centroidX, centroidY, sumI, blob.size());
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

        if (obj.elongation > 3.0 && blob.size() > 15) {
            obj.isStreak = true;
            obj.isNoise = false;
        }
        else if (obj.elongation > 1.8 && blob.size() <= 15) {
            obj.isStreak = false;
            obj.isNoise = true;
        }
        else {
            obj.isStreak = false;
            obj.isNoise = false;
        }

        return obj;
    }
}