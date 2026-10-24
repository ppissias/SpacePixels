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

import eu.startales.spacepixels.config.DetectionRunOptions;
import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.util.reporting.ReportRunContext;
import io.github.ppissias.jplatesolve.PlateSolveResult;
import io.github.ppissias.jplatesolve.PlateSolveOperation;

import nom.tam.fits.*;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.session.SessionFrames;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * High-level FITS processing facade for SpacePixels.
 *
 * <p>This class coordinates FITS import validation, image conversion, preview/stretch generation,
 * report handoff, and the JTransient detection pipeline. Specialized WCS/plate-solving behavior is
 * delegated to focused collaborators so the public processing API can remain stable while the
 * implementation is split into smaller responsibilities.</p>
 */
public class ImageProcessing implements AutoCloseable {
    public static final int MIN_USABLE_FRAMES_FOR_MULTI_FRAME_ANALYSIS =
            DetectionPipelineSupport.MIN_USABLE_FRAMES_FOR_MULTI_FRAME_ANALYSIS;

    /** Caller-supplied report decision after detection; implementations may set report-only inclusion. */
    @FunctionalInterface
    public interface DetectionSafetyPrompt {
        /** Returns whether export may proceed for this summary. */
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
        public final int slowMoverCandidates;
        public final int localRescueCandidates;
        public final int localActivityClusters;
        /** Compact sources that share one displacement. Included in {@link #totalDetections}. */
        public final int commonMotionGroups;
        /** Detections that stay at one camera position (hot pixels, dust). Included in {@link #totalDetections}. */
        public final int cameraFixedGroups;
        /** Leftover detections. Shown in the summary, and not added to {@link #totalDetections}. */
        public final int unclassifiedDetections;
        /** Photometry verdict ("READY", "LIMITED", "NOT_READY") or null when variable-star detection did not run. */
        public final String photometryVerdict;
        public final int variableStarCandidates;
        public final int possibleVariableStars;
        /** Groups to write. The warning dialog replaces this when the user leaves some out. */
        private ReportInclusion reportInclusion = ReportInclusion.all();

        /** Captures measured counts; report inclusion starts with every optional group enabled. */
        public DetectionSummary(int totalDetections,
                         int singleStreaks,
                         int streakTracks,
                         int movingTargets,
                         int anomalies,
                         int slowMoverCandidates,
                         int localRescueCandidates,
                         int localActivityClusters,
                         String photometryVerdict,
                         int variableStarCandidates,
                         int possibleVariableStars,
                         int commonMotionGroups,
                         int unclassifiedDetections,
                         int cameraFixedGroups) {
            this.totalDetections = totalDetections;
            this.singleStreaks = singleStreaks;
            this.streakTracks = streakTracks;
            this.movingTargets = movingTargets;
            this.anomalies = anomalies;
            this.slowMoverCandidates = slowMoverCandidates;
            this.localRescueCandidates = localRescueCandidates;
            this.localActivityClusters = localActivityClusters;
            this.photometryVerdict = photometryVerdict;
            this.variableStarCandidates = variableStarCandidates;
            this.possibleVariableStars = possibleVariableStars;
            this.commonMotionGroups = commonMotionGroups;
            this.unclassifiedDetections = unclassifiedDetections;
            this.cameraFixedGroups = cameraFixedGroups;
        }

        /** Which optional groups this report should contain. All of them, unless the warning dialog said otherwise. */
        public ReportInclusion reportInclusion() {
            return reportInclusion;
        }

        /** Replaces report-only inclusion choices without mutating detection settings or measured results. */
        public void includeInReport(ReportInclusion inclusion) {
            reportInclusion = Objects.requireNonNull(inclusion);
        }

        /** A checkbox is offered only for a group the detection ran and that found something. */
        public static boolean offerInReport(boolean featureEnabled, int count) {
            return featureEnabled && count > 0;
        }

