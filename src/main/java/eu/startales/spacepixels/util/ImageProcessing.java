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

        /**
         * The warning shown when a run returns many detections, grouped by kind of result. Slow-mover candidates
         * (maximum stack) and local rescue candidates (residual analysis of leftover transients) are different
         * analyses and are listed separately.
         *
         * @param plateSolved whether a plate-solved frame gives the report sky coordinates
         * @param limit the count above which the warning is shown
         */
        public String warningMessage(boolean plateSolved, int limit) {
            String photometry;
            if (photometryVerdict == null || "NOT_RUN".equals(photometryVerdict)) {
                photometry = "off";
            } else if ("NOT_READY".equals(photometryVerdict)) {
                photometry = "session not ready for photometry (see the report)";
            } else {
                photometry = variableStarCandidates + " candidates, " + possibleVariableStars + " possible";
            }
            return "The engine found " + totalDetections + " detections, more than the usual " + limit + ".\n\n"
                    + "Moving-object tracks: " + movingTargets + "\n"
                    + "Streak tracks (multi-frame): " + streakTracks + "\n"
                    + "Single streaks: " + singleStreaks + "\n"
                    + "Single-frame anomalies: " + anomalies + "\n"
                    + "Slow-mover candidates (maximum stack): " + slowMoverCandidates + "\n"
                    + "Local rescue candidates: " + localRescueCandidates + "\n"
                    + "Local activity clusters: " + localActivityClusters + "\n"
                    + "Common-motion groups: " + commonMotionGroups + "\n"
                    + "Camera-fixed groups (hot pixels, dust): " + cameraFixedGroups + "\n"
                    + "Still unclassified, not counted above: " + unclassifiedDetections + "\n"
                    + "Variable stars: " + photometry + "\n"
                    + "Astrometry: " + (plateSolved
                    ? "a plate-solved frame is available, so the report has sky coordinates and online identification."
                    : "no plate-solved frame, so the report has no sky coordinates or identification.") + "\n\n"
                    + "Largest group: " + largestGroup() + ".\n"
                    + "Generating image crops, GIFs and the HTML report for this many objects takes a long time and uses a lot of disk space.\n"
                    + "Many tracks or anomalies usually mean the Detection Sigma is too low and noise was linked.\n\n"
                    + "Do you want to generate the report anyway?";
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
        private final PipelineResult pipelineResult;
        private final DetectionConfig effectiveConfig;
        private final FitsFileInformation[] filesInformation;
        private final List<short[][]> rawFramesForExport;
        private final long pipelineDurationMillis;

        PipelineExecutionData(PipelineResult pipelineResult,
                              DetectionConfig effectiveConfig,
                              FitsFileInformation[] filesInformation,
                              List<short[][]> rawFramesForExport,
                              long pipelineDurationMillis) {
            this.pipelineResult = pipelineResult;
            this.effectiveConfig = effectiveConfig == null ? null : effectiveConfig.clone();
            this.filesInformation = filesInformation == null ? new FitsFileInformation[0] : filesInformation.clone();
            this.rawFramesForExport = Collections.unmodifiableList(new ArrayList<>(rawFramesForExport));
            this.pipelineDurationMillis = pipelineDurationMillis;
        }

        public PipelineResult getPipelineResult() {
            return pipelineResult;
        }

        public DetectionConfig getEffectiveConfig() {
            return effectiveConfig == null ? null : effectiveConfig.clone();
        }

        public FitsFileInformation[] getFilesInformation() {
            return filesInformation.clone();
        }

        public List<short[][]> getRawFramesForExport() {
            return rawFramesForExport;
        }

        public long getPipelineDurationMillis() {
            return pipelineDurationMillis;
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
    private final IterativeDetectionPipelineService iterativeDetectionPipelineService;
    private final FitsVisualizationRenderer fitsVisualizationRenderer;

    public SessionFrames getSessionFrames() { return sessionFrames; }

    /**
     * Creates a noninteractive processing facade bound to an aligned FITS directory.
     * Desktop import decisions require the overload accepting a FitsImportInteraction.
     */
    public static synchronized ImageProcessing getInstance(File alignedFitsFolderFullPath) throws IOException, FitsException {
        return getInstance(alignedFitsFolderFullPath, ApplicationSettings.loadDefault());
    }

    /** Creates a noninteractive facade using the caller's settings. */
    public static ImageProcessing getInstance(File folder, ApplicationSettings settings) throws IOException, FitsException {
        return getInstance(folder, settings, null);
    }

    /** Creates a facade with explicit import decisions; null uses strict, noninteractive validation. */
    public static ImageProcessing getInstance(File folder, ApplicationSettings settings,
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
        this.standardDetectionPipelineService = new StandardDetectionPipelineService(this.appConfig, sessionFrames);
        this.iterativeDetectionPipelineService = new IterativeDetectionPipelineService(this.appConfig);
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
     * Runs the standard detection pipeline on the full cached frame set and exports the HTML
     * session report plus associated visualizations.
     */
    public File detectObjects(DetectionConfig config, DetectionSafetyPrompt safetyPrompt, TransientEngineProgressListener progressListener) throws Exception {
        if (sessionFrames.getFiles() == null) getFitsfileInformation();
        return standardDetectionPipelineService.detectObjects(config, sessionFrames.getFiles(), safetyPrompt, progressListener);
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
     * Runs the standard detection pipeline and returns the in-memory engine output without writing
     * any report artifacts to disk.
     */
    public PipelineExecutionData runDetectionPipeline(DetectionConfig config, TransientEngineProgressListener progressListener) throws Exception {
        if (sessionFrames.getFiles() == null) getFitsfileInformation();
        return standardDetectionPipelineService.runDetectionPipeline(config, sessionFrames.getFiles(), progressListener);
    }

    /**
     * Exports the HTML report and visualization bundle for a previously executed pipeline run.
     */
    public File exportDetectionReport(PipelineExecutionData executionData) throws IOException {
        return exportDetectionReport(executionData, null);
    }

    /** As {@link #exportDetectionReport(PipelineExecutionData)}, telling {@code progressListener} 0-99% as it writes. */
    public File exportDetectionReport(PipelineExecutionData executionData, TransientEngineProgressListener progressListener) throws IOException {
        return standardDetectionPipelineService.exportDetectionReport(executionData, progressListener);
    }

// =========================================================================
// ITERATIVE SLOW-MOVER PIPELINE
// =========================================================================

    /**
     * Re-runs detection on progressively larger time-spaced subsets so slow movers can be explored
     * across several pass sizes, with an independent ordinary pipeline run for each subset.
     */
    public File detectSlowObjectsIterative(DetectionConfig config, DetectionSafetyPrompt safetyPrompt, TransientEngineProgressListener progressListener, int maxFramesLimit) throws Exception {
        if (sessionFrames.getFiles() == null) getFitsfileInformation();
        return iterativeDetectionPipelineService.detectSlowObjectsIterative(
                config,
                sessionFrames.getFiles(),
                safetyPrompt,
                progressListener,
                maxFramesLimit);
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

    /**
     * Writes the current application preferences back to the user's config file.
     */
    public void saveAppConfig() throws IOException {
        applicationSettings.save();
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
     * Applies the selected stretch algorithm directly to a FITS image in memory.
     */
    public void stretchFITSImage(Fits fitsImage, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException, IOException {
        fitsVisualizationRenderer.stretchFitsImage(fitsImage, stretchFactor, iterations, algo);
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
