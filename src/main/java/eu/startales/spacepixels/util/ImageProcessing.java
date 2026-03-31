/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util;

import io.github.ppissias.jplatesolve.astap.ASTAPInterface;
import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNet;
import io.github.ppissias.jplatesolve.PlateSolveResult;
import io.github.ppissias.jplatesolve.astrometrydotnet.SubmitFileRequest;

import nom.tam.fits.*;
import nom.tam.util.Cursor;
import nom.tam.image.compression.hdu.CompressedImageHDU;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.SpacePixelsAppConfigIO;
import eu.startales.spacepixels.gui.ApplicationWindow;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import io.github.ppissias.jtransient.core.TrackLinker;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * High-level FITS processing facade for SpacePixels.
 *
 * <p>This class owns the end-to-end workflow around FITS import validation, image conversion,
 * preview/stretch generation, plate-solving persistence, and handoff to the JTransient detection
 * engine. It also applies small SpacePixels-specific safeguards around engine configuration before
 * running the pipeline.</p>
 */
public class ImageProcessing {

    @FunctionalInterface
    public interface DetectionSafetyPrompt {
        boolean shouldProceed(DetectionSummary summary);
    }

    /**
     * Lightweight detection-count snapshot used by UI safety prompts before heavy report export.
     */
    public static final class DetectionSummary {
        public final int totalDetections;
        public final int singleStreaks;
        public final int streakTracks;
        public final int movingTargets;
        public final int anomalies;
        public final int suspectedThresholdStreakTracks;
        public final int slowMoverCandidates;
        public final int maximumStackTransientStreaks;
        public final int potentialSlowMovers;

        private DetectionSummary(int totalDetections,
                                 int singleStreaks,
                                 int streakTracks,
                                 int movingTargets,
                                 int anomalies,
                                 int suspectedThresholdStreakTracks,
                                 int slowMoverCandidates,
                                 int maximumStackTransientStreaks) {
            this.totalDetections = totalDetections;
            this.singleStreaks = singleStreaks;
            this.streakTracks = streakTracks;
            this.movingTargets = movingTargets;
            this.anomalies = anomalies;
            this.suspectedThresholdStreakTracks = suspectedThresholdStreakTracks;
            this.slowMoverCandidates = slowMoverCandidates;
            this.maximumStackTransientStreaks = maximumStackTransientStreaks;
            this.potentialSlowMovers = slowMoverCandidates + maximumStackTransientStreaks;
        }
    }

    /**
     * Clones the user config and applies runtime-only adjustments derived from the actual frame
     * count of the current run.
     *
     * <p>The current adjustment keeps the slow-mover middle-fraction below the effective maximum
     * order statistic so small runs do not accidentally collapse into a pure maximum stack.</p>
     */
    static DetectionConfig createEffectiveDetectionConfig(DetectionConfig baseConfig, int frameCount) {
        if (baseConfig == null) {
            return null;
        }

        DetectionConfig effectiveConfig = baseConfig.clone();
        if (!effectiveConfig.enableSlowMoverDetection) {
            return effectiveConfig;
        }

        double adjustedFraction = clampSlowMoverStackMiddleFraction(effectiveConfig.slowMoverStackMiddleFraction, frameCount);
        if (Double.compare(adjustedFraction, effectiveConfig.slowMoverStackMiddleFraction) != 0) {
            System.out.printf(
                    Locale.US,
                    "Adjusting slowMoverStackMiddleFraction from %.4f to %.4f for %d frames so the slow-mover stack stays one frame below the maximum stack.%n",
                    effectiveConfig.slowMoverStackMiddleFraction,
                    adjustedFraction,
                    frameCount);
            effectiveConfig.slowMoverStackMiddleFraction = adjustedFraction;
        }

        return effectiveConfig;
    }

    /**
     * Lowers the requested slow-mover stack fraction only when the engine would otherwise pick the
     * same order statistic as the maximum stack for the given number of frames.
     */
    static double clampSlowMoverStackMiddleFraction(double requestedFraction, int frameCount) {
        double boundedFraction = Math.max(0.0, Math.min(1.0, requestedFraction));
        if (frameCount <= 1) {
            return boundedFraction;
        }

        int maximumAllowedIndex = frameCount - 2;
        if (computeSlowMoverStackOrderIndex(frameCount, boundedFraction) <= maximumAllowedIndex) {
            return boundedFraction;
        }

        int requestedRoundedWindow = (int) Math.round(frameCount * boundedFraction);
        int safeRoundedWindow = requestedRoundedWindow;

        // Match the engine's rounded-window bucketing, but never let the selected sample hit the pure maximum.
        while (safeRoundedWindow > 0
                && computeSlowMoverStackOrderIndex(frameCount, safeRoundedWindow / (double) frameCount) > maximumAllowedIndex) {
            safeRoundedWindow--;
        }

        return safeRoundedWindow / (double) frameCount;
    }

    /**
     * Mirrors the JTransient slow-mover stack bucket math so SpacePixels can reason about the
     * selected per-pixel order statistic before invoking the engine.
     */
    static int computeSlowMoverStackOrderIndex(int frameCount, double fraction) {
        if (frameCount <= 0) {
            return -1;
        }

        double boundedFraction = Math.max(0.0, Math.min(1.0, fraction));
        int roundedWindow = (int) Math.round(frameCount * boundedFraction);
        int centerIndex = (frameCount - 1) / 2;
        return Math.min(frameCount - 1, centerIndex + (roundedWindow / 2));
    }

    /**
     * Internal metadata bundle used during parallel FITS validation/loading in headless mode.
     */
    private static class FitsMetadataLoadResult {
        private final FitsFileInformation fileInfo;
        private final int width;
        private final int height;
        private final int bitpix;

        private FitsMetadataLoadResult(FitsFileInformation fileInfo, int width, int height, int bitpix) {
            this.fileInfo = fileInfo;
            this.width = width;
            this.height = height;
            this.bitpix = bitpix;
        }
    }

    // --- NEW: Custom Exception for Automatic Redirection ---
    public static class RedirectImportException extends Exception {
        private final File newDirectory;

        /**
         * Carries the replacement directory that the caller should load instead of the current one.
         */
        public RedirectImportException(File newDirectory) {
            super("Redirecting import to new directory");
            this.newDirectory = newDirectory;
        }

        /**
         * Returns the directory that should replace the original import target.
         */
        public File getNewDirectory() {
            return newDirectory;
        }
    }

    private final File alignedFitsFolderFullPath;

    // --- NEW: Multi-threading Executor! ---
    // Uses a cached pool to dynamically spin up threads based on available CPU cores
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final AstrometryDotNet astrometryNetInterface = new AstrometryDotNet();
    private final AppConfig appConfig;
    private final File appConfigFile;

    private FitsFileInformation[] cachedFileInfo;

    /**
     * Creates a processing facade bound to a specific aligned FITS directory.
     */
    public static synchronized ImageProcessing getInstance(File alignedFitsFolderFullPath) throws IOException, FitsException {
        return new ImageProcessing(alignedFitsFolderFullPath);
    }

    /**
     * Initializes the processor and loads persisted app-level preferences if they exist.
     */
    private ImageProcessing(File alignedFitsFolderFullPath) throws IOException, FitsException {
        this.alignedFitsFolderFullPath = alignedFitsFolderFullPath;

        String userhome = System.getProperty("user.home");
        if (userhome == null) userhome = "";
        this.appConfigFile = new File(userhome, SpacePixelsAppConfigIO.DEFAULT_FILENAME);

        AppConfig loadedConfig = null;
        if (this.appConfigFile.exists()) {
            try {
                loadedConfig = SpacePixelsAppConfigIO.load(this.appConfigFile);
            } catch (Exception e) {
                ApplicationWindow.logger.warning("Failed to load JSON app config: " + e.getMessage());
            }
        }
        this.appConfig = (loadedConfig != null) ? loadedConfig : new AppConfig();
    }

    // =========================================================================
    // HDU HELPER METHODS (Immune to empty primary HDUs and Compression)
    // =========================================================================

    /**
     * Iterates through the FITS structure to find the actual Image HDU.
     * Safely decompresses .fz files and skips empty primary headers.
     */
    public static BasicHDU<?> getImageHDU(Fits fits) throws FitsException, IOException {
        BasicHDU<?> hdu;
        BasicHDU<?> fallback = null;
        
        // 1. If the Fits object already read HDUs into memory, use them directly
        int numHdus = fits.getNumberOfHDUs();
        for (int i = 0; i < numHdus; i++) {
            hdu = fits.getHDU(i);
            if (fallback == null) fallback = hdu;
            
            if (hdu instanceof CompressedImageHDU) {
                return ((CompressedImageHDU) hdu).asImageHDU();
            }
            int[] axes = hdu.getAxes();
            if (axes != null && axes.length >= 2) {
                boolean hasData = true;
                for (int axis : axes) if (axis <= 0) hasData = false;
                if (hasData) return hdu;
            }
        }

        // 2. Otherwise, safely stream through the file to find the target HDU
        while ((hdu = fits.readHDU()) != null) {
            if (fallback == null) fallback = hdu; // Store the dummy Primary HDU just in case

            if (hdu instanceof CompressedImageHDU) {
                return ((CompressedImageHDU) hdu).asImageHDU();
            }
            
            int[] axes = hdu.getAxes();
            if (axes != null && axes.length >= 2) {
                boolean hasData = true;
                for (int axis : axes) {
                    if (axis <= 0) hasData = false;
                }
                if (hasData) return hdu;
            }
        }
        return fallback;
    }

