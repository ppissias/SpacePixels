/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.detection;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import eu.startales.spacepixels.fits.FitsInput;
import eu.startales.spacepixels.fits.FitsPixelConverter;
import eu.startales.spacepixels.fits.FitsPreparation;
import eu.startales.spacepixels.fits.XisfImageConverter;
import eu.startales.spacepixels.fits.ImageScaling;

/**
 * Headless preparation utility that normalizes supported FITS/XISF inputs into a directory of
 * uncompressed 16-bit monochrome FITS files that the batch pipeline can consume directly.
 */
public final class DetectionInputPreparation {

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
        return prepareInputDirectory(inputDirectory, autoPrepare, keepNegativeValues, progressListener, null);
    }

    /** Applies an explicit outlier decision; null rejects extremes before any converted sequence is written. */
    public static PreparedDirectory prepareInputDirectory(File inputDirectory, boolean autoPrepare, boolean keepNegativeValues,
                                                          ProgressListener progressListener, ImageScaling.Decision decision) throws Exception {
        Objects.requireNonNull(inputDirectory, "inputDirectory");
        if (!inputDirectory.exists() || !inputDirectory.isDirectory()) {
            throw new IOException("Input path is not a directory: " + inputDirectory.getAbsolutePath());
        }
        FitsInput.validateDirectoryExtensions(inputDirectory);

        if (!autoPrepare) {
            return new PreparedDirectory(inputDirectory, inputDirectory, false, null);
        }

        emitProgress(progressListener, 0, "Inspecting input directory...");

        File workingDirectory = XisfImageConverter.prepareDirectoryForFitsImport(inputDirectory,
                (percentage, message) -> emitProgress(progressListener, scaleProgress(percentage, 5, 70), message), decision, keepNegativeValues);
        boolean inputWasPrepared = !inputDirectory.equals(workingDirectory);
        File[] fitsFiles = FitsInput.list(workingDirectory);

        emitProgress(progressListener, 72, "Validating whether the FITS inputs are already detection-ready...");
        FitsInput.Input[] inputs = FitsInput.inspect(fitsFiles, null, false);
        if (Arrays.stream(inputs).allMatch(FitsInput.Input::detectionReady)) {
            emitProgress(progressListener, 100, "Input directory is already ready for 16-bit monochrome detection.");
            return new PreparedDirectory(inputDirectory, workingDirectory, inputWasPrepared, inputWasPrepared ? workingDirectory : null);
        }

        ImageScaling.Summary inspection = ImageScaling.inspect(fitsFiles,
                percentage -> emitProgress(progressListener, scaleProgress(percentage, 73, 75), "Inspecting sequence intensity range..."));
        ImageScaling.Plan scaling = ImageScaling.plan(inspection, keepNegativeValues, decision);

        File preparedOutputDirectory = createPreparedOutputDirectory(workingDirectory);
        try {
            for (int fileIndex = 0; fileIndex < fitsFiles.length; fileIndex++) {
                File fitsFile = fitsFiles[fileIndex];
                emitProgress(progressListener,
                        scaleProgress(fileIndex + 1, fitsFiles.length, 75, 100),
                        "Preparing " + fitsFile.getName() + " for mono 16-bit detection...");
                File outputFile = new File(preparedOutputDirectory, FitsPreparation.preparedFilename(fitsFile, ""));
                FitsPreparation.standardizeFile(fitsFile, scaling, outputFile, null);
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
        return eu.startales.spacepixels.util.RunDirectories.create(workingDirectory.toPath(), OUTPUT_DIRECTORY_PREFIX).toFile();
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
