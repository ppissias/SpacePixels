package spv.util;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

public class ImageDisplayUtils {

    public static BufferedImage createDisplayImage(short[][] imageData) {
        // FITS data is row-major: the first dimension is Height (Y), the second is Width (X)
        int height = imageData.length;
        int width = imageData[0].length;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = image.getRaster();

        // 1. Find Min, Max, and the Total Sum (to find the background average)
        long sum = 0;
        int min = 65535;
        int max = 0;

        // Iterate over Y first, then X for row-major arrays
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Access data correctly as [y][x]
                int val = imageData[y][x] & 0xFFFF;
                if (val < min) min = val;
                if (val > max) max = val;
                sum += val;
            }
        }

        // Calculate the average pixel value (this accurately represents the sky background)
        double mean = (double) sum / (width * height);

        // --- THE CONTRAST TUNING ---

        // BLACK POINT: Set the floor near the mean to crush the gray background.
        double blackPoint = min + ((mean - min) * 0.9);

        // WHITE POINT: Lower the ceiling so fainter stars become pure white faster.
        double whitePoint = min + ((max - min) * 0.8);

        double range = whitePoint - blackPoint;
        if (range <= 0) range = 1; // Safety catch

        // 2. Map the data using the new contrast bounds
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Access data correctly as [y][x]
                int val = imageData[y][x] & 0xFFFF;

                // Subtract the black point. If it's darker than the black point, clamp it to 0.
                double adjustedVal = val - blackPoint;
                if (adjustedVal < 0) adjustedVal = 0;

                // If it's brighter than the white point, clamp it to the max range.
                if (adjustedVal > range) adjustedVal = range;

                // Normalize between 0.0 and 1.0 based on our new tighter range
                double normalized = adjustedVal / range;

                // Apply the Square Root stretch to boost the midtones
                double stretched = Math.sqrt(normalized);

                // Scale up to standard 8-bit visual bounds (0 to 255)
                int displayValue = (int) (stretched * 255.0);

                // Final safety clamp
                if (displayValue > 255) displayValue = 255;
                if (displayValue < 0) displayValue = 0;

                raster.setSample(x, y, 0, displayValue);
            }
        }

        return image;
    }
}