    /**
     * Detects whether a FITS file is compressed, either via a compressed HDU or `ZIMAGE` header.
     */
    private boolean isCompressedFits(Fits fits) throws FitsException, IOException {
        int numHdus = fits.getNumberOfHDUs();
        for (int i = 0; i < numHdus; i++) {
            if (fits.getHDU(i) instanceof CompressedImageHDU || fits.getHDU(i).getHeader().getBooleanValue("ZIMAGE", false)) {
                return true;
            }
        }
        
        BasicHDU<?> hdu;
        while ((hdu = fits.readHDU()) != null) {
            if (hdu instanceof CompressedImageHDU || hdu.getHeader().getBooleanValue("ZIMAGE", false)) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // NEW BATCH PROCESSING METHODS (Called by UI)
    // =========================================================================

    /**
     * Converts all images in the folder to monochrome.
     * If stretch is true, it also stretches the newly created monochrome images.
     */
    public void batchConvertToMono(boolean stretch, int stretchFactor, int iterations, StretchAlgorithm algo, TransientEngineProgressListener progressListener) throws IOException, FitsException {
        File[] fitsFileInformation = getFitsFilesDetails();

        int total = fitsFileInformation.length;
        for (int i = 0; i < total; i++) {
            File fileInfo = fitsFileInformation[i];
            if (progressListener != null) {
                int percent = (int) (((float) i / total) * 100);
                progressListener.onProgressUpdate(percent, "Converting " + fileInfo.getName() + "...");
            }

            Fits originalFits = new Fits(fileInfo);
            BasicHDU<?> imageHDU = getImageHDU(originalFits);

            // Check if it's already mono to save time
            int naxis = imageHDU.getHeader().getIntValue("NAXIS");
            if (naxis == 3) { // It's a color image
                ApplicationWindow.logger.info("Converting color image to mono: " + fileInfo.getName());
                Fits monochromeFits = convertToMono(originalFits);

                // Save standard mono
                String monoFilename = addDirectory(fileInfo, "_mono");
                writeFitsWithSuffix(monochromeFits, monoFilename, "_mono");

                // If stretch is enabled, stretch the mono version and save it
                if (stretch) {
                    ApplicationWindow.logger.info("Stretching newly converted mono image: " + fileInfo.getName());
                    stretchFITSImage(monochromeFits, stretchFactor, iterations, algo);
                    String monoStretchFilename = addDirectory(fileInfo, "_mono_stretched");
                    writeFitsWithSuffix(monochromeFits, monoStretchFilename, "_mono_stretch");
                }
            } else {
                ApplicationWindow.logger.info("Skipping mono conversion (already mono): " + fileInfo.getName());
            }
            originalFits.close();
        }
    }

    /**
     * Stretches all images in the folder, regardless of whether they are color or mono.
     */
    public void batchStretch(int stretchFactor, int iterations, StretchAlgorithm algo, TransientEngineProgressListener progressListener) throws IOException, FitsException {
        File[] fitsFileInformation = getFitsFilesDetails();

        int total = fitsFileInformation.length;
        for (int i = 0; i < total; i++) {
            File fileInfo = fitsFileInformation[i];
            if (progressListener != null) {
                int percent = (int) (((float) i / total) * 100);
                progressListener.onProgressUpdate(percent, "Stretching " + fileInfo.getName() + "...");
            }

            Fits originalFits = new Fits(fileInfo);

            ApplicationWindow.logger.info("Stretching image: " + fileInfo.getName());

            // Stretch the file in memory
            stretchFITSImage(originalFits, stretchFactor, iterations, algo);

            // Save the stretched version
            String stretchFilename = addDirectory(fileInfo, "_stretched");
            writeFitsWithSuffix(originalFits, stretchFilename, "_stretch");

            originalFits.close();
        }
    }


// =========================================================================
    // THE MULTI-THREADED DETECTION PIPELINE
    // =========================================================================

    /**
     * Runs the standard detection pipeline on the full cached frame set and exports the HTML
     * session report plus associated visualizations.
     */
    public File detectObjects(DetectionConfig config, DetectionSafetyPrompt safetyPrompt, TransientEngineProgressListener progressListener) throws Exception {
        long startTime = System.currentTimeMillis();

        if (this.cachedFileInfo == null) getFitsfileInformation();
        int numFrames = this.cachedFileInfo.length;

        System.out.println("\n--- Loading " + numFrames + " FITS files for JTransient Engine ---");

        List<ImageFrame> framesForLibrary = new ArrayList<>();
        List<short[][]> rawFramesForExport = new ArrayList<>();

        for (int i = 0; i < numFrames; i++) {

            // --- PROGRESS UPDATE: FITS Loading (0% to 20%) ---
            if (progressListener != null) {
                int percent = (int) (((float) i / numFrames) * 20);
                progressListener.onProgressUpdate(percent, "Loading frame " + (i + 1) + " of " + numFrames + "...");
            }

            File currentFile = new File(this.cachedFileInfo[i].getFilePath());
            Fits fitsFile = new Fits(currentFile);
            BasicHDU<?> hdu = getImageHDU(fitsFile);
            Object kernel = hdu.getKernel();

            if (!(kernel instanceof short[][])) {
                fitsFile.close();
                throw new IOException("Cannot process: Expected short[][] but found " + kernel.getClass().toString());
            }

            short[][] imageData = (short[][]) kernel;
            long timestamp = this.cachedFileInfo[i].getObservationTimestamp();
            long exposure = this.cachedFileInfo[i].getExposureDurationMillis();
            framesForLibrary.add(new ImageFrame(i, currentFile.getName(), imageData, timestamp, exposure));
            rawFramesForExport.add(imageData);
            fitsFile.close();
        }

        // =========================================================
        // 2. HAND OFF TO THE LIBRARY!
        // =========================================================
        System.out.println("\n--- Passing data to JTransient Engine ---");

        if (progressListener != null) {
            progressListener.onProgressUpdate(20, "Initializing JTransient Engine...");
        }

        DetectionConfig effectiveConfig = createEffectiveDetectionConfig(config, framesForLibrary.size());

        JTransientEngine engine = new JTransientEngine();
        JTransientEngine.DEBUG = true;

        PipelineResult result = engine.runPipeline(framesForLibrary, effectiveConfig, progressListener);

        engine.shutdown();
        // =========================================================

        if (safetyPrompt != null) {
            DetectionSummary detectionSummary = summarizeDetections(result);
            if (!safetyPrompt.shouldProceed(detectionSummary)) {
                System.out.println("Report generation aborted by UI callback. Detection count: " + detectionSummary.totalDetections);
                return null;
            }
        }

        // 3. Handle the results in SpacePixels
        System.out.println("\n--- PHASE 5: Exporting Visualizations ---");

        if (progressListener != null) {
            progressListener.onProgressUpdate(90, "Exporting tracks and generating HTML report...");
        }

        if (numFrames > 0) {
            File exportDir = createDetectionsDirectory(new File(this.cachedFileInfo[0].getFilePath()));

            System.out.println("Total Pipeline Time: " + (System.currentTimeMillis() - startTime) + "ms");

            try {
                ImageDisplayUtils.exportTrackVisualizations(result, rawFramesForExport, this.cachedFileInfo, exportDir, effectiveConfig, appConfig);

                if (progressListener != null) {
                    progressListener.onProgressUpdate(100, "Finished!");
                }

                return new File(exportDir, ImageDisplayUtils.detectionReportName);
            } catch (IOException e) {
                System.err.println("Failed to export visualizations: " + e.getMessage());
            }
        }
        return null;
    }

// =========================================================================
// ITERATIVE SLOW-MOVER PIPELINE
// =========================================================================

    /**
     * Re-runs detection on progressively larger time-spaced subsets so slow movers can be explored
     * across several pass sizes while sharing a single global master stack.
     */
    public File detectSlowObjectsIterative(DetectionConfig config, DetectionSafetyPrompt safetyPrompt, TransientEngineProgressListener progressListener, int maxFramesLimit) throws Exception {
        long startTime = System.currentTimeMillis();

        if (this.cachedFileInfo == null) getFitsfileInformation();
        int numFrames = this.cachedFileInfo.length;

        if (numFrames < 5) {
            System.out.println("Not enough frames for iterative detection. Falling back to standard pipeline.");
            return detectObjects(config, safetyPrompt, progressListener);
        }

        System.out.println("\n--- Starting ITERATIVE PIPELINE for " + numFrames + " FITS files (On-Demand Loading) ---");

        File parentDir = new File(this.cachedFileInfo[0].getFilePath()).getParentFile();
        if (parentDir == null) {
            parentDir = new File(System.getProperty("user.dir"));
        }
        String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File masterDir = new File(parentDir, "iterative_detections_" + timestamp);

        if (!masterDir.exists()) {
            masterDir.mkdirs();
        }

        // Calculate the absolute highest pass we will perform based on the user limit
        int targetMaxLimit = (maxFramesLimit > 0 && maxFramesLimit < numFrames) ? maxFramesLimit : numFrames;
        if (targetMaxLimit < 5) {
            targetMaxLimit = 5;
        }

        System.out.println("\n--- Extracting Timestamps & Exposures for Temporal Spacing ---");
        long[] frameTimestamps = new long[numFrames];
        long[] frameExposures = new long[numFrames];
        boolean hasValidTime = true;
        for (int i = 0; i < numFrames; i++) {
            frameTimestamps[i] = this.cachedFileInfo[i].getObservationTimestamp();
            frameExposures[i] = this.cachedFileInfo[i].getExposureDurationMillis();
            if (frameTimestamps[i] == -1) {
                hasValidTime = false;
            }
        }

        System.out.println("\n--- Generating Global Master Map from " + targetMaxLimit + " globally spaced frames ---");
        TransientEngineProgressListener masterListener = (percent, msg) -> {
            if (progressListener != null) {
                progressListener.onProgressUpdate(percent / 5, "Global Master Map: " + msg); // Uses 0-20%
            }
        };

        JTransientEngine globalEngine = new JTransientEngine();
        List<ImageFrame> masterFrames = new ArrayList<>();

        List<Integer> masterIndices = getSampledIndices(frameTimestamps, hasValidTime, numFrames, targetMaxLimit);
        for (int idx : masterIndices) {
            File currentFile = new File(this.cachedFileInfo[idx].getFilePath());
            try (Fits fitsFile = new Fits(currentFile)) {
                BasicHDU<?> hdu = getImageHDU(fitsFile);
                Object kernel = hdu.getKernel();
                if (!(kernel instanceof short[][])) {
                    throw new IOException("Cannot process: Expected short[][] but found " + kernel.getClass().toString() + " in file " + currentFile.getName());
                }
                masterFrames.add(new ImageFrame(idx, currentFile.getName(), (short[][]) kernel, frameTimestamps[idx], frameExposures[idx]));
            }
        }

        short[][] providedMasterStack = globalEngine.generateMasterStack(masterFrames, config, masterListener);
        masterFrames.clear(); // Free memory
        System.gc(); // Encourage GC to reclaim the loaded frames

        // Calculate total iterations to scale the progress bar properly
        int totalIterations = (int) Math.ceil((targetMaxLimit - 4.0) / 5.0);
        int currentIteration = 0;
        List<ImageDisplayUtils.IterationSummary> summaries = new ArrayList<>();

        // 3. The Iteration Loop (5, 10, 15... up to the user's maximum limit)
        for (int k = 5; k <= targetMaxLimit; k += 5) {
            System.out.println("\n>>> RUNNING ITERATION: " + k + " Frames (Time-Spaced)");

            // --- PROGRESS UPDATE: The SCALED Listener ---
            // We map the engine's 0-100% output to fit within this specific iteration's window (20% to 100%)
            final int basePercent = 20 + (int) (((float) currentIteration / totalIterations) * 80);
            final int nextBasePercent = 20 + (int) (((float) (currentIteration + 1) / totalIterations) * 80);

            final int currentK = k;
            TransientEngineProgressListener scaledListener = (enginePercent, message) -> {
                if (progressListener != null) {
                    int scaledPercent = basePercent + (int) ((enginePercent / 100.0f) * (nextBasePercent - basePercent));
                    progressListener.onProgressUpdate(scaledPercent, "Pass " + currentK + " frames: " + message);
                }
            };

            // --- ON-DEMAND LOADING for the engine ---
            if (progressListener != null) {
                // Use the scaled listener to show progress within the current iteration's slice
                scaledListener.onProgressUpdate(0, "Loading " + k + " frames for engine...");
            }

            List<Integer> sampledIndices = getSampledIndices(frameTimestamps, hasValidTime, numFrames, k);

            List<ImageFrame> spacedSubset = new ArrayList<>();
            for (int index : sampledIndices) {
                File currentFile = new File(this.cachedFileInfo[index].getFilePath());
                try (Fits fitsFile = new Fits(currentFile)) {
                    BasicHDU<?> hdu = getImageHDU(fitsFile);
                    Object kernel = hdu.getKernel();
                    if (!(kernel instanceof short[][])) {
                        throw new IOException("Cannot process: Expected short[][] but found " + kernel.getClass().toString() + " in file " + currentFile.getName());
                    }
                    short[][] imageData = (short[][]) kernel;
                    // The ImageFrame needs the original index in the full sequence
                    spacedSubset.add(new ImageFrame(index, currentFile.getName(), imageData, frameTimestamps[index], frameExposures[index]));
                }
            }

            DetectionConfig effectiveConfig = createEffectiveDetectionConfig(config, spacedSubset.size());

            JTransientEngine engine = new JTransientEngine();
            // Pass the SCALED listener and the pre-computed Master Stack to the engine
            PipelineResult result = engine.runPipeline(spacedSubset, effectiveConfig, scaledListener, providedMasterStack);
            engine.shutdown();

            DetectionSummary detectionSummary = summarizeDetections(result);
            if (safetyPrompt != null && !safetyPrompt.shouldProceed(detectionSummary)) {
                System.out.println("Iteration " + k + " aborted by UI callback due to high detection count. Stopping further iterations.");
                break;
            }

            // --- ON-DEMAND LOADING for the export ---
            // This is I/O intensive but perfectly memory-safe for very large datasets!
            if (progressListener != null) {
                scaledListener.onProgressUpdate(95, "Generating report (on-demand disk reads)...");
            }

            final FitsFileInformation[] currentFitsFiles = this.cachedFileInfo;
            List<short[][]> rawFramesForExport = new java.util.AbstractList<short[][]>() {
                @Override
                public short[][] get(int index) {
                    try {
                        File currentFile = new File(currentFitsFiles[index].getFilePath());
                        try (Fits fitsFile = new Fits(currentFile)) {
                            BasicHDU<?> hdu = getImageHDU(fitsFile);
                            Object kernel = hdu.getKernel();
                            if (!(kernel instanceof short[][])) {
                                throw new IOException("Cannot process: Expected short[][] but found " + kernel.getClass().toString() + " in file " + currentFile.getName());
                            }
                            return (short[][]) kernel;
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to read frame " + index + " on demand: " + e.getMessage(), e);
                    }
                }

                @Override
                public int size() {
                    return currentFitsFiles.length;
                }
            };

            File iterationDir = new File(masterDir, k + "_frames");
            iterationDir.mkdirs();

            try {
                ImageDisplayUtils.exportTrackVisualizations(result, rawFramesForExport, this.cachedFileInfo, iterationDir, effectiveConfig, appConfig);

            } catch (IOException e) {
                System.err.println("Failed to export visualization for iteration " + k + ": " + e.getMessage());
            }

            int anomalyCount = result.anomalies == null ? 0 : result.anomalies.size();
            summaries.add(new ImageDisplayUtils.IterationSummary(k, k + "_frames", result.tracks.size(), anomalyCount));

            currentIteration++;
        }

        // --- PROGRESS UPDATE: Finished! ---
        if (progressListener != null) {
            progressListener.onProgressUpdate(100, "All iterative passes complete!");
        }

        File indexFile = new File(masterDir, "index.html");
        try {
            ImageDisplayUtils.exportIterativeIndexReport(masterDir, summaries);
            System.out.println("Total Iterative Pipeline Time: " + (System.currentTimeMillis() - startTime) + "ms");
            return indexFile;
        } catch (IOException e) {
            System.err.println("Failed to write iterative index report: " + e.getMessage());
        }

        System.out.println("Total Iterative Pipeline Time: " + (System.currentTimeMillis() - startTime) + "ms");
        return masterDir;
    }

    /**
     * Condenses the engine result into coarse detection categories for UI confirmation prompts.
     */
    private static DetectionSummary summarizeDetections(PipelineResult result) {
        List<TrackLinker.Track> tracks = result.tracks;
        int anomalies = result.anomalies == null ? 0 : result.anomalies.size();
        int suspectedThresholdStreakTracks = result.suspectedThresholdStreakTracks == null ? 0 : result.suspectedThresholdStreakTracks.size();
        int singleStreaks = 0;
        int streakTracks = 0;
        int movingTargets = 0;

        for (TrackLinker.Track track : tracks) {
            if (track.points.size() == 1) {
                singleStreaks++;
            } else if (track.isStreakTrack) {
                streakTracks++;
            } else {
                movingTargets++;
            }
        }

        int slowMoverCandidates = result.slowMoverCandidates == null ? 0 : result.slowMoverCandidates.size();
        int maximumStackTransientStreaks = result.masterMaximumStackTransientStreaks == null ? 0 : result.masterMaximumStackTransientStreaks.size();

        return new DetectionSummary(
                singleStreaks + streakTracks + movingTargets + anomalies + suspectedThresholdStreakTracks,
                singleStreaks,
                streakTracks,
                movingTargets,
                anomalies,
                suspectedThresholdStreakTracks,
                slowMoverCandidates,
                maximumStackTransientStreaks);
    }

    /**
     * Chooses a deterministic subset of frame indices, preferring timestamp spacing when valid
     * times are available and falling back to index spacing otherwise.
     */
    private List<Integer> getSampledIndices(long[] times, boolean hasValidTime, int maxLimit, int k) {
        List<Integer> indices = new ArrayList<>();

        if (hasValidTime && maxLimit > 1) {
            long startTime = times[0];
            long endTime = times[maxLimit - 1];
            long duration = endTime - startTime;

            if (duration > 0) {
                indices.add(0);
                for (int i = 1; i < k - 1; i++) {
                    long targetTime = startTime + (long) (i * duration / (double) (k - 1));
                    int bestIdx = 0;
                    long minDiff = Long.MAX_VALUE;
                    for (int j = 0; j < maxLimit; j++) {
                        long diff = Math.abs(times[j] - targetTime);
                        if (diff < minDiff) {
                            minDiff = diff;
                            bestIdx = j;
                        }
                    }
                    if (!indices.contains(bestIdx)) {
                        indices.add(bestIdx);
                    }
                }
                if (!indices.contains(maxLimit - 1)) {
                    indices.add(maxLimit - 1);
                }
                Collections.sort(indices);

                // Valid return only if time-based extraction found enough unique timestamps
                if (indices.size() == k) {
                    return indices;
                }
            }
        }

        // Fallback: Pure array-index spacing
        indices.clear();
        for (int i = 0; i < k; i++) {
            int index = (int) Math.round(i * (maxLimit - 1) / (double) (k - 1));
            indices.add(index);
        }
        return indices;
    }

// =========================================================================
    // UPDATED FITS FILE INFORMATION (The Import Gatekeeper)
    // =========================================================================

    /**
     * Loads FITS metadata for interactive use after validating compression state, format
     * consistency, dimensions, and optional auto-conversion paths.
     */
    public FitsFileInformation[] getFitsfileInformation() throws Exception {
        File[] fitsFileInformation = getFitsFilesDetails();
        int numFiles = fitsFileInformation.length;

        if (numFiles == 0) return new FitsFileInformation[0];

        // --- NEW: COMPRESSION GATEKEEPER ---
        try (Fits firstFits = new Fits(fitsFileInformation[0])) {
            if (isCompressedFits(firstFits)) {
                int choice = JOptionPane.showConfirmDialog(null,
                        "These images appear to be compressed (.fz format).\nSpacePixels requires uncompressed FITS files for optimal tracking and plate-solving.\n\n" +
                                "Would you like to automatically decompress this sequence into a new directory?",
                        "Decompression Required",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);

                if (choice == JOptionPane.YES_OPTION) {
                    ApplicationWindow.logger.info("Starting decompression...");
                    File newDir = batchDecompress(fitsFileInformation);
                    
                    int loadChoice = JOptionPane.showConfirmDialog(null, 
                            "Decompression Complete!\n\nWould you like to automatically load the new uncompressed directory?", 
                            "Success", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                    
                    if (loadChoice == JOptionPane.YES_OPTION && newDir != null) {
                        throw new RedirectImportException(newDir);
                    }
                }
                return new FitsFileInformation[0]; // Stop the current import
            }
        } catch (RedirectImportException re) {
            throw re;
        } catch (Exception e) {
            ApplicationWindow.logger.warning("Failed to read first FITS for compression check: " + e.getMessage());
        }


        // --- PRE-FLIGHT CONSISTENCY CHECK ---
        ApplicationWindow.logger.info("Performing pre-flight consistency check on " + numFiles + " files...");
        FitsFormatChecker.FitsFormat refFormat = FitsFormatChecker.FitsFormat.UNSUPPORTED;
        try {
            refFormat = FitsFormatChecker.checkFormat(fitsFileInformation[0]);
        } catch (Exception ignore) {}

        int tempRefWidth = -1;
        int tempRefHeight = -1;
        boolean tempRefMono = true;

        try {
            Fits firstFits = new Fits(fitsFileInformation[0]);
            BasicHDU<?> hdu = getImageHDU(firstFits);
            int[] axes = hdu.getAxes();
            if (axes == null) throw new FitsException("No image axes found");

            if (axes.length == 2) {
                tempRefHeight = axes[0];
                tempRefWidth = axes[1];
                tempRefMono = true;
            } else if (axes.length == 3) {
                tempRefHeight = axes[1];
                tempRefWidth = axes[2];
                tempRefMono = false;
            } else {
                firstFits.close();
                JOptionPane.showMessageDialog(null, "Unsupported FITS axes length: " + axes.length, "Import Error", JOptionPane.ERROR_MESSAGE);
                return new FitsFileInformation[0];
            }
            firstFits.close();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Failed to read the first FITS file: " + e.getMessage(), "Import Error", JOptionPane.ERROR_MESSAGE);
            return new FitsFileInformation[0];
        }

        final int refWidth = tempRefWidth;
        final int refHeight = tempRefHeight;
        final boolean refMono = tempRefMono;
        final FitsFormatChecker.FitsFormat finalRefFormat = refFormat;

        List<Callable<String>> validationTasks = new ArrayList<>();
        for (int i = 1; i < numFiles; i++) {
            final File f = fitsFileInformation[i];
            validationTasks.add(() -> {
                FitsFormatChecker.FitsFormat format = FitsFormatChecker.FitsFormat.UNSUPPORTED;
                try { format = FitsFormatChecker.checkFormat(f); } catch (Exception ignore) {}

                if (format != finalRefFormat) {
                    return "Inconsistent FITS formats detected!\nFile: " + f.getName() + " has format " + format + " but expected " + finalRefFormat + ".\nAll FITS files in the sequence must be of the exact same type.";
                }
                Fits fits = null;
                try {
                    fits = new Fits(f);
                    BasicHDU<?> hdu = getImageHDU(fits);
                    int[] axes = hdu.getAxes();
                    if (axes == null) return "No valid image HDU found in " + f.getName();

                    boolean mono = (axes.length == 2);
                    int w = -1, h = -1;
                    if (mono) {
                        h = axes[0];
                        w = axes[1];
                    } else if (axes.length == 3) {
                        h = axes[1];
                        w = axes[2];
                    }

                    if (mono != refMono || w != refWidth || h != refHeight) {
                        return "Inconsistent FITS dimensions or color-space detected!\nFile: " + f.getName() + " is " + w + "x" + h + " (Mono: " + mono + ")\nExpected: " + refWidth + "x" + refHeight + " (Mono: " + refMono + ").\nAll files must have the exact same resolution and color space.";
                    }
                } catch (Exception e) {
                    return "Failed to read FITS file " + f.getName() + ": " + e.getMessage();
                } finally {
                    if (fits != null) {
                        try { fits.close(); } catch (Exception ignored) {}
                    }
                }
                return null; // OK
            });
        }

        try {
            List<Future<String>> results = executor.invokeAll(validationTasks);
            for (Future<String> res : results) {
                String errorMsg = res.get();
                if (errorMsg != null) {
                    JOptionPane.showMessageDialog(null, errorMsg, "Import Error", JOptionPane.ERROR_MESSAGE);
                    return new FitsFileInformation[0];
                }
            }
        } catch (Exception e) {
            throw new IOException("Multi-threaded validation failed: " + e.getMessage(), e);
        }
        // --- END CONSISTENCY CHECK ---

        // --- THE GATEKEEPER: Check the format of the first file ---
        FitsFormatChecker.FitsFormat format = refFormat;

        if (format == FitsFormatChecker.FitsFormat.MONO_32BIT_FLOAT || format == FitsFormatChecker.FitsFormat.MONO_32BIT_INT) {
            int choice = JOptionPane.showConfirmDialog(null,
                    "These images appear to be 32-bit.\nSpacePixels' transient detection engine is highly optimized for 16-bit monochrome files.\n\n" +
                            "Would you like to automatically standardize this sequence to 16-bit Mono?",
                    "Format Conversion Required",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                ApplicationWindow.logger.info("Starting 32-bit Mono to 16-bit Mono conversion...");
                File newDir = batchConvert32BitTo16Bit(fitsFileInformation, false);
                
                int loadChoice = JOptionPane.showConfirmDialog(null, 
                        "Conversion Complete!\n\nWould you like to automatically load the newly created 16-bit directory?", 
                        "Success", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (loadChoice == JOptionPane.YES_OPTION && newDir != null) {
                    throw new RedirectImportException(newDir);
                }
                return new FitsFileInformation[0]; // Stop the current import
            }

        } else if (format == FitsFormatChecker.FitsFormat.COLOR_32BIT_FLOAT || format == FitsFormatChecker.FitsFormat.COLOR_32BIT_INT) {
            int choice = JOptionPane.showConfirmDialog(null,
                    "These images are 32-bit Color.\nSpacePixels' transient detection engine requires 16-bit monochrome files.\n\n" +
                            "Would you like to extract the Luminance (16-bit Mono) and save a 16-bit Color version?",
                    "Color Format Conversion Required",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                ApplicationWindow.logger.info("Starting 32-bit Color to 16-bit Mono/Color conversion...");
                File newDir = batchConvert32BitTo16Bit(fitsFileInformation, true);
                
                int loadChoice = JOptionPane.showConfirmDialog(null, 
                        "Conversion Complete!\n\nWould you like to automatically load the newly created 16-bit Mono directory to run detections?", 
                        "Success", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (loadChoice == JOptionPane.YES_OPTION && newDir != null) {
                    throw new RedirectImportException(newDir);
                }
                return new FitsFileInformation[0]; // Stop the current import
            }
        } else if (format == FitsFormatChecker.FitsFormat.UNSUPPORTED) {
            JOptionPane.showMessageDialog(null, "Unsupported FITS format detected. SpacePixels supports 16-bit and 32-bit FITS files.", "Import Error", JOptionPane.ERROR_MESSAGE);
            return new FitsFileInformation[0];
        }

        System.out.println("\n--- Fast-Loading Information for " + numFiles + " FITS files ---");

        List<Callable<FitsFileInformation>> tasks = new ArrayList<>();

        for (int i = 0; i < numFiles; i++) {
            final int index = i;
            final File currentFile = fitsFileInformation[i];

            tasks.add(() -> {
                Fits fitsFile = null;
                try {
                    fitsFile = new Fits(currentFile);
                    BasicHDU<?> hdu = getImageHDU(fitsFile);
                    Header fitsHeader = hdu.getHeader();

                    String fpath = currentFile.getAbsolutePath();
                    boolean monochromeImage;
                    int width;
                    int height;

                    int[] axes = hdu.getAxes();

                    if (axes.length == 2) {
                        monochromeImage = true;
                        height = axes[0];
                        width = axes[1];
                    } else if (axes.length == 3) {
                        monochromeImage = false;
                        height = axes[1];
                        width = axes[2];
                    } else {
                        throw new FitsException("Cannot understand file, it has axes length=" + axes.length);
                    }

                    FitsFileInformation fileInfo = new FitsFileInformation(fpath, currentFile.getName(), monochromeImage, width, height);

                    Cursor<String, HeaderCard> iter = fitsHeader.iterator();
                    while (iter.hasNext()) {
                        HeaderCard fitsHeaderCard = iter.next();
                        fileInfo.getFitsHeader().put(fitsHeaderCard.getKey(), fitsHeaderCard.getValue());
                    }

                    PlateSolveResult previousSolveresult = readSolveResults(fpath);
                    if (previousSolveresult != null) {
                        fileInfo.setSolveResult(previousSolveresult);
                    }

                    return fileInfo;

                } finally {
                    if (fitsFile != null) {
                        fitsFile.close();
                    }
                }
            });
        }

        FitsFileInformation[] ret = new FitsFileInformation[numFiles];

        try {
            List<Future<FitsFileInformation>> futures = executor.invokeAll(tasks);
            for (int i = 0; i < futures.size(); i++) {
                ret[i] = futures.get(i).get();
            }
        } catch (Exception e) {
            throw new IOException("Multi-threaded file loading failed: " + e.getMessage(), e);
        }

        // --- NEW: Sort chronologically based on DATE-OBS FITS header ---
        Arrays.sort(ret, (a, b) -> {
            long t1 = a.getObservationTimestamp();
            long t2 = b.getObservationTimestamp();
            if (t1 != -1 && t2 != -1) {
                return Long.compare(t1, t2);
            }
            // Fallback to alphabetical if time isn't present
            return a.getFileName().compareTo(b.getFileName());
        });

        this.cachedFileInfo = ret;
        System.out.println("Finished loading metadata for " + numFiles + " files instantly.");
        return ret;
    }

    /**
     * Headless variant of FITS metadata loading used by CLI/batch flows that cannot prompt the
     * user for decompression or format-conversion decisions.
     */
    public FitsFileInformation[] getFitsfileInformationHeadless() throws Exception {
        File[] fitsFiles = getFitsFilesDetails();
        int numFiles = fitsFiles.length;

        if (numFiles == 0) {
            this.cachedFileInfo = new FitsFileInformation[0];
            return this.cachedFileInfo;
        }

        System.out.println("\n--- Validating " + numFiles + " FITS files for headless batch detection ---");

        List<Callable<FitsMetadataLoadResult>> tasks = new ArrayList<>();
        for (File currentFile : fitsFiles) {
            tasks.add(() -> loadFitsMetadataHeadless(currentFile));
        }

        FitsMetadataLoadResult[] loadedResults = new FitsMetadataLoadResult[numFiles];
        try {
            List<Future<FitsMetadataLoadResult>> futures = executor.invokeAll(tasks);
            for (int i = 0; i < futures.size(); i++) {
                loadedResults[i] = futures.get(i).get();
            }
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new IOException("Headless FITS validation failed.", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Headless FITS validation was interrupted.", e);
        }

        FitsMetadataLoadResult reference = loadedResults[0];
        for (int i = 1; i < loadedResults.length; i++) {
            validateHeadlessFitsConsistency(reference, loadedResults[i]);
        }

        FitsFileInformation[] ret = new FitsFileInformation[numFiles];
        for (int i = 0; i < loadedResults.length; i++) {
            ret[i] = loadedResults[i].fileInfo;
        }

        Arrays.sort(ret, (a, b) -> {
            long t1 = a.getObservationTimestamp();
            long t2 = b.getObservationTimestamp();
            if (t1 != -1 && t2 != -1) {
                return Long.compare(t1, t2);
            }
            return a.getFileName().compareTo(b.getFileName());
        });

        this.cachedFileInfo = ret;
        System.out.println("Validated and loaded metadata for " + numFiles + " FITS files.");
        return ret;
    }

    // =========================================================================
    // EXISTING HELPER METHODS (Unchanged)
    // =========================================================================

    /**
     * Loads and validates a single FITS file for headless mode, enforcing the strict mono 16-bit
     * requirements expected by the batch pipeline.
     */
    private FitsMetadataLoadResult loadFitsMetadataHeadless(File currentFile) throws Exception {
        Fits fitsFile = null;
        try {
            fitsFile = new Fits(currentFile);
            if (isCompressedFits(fitsFile)) {
                throw new IOException("Compressed FITS files are not supported in batch mode: " + currentFile.getName());
            }

            BasicHDU<?> hdu = getImageHDU(fitsFile);
            if (hdu == null) {
                throw new FitsException("No valid image HDU found in " + currentFile.getName());
            }

            int[] axes = hdu.getAxes();
            if (axes == null || axes.length != 2) {
                throw new FitsException("Expected uncompressed 16-bit monochrome FITS files. File " + currentFile.getName() + " has axes length " + (axes == null ? 0 : axes.length) + ".");
            }

            Header fitsHeader = hdu.getHeader();
            int bitpix = fitsHeader.getIntValue("BITPIX", 0);
            if (bitpix != 16) {
                throw new FitsException("Expected uncompressed 16-bit monochrome FITS files. File " + currentFile.getName() + " has BITPIX=" + bitpix + ".");
            }

            int height = axes[0];
            int width = axes[1];
            if (width <= 0 || height <= 0) {
                throw new FitsException("Invalid image dimensions in " + currentFile.getName() + ": " + width + "x" + height);
            }

            FitsFileInformation fileInfo = new FitsFileInformation(currentFile.getAbsolutePath(), currentFile.getName(), true, width, height);

            Cursor<String, HeaderCard> iter = fitsHeader.iterator();
            while (iter.hasNext()) {
                HeaderCard fitsHeaderCard = iter.next();
                fileInfo.getFitsHeader().put(fitsHeaderCard.getKey(), fitsHeaderCard.getValue());
            }

            PlateSolveResult previousSolveresult = readSolveResults(currentFile.getAbsolutePath());
            if (previousSolveresult != null) {
                fileInfo.setSolveResult(previousSolveresult);
            }

            return new FitsMetadataLoadResult(fileInfo, width, height, bitpix);
        } finally {
            if (fitsFile != null) {
                fitsFile.close();
            }
        }
    }

    /**
     * Ensures every headless input frame matches the reference frame in geometry and bit depth.
     */
    private void validateHeadlessFitsConsistency(FitsMetadataLoadResult reference, FitsMetadataLoadResult candidate) throws IOException {
        if (candidate.bitpix != reference.bitpix) {
            throw new IOException("Inconsistent FITS bit depth detected. File " + candidate.fileInfo.getFileName() +
                    " has BITPIX=" + candidate.bitpix + " but " + reference.fileInfo.getFileName() + " has BITPIX=" + reference.bitpix + ".");
        }

        if (candidate.width != reference.width || candidate.height != reference.height) {
            throw new IOException("Inconsistent FITS dimensions detected. File " + candidate.fileInfo.getFileName() +
                    " is " + candidate.width + "x" + candidate.height + " but " + reference.fileInfo.getFileName() +
                    " is " + reference.width + "x" + reference.height + ".");
        }
    }



    /**
     * Enumerates FITS-like files in the configured directory and returns them in stable path order.
     */
    private File[] getFitsFilesDetails() throws IOException, FitsException {
        File directory = alignedFitsFolderFullPath;
        if (!directory.isDirectory()) {
            throw new IOException("file:" + directory.getAbsolutePath() + " is not a directory");
        }

        List<File> fitsFilesPath = new ArrayList<File>();
        for (File f : directory.listFiles((dir, name) -> {
            String[] acceptedFileTypes = {"fits", "fit", "fts", "Fits", "Fit", "FIT", "FTS", "Fts", "FITS", "fz", "Fz", "FZ"};
            for (String acceptedFileEnd : acceptedFileTypes) {
                if (name.endsWith(acceptedFileEnd)) {
                    return true;
                }
            }
            return false;
        })) {
            fitsFilesPath.add(f);
        }

        if (fitsFilesPath.isEmpty()) {
            throw new IOException("No fits files in directory:" + directory.getAbsolutePath());
        }

        File[] ret = fitsFilesPath.toArray(new File[]{});
        Arrays.sort(ret, Comparator.comparing(File::getAbsolutePath));
        return ret;
    }

    /**
     * Closes a group of FITS handles that were opened as a batch.
     */
    private static void closeFitsFiles(Fits[] fitsFiles) throws IOException {
        for (Fits fitsFile : fitsFiles) {
            fitsFile.close();
        }
    }

    /**
     * Starts plate solving through either ASTAP or astrometry.net using the currently configured
     * external tooling.
     */
    public Future<PlateSolveResult> solve(String fitsFileFullPath, boolean astap, boolean astrometry) throws FitsException, IOException {
        ApplicationWindow.logger.info("trying to solve image astap=" + astap + " astrometry=" + astrometry);

        if (astap) {
            String astapPath = appConfig.astapExecutablePath;
            if (astapPath != null && (!"".equals(astapPath))) {
                File astapPathFile = new File(astapPath);
                if (astapPathFile.exists()) {
                    FutureTask<PlateSolveResult> task = ASTAPInterface.solveImage(astapPathFile, fitsFileFullPath);
                    executor.execute(task);
                    return task;
                }
            }
            throw new IOException("ASTAP executable path is not correct:" + astapPath);
        } else if (astrometry) {
            try {
                astrometryNetInterface.login();
                SubmitFileRequest typicalParamsRequest = SubmitFileRequest.builder().withPublicly_visible("y").withScale_units("degwidth").withScale_lower(0.1f).withScale_upper(180.0f).withDownsample_factor(2f).withRadius(10f).build();
                return astrometryNetInterface.customSolve(new File(fitsFileFullPath), typicalParamsRequest);
            } catch (IOException | InterruptedException e) {
                JOptionPane.showMessageDialog(new JFrame(), "Could not solve image with astrometry.net :" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
        return null;
    }

    /**
     * Copies WCS keywords from a reference FITS file into every file in the active directory and
     * optionally emits stretched derivatives alongside the solved outputs.
     */
    public void applyWCSHeader(String wcsHeaderFile, int stretchFactor, int iterations, boolean stretch, StretchAlgorithm algo) throws IOException, FitsException {
        File[] fitsFileInformation = getFitsFilesDetails();
        Fits[] fitsFiles = new Fits[fitsFileInformation.length];
        for (int i = 0; i < fitsFiles.length; i++) {
            fitsFiles[i] = new Fits(fitsFileInformation[i]);
        }

        Fits wcsHeaderFITS = new Fits(wcsHeaderFile);
        Header wcsHeaderFITSHeader = getImageHDU(wcsHeaderFITS).getHeader();
        String[] wcsHeaderElements = {"CTYPE", "CUNIT1", "CUNIT2", "WCSAXES", "IMAGEW", "IMAGEH", "A_ORDER", "B_ORDER", "AP_ORDER", "BP_ORDER", "CRPIX", "CRVAL", "CDELT", "CROTA", "CD1_", "CD2_", "EQUINOX", "LONPOLE", "LATPOLE", "A_", "B_", "AP_", "BP_"};

        for (int i = 0; i < fitsFiles.length; i++) {
            Header headerHDU = getImageHDU(fitsFiles[i]).getHeader();
            Cursor<String, HeaderCard> wcsHeaderFITSHeaderIter = wcsHeaderFITSHeader.iterator();

            while (wcsHeaderFITSHeaderIter.hasNext()) {
                HeaderCard wcsHeaderFITSHeaderCard = wcsHeaderFITSHeaderIter.next();
                String wcsHeaderKey = wcsHeaderFITSHeaderCard.getKey();
                for (String wcsKeyword : wcsHeaderElements) {
                    if (wcsHeaderKey.startsWith(wcsKeyword)) {
                        headerHDU.deleteKey(wcsHeaderKey);
                        headerHDU.addLine(wcsHeaderFITSHeaderCard);
                        break;
                    }
                }
            }
            writeUpdatedFITSFile(fitsFileInformation[i], fitsFiles[i], stretchFactor, iterations, stretch, algo);
        }
        wcsHeaderFITS.close();
        closeFitsFiles(fitsFiles);
    }

    /**
     * Writes stretched derivatives for every FITS file in the active directory without modifying
     * headers or running any detection logic.
     */
    public void onlyStretch(int stretchFactor, int iterations, StretchAlgorithm algo) throws IOException, FitsException {
        File[] fitsFileInformation = getFitsFilesDetails();
        for (int i = 0; i < fitsFileInformation.length; i++) {
            Fits fitsFile = new Fits(fitsFileInformation[i]);
            writeOnlyStretchedFitsFile(fitsFileInformation[i], fitsFile, stretchFactor, iterations, algo);
        }
    }

    /**
     * Downloads a remote file to disk and returns the number of bytes written.
     */
    public static int downloadFile(URL fileURL, String targetFilePath) throws IOException {
        ApplicationWindow.logger.info("downloading : " + fileURL.toString() + " to " + targetFilePath);
        File targetfile = new File(targetFilePath);
        if (targetfile.exists()) {
            targetfile.delete();
            ApplicationWindow.logger.info("deleted pre-existing : " + targetFilePath);
        }

        BufferedInputStream in = new BufferedInputStream(fileURL.openStream());
        FileOutputStream fileOutputStream = new FileOutputStream(targetFilePath);
        byte dataBuffer[] = new byte[1024];
        int bytesRead;
        int totalBytesRead = 0;
        while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
            fileOutputStream.write(dataBuffer, 0, bytesRead);
            totalBytesRead += bytesRead;
        }
        fileOutputStream.close();
        return totalBytesRead;
    }

    /**
     * Persists a plate-solving result next to the FITS file as a simple INI-style sidecar.
     */
    public void writeSolveResults(String fitsFileFullPath, PlateSolveResult result) throws IOException {
        String solveResultFilename = fitsFileFullPath.substring(0, fitsFileFullPath.lastIndexOf(".")) + "_result.ini";
        File solveResultFile = new File(solveResultFilename);

        Properties props = new Properties();
        props.setProperty("success", String.valueOf(result.isSuccess()));
        if (result.getFailureReason() != null) props.setProperty("failure_reason", result.getFailureReason());
        if (result.getWarning() != null) props.setProperty("warning", result.getWarning());

        Map<String, String> solveInformation = result.getSolveInformation();
        if (solveInformation != null) {
            for (Map.Entry<String, String> entry : solveInformation.entrySet()) {
                if (entry.getValue() != null) props.setProperty(entry.getKey(), entry.getValue());
            }
        }

        try (FileOutputStream fos = new FileOutputStream(solveResultFile)) {
            props.store(fos, "SpacePixels Plate Solve Results");
        }
    }

    /**
     * Reads a previously saved plate-solving sidecar if one exists for the FITS file.
     */
    public PlateSolveResult readSolveResults(String fitsFileFullPath) {
        String solveResultFilename = fitsFileFullPath.substring(0, fitsFileFullPath.lastIndexOf(".")) + "_result.ini";
        File solveResultFile = new File(solveResultFilename);

        if (solveResultFile.exists()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(solveResultFile)) {
                props.load(fis);

                boolean success = Boolean.parseBoolean(props.getProperty("success", "false"));
                String failure_reason = props.getProperty("failure_reason");
                String warning = props.getProperty("warning");

                Map<String, String> solveInfo = new HashMap<>();
                for (String key : props.stringPropertyNames()) {
                    if (!key.equals("success") && !key.equals("failure_reason") && !key.equals("warning")) {
                        solveInfo.put(key, props.getProperty(key));
                    }
                }
                return new PlateSolveResult(success, failure_reason, warning, solveInfo);
            } catch (IOException e) {
                ApplicationWindow.logger.warning("Could not read solve results: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Safely injects Plate Solve WCS coordinates directly into the original FITS header.
     * Uses a temporary file swap to prevent data corruption.
     */
    public void updateFitsHeaderWithWCS(String fitsFileFullPath, Map<String, String> wcsData) throws Exception {
        File originalFile = new File(fitsFileFullPath);
        File tempFile = new File(fitsFileFullPath + ".tmp");

        try (Fits fits = new Fits(originalFile)) {
            BasicHDU<?> hdu = getImageHDU(fits);
            Header header = hdu.getHeader();

            for (Map.Entry<String, String> entry : wcsData.entrySet()) {
                String key = entry.getKey().toUpperCase();
                String value = entry.getValue();

                // Standard FITS keys cannot exceed 8 chars.
                // This cleanly filters out internal JPlateSolve keys like 'annotated_image_link' or 'success'
                if (key.length() > 8 || value == null) {
                    continue;
                }

                // Infer types to write proper FITS header values
                try {
                    if (value.contains(".")) {
                        header.addValue(key, Double.parseDouble(value), "SpacePixels WCS");
                    } else {
                        header.addValue(key, Integer.parseInt(value), "SpacePixels WCS");
                    }
                } catch (NumberFormatException e) {
                    String cleanValue = value;
                    if (cleanValue.startsWith("'") && cleanValue.endsWith("'")) {
                        cleanValue = cleanValue.substring(1, cleanValue.length() - 1).trim();
                    }
                    header.addValue(key, cleanValue, "SpacePixels WCS");
                }
            }
            fits.write(tempFile);
        }

        if (originalFile.delete()) {
            if (!tempFile.renameTo(originalFile)) {
                throw new IOException("Failed to rename temporary FITS file back to original.");
            }
        } else {
            throw new IOException("Failed to delete original FITS file to overwrite it.");
        }
    }

    /**
     * Exposes the mutable application preferences currently associated with this processor.
     */
    public AppConfig getAppConfig() {
        return appConfig;
    }

    /**
     * Writes the current application preferences back to the user's config file.
     */
    public void saveAppConfig() throws IOException {
        SpacePixelsAppConfigIO.write(appConfigFile, appConfig);
    }

    /**
     * Emits solved FITS derivatives for a single source file, including mono conversions and
     * optional stretched variants when requested.
     */
    private void writeUpdatedFITSFile(File fileInformation, Fits originalFits, int stretchFactor, int iterations, boolean stretch, StretchAlgorithm algo) throws FitsException, IOException {
        int naxis = getImageHDU(originalFits).getHeader().getIntValue("NAXIS");
        boolean isColor = false;
        if (naxis == 3) {
            isColor = true;
        }

        String newFNameSolved = addDirectory(fileInformation, "_solved");
        writeFitsWithSuffix(originalFits, newFNameSolved, "_wcs");

        Fits monochromeFits = null;
        if (isColor) {
            monochromeFits = convertToMono(originalFits);
            String newFNameSolvedMono = addDirectory(fileInformation, "_solved_mono");
            writeFitsWithSuffix(monochromeFits, newFNameSolvedMono, "_mono_wcs");
        }

        if (stretch) {
            String newFNameSolvedStretched = addDirectory(fileInformation, "_solved_stretched");
            stretchFITSImage(originalFits, stretchFactor, iterations, algo);
            writeFitsWithSuffix(originalFits, newFNameSolvedStretched, "_wcs_stretch");

            if (isColor) {
                String newFNameSolvedMonoStretch = addDirectory(fileInformation, "_solved_mono_stretched");
                stretchFITSImage(monochromeFits, stretchFactor, iterations, algo);
                writeFitsWithSuffix(monochromeFits, newFNameSolvedMonoStretch, "_mono_wcs_stretch");
            }
        }
    }

    /**
     * Emits only stretched derivatives for a single FITS file, preserving both color and derived
     * mono outputs when applicable.
     */
    private void writeOnlyStretchedFitsFile(File fileInformation, Fits originalFits, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException, IOException {
        int naxis = getImageHDU(originalFits).getHeader().getIntValue("NAXIS");
        boolean isColor = false;
        if (naxis == 3) {
            isColor = true;
        }

        Fits monochromeFits = null;
        if (isColor) {
            monochromeFits = convertToMono(originalFits);
        }

        String newFNameSolvedStretched = addDirectory(fileInformation, "_stretched");
        stretchFITSImage(originalFits, stretchFactor, iterations, algo);
        writeFitsWithSuffix(originalFits, newFNameSolvedStretched, "_stretch");

        if (isColor) {
            String newFNameSolvedMonoStretch = addDirectory(fileInformation, "_mono_stretched");
            stretchFITSImage(monochromeFits, stretchFactor, iterations, algo);
            writeFitsWithSuffix(monochromeFits, newFNameSolvedMonoStretch, "_mono_stretch");
        }
    }

    /**
     * Converts a color FITS container into a new mono FITS container while preserving the original
     * non-structural header metadata.
     */
    private Fits convertToMono(Fits colorFITSImage) throws FitsException, IOException {
        BasicHDU<?> originalHDU = getImageHDU(colorFITSImage);
        Object monoKernelData = convertToMono(originalHDU.getKernel());
        Fits updatedFits = new Fits();
        updatedFits.addHDU(FitsFactory.hduFactory(monoKernelData));

        Cursor<String, HeaderCard> updatedFitsHeaderIterator = updatedFits.getHDU(0).getHeader().iterator();
        while (updatedFitsHeaderIterator.hasNext()) {
            updatedFitsHeaderIterator.next();
            updatedFitsHeaderIterator.remove();
        }

        Cursor<String, HeaderCard> originalHeader = originalHDU.getHeader().iterator();
        while (originalHeader.hasNext()) {
            HeaderCard originalHeaderCard = originalHeader.next();
            updatedFits.getHDU(0).getHeader().addLine(originalHeaderCard);
        }

        Cursor<String, HeaderCard> headerCursor = updatedFits.getHDU(0).getHeader().iterator();
        headerCursor.setKey("NAXIS");
        if (headerCursor.hasNext()) {
            headerCursor.next();
            headerCursor.remove();
            headerCursor.add(new HeaderCard("NAXIS", 2, "replaced"));
        }

        headerCursor.setKey("NAXIS3");
        if (headerCursor.hasNext()) {
            headerCursor.next();
            headerCursor.remove();
        }
        return updatedFits;
    }

    /**
     * Writes a FITS object to disk using SpacePixels' suffix-based naming convention.
     */
    private void writeFitsWithSuffix(Fits fitsImage, String fitsFilename, String suffix) throws IOException, FitsException {
        int lastSepPosition = fitsFilename.lastIndexOf(".");
        fitsFilename = fitsFilename.substring(0, lastSepPosition) + suffix + ".fit";
        File toDeleteFile = new File(fitsFilename);
        if (toDeleteFile.exists()) {
            toDeleteFile.delete();
        }
        fitsImage.write(new File(fitsFilename));
    }

    /**
     * Ensures a sibling output directory exists and returns the matching output path for a file.
     */
    private String addDirectory(File currentFile, String directory) throws IOException {
        String newDirectory = currentFile.getParent() + File.separator + directory;
        File newDirFile = new File(newDirectory);
        if (!newDirFile.exists()) {
            newDirFile.mkdirs();
        }
        return newDirFile.getAbsolutePath() + File.separator + currentFile.getName();
    }

// =========================================================================
    // 32-BIT TO 16-BIT STANDARDIZATION LOGIC
    // =========================================================================

    /**
     * Decompresses a sequence of compressed FITS files into a sibling directory and returns that
     * new directory for optional reload.
     */
    private File batchDecompress(File[] files) throws Exception {
        eu.startales.spacepixels.gui.ProcessingProgressDialog progressDialog =
                new eu.startales.spacepixels.gui.ProcessingProgressDialog(null);
        progressDialog.setTitle("Decompressing FITS files...");

        SwingUtilities.invokeLater(() -> progressDialog.setVisible(true));

        File targetDir = null;
        try {
            for (int i = 0; i < files.length; i++) {
                File file = files[i];
                final int percent = (int) (((float) i / files.length) * 100);
                SwingUtilities.invokeLater(() -> progressDialog.updateProgress(percent, "Decompressing " + file.getName() + "..."));

                Fits originalFits = new Fits(file);
                BasicHDU<?> imageHDU = getImageHDU(originalFits);

                if (imageHDU != null) {
                    Fits decompressedFits = new Fits();
                    decompressedFits.addHDU(imageHDU);

                    String outName = addDirectory(file, "_uncompressed");
                    if (outName.toLowerCase().endsWith(".fz")) {
                        outName = outName.substring(0, outName.length() - 3);
                    }

                    if (targetDir == null) targetDir = new File(outName).getParentFile();
                    File outFile = new File(outName);
                    if (outFile.exists()) outFile.delete();
                    decompressedFits.write(outFile);
                }
                originalFits.close();
            }
        } finally {
            SwingUtilities.invokeLater(() -> progressDialog.dispose());
        }
        return targetDir;
    }

    /**
     * Standardizes 32-bit FITS inputs into 16-bit outputs, optionally preserving color frames and
     * extracting luminance for detection workflows.
     */
    private File batchConvert32BitTo16Bit(File[] files, boolean isColor) throws Exception {
        eu.startales.spacepixels.gui.ProcessingProgressDialog progressDialog =
                new eu.startales.spacepixels.gui.ProcessingProgressDialog(null);
        progressDialog.setTitle("Converting 32-bit to 16-bit...");

        SwingUtilities.invokeLater(() -> progressDialog.setVisible(true));

        File targetDir = null;
        try {
            for (int i = 0; i < files.length; i++) {
                File file = files[i];
                final int percent = (int) (((float) i / files.length) * 100);
                SwingUtilities.invokeLater(() -> progressDialog.updateProgress(percent, "Converting " + file.getName() + "..."));

                ApplicationWindow.logger.info("Converting 32-bit file: " + file.getName());

                // --- DEBUG: Print Original File ---
                printFitsDebugInfo("ORIGINAL 32-BIT", file);

                Fits originalFits = new Fits(file);
                BasicHDU<?> imageHDU = getImageHDU(originalFits);
                Header origHeader = imageHDU.getHeader();
                Object kernel = imageHDU.getKernel();

                if (isColor) {
                    // 1. Convert to 16-bit Color
                    short[][][] color16 = standardizeTo16BitColor(kernel);
                    Fits colorFits = createFitsFromData(color16, origHeader);
                    String colorName = addDirectory(file, "_16bit_color");
                    writeFitsWithSuffix(colorFits, colorName, "_16bit_color");

                    // --- DEBUG: Print Converted Color File ---
                    String finalColorPath = colorName.substring(0, colorName.lastIndexOf(".")) + "_16bit_color.fit";
                    printFitsDebugInfo("CONVERTED 16-BIT COLOR", new File(finalColorPath));

                    // 2. Extract Luminance to 16-bit Mono
                    short[][] mono16 = extractLuminance(color16);
                    Fits monoFits = createFitsFromData(mono16, origHeader);

                    // Note: No manual header hacking needed here anymore!
                    // createFitsFromData safely handles NAXIS and NAXIS3 automatically.
                    String monoName = addDirectory(file, "_16bit_mono");
                    if (targetDir == null) targetDir = new File(monoName).getParentFile();
                    writeFitsWithSuffix(monoFits, monoName, "_16bit_mono");

                    // --- DEBUG: Print Converted Luminance File ---
                    String finalLuminancePath = monoName.substring(0, monoName.lastIndexOf(".")) + "_16bit_mono.fit";
                    printFitsDebugInfo("CONVERTED 16-BIT LUMINANCE", new File(finalLuminancePath));

                } else {
                    // Convert to 16-bit Mono
                    short[][] mono16 = standardizeTo16BitMono(kernel);
                    Fits monoFits = createFitsFromData(mono16, origHeader);
                    String monoName = addDirectory(file, "_16bit_converted");
                    if (targetDir == null) targetDir = new File(monoName).getParentFile();
                    writeFitsWithSuffix(monoFits, monoName, "_16bit");

                    // --- DEBUG: Print Converted Mono File ---
                    String finalMonoPath = monoName.substring(0, monoName.lastIndexOf(".")) + "_16bit.fit";
                    printFitsDebugInfo("CONVERTED 16-BIT MONO", new File(finalMonoPath));
                }
                originalFits.close();
            }
        } finally {
            SwingUtilities.invokeLater(() -> progressDialog.dispose());
        }
        return targetDir;
    }

    /**
     * Builds a new FITS container from converted pixel data while preserving non-structural header
     * cards and restoring the unsigned-16-bit interpretation through `BZERO`/`BSCALE`.
     */
    private Fits createFitsFromData(Object newData, Header originalHeader) throws FitsException, IOException {
        Fits updatedFits = new Fits();
        BasicHDU<?> newHDU = FitsFactory.hduFactory(newData);
        updatedFits.addHDU(newHDU);

        Header newHeader = newHDU.getHeader();

        java.util.List<String> structuralKeys = java.util.Arrays.asList(
                "SIMPLE", "BITPIX", "NAXIS", "NAXIS1", "NAXIS2", "NAXIS3",
                "EXTEND", "BZERO", "BSCALE"
        );

        Cursor<String, HeaderCard> originalCursor = originalHeader.iterator();
        while (originalCursor.hasNext()) {
            HeaderCard card = originalCursor.next();
            String key = card.getKey();

            if (key != null && !structuralKeys.contains(key)) {
                if (!newHeader.containsKey(key)) {
                    newHeader.addLine(card);
                }
            }
        }

        // --- THE CRITICAL FIX ---
        // Explicitly tell the FITS format that this signed 16-bit array
        // actually represents unsigned 0-65535 data!
        newHeader.addValue("BZERO", 32768.0, "offset data range to that of unsigned short");
        newHeader.addValue("BSCALE", 1.0, "default scaling factor");

        return updatedFits;
    }

// =========================================================================
    // DEBUG: FITS VERIFICATION
    // =========================================================================

    /**
     * Dumps a compact debug summary of a FITS file's stored and display-scaled numeric range.
     */
    private void printFitsDebugInfo(String label, File fitsFile) {
        try (Fits fits = new Fits(fitsFile)) {
            BasicHDU<?> hdu = getImageHDU(fits);
            Header header = hdu.getHeader();
            Object kernel = hdu.getKernel();

            int bitpix = header.getIntValue("BITPIX", 0);
            double bzero = header.getDoubleValue("BZERO", 0.0);
            double bscale = header.getDoubleValue("BSCALE", 1.0);

            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;

            // Scan 2D arrays (Mono)
            if (kernel instanceof float[][]) {
                float[][] data = (float[][]) kernel;
                for (float[] row : data) {
                    for (float val : row) {
                        if (val < min) min = val;
                        if (val > max) max = val;
                    }
                }
            } else if (kernel instanceof short[][]) {
                short[][] data = (short[][]) kernel;
                for (short[] row : data) {
                    for (short val : row) {
                        if (val < min) min = val;
                        if (val > max) max = val;
                    }
                }
            } else if (kernel instanceof int[][]) {
                int[][] data = (int[][]) kernel;
                for (int[] row : data) {
                    for (int val : row) {
                        if (val < min) min = val;
                        if (val > max) max = val;
                    }
                }
            }

            System.out.println("\n--- DEBUG: " + label + " ---");
            System.out.println("File:   " + fitsFile.getName());
            System.out.println("BITPIX: " + bitpix);
            System.out.println("BZERO:  " + bzero);
            System.out.println("BSCALE: " + bscale);
            if (min != Double.MAX_VALUE) {
                System.out.println("Raw Min Value: " + min);
                System.out.println("Raw Max Value: " + max);
                // Calculate what the true value is after BZERO/BSCALE are applied by a viewer
                System.out.println("True Visual Min: " + ((min * bscale) + bzero));
                System.out.println("True Visual Max: " + ((max * bscale) + bzero));
            } else {
                System.out.println("(3D Color array, skipping min/max scan for brevity)");
            }
            System.out.println("-----------------------------------");

        } catch (Exception e) {
            System.err.println("Debug read failed for " + fitsFile.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Converts supported mono kernels into the signed-short storage layout used for unsigned 16-bit
     * FITS export.
     */
    private short[][] standardizeTo16BitMono(Object kernel) throws IOException {
        if (kernel instanceof float[][]) {
            float[][] floatData = (float[][]) kernel;
            int height = floatData.length;
            int width = floatData[0].length;
            short[][] shortData = new short[height][width];

            float maxVal = -Float.MAX_VALUE;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (floatData[y][x] > maxVal) maxVal = floatData[y][x];
                }
            }
            // Standard FITS floats are 0.0 to 1.0, but hot pixels/bright stars
            // frequently overshoot to values like 1.14 or 2.5.
            float scaleFactor = (maxVal <= 10.0f && maxVal > 0.0f) ? 65535.0f : 1.0f;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float trueVal = floatData[y][x] * scaleFactor;
                    if (trueVal < 0) trueVal = 0;
                    if (trueVal > 65535) trueVal = 65535;

                    // The FITS Shift: Map 0...65535 down to -32768...32767
                    int shiftedVal = Math.round(trueVal) - 32768;
                    shortData[y][x] = (short) shiftedVal;
                }
            }
            return shortData;
        } else if (kernel instanceof int[][]) {
            int[][] intData = (int[][]) kernel;
            int height = intData.length;
            int width = intData[0].length;
            short[][] shortData = new short[height][width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int trueVal = intData[y][x];
                    if (trueVal < 0) trueVal = 0;
                    if (trueVal > 65535) trueVal = 65535;

                    // The FITS Shift
                    shortData[y][x] = (short) (trueVal - 32768);
                }
            }
            return shortData;
        }
        throw new IOException("Unsupported FITS format for Mono Standardization");
    }

    /**
     * Converts supported color kernels into a 16-bit signed-short RGB cube suitable for FITS
     * export and later luminance extraction.
     */
    private short[][][] standardizeTo16BitColor(Object kernel) throws IOException {
        if (kernel instanceof float[][][]) {
            float[][][] floatData = (float[][][]) kernel;
            int depth = floatData.length;
            int height = floatData[0].length;
            int width = floatData[0][0].length;
            short[][][] shortData = new short[depth][height][width];

            float maxVal = -Float.MAX_VALUE;
            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        if (floatData[z][y][x] > maxVal) maxVal = floatData[z][y][x];
                    }
                }
            }
            // Standard FITS floats are 0.0 to 1.0, but hot pixels/bright stars
            // frequently overshoot to values like 1.14 or 2.5.
            float scaleFactor = (maxVal <= 10.0f && maxVal > 0.0f) ? 65535.0f : 1.0f;

            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        float trueVal = floatData[z][y][x] * scaleFactor;
                        if (trueVal < 0) trueVal = 0;
                        if (trueVal > 65535) trueVal = 65535;

                        // The FITS Shift
                        int shiftedVal = Math.round(trueVal) - 32768;
                        shortData[z][y][x] = (short) shiftedVal;
                    }
                }
            }
            return shortData;
        } else if (kernel instanceof int[][][]) {
            int[][][] intData = (int[][][]) kernel;
            int depth = intData.length;
            int height = intData[0].length;
            int width = intData[0][0].length;
            short[][][] shortData = new short[depth][height][width];
            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int trueVal = intData[z][y][x];
                        if (trueVal < 0) trueVal = 0;
                        if (trueVal > 65535) trueVal = 65535;

                        // The FITS Shift
                        shortData[z][y][x] = (short) (trueVal - 32768);
                    }
                }
            }
            return shortData;
        }
        throw new IOException("Unsupported FITS format for Color Standardization");
    }

    /**
     * Produces a simple luminance plane from a 16-bit RGB cube using an equal-channel average.
     */
    private short[][] extractLuminance(short[][][] color16) {
        int height = color16[0].length;
        int width = color16[0][0].length;
        short[][] monoData = new short[height][width];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Standard algebraic average safely maintains the FITS BZERO shift
                int r = color16[0][y][x];
                int g = color16[1][y][x];
                int b = color16[2][y][x];
                monoData[y][x] = (short) ((r + g + b) / 3);
            }
        }
        return monoData;
    }

