/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.fits;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Renders previews and applies stretch algorithms to FITS image kernels. It keeps no state, so one instance can serve
 * several threads.
 */
public final class FitsVisualizationRenderer {

    /** Stretches the image data of an opened FITS file in place, as Batch Stretch does. */
    public void stretchFitsImage(Fits fitsImage, int stretchFactor, int iterations, StretchAlgorithm algo)
            throws FitsException, IOException {
        BasicHDU<?> hdu = FitsInput.getImageHDU(fitsImage);
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

    /** A quick-look picture of raw FITS kernel data, at most 350 by 350 pixels, without a stretch. */
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

    /** A 350 by 350 stretched picture of FITS kernel data. */
    public BufferedImage getStretchedImagePreview(
            Object kernelData,
            int stretchFactor,
            int iterations,
            StretchAlgorithm algo) throws FitsException {
        return getStretchedImage(kernelData, 350, 350, stretchFactor, iterations, algo);
    }

    /** A stretched picture of FITS kernel data at the requested size. */
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
        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;
            short[] stretched = DisplayStretch.stretchedValues(
                    DisplayStretch.histogram(data), algo, intensity, iterations);
            short[][] returnData = new short[height][width];
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    returnData[i][j] = stretched[data[i][j] - Short.MIN_VALUE];
                }
            }
            return returnData;
        } else if (kernelData instanceof int[][] || kernelData instanceof float[][]) {
            return null;
        } else {
            throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
        }
    }
}
