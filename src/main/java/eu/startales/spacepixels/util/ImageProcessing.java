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

import io.github.ppissias.jplatesolve.PlateSolveResult;
import io.github.ppissias.jplatesolve.PlateSolveOperation;

import nom.tam.fits.*;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.ApplicationSettings;

import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Internal facade over one imported folder of frames: import validation, image conversion, previews and stretches,
 * and plate solving, each delegated to a focused service. Detection does not pass through it: Detect Moving Targets,
 * Detect Iteratively and the API run {@link StandardDetectionPipelineService} themselves.
 */
public class ImageProcessing implements AutoCloseable {
    public static final int MIN_USABLE_FRAMES_FOR_MULTI_FRAME_ANALYSIS =
            DetectionPipelineSupport.MIN_USABLE_FRAMES_FOR_MULTI_FRAME_ANALYSIS;

    private final FitsImportService importService;
    private final FitsBatchService batchService;

    private final AppConfig appConfig;
    private final ApplicationSettings applicationSettings;
    private final PlateSolveService plateSolveService;
    private final FitsVisualizationRenderer fitsVisualizationRenderer;

    /**
     * Opens a new, caller-owned noninteractive processing facade bound to an aligned FITS directory.
     * Loads standalone preferences; desktop import injects settings and explicit interaction instead.
     * The caller closes the facade to release its import and solver services; it retains any returned metadata.
     */
    public static synchronized ImageProcessing openProcessor(File alignedFitsFolderFullPath) throws IOException, FitsException {
        return openProcessor(alignedFitsFolderFullPath, ApplicationSettings.loadDefault());
    }

    /** Opens a new caller-owned facade sharing the supplied application settings; closing it releases its services. */
    public static ImageProcessing openProcessor(File folder, ApplicationSettings settings) throws IOException, FitsException {
        return openProcessor(folder, settings, null);
    }

    /** Opens a new caller-owned facade with explicit import decisions; null interaction uses strict headless validation. */
    public static ImageProcessing openProcessor(File folder, ApplicationSettings settings,
                                              FitsImportInteraction interaction) throws IOException, FitsException {
        return new ImageProcessing(folder, settings, interaction);
    }

    @Override
    public void close() {
        importService.close();
        plateSolveService.close();
    }

    /**
     * Initializes the processor and loads persisted app-level preferences if they exist.
     */
    private ImageProcessing(File alignedFitsFolderFullPath, ApplicationSettings settings, FitsImportInteraction interaction) throws IOException, FitsException {
        this.applicationSettings = Objects.requireNonNull(settings);
        this.appConfig = settings.getConfig();
        this.plateSolveService = new PlateSolveService(this.appConfig);
        this.fitsVisualizationRenderer = new FitsVisualizationRenderer();
        this.batchService = new FitsBatchService(alignedFitsFolderFullPath, fitsVisualizationRenderer);
        this.importService = new FitsImportService(alignedFitsFolderFullPath, batchService, interaction);
    }

    // =========================================================================
    // HDU HELPER METHODS (Immune to empty primary HDUs and Compression)
    // =========================================================================

    /**
     * Iterates through the FITS structure to find the actual Image HDU.
     * Safely decompresses .fz files and skips empty primary headers.
     */
    public static BasicHDU<?> getImageHDU(Fits fits) throws FitsException, IOException {
        return FitsImages.getImageHDU(fits);
    }

// =========================================================================
    // NEW BATCH PROCESSING METHODS (Called by UI)
    // =========================================================================

    /**
     * Converts all color images in the folder to monochrome and returns the generated mono
     * directory so the UI can optionally import it immediately.
     *
     * <p>If stretch is enabled, stretched mono derivatives are also written alongside the mono
     * outputs.</p>
     */
    public File batchConvertToMono(boolean stretch, int stretchFactor, int iterations, StretchAlgorithm algo, TransientEngineProgressListener progressListener) throws IOException, FitsException {
        return batchService.batchConvertToMono(stretch, stretchFactor, iterations, algo, progressListener);
    }