        /** Describes the largest classified group, preserving the existing tie order. */
        public String largestGroup() {
            String[] names = {"moving objects", "single-frame anomalies", "slow-mover candidates",
                    "residual analysis", "common-motion groups", "camera-fixed groups"};
            int[] counts = {movingTargets + streakTracks + singleStreaks, anomalies,
                    slowMoverCandidates, localRescueCandidates + localActivityClusters, commonMotionGroups,
                    cameraFixedGroups};
            int best = 0;
            for (int i = 1; i < counts.length; i++) {
                if (counts[i] > counts[best]) {
                    best = i;
                }
            }
            return names[best] + " (" + counts[best] + ")";
        }
    }

    /**
     * Immutable payload returned after the JTransient engine completes but before any optional
     * report export starts.
     */
    public static final class PipelineExecutionData {
        private final ReportRunContext reportContext;
        private final PipelineResult pipelineResult;
        private final DetectionConfig effectiveConfig;
        private final FitsFileInformation[] filesInformation;
        private final List<short[][]> rawFramesForExport;

        PipelineExecutionData(PipelineResult pipelineResult,
                              DetectionConfig effectiveConfig,
                              FitsFileInformation[] filesInformation,
                              List<short[][]> rawFramesForExport,
                              ReportRunContext reportContext) {
            this.reportContext = java.util.Objects.requireNonNull(reportContext);
            this.pipelineResult = pipelineResult;
            this.effectiveConfig = effectiveConfig == null ? null : effectiveConfig.clone();
            this.filesInformation = filesInformation == null ? new FitsFileInformation[0] : filesInformation.clone();
            this.rawFramesForExport = Collections.unmodifiableList(new ArrayList<>(rawFramesForExport));
        }

        /** Returns the captured report options, metadata, WCS and catalogue owned by this execution. */
        public ReportRunContext getReportContext() {
            return reportContext;
        }

        public PipelineResult getPipelineResult() {
            return pipelineResult;
        }

        public DetectionConfig getEffectiveConfig() {
            return effectiveConfig == null ? null : effectiveConfig.clone();
        }

        public FitsFileInformation[] getFilesInformation() {
            return java.util.Arrays.stream(filesInformation).map(FitsFileInformation::snapshot).toArray(FitsFileInformation[]::new);
        }

        public List<short[][]> getRawFramesForExport() {
            return rawFramesForExport;
        }
    }

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

    private final SessionFrames sessionFrames = new SessionFrames();
    private final FitsImportService importService;
    private final FitsBatchService batchService;

    private final AppConfig appConfig;
    private final ApplicationSettings applicationSettings;
    private final PlateSolveService plateSolveService;
    private final StandardDetectionPipelineService standardDetectionPipelineService;
    private final FitsVisualizationRenderer fitsVisualizationRenderer;

    public SessionFrames getSessionFrames() { return sessionFrames; }

