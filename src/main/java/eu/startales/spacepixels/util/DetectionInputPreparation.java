/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Headless preparation utility that normalizes supported FITS/XISF inputs into a directory of
 * uncompressed 16-bit monochrome FITS files that the batch pipeline can consume directly.
 */
public final class DetectionInputPreparation {

    private static final DateTimeFormatter OUTPUT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String OUTPUT_DIRECTORY_PREFIX = "_spacepixels_prepared_mono16_";

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
        private final File createdDirectory;

        private PreparedDirectory(File originalInputDirectory, File preparedInputDirectory, boolean inputWasPrepared,
                                  File createdDirectory) {
            this.originalInputDirectory = originalInputDirectory;
            this.preparedInputDirectory = preparedInputDirectory;
            this.inputWasPrepared = inputWasPrepared;
            this.createdDirectory = createdDirectory;
        }

        /**
         * The converted copy this preparation created, or null when it created none: ready input, or input whose
         * only conversion went to the reused XISF folder. A caller may remove it when nothing refers to it.
         */
        public File getCreatedDirectory() {
            return createdDirectory;
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
        return prepareInputDirectory(inputDirectory, autoPrepare, true, progressListener);
    }

    /**
     * @param keepNegativeValues when converted pixels fall below zero, add the same offset to every frame so all
     *                           values are kept (true), or set them to 0 (false)
     */
    public static PreparedDirectory prepareInputDirectory(File inputDirectory,
                                                          boolean autoPrepare,
                                                          boolean keepNegativeValues,
                                                          ProgressListener progressListener) throws Exception {
        Objects.requireNonNull(inputDirectory, "inputDirectory");
        if (!inputDirectory.exists() || !inputDirectory.isDirectory()) {
            throw new IOException("Input path is not a directory: " + inputDirectory.getAbsolutePath());
        }
        FitsSequenceValidator.validateDirectoryExtensions(inputDirectory);

        if (!autoPrepare) {
            return new PreparedDirectory(inputDirectory, inputDirectory, false, null);
        }

        emitProgress(progressListener, 0, "Inspecting input directory...");

        File workingDirectory = XisfImageConverter.prepareDirectoryForFitsImport(inputDirectory,
                (percentage, message) -> emitProgress(progressListener, scaleProgress(percentage, 5, 70), message));
        boolean inputWasPrepared = !inputDirectory.equals(workingDirectory);
        File[] fitsFiles = FitsFiles.list(workingDirectory);

        emitProgress(progressListener, 72, "Validating whether the FITS inputs are already detection-ready...");
        FitsPreparationService.Input[] inputs = FitsPreparationService.inspect(fitsFiles, null, false);
        if (Arrays.stream(inputs).allMatch(FitsPreparationService.Input::detectionReady)) {
            emitProgress(progressListener, 100, "Input directory is already ready for 16-bit monochrome detection.");
            return new PreparedDirectory(inputDirectory, workingDirectory, inputWasPrepared, null);
        }

        int addedOffset = 0;
        if (keepNegativeValues) {
            emitProgress(progressListener, 73, "Checking for pixel values below zero...");
            FitsPixelConverter.NegativeValueScan scan = FitsPixelConverter.scanNegativeValues(fitsFiles,
                    percentage -> emitProgress(progressListener, scaleProgress(percentage, 73, 75), "Checking for pixel values below zero..."));
            addedOffset = scan.offsetToKeepNegativeValues();
            if (addedOffset > 0) {
                emitProgress(progressListener, 75, String.format(Locale.ROOT,
                        "%d pixels are below zero (lowest %.0f ADU): adding %d ADU to every pixel of every frame to keep them.",
                        scan.negativePixels(), scan.lowest(), addedOffset));
            }
        }

        File preparedOutputDirectory = createPreparedOutputDirectory(workingDirectory);
        try {
            for (int fileIndex = 0; fileIndex < fitsFiles.length; fileIndex++) {
                File fitsFile = fitsFiles[fileIndex];
                emitProgress(progressListener,
                        scaleProgress(fileIndex + 1, fitsFiles.length, 75, 100),
                        "Preparing " + fitsFile.getName() + " for mono 16-bit detection...");
                File outputFile = new File(preparedOutputDirectory, FitsPreparationService.preparedFilename(fitsFile, ""));
                FitsPreparationService.standardizeFile(fitsFile, addedOffset, outputFile, null);
            }
        } catch (Exception failure) {
            // A stopped or failed conversion leaves a partial copy that nothing refers to.
            OutputFolders.deleteUnfinished(preparedOutputDirectory);
            throw failure;
        }

        emitProgress(progressListener, 100, "Prepared a 16-bit monochrome FITS directory for detection.");
        return new PreparedDirectory(inputDirectory, preparedOutputDirectory, true, preparedOutputDirectory);
    }

    private static File createPreparedOutputDirectory(File workingDirectory) throws IOException {
        File preparedOutputDirectory = new File(
                workingDirectory,
                OUTPUT_DIRECTORY_PREFIX + LocalDateTime.now().format(OUTPUT_TIMESTAMP));
        Files.createDirectories(preparedOutputDirectory.toPath());
        return preparedOutputDirectory;
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
