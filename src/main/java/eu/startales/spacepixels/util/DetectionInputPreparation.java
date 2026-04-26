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
import nom.tam.fits.Header;
import nom.tam.image.compression.hdu.CompressedImageHDU;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Headless preparation utility that normalizes supported FITS/XISF inputs into a directory of
 * uncompressed 16-bit monochrome FITS files that the batch pipeline can consume directly.
 */
public final class DetectionInputPreparation {

    private static final DateTimeFormatter OUTPUT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String OUTPUT_DIRECTORY_PREFIX = "_spacepixels_prepared_mono16_";
    private static final String[] FITS_EXTENSIONS = {"fit", "fits", "fts", "fz"};
    private static final String[] XISF_EXTENSIONS = {"xisf"};

    private DetectionInputPreparation() {
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int percentage, String message);
    }

    public static final class PreparedDirectory {
        private final File originalInputDirectory;
        private final File preparedInputDirectory;
        private final boolean inputWasPrepared;

        private PreparedDirectory(File originalInputDirectory, File preparedInputDirectory, boolean inputWasPrepared) {
            this.originalInputDirectory = originalInputDirectory;
            this.preparedInputDirectory = preparedInputDirectory;
            this.inputWasPrepared = inputWasPrepared;
        }

        public File getOriginalInputDirectory() {
            return originalInputDirectory;
        }

        public File getPreparedInputDirectory() {
            return preparedInputDirectory;
        }

        public boolean isInputWasPrepared() {
            return inputWasPrepared;
        }
    }

    public static PreparedDirectory prepareInputDirectory(File inputDirectory,
                                                          boolean autoPrepare,
                                                          ProgressListener progressListener) throws Exception {
        Objects.requireNonNull(inputDirectory, "inputDirectory");
        if (!inputDirectory.exists() || !inputDirectory.isDirectory()) {
            throw new IOException("Input path is not a directory: " + inputDirectory.getAbsolutePath());
        }

        if (!autoPrepare) {
            return new PreparedDirectory(inputDirectory, inputDirectory, false);
        }

        emitProgress(progressListener, 0, "Inspecting input directory...");

        File workingDirectory = inputDirectory;
        boolean inputWasPrepared = false;

        if (!containsFilesWithExtensions(inputDirectory, FITS_EXTENSIONS)
                && containsFilesWithExtensions(inputDirectory, XISF_EXTENSIONS)) {
            emitProgress(progressListener, 5, "Converting XISF inputs to FITS...");
            workingDirectory = XisfImageConverter.prepareDirectoryForFitsImport(
                    inputDirectory,
                    (percentage, message) -> emitProgress(progressListener, scaleProgress(percentage, 5, 70), message));
            inputWasPrepared = !inputDirectory.equals(workingDirectory);
        }

        File[] fitsFiles = listFilesWithExtensions(workingDirectory, FITS_EXTENSIONS);
        if (fitsFiles.length == 0) {
            throw new IOException("No FITS files in directory: " + workingDirectory.getAbsolutePath());
        }

        emitProgress(progressListener, 72, "Validating whether the FITS inputs are already detection-ready...");
        if (allFilesDetectionReady(fitsFiles)) {
            emitProgress(progressListener, 100, "Input directory is already ready for 16-bit monochrome detection.");
            return new PreparedDirectory(inputDirectory, workingDirectory, inputWasPrepared);
        }

        File preparedOutputDirectory = createPreparedOutputDirectory(workingDirectory);
        for (int i = 0; i < fitsFiles.length; i++) {
            File fitsFile = fitsFiles[i];
            emitProgress(progressListener,
                    scaleProgress(i + 1, fitsFiles.length, 75, 100),
                    "Preparing " + fitsFile.getName() + " for mono 16-bit detection...");
            prepareFitsFile(fitsFile, preparedOutputDirectory);
        }

        emitProgress(progressListener, 100, "Prepared a 16-bit monochrome FITS directory for detection.");
        return new PreparedDirectory(inputDirectory, preparedOutputDirectory, true);
    }

    private static boolean allFilesDetectionReady(File[] fitsFiles) throws Exception {
        for (File fitsFile : fitsFiles) {
            if (!isDetectionReady(fitsFile)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDetectionReady(File fitsFile) throws Exception {
        try (Fits fits = new Fits(fitsFile)) {
            if (isCompressedFits(fits)) {
                return false;
            }

            BasicHDU<?> imageHdu = ImageProcessing.getImageHDU(fits);
            if (imageHdu == null) {
                return false;
            }

            int[] axes = imageHdu.getAxes();
            if (axes == null || axes.length != 2) {
                return false;
            }

            return imageHdu.getHeader().getIntValue("BITPIX", 0) == 16;
        }
    }

    private static void prepareFitsFile(File inputFile, File outputDirectory) throws Exception {
        try (Fits preparedFits = createPreparedFits(inputFile)) {
            File outputFile = new File(outputDirectory, buildPreparedFilename(inputFile));
            Files.deleteIfExists(outputFile.toPath());
            preparedFits.write(outputFile);
        }
    }

    private static Fits createPreparedFits(File inputFile) throws Exception {
        try (Fits originalFits = new Fits(inputFile)) {
            BasicHDU<?> imageHdu = ImageProcessing.getImageHDU(originalFits);
            if (imageHdu == null) {
                throw new IOException("No valid image HDU found in " + inputFile.getName());
            }

            Header originalHeader = imageHdu.getHeader();
            Object kernel = imageHdu.getKernel();

            if (kernel instanceof short[][]) {
                return FitsPixelConverter.createFitsFromData(kernel, originalHeader);
            }
            if (kernel instanceof short[][][]) {
                short[][] monoKernel = FitsPixelConverter.convertColorKernelToMono(kernel);
                return FitsPixelConverter.createFitsFromData(monoKernel, originalHeader);
            }
            if (kernel instanceof float[][] || kernel instanceof int[][]) {
                short[][] monoKernel = FitsPixelConverter.standardizeTo16BitMono(kernel);
                return FitsPixelConverter.createFitsFromData(monoKernel, originalHeader);
            }
            if (kernel instanceof float[][][] || kernel instanceof int[][][]) {
                short[][][] colorKernel = FitsPixelConverter.standardizeTo16BitColor(kernel);
                short[][] monoKernel = FitsPixelConverter.extractLuminance(colorKernel);
                return FitsPixelConverter.createFitsFromData(monoKernel, originalHeader);
            }

            throw new IOException("Unsupported FITS kernel type in " + inputFile.getName() + ": " + kernel.getClass().getName());
        }
    }

    private static boolean isCompressedFits(Fits fits) throws Exception {
        int numHdus = fits.getNumberOfHDUs();
        for (int i = 0; i < numHdus; i++) {
            BasicHDU<?> hdu = fits.getHDU(i);
            if (hdu instanceof CompressedImageHDU || hdu.getHeader().getBooleanValue("ZIMAGE", false)) {
                return true;
            }
        }
        return false;
    }

    private static File createPreparedOutputDirectory(File workingDirectory) throws IOException {
        File preparedOutputDirectory = new File(
                workingDirectory,
                OUTPUT_DIRECTORY_PREFIX + LocalDateTime.now().format(OUTPUT_TIMESTAMP));
        Files.createDirectories(preparedOutputDirectory.toPath());
        return preparedOutputDirectory;
    }

    private static String buildPreparedFilename(File inputFile) {
        String filename = inputFile.getName();
        String lowerCaseFilename = filename.toLowerCase(Locale.ROOT);

        if (lowerCaseFilename.endsWith(".fz")) {
            filename = filename.substring(0, filename.length() - 3);
            lowerCaseFilename = filename.toLowerCase(Locale.ROOT);
        }

        if (lowerCaseFilename.endsWith(".fit") || lowerCaseFilename.endsWith(".fits") || lowerCaseFilename.endsWith(".fts")) {
            int dotIndex = filename.lastIndexOf('.');
            if (dotIndex > 0) {
                filename = filename.substring(0, dotIndex);
            }
        }

        return filename + ".fit";
    }

    private static boolean containsFilesWithExtensions(File directory, String[] extensions) {
        return listFilesWithExtensions(directory, extensions).length > 0;
    }

    private static File[] listFilesWithExtensions(File directory, String[] extensions) {
        File[] files = directory.listFiles((dir, name) -> hasExtension(name, extensions));
        if (files == null) {
            return new File[0];
        }

        List<File> orderedFiles = new ArrayList<>(Arrays.asList(files));
        orderedFiles.sort(Comparator.comparing(File::getAbsolutePath));
        return orderedFiles.toArray(new File[0]);
    }

    private static boolean hasExtension(String filename, String[] extensions) {
        String lowerCaseFilename = filename.toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (lowerCaseFilename.endsWith("." + extension.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static void emitProgress(ProgressListener progressListener, int percentage, String message) {
        if (progressListener != null) {
            progressListener.onProgress(Math.max(0, Math.min(100, percentage)), message);
        }
    }

    private static int scaleProgress(int percentage, int startPercent, int endPercent) {
        int boundedPercent = Math.max(0, Math.min(100, percentage));
        return startPercent + (int) Math.round((boundedPercent / 100.0d) * (endPercent - startPercent));
    }

    private static int scaleProgress(int currentIndex, int total, int startPercent, int endPercent) {
        if (total <= 0) {
            return startPercent;
        }
        int boundedCurrent = Math.max(0, Math.min(total, currentIndex));
        int derivedPercent = (int) Math.round((boundedCurrent * 100.0d) / total);
        return scaleProgress(derivedPercent, startPercent, endPercent);
    }
}