    /**
     * Opens a new, caller-owned noninteractive processing facade bound to an aligned FITS directory.
     * Loads standalone preferences; desktop import injects settings and explicit interaction instead.
     * The caller closes the facade to release its import/solver services and session buffers.
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
        try {
            importService.close();
            plateSolveService.close();
        } finally {
            // Also when the plate-solve client cannot confirm in time that it stopped.
            sessionFrames.clear();
        }
    }

    /**
     * Initializes the processor and loads persisted app-level preferences if they exist.
     */
    private ImageProcessing(File alignedFitsFolderFullPath, ApplicationSettings settings, FitsImportInteraction interaction) throws IOException, FitsException {
        this.applicationSettings = Objects.requireNonNull(settings);
        this.appConfig = settings.getConfig();
        this.plateSolveService = new PlateSolveService(this.appConfig);
        this.standardDetectionPipelineService = new StandardDetectionPipelineService(sessionFrames);
        this.fitsVisualizationRenderer = new FitsVisualizationRenderer();
        this.batchService = new FitsBatchService(alignedFitsFolderFullPath, fitsVisualizationRenderer);
        this.importService = new FitsImportService(alignedFitsFolderFullPath, sessionFrames, batchService, interaction);
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
    // THE MULTI-THREADED DETECTION PIPELINE
    // =========================================================================

    /**
     * Captures application options, with the visualization settings and the Auto-Tune frame limit of the detection
     * settings, before a desktop job is queued; no file access or image copying occurs.
     */
    public DetectionRunOptions captureRunOptions(VisualizationSettings visualization, int autoTuneMaxCandidateFrames) {
        return DetectionRunOptions.capture(appConfig, autoTuneMaxCandidateFrames).withVisualization(visualization);
    }

    /**
     * Runs the standard detection pipeline on the full cached frame set with the request's captured application
     * options and the session's sky data ({@code sky}; null reads the frames' folder), and exports the HTML session
     * report plus associated visualizations.
     */
    public File detectObjects(DetectionConfig config, DetectionSafetyPrompt safetyPrompt,
            TransientEngineProgressListener progressListener, DetectionRunOptions options, SkySnapshot sky) throws Exception {
        if (sessionFrames.getFiles() == null) getFitsfileInformation();
        return standardDetectionPipelineService.run(config, sessionFrames.getFiles(), safetyPrompt, progressListener, null, options, sky).report();
    }

    /** Tracks of the last detection run on these frames, or null when none has run. */
    public TrackOverlay getLastTrackOverlay() {
        return sessionFrames.getTracks();
    }

    /** Variable-star candidates of the last standard detection run, numbered as in its report. */
    public VariableStarOverlay getLastVariableStarOverlay() {
        return sessionFrames.getVariables();
    }

    /**
     * Runs the standard detection pipeline with explicit immutable application options and returns the in-memory
     * engine output without writing any report artifacts; the execution data carries the options into the export.
     */
    public PipelineExecutionData runDetectionPipeline(DetectionConfig config, TransientEngineProgressListener progressListener,
            DetectionRunOptions options) throws Exception {
        if (sessionFrames.getFiles() == null) getFitsfileInformation();
        // Headless runs have no session: the correction and the catalogue are read from the frames' folder once.
        return standardDetectionPipelineService.runDetectionPipeline(config, sessionFrames.getFiles(), progressListener, options, null);
    }

    /**
     * Exports the HTML report and visualization bundle for a previously executed pipeline run, telling
     * {@code progressListener} (which may be null) 0-99% as it writes.
     */
    public File exportDetectionReport(PipelineExecutionData executionData, TransientEngineProgressListener progressListener) throws IOException {
        return standardDetectionPipelineService.exportDetectionReport(executionData, progressListener);
    }

// =========================================================================
    // UPDATED FITS FILE INFORMATION (The Import Gatekeeper)
    // =========================================================================

    /** Whether any imported frame is plate-solved, which gives the report sky coordinates. */
    public boolean hasPlateSolvedFrame() {
        try {
            for (FitsFileInformation file : getImportedFiles()) {
                if (file.isWcsSolved()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // No frames: nothing is solved.
        }
        return false;
    }

    /**
     * The imported frames: the same objects the frame table shows and the detection run uses, so a header updated in
     * place (Plate Solve writes the WCS) reaches all of them. Reads the folder only when nothing is loaded yet; use
     * {@link #getFitsfileInformation()} only to import.
     */
    public FitsFileInformation[] getImportedFiles() throws Exception {
        return sessionFrames.getFiles() != null ? sessionFrames.getFiles() : getFitsfileInformation();
    }

    /**
     * Loads metadata, checking sequence consistency before readiness. Explicit interaction may offer conversions;
     * without it, unsupported inputs are rejected without dialogs or derivatives. Replaces the cached frames.
     */
    public FitsFileInformation[] getFitsfileInformation() throws Exception {
        return importService.getFitsfileInformation();
    }

    /**
     * Headless variant of FITS metadata loading used by CLI/batch flows that cannot prompt the
     * user for decompression or format-conversion decisions.
     */
    public FitsFileInformation[] getFitsfileInformationHeadless() throws Exception {
        return importService.getFitsfileInformationHeadless();
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