    /**
     * Stretches all images in the folder, regardless of whether they are color or mono.
     */
    public void batchStretch(int stretchFactor, int iterations, StretchAlgorithm algo, TransientEngineProgressListener progressListener) throws IOException, FitsException {
        batchService.batchStretch(stretchFactor, iterations, algo, progressListener);
    }

// =========================================================================
    // UPDATED FITS FILE INFORMATION (The Import Gatekeeper)
    // =========================================================================

    /**
     * Reads and validates metadata. Explicit interaction checks sequence consistency before offering conversions;
     * without interaction, readiness is checked first and unsupported inputs are rejected without derivatives.
     * Each call returns a fresh caller-owned array; the facade keeps no frames. Run this file work on a worker.
     */
    public FitsFileInformation[] readFiles() throws Exception {
        return importService.readFiles();
    }

    public PlateSolveOperation startSolve(String path, boolean astap, boolean astrometry,
            Consumer<PlateSolveOperation.Progress> progress) throws IOException {
        return plateSolveService.startSolve(path, astap, astrometry, progress);
    }

    /**
     * What to tell the user when an online solve fails: a lost connection and a refused login are named, for the
     * Astrometry.net account in use.
     */
    public String astrometryNetFailure(Throwable failure) {
        return plateSolveService.astrometryNetFailure(failure);
    }

    /**
     * Safely injects Plate Solve WCS coordinates directly into the original FITS header.
     * Uses a temporary file swap to prevent data corruption.
     */
    public Map<String, String> updateFitsHeaderWithWCS(String fitsFileFullPath, Map<String, String> wcsData) throws Exception {
        return plateSolveService.updateFitsHeaderWithWCS(fitsFileFullPath, wcsData);
    }

    public void cleanupSolveArtifacts(String fitsFileFullPath, PlateSolveResult result) {
        plateSolveService.cleanupSolveArtifacts(fitsFileFullPath, result);
    }

    /**
     * Exposes the mutable application preferences currently associated with this processor.
     */
    public AppConfig getAppConfig() {
        return appConfig;
    }

    /** The interactive conversion's negative-value question. */
    static String negativeValueQuestion(FitsPixelConverter.NegativeValueScan scan, int offset) {
        return FitsImportService.negativeValueQuestion(scan, offset);
    }

    /** Offers to copy the largest exposure group; returns null when declined or no interaction is supplied. */
    public static File offerToImportLargestExposureGroup(MixedExposureException mixed,
                                                        FitsImportInteraction interaction) throws IOException {
        return interaction == null ? null : FitsImportService.offerToImportLargestExposureGroup(mixed, interaction);
    }

    /** The warning {@link #offerToImportLargestExposureGroup} shows. */
    static String mixedExposureQuestion(MixedExposureException mixed) {
        return FitsImportService.mixedExposureQuestion(mixed);
    }

    /**
     * Builds a quick-look preview image from raw FITS kernel data without applying any stretch.
     */
    public BufferedImage getImagePreview(Object kernelData) throws FitsException {
        return fitsVisualizationRenderer.getImagePreview(kernelData);
    }

    /**
     * Convenience wrapper that renders a 350x350 stretched preview.
     */
    public BufferedImage getStretchedImagePreview(Object kernelData, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException {
        return fitsVisualizationRenderer.getStretchedImagePreview(kernelData, stretchFactor, iterations, algo);
    }

    /**
     * Convenience wrapper that renders a stretched preview at the caller's requested size.
     */
    public BufferedImage getStretchedImageFullSize(Object kernelData, int width, int height, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException {
        return fitsVisualizationRenderer.getStretchedImageFullSize(kernelData, width, height, stretchFactor, iterations, algo);
    }

    /**
     * Creates a new timestamped sibling directory for detection exports and returns its location. It is never the
     * folder of another run, even when two runs start in the same second.
     */
    public static File createDetectionsDirectory(File anyFitsFile) {
        File parentDir = anyFitsFile.getParentFile();
        if (parentDir == null) {
            parentDir = new File(System.getProperty("user.dir"));
        }
        File detectionsDir = OutputFolders.createNew(parentDir, "detections_");
        DebugOutput.println("Created new export directory at: " + detectionsDir.getAbsolutePath());
        return detectionsDir;
    }
}
