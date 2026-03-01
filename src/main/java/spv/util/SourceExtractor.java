package spv.util;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class SourceExtractor {

    // Helper classes to hold our data
    public static class DetectedObject {
        public double x, y;
        public double totalFlux;
        public int pixelCount;

        // --- New FWHM Metric ---
        public double fwhm;

        // --- New Shape Fields ---
        public double elongation;
        public double angle; // The angle of the streak in radians
        public boolean isStreak;

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
        int width = image.length;
        int height = image[0].length;

        // 1. Calculate Background
        BackgroundMetrics bg = calculateBackground(image, width, height, sigmaMultiplier);
        System.out.println("Background Median: " + bg.median + ", Sigma: " + bg.sigma + ", Threshold: " + bg.threshold);

        // 2. Setup for BFS Blob Detection
        List<DetectedObject> detectedObjects = new ArrayList<>();
        boolean[][] visited = new boolean[width][height];

        // 8-way directional arrays for finding neighbors
        int[] dx = {-1, -1, -1, 0, 0, 1, 1, 1};
        int[] dy = {-1, 0, 1, -1, 1, -1, 0, 1};

        // 3. Scan the image
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int pixelValue = image[x][y] & 0xFFFF; // Convert Java signed short to unsigned 16-bit

                if (pixelValue > bg.threshold && !visited[x][y]) {
                    // Start a new BFS for a new Blob
                    List<Pixel> currentBlob = new ArrayList<>();
                    Queue<Pixel> queue = new LinkedList<>();

                    Pixel startPixel = new Pixel(x, y, pixelValue);
                    queue.add(startPixel);
                    visited[x][y] = true;

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
                                if (!visited[nx][ny]) {
                                    int nValue = image[nx][ny] & 0xFFFF;
                                    if (nValue > bg.threshold) {
                                        visited[nx][ny] = true;
                                        queue.add(new Pixel(nx, ny, nValue));
                                    }
                                }
                            }
                        }
                    }

                    // 4. Centroiding and Filtering
                    if (currentBlob.size() >= minPixels) {

                        // We use the new advanced method instead of just calculateCentroid
                        DetectedObject obj = analyzeShape(currentBlob, bg.median);

                        // Optional: You could even log or handle streaks immediately here
                        if (obj.isStreak) {
                            //System.out.println("Streak detected at X: " + obj.x + ", Y: " + obj.y + " with angle: " + obj.angle);
                        }

                        detectedObjects.add(obj);
                    }
                }
            }
        }
        return detectedObjects;
    }

    /**
     * Fast Histogram-based background estimation.
     */
    public static BackgroundMetrics calculateBackground(short[][] image, int width, int height, double sigmaMultiplier) {
        BackgroundMetrics metrics = new BackgroundMetrics();
        int[] histogram = new int[65536]; // For 16-bit unsigned data
        int totalPixels = width * height;

        // Build Histogram
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int val = image[x][y] & 0xFFFF;
                histogram[val]++;
            }
        }

        // Find Median
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

        // Calculate Standard Deviation (ignoring bright stars to avoid skewing)
        // We only look at pixels reasonably close to the median (e.g., within 2x median)
        double sumSqDiff = 0;
        int bgPixelCount = 0;
        int upperLimit = medianValue * 2; // Rough cutoff to exclude stars from noise math

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int val = image[x][y] & 0xFFFF;
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
            // Only use the light from the object, subtract the background sky
            double intensity = p.value - backgroundMedian;
            if (intensity < 0) intensity = 0; // Safety catch

            sumI += intensity;
            sumIX += p.x * intensity;
            sumIY += p.y * intensity;
        }

        double centroidX = sumI > 0 ? sumIX / sumI : blob.get(0).x;
        double centroidY = sumI > 0 ? sumIY / sumI : blob.get(0).y;

        return new DetectedObject(centroidX, centroidY, sumI, blob.size());
    }


    /**
     * Calculates centroid, builds the covariance matrix using Image Moments,
     * and determines if the blob is a streak.
     */
    private static DetectedObject analyzeShape(List<Pixel> blob, double backgroundMedian) {
        double m00 = 0; // Sum of intensities (Area)
        double m10 = 0; // Sum of X*I
        double m01 = 0; // Sum of Y*I

        // 1. First pass: Find the Centroid (m10/m00 and m01/m00)
        for (Pixel p : blob) {
            double intensity = p.value - backgroundMedian;
            if (intensity <= 0) intensity = 0.001; // Avoid division by zero

            m00 += intensity;
            m10 += p.x * intensity;
            m01 += p.y * intensity;
        }

        double centroidX = m10 / m00;
        double centroidY = m01 / m00;

        // 2. Second pass: Calculate Second-Order Central Moments
        double mu20 = 0;
        double mu02 = 0;
        double mu11 = 0;

        for (Pixel p : blob) {
            double intensity = p.value - backgroundMedian;
            if (intensity <= 0) intensity = 0.001;

            double dx = p.x - centroidX;
            double dy = p.y - centroidY;

            mu20 += (dx * dx) * intensity;
            mu02 += (dy * dy) * intensity;
            mu11 += (dx * dy) * intensity;
        }

        // Normalize the central moments by total intensity
        mu20 /= m00;
        mu02 /= m00;
        mu11 /= m00;



        // 3. Calculate Eigenvalues (Semi-major and Semi-minor axes squared)
        double diff = mu20 - mu02;
        double root = Math.sqrt((diff * diff) + 4 * (mu11 * mu11));

        double aSquared = (mu20 + mu02 + root) / 2.0;
        double bSquared = (mu20 + mu02 - root) / 2.0;

        // Prevent NaN errors if blob is perfectly 1 pixel wide
        double a = Math.sqrt(Math.max(0, aSquared));
        double b = Math.sqrt(Math.max(0, bSquared));

        // 4. Calculate Elongation and Angle
        // If b is extremely small, it's highly elongated.
        double elongation = (b > 0.1) ? (a / b) : 10.0;

        // Calculate the angle of the streak (-PI/2 to PI/2)
        double angle = 0.5 * Math.atan2(2 * mu11, mu20 - mu02);

        // 5. Create Object and Flag it
        DetectedObject obj = new DetectedObject(centroidX, centroidY, m00, blob.size());
        obj.elongation = elongation;
        obj.angle = angle;

        // Define our threshold: If it's more than twice as long as it is wide, it's a streak.
        obj.isStreak = (elongation > 2.5);

        // Calculate standard deviation of the Gaussian profile
        double sigmaAvg = Math.sqrt((mu20 + mu02) / 2.0);
        obj.fwhm = 2.355 * sigmaAvg; // Standard conversion to FWHM

        return obj;
    }



}