    /**
     * Converts an in-memory 16-bit color cube to a mono plane without changing the FITS container.
     */
    private Object convertToMono(Object kernelData) throws FitsException {
        if (kernelData instanceof short[][][]) {
            short[][][] data = (short[][][]) kernelData;
            short[][] monoData = new short[data[0].length][data[0][0].length];

            for (int i = 0; i < data[0].length; i++) {
                for (int j = 0; j < data[0][i].length; j++) {
                    short val1 = data[0][i][j];
                    short val2 = data[1][i][j];
                    short val3 = data[2][i][j];

                    int average = ((val1 + val2 + val3) / 3);
                    monoData[i][j] = (short) average;
                }
            }
            return monoData;

        } else {
            throw new FitsException("Cannot convert to mono. Expected 16-bit color (short[][][]), but received type=" + kernelData.getClass().getName());
        }
    }

    /**
     * Applies the selected stretch algorithm directly to a FITS image in memory.
     */
    public void stretchFITSImage(Fits fitsImage, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException, IOException {
        BasicHDU<?> hdu = getImageHDU(fitsImage);
        Object kernelData = hdu.getKernel();

        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;
            short[][] stretchedData = (short[][]) stretchImageData(data, stretchFactor, iterations, data[0].length, data.length, algo);
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

            short[][] stretchedRedData = (short[][]) stretchImageData(data[0], stretchFactor, iterations, data[0][0].length, data[0].length, algo);
            short[][] stretchedGreenData = (short[][]) stretchImageData(data[1], stretchFactor, iterations, data[1][0].length, data[1].length, algo);
            short[][] stretchedBlueData = (short[][]) stretchImageData(data[2], stretchFactor, iterations, data[2][0].length, data[2].length, algo);

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

                    data[0][i][j] = (short) (stretchedRedData[i][j]);
                    data[1][i][j] = (short) (stretchedGreenData[i][j]);
                    data[2][i][j] = (short) (stretchedBlueData[i][j]);
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

    /**
     * Builds a quick-look preview image from raw FITS kernel data without applying any stretch.
     */
    public BufferedImage getImagePreview(Object kernelData) throws FitsException {
        BufferedImage ret = new BufferedImage(350, 350, BufferedImage.TYPE_INT_ARGB);

        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;

            int imageHeight = data.length;
            int imageWidth = data[0].length;

            if (imageWidth > 350) imageWidth = 350;
            if (imageHeight > 350) imageHeight = 350;

            for (int i = 0; i < imageHeight; i++) {
                for (int j = 0; j < imageWidth; j++) {
                    int convertedValue = ((int) data[i][j]) + ((int) Short.MAX_VALUE);
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

            if (imageWidth > 350) imageWidth = 350;
            if (imageHeight > 350) imageHeight = 350;

            for (int i = 0; i < imageHeight; i++) {
                for (int j = 0; j < imageWidth; j++) {
                    int convertedValueR = ((int) data[0][i][j]) + ((int) Short.MAX_VALUE) + 1;
                    float intensityR = ((float) convertedValueR) / (2 * (float) Short.MAX_VALUE);

                    int convertedValueG = ((int) data[1][i][j]) + ((int) Short.MAX_VALUE) + 1;
                    float intensityG = ((float) convertedValueG) / (2 * (float) Short.MAX_VALUE);

                    int convertedValueB = ((int) data[2][i][j]) + ((int) Short.MAX_VALUE) + 1;
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

    /**
     * Renders a stretched preview image at the requested output size from raw FITS kernel data.
     */
    private BufferedImage getStretchedImage(Object kernelData, int width, int height, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException {
        BufferedImage ret = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;

            int imageHeight = data.length;
            int imageWidth = data[0].length;

            if (imageWidth > width) imageWidth = width;
            if (imageHeight > height) imageHeight = height;

            short[][] stretchedData = (short[][]) stretchImageData(data, stretchFactor, iterations, imageWidth, imageHeight, algo);

            for (int i = 0; i < imageHeight; i++) {
                for (int j = 0; j < imageWidth; j++) {
                    int absValue = ((int) stretchedData[i][j]) + ((int) Short.MAX_VALUE) + 1;
                    if (absValue > 2 * Short.MAX_VALUE) absValue = 2 * Short.MAX_VALUE;
                    float intensity = (((float) absValue) / ((float) (2 * Short.MAX_VALUE)));
                    try {
                        ret.setRGB(j, i, new Color(intensity, intensity, intensity, 1.0f).getRGB());
                    } catch (IllegalArgumentException e) {
                        throw (e);
                    }
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

            if (imageWidth > width) imageWidth = width;
            if (imageHeight > height) imageHeight = height;

            for (int i = 0; i < imageHeight; i++) {
                for (int j = 0; j < imageWidth; j++) {
                    int absValueRed = ((int) stretchedDataRed[i][j]) + ((int) Short.MAX_VALUE) + 1;
                    if (absValueRed > 2 * Short.MAX_VALUE) absValueRed = 2 * Short.MAX_VALUE;
                    float intensityRed = (((float) absValueRed) / ((float) (2 * Short.MAX_VALUE)));

                    int absValueGreen = ((int) stretchedDataGreen[i][j]) + ((int) Short.MAX_VALUE) + 1;
                    if (absValueGreen > 2 * Short.MAX_VALUE) absValueGreen = 2 * Short.MAX_VALUE;
                    float intensityGreen = (((float) absValueGreen) / ((float) (2 * Short.MAX_VALUE)));

                    int absValueBlue = ((int) stretchedDataBlue[i][j]) + ((int) Short.MAX_VALUE) + 1;
                    if (absValueBlue > 2 * Short.MAX_VALUE) absValueBlue = 2 * Short.MAX_VALUE;
                    float intensityBlue = (((float) absValueBlue) / ((float) (2 * Short.MAX_VALUE)));

                    if (algo.equals(StretchAlgorithm.EXTREME)) {
                        float maxValue = absValueRed;
                        if (maxValue < absValueGreen) maxValue = absValueGreen;
                        if (maxValue < absValueBlue) maxValue = absValueBlue;

                        intensityRed = (((float) maxValue) / ((float) (2 * Short.MAX_VALUE)));
                        intensityGreen = intensityRed;
                        intensityBlue = intensityRed;
                    }

                    try {
                        Color targetColor = new Color(intensityRed, intensityGreen, intensityBlue, 1.0f);
                        ret.setRGB(j, i, targetColor.getRGB());
                    } catch (IllegalArgumentException e) {
                        throw (e);
                    }
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

    /**
     * Convenience wrapper that renders a 350x350 stretched preview.
     */
    public BufferedImage getStretchedImagePreview(Object kernelData, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException {
        return getStretchedImage(kernelData, 350, 350, stretchFactor, iterations, algo);
    }

    /**
     * Convenience wrapper that renders a stretched preview at the caller's requested size.
     */
    public BufferedImage getStretchedImageFullSize(Object kernelData, int width, int height, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException {
        return getStretchedImage(kernelData, width, height, stretchFactor, iterations, algo);
    }

    /**
     * Dispatches raw image data to the concrete stretch implementation selected by the caller.
     */
    private Object stretchImageData(Object kernelData, int intensity, int iterations, int width, int height, StretchAlgorithm algo) throws FitsException {
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

    /**
     * Applies an aggressive repeated multiplicative stretch intended to quickly brighten faint
     * detail in mono data.
     */
    private Object stretchImageEnhanceHigh(Object kernelData, int intensity, int iterations, int width, int height) throws FitsException {
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
                        int absValue = (int) returnData[i][j] - (int) Short.MIN_VALUE;
                        float newValue = (float) absValue * ((float) 1 + ((float) intensity / (float) 100));
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

                int minimumValueDistanceFromZero = (int) minimumValue - (int) Short.MIN_VALUE;
                if (minimumValueDistanceFromZero > 2 * (int) Short.MAX_VALUE) {
                    minimumValueDistanceFromZero = 2 * (int) Short.MAX_VALUE;
                }

                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        returnData[i][j] = (short) ((int) returnData[i][j] - minimumValueDistanceFromZero);
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

    /**
     * Applies a softer adaptive stretch that favors dim regions more than already bright pixels.
     */
    private Object stretchImageEnhanceLow(Object kernelData, int intensity, int iterations, int width, int height) throws FitsException {
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
                        int absValue = (int) returnData[i][j] - (int) Short.MIN_VALUE;
                        float scale = 1 - (((float) absValue) / (2 * ((float) Short.MAX_VALUE)));
                        float newValue = (float) absValue * ((float) 1 + ((((float) intensity / (float) 100)) * scale));
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

                int minimumValueDistanceFromZero = (int) minimumValue - (int) Short.MIN_VALUE;
                if (minimumValueDistanceFromZero > 2 * (int) Short.MAX_VALUE) {
                    minimumValueDistanceFromZero = 2 * (int) Short.MAX_VALUE;
                }
                int maximumValueDistanceFromMax = (int) Short.MAX_VALUE - (int) maximumValue;
                if (maximumValueDistanceFromMax > 2 * (int) Short.MAX_VALUE) {
                    maximumValueDistanceFromMax = 2 * (int) Short.MAX_VALUE;
                }

                float stretchCoefficient = 1 + (((float) maximumValueDistanceFromMax) / (2 * (float) Short.MAX_VALUE));

                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        int absValue = (int) returnData[i][j] - (int) Short.MIN_VALUE - minimumValueDistanceFromZero;
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

    /**
     * Isolates only the strongest excursions above the average background for a high-contrast
     * detection-style preview.
     */
    private Object stretchImageEnhanceExtreme(Object kernelData, int threshold, int intensity, int width, int height) throws FitsException {
        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;
            short[][] returnData = new short[height][width];

            long allPixelSumValue = 0;
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    allPixelSumValue += (data[i][j] - (int) Short.MIN_VALUE);
                }
            }

            float averageNoiseLevel = ((float) allPixelSumValue) / ((float) width * height);

            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    returnData[i][j] = data[i][j];
                    int absValue = (int) returnData[i][j] - (int) Short.MIN_VALUE;

                    if (absValue >= averageNoiseLevel + 10 * threshold) {
                        float newValue = (((float) intensity) / (float) 20) * (2 * ((float) Short.MAX_VALUE));
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

    /**
     * Applies an asinh stretch using histogram-derived black and white points for a more
     * photographic preview.
     */
    private Object stretchImageAsinh(Object kernelData, int blackPointPercent, int stretchStrength, int width, int height) throws FitsException {
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

    /**
     * Small math helper for the asinh stretch implementation.
     */
    private static double asinh(double value) {
        return Math.log(value + Math.sqrt((value * value) + 1.0));
    }

    /**
     * Resolves a percentile from a histogram without needing to sort the full image sample set.
     */
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

    /**
     * Creates a timestamped sibling directory for detection exports and returns its location.
     */
    public static File createDetectionsDirectory(File anyFitsFile) {
        File parentDir = anyFitsFile.getParentFile();
        if (parentDir == null) {
            parentDir = new File(System.getProperty("user.dir"));
        }

        // Generate a sortable timestamp
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        String timestamp = java.time.LocalDateTime.now().format(formatter);

        // Create the uniquely named detections directory
        File detectionsDir = new File(parentDir, "detections_" + timestamp);

        if (!detectionsDir.exists()) {
            boolean created = detectionsDir.mkdirs();
            if (created) {
                System.out.println("Created new export directory at: " + detectionsDir.getAbsolutePath());
            } else {
                System.err.println("Failed to create export directory. Check folder permissions.");
            }
        }

        return detectionsDir;
    }
}
