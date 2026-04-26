/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Renders previews and applies stretch algorithms to FITS image kernels.
 */
final class FitsVisualizationRenderer {

    public void stretchFitsImage(Fits fitsImage, int stretchFactor, int iterations, StretchAlgorithm algo)
            throws FitsException, IOException {
        BasicHDU<?> hdu = ImageProcessing.getImageHDU(fitsImage);
        Object kernelData = hdu.getKernel();

        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;
            short[][] stretchedData = (short[][]) stretchImageData(
                    data, stretchFactor, iterations, data[0].length, data.length, algo);
            for (int i = 0; i < data.length; i++) {
                for (int j = 0; j < data[i].length; j++) {
                    data[i][j] = stretchedData[i][j];
                }
            }

        } else if (kernelData instanceof int[][]) {
            int[][] data = (int[][]) kernelData;

        } else if (kernelData instanceof float[][]) {
            float[][] data = (float[][]) kernelData;

        } else if (kernelData instanceof short[][][]) {
            short[][][] data = (short[][][]) kernelData;

            short[][] stretchedRedData = (short[][]) stretchImageData(
                    data[0], stretchFactor, iterations, data[0][0].length, data[0].length, algo);
            short[][] stretchedGreenData = (short[][]) stretchImageData(
                    data[1], stretchFactor, iterations, data[1][0].length, data[1].length, algo);
            short[][] stretchedBlueData = (short[][]) stretchImageData(
                    data[2], stretchFactor, iterations, data[2][0].length, data[2].length, algo);

            for (int i = 0; i < data[0].length; i++) {
                for (int j = 0; j < data[0][i].length; j++) {
                    if (algo.equals(StretchAlgorithm.EXTREME)) {
                        short max = stretchedRedData[i][j];
                        if (max < stretchedGreenData[i][j]) {
                            max = stretchedGreenData[i][j];
                        }
                        if (max < stretchedBlueData[i][j]) {
                            max = stretchedBlueData[i][j];
                        }
                        stretchedRedData[i][j] = max;
                        stretchedGreenData[i][j] = max;
                        stretchedBlueData[i][j] = max;
                    }

                    data[0][i][j] = stretchedRedData[i][j];
                    data[1][i][j] = stretchedGreenData[i][j];
                    data[2][i][j] = stretchedBlueData[i][j];
                }
            }
        } else if (kernelData instanceof int[][][]) {
            int[][][] data = (int[][][]) kernelData;
        } else if (kernelData instanceof float[][][]) {
            float[][][] data = (float[][][]) kernelData;
        } else {
            throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
        }
    }

    public BufferedImage getImagePreview(Object kernelData) throws FitsException {
        BufferedImage ret = new BufferedImage(350, 350, BufferedImage.TYPE_INT_ARGB);

        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;

            int imageHeight = data.length;
            int imageWidth = data[0].length;

            if (imageWidth > 350) {
                imageWidth = 350;
            }
            if (imageHeight > 350) {
                imageHeight = 350;
            }

            for (int i = 0; i < imageHeight; i++) {
                for (int j = 0; j < imageWidth; j++) {
                    int convertedValue = data[i][j] + Short.MAX_VALUE;
                    float intensity = ((float) convertedValue) / (2 * (float) Short.MAX_VALUE);
                    ret.setRGB(j, i, new Color(intensity, intensity, intensity, 1.0f).getRGB());
                }
            }
        } else if (kernelData instanceof int[][]) {
            int[][] data = (int[][]) kernelData;
        } else if (kernelData instanceof float[][]) {
            float[][] data = (float[][]) kernelData;
        } else if (kernelData instanceof short[][][]) {
            short[][][] data = (short[][][]) kernelData;

            int imageHeight = data[0].length;
            int imageWidth = data[0][0].length;

            if (imageWidth > 350) {
                imageWidth = 350;
            }
            if (imageHeight > 350) {
                imageHeight = 350;
            }

            for (int i = 0; i < imageHeight; i++) {
                for (int j = 0; j < imageWidth; j++) {
                    int convertedValueR = data[0][i][j] + Short.MAX_VALUE + 1;
                    float intensityR = ((float) convertedValueR) / (2 * (float) Short.MAX_VALUE);

                    int convertedValueG = data[1][i][j] + Short.MAX_VALUE + 1;
                    float intensityG = ((float) convertedValueG) / (2 * (float) Short.MAX_VALUE);

                    int convertedValueB = data[2][i][j] + Short.MAX_VALUE + 1;
                    float intensityB = ((float) convertedValueB) / (2 * (float) Short.MAX_VALUE);

                    ret.setRGB(j, i, new Color(intensityR, intensityG, intensityB, 1.0f).getRGB());
                }
            }
        } else if (kernelData instanceof int[][][]) {
            int[][][] data = (int[][][]) kernelData;
        } else if (kernelData instanceof float[][][]) {
            float[][][] data = (float[][][]) kernelData;
        } else {
            throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
        }
        return ret;
    }

    public BufferedImage getStretchedImagePreview(
            Object kernelData,
            int stretchFactor,
            int iterations,
            StretchAlgorithm algo) throws FitsException {
        return getStretchedImage(kernelData, 350, 350, stretchFactor, iterations, algo);
    }

    public BufferedImage getStretchedImageFullSize(
            Object kernelData,
            int width,
            int height,
            int stretchFactor,
            int iterations,
            StretchAlgorithm algo) throws FitsException {
        return getStretchedImage(kernelData, width, height, stretchFactor, iterations, algo);
    }

    private BufferedImage getStretchedImage(
            Object kernelData,
            int width,
            int height,
            int stretchFactor,
            int iterations,
            StretchAlgorithm algo) throws FitsException {
        BufferedImage ret = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;

            int imageHeight = data.length;
            int imageWidth = data[0].length;

            if (imageWidth > width) {
                imageWidth = width;
            }
            if (imageHeight > height) {
                imageHeight = height;
            }

            short[][] stretchedData = (short[][]) stretchImageData(
                    data, stretchFactor, iterations, imageWidth, imageHeight, algo);

            for (int i = 0; i < imageHeight; i++) {
                for (int j = 0; j < imageWidth; j++) {
                    int absValue = stretchedData[i][j] + Short.MAX_VALUE + 1;
                    if (absValue > 2 * Short.MAX_VALUE) {
                        absValue = 2 * Short.MAX_VALUE;
                    }
                    float intensity = ((float) absValue) / (2 * (float) Short.MAX_VALUE);
                    ret.setRGB(j, i, new Color(intensity, intensity, intensity, 1.0f).getRGB());
                }
            }
        } else if (kernelData instanceof int[][]) {
            int[][] data = (int[][]) kernelData;
        } else if (kernelData instanceof float[][]) {
            float[][] data = (float[][]) kernelData;
        } else if (kernelData instanceof short[][][]) {
            short[][][] data = (short[][][]) kernelData;

            short[][] stretchedDataRed = (short[][]) stretchImageData(data[0], stretchFactor, iterations, width, height, algo);
            short[][] stretchedDataGreen = (short[][]) stretchImageData(data[1], stretchFactor, iterations, width, height, algo);
            short[][] stretchedDataBlue = (short[][]) stretchImageData(data[2], stretchFactor, iterations, width, height, algo);

            int imageHeight = data[0].length;
            int imageWidth = data[0][0].length;

            if (imageWidth > width) {
                imageWidth = width;
            }
            if (imageHeight > height) {
                imageHeight = height;
            }

            for (int i = 0; i < imageHeight; i++) {
                for (int j = 0; j < imageWidth; j++) {
                    int absValueRed = stretchedDataRed[i][j] + Short.MAX_VALUE + 1;
                    if (absValueRed > 2 * Short.MAX_VALUE) {
                        absValueRed = 2 * Short.MAX_VALUE;
                    }
                    float intensityRed = ((float) absValueRed) / (2 * (float) Short.MAX_VALUE);

                    int absValueGreen = stretchedDataGreen[i][j] + Short.MAX_VALUE + 1;
                    if (absValueGreen > 2 * Short.MAX_VALUE) {
                        absValueGreen = 2 * Short.MAX_VALUE;
                    }
                    float intensityGreen = ((float) absValueGreen) / (2 * (float) Short.MAX_VALUE);

                    int absValueBlue = stretchedDataBlue[i][j] + Short.MAX_VALUE + 1;
                    if (absValueBlue > 2 * Short.MAX_VALUE) {
                        absValueBlue = 2 * Short.MAX_VALUE;
                    }
                    float intensityBlue = ((float) absValueBlue) / (2 * (float) Short.MAX_VALUE);

                    if (algo.equals(StretchAlgorithm.EXTREME)) {
                        float maxValue = absValueRed;
                        if (maxValue < absValueGreen) {
                            maxValue = absValueGreen;
                        }
                        if (maxValue < absValueBlue) {
                            maxValue = absValueBlue;
                        }

                        intensityRed = ((float) maxValue) / (2 * (float) Short.MAX_VALUE);
                        intensityGreen = intensityRed;
                        intensityBlue = intensityRed;
                    }

                    Color targetColor = new Color(intensityRed, intensityGreen, intensityBlue, 1.0f);
                    ret.setRGB(j, i, targetColor.getRGB());
                }
            }
        } else if (kernelData instanceof int[][][]) {
            int[][][] data = (int[][][]) kernelData;
        } else if (kernelData instanceof float[][][]) {
            float[][][] data = (float[][][]) kernelData;
        } else {
            throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
        }
        return ret;
    }

    private Object stretchImageData(
            Object kernelData,
            int intensity,
            int iterations,
            int width,
            int height,
            StretchAlgorithm algo) throws FitsException {
        switch (algo) {
            case ENHANCE_HIGH:
                return stretchImageEnhanceHigh(kernelData, intensity, iterations, width, height);
            case ENHANCE_LOW:
                return stretchImageEnhanceLow(kernelData, intensity, iterations, width, height);
            case EXTREME:
                return stretchImageEnhanceExtreme(kernelData, intensity, iterations, width, height);
            case ASINH:
                return stretchImageAsinh(kernelData, intensity, iterations, width, height);
            default:
                return stretchImageEnhanceLow(kernelData, intensity, iterations, width, height);
        }
    }

    private Object stretchImageEnhanceHigh(
            Object kernelData,
            int intensity,
            int iterations,
            int width,
            int height) throws FitsException {
        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;
            short[][] returnData = new short[height][width];

            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    returnData[i][j] = data[i][j];
                }
            }

            for (int iteration = 0; iteration < iterations; iteration++) {
                short minimumValue = Short.MAX_VALUE;
                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        int absValue = returnData[i][j] - Short.MIN_VALUE;
                        float newValue = (float) absValue * (1 + ((float) intensity / 100));
                        newValue = newValue - Short.MAX_VALUE;

                        if (newValue > Short.MAX_VALUE) {
                            returnData[i][j] = Short.MAX_VALUE;
                        } else {
                            returnData[i][j] = (short) newValue;
                        }

                        if (minimumValue > returnData[i][j]) {
                            minimumValue = returnData[i][j];
                        }
                    }
                }

                int minimumValueDistanceFromZero = minimumValue - Short.MIN_VALUE;
                if (minimumValueDistanceFromZero > 2 * Short.MAX_VALUE) {
                    minimumValueDistanceFromZero = 2 * Short.MAX_VALUE;
                }

                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        returnData[i][j] = (short) (returnData[i][j] - minimumValueDistanceFromZero);
                    }
                }
            }
            return returnData;
        } else if (kernelData instanceof int[][]) {
            int[][] data = (int[][]) kernelData;
            return null;
        } else if (kernelData instanceof float[][]) {
            float[][] data = (float[][]) kernelData;
            return null;
        } else {
            throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
        }
    }

    private Object stretchImageEnhanceLow(
            Object kernelData,
            int intensity,
            int iterations,
            int width,
            int height) throws FitsException {
        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;
            short[][] returnData = new short[height][width];

            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    returnData[i][j] = data[i][j];
                }
            }

            for (int iteration = 0; iteration < iterations; iteration++) {
                short minimumValue = Short.MAX_VALUE;
                short maximumValue = Short.MIN_VALUE;

                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        int absValue = returnData[i][j] - Short.MIN_VALUE;
                        float scale = 1 - (((float) absValue) / (2 * (float) Short.MAX_VALUE));
                        float newValue = (float) absValue * (1 + (((float) intensity / 100) * scale));
                        newValue = newValue - Short.MAX_VALUE;

                        if (newValue > Short.MAX_VALUE) {
                            returnData[i][j] = Short.MAX_VALUE;
                        } else {
                            returnData[i][j] = (short) newValue;
                        }

                        if (minimumValue > returnData[i][j]) {
                            minimumValue = returnData[i][j];
                        }
                        if (maximumValue < returnData[i][j]) {
                            maximumValue = returnData[i][j];
                        }
                    }
                }

                int minimumValueDistanceFromZero = minimumValue - Short.MIN_VALUE;
                if (minimumValueDistanceFromZero > 2 * Short.MAX_VALUE) {
                    minimumValueDistanceFromZero = 2 * Short.MAX_VALUE;
                }
                int maximumValueDistanceFromMax = Short.MAX_VALUE - maximumValue;
                if (maximumValueDistanceFromMax > 2 * Short.MAX_VALUE) {
                    maximumValueDistanceFromMax = 2 * Short.MAX_VALUE;
                }

                float stretchCoefficient = 1 + (((float) maximumValueDistanceFromMax) / (2 * (float) Short.MAX_VALUE));

                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        int absValue = returnData[i][j] - Short.MIN_VALUE - minimumValueDistanceFromZero;
                        float newValue = ((float) absValue) * stretchCoefficient;
                        newValue = newValue - Short.MAX_VALUE;

                        if (newValue > Short.MAX_VALUE) {
                            returnData[i][j] = Short.MAX_VALUE;
                        } else {
                            returnData[i][j] = (short) newValue;
                        }
                    }
                }
            }
            return returnData;
        } else if (kernelData instanceof int[][]) {
            int[][] data = (int[][]) kernelData;
            return null;
        } else if (kernelData instanceof float[][]) {
            float[][] data = (float[][]) kernelData;
            return null;
        } else {
            throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
        }
    }

    private Object stretchImageEnhanceExtreme(
            Object kernelData,
            int threshold,
            int intensity,
            int width,
            int height) throws FitsException {
        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;
            short[][] returnData = new short[height][width];

            long allPixelSumValue = 0;
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    allPixelSumValue += (data[i][j] - Short.MIN_VALUE);
                }
            }

            float averageNoiseLevel = ((float) allPixelSumValue) / ((float) width * height);

            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    returnData[i][j] = data[i][j];
                    int absValue = returnData[i][j] - Short.MIN_VALUE;

                    if (absValue >= averageNoiseLevel + 10 * threshold) {
                        float newValue = (((float) intensity) / 20) * (2 * (float) Short.MAX_VALUE);
                        newValue = newValue - Short.MAX_VALUE;

                        if (newValue > Short.MAX_VALUE) {
                            returnData[i][j] = Short.MAX_VALUE;
                        } else {
                            returnData[i][j] = (short) newValue;
                        }
                    }
                }
            }
            return returnData;
        } else if (kernelData instanceof int[][]) {
            int[][] data = (int[][]) kernelData;
            return null;
        } else if (kernelData instanceof float[][]) {
            float[][] data = (float[][]) kernelData;
            return null;
        } else {
            throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
        }
    }

    private Object stretchImageAsinh(
            Object kernelData,
            int blackPointPercent,
            int stretchStrength,
            int width,
            int height) throws FitsException {
        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;
            short[][] returnData = new short[height][width];

            int sourceHeight = data.length;
            int sourceWidth = data[0].length;
            int[] histogram = new int[(2 * Short.MAX_VALUE) + 2];

            for (int i = 0; i < sourceHeight; i++) {
                for (int j = 0; j < sourceWidth; j++) {
                    histogram[data[i][j] - Short.MIN_VALUE]++;
                }
            }

            long totalPixels = (long) sourceHeight * sourceWidth;
            int blackPointValue = percentileFromHistogram(histogram, totalPixels, blackPointPercent / 100.0);
            int whitePointValue = percentileFromHistogram(histogram, totalPixels, 0.999);

            if (whitePointValue <= blackPointValue) {
                whitePointValue = blackPointValue + 1;
            }

            double usableRange = whitePointValue - blackPointValue;
            double stretchScale = Math.max(1.0, stretchStrength);
            double normalization = asinh(stretchScale);

            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    double absValue = data[i][j] - Short.MIN_VALUE;
                    double normalizedValue = (absValue - blackPointValue) / usableRange;
                    if (normalizedValue < 0.0) {
                        normalizedValue = 0.0;
                    } else if (normalizedValue > 1.0) {
                        normalizedValue = 1.0;
                    }

                    double stretchedValue = asinh(normalizedValue * stretchScale) / normalization;
                    int unsignedValue = (int) Math.round(stretchedValue * ((2.0 * Short.MAX_VALUE) + 1.0));
                    if (unsignedValue < 0) {
                        unsignedValue = 0;
                    } else if (unsignedValue > (2 * Short.MAX_VALUE) + 1) {
                        unsignedValue = (2 * Short.MAX_VALUE) + 1;
                    }

                    returnData[i][j] = (short) (unsignedValue + Short.MIN_VALUE);
                }
            }
            return returnData;
        } else if (kernelData instanceof int[][]) {
            int[][] data = (int[][]) kernelData;
            return null;
        } else if (kernelData instanceof float[][]) {
            float[][] data = (float[][]) kernelData;
            return null;
        } else {
            throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
        }
    }

    private static double asinh(double value) {
        return Math.log(value + Math.sqrt((value * value) + 1.0));
    }

    private static int percentileFromHistogram(int[] histogram, long totalPixels, double percentile) {
        if (totalPixels <= 0) {
            return 0;
        }

        long targetCount = Math.max(0L, Math.min(totalPixels - 1, (long) Math.floor((totalPixels - 1) * percentile)));
        long runningCount = 0;

        for (int value = 0; value < histogram.length; value++) {
            runningCount += histogram[value];
            if (runningCount > targetCount) {
                return value;
            }
        }

        return histogram.length - 1;
    }
}
