/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.util.reporting.DetectionReportGenerator;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;

import java.io.File;
import java.io.IOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns the iterative slow-mover detection workflow that re-runs the engine on progressively larger
 * time-spaced subsets while keeping a shared master stack.
 */
final class IterativeDetectionPipelineService {

    private final AppConfig appConfig;
    private final StandardDetectionPipelineService standardDetectionPipelineService;

    IterativeDetectionPipelineService(AppConfig appConfig,
                                      StandardDetectionPipelineService standardDetectionPipelineService) {
        this.appConfig = appConfig;
        this.standardDetectionPipelineService = standardDetectionPipelineService;
    }

    File detectSlowObjectsIterative(DetectionConfig config,
                                    FitsFileInformation[] cachedFileInfo,
                                    ImageProcessing.DetectionSafetyPrompt safetyPrompt,
                                    TransientEngineProgressListener progressListener,
                                    int maxFramesLimit) throws Exception {
        long startTime = System.currentTimeMillis();
        int numFrames = cachedFileInfo.length;

        if (numFrames < 5) {
            DebugOutput.println("Not enough frames for iterative detection. Falling back to standard pipeline.");
            return standardDetectionPipelineService.detectObjects(config, cachedFileInfo, safetyPrompt, progressListener);
        }

        DebugOutput.println("\n--- Starting ITERATIVE PIPELINE for " + numFrames + " FITS files (On-Demand Loading) ---");

        File masterDir = createIterativeOutputDirectory(cachedFileInfo);
        List<DetectionReportGenerator.IterationSummary> summaries = new ArrayList<>();
        try {
            runPasses(config, cachedFileInfo, safetyPrompt, progressListener, maxFramesLimit, masterDir, summaries);
        } catch (Exception e) {
            if (Cancellation.isCancellation(e)) {
                keepFinishedPasses(masterDir, summaries);
            }
            throw e;
        }

        if (progressListener != null) {
            progressListener.onProgressUpdate(100, "All iterative passes complete!");
        }

        File indexFile = new File(masterDir, "index.html");
        try {
            DetectionReportGenerator.exportIterativeIndexReport(masterDir, summaries);
        } catch (IOException | RuntimeException e) {
            throw new IOException("The passes were written to " + masterDir.getAbsolutePath()
                    + ", but their index page could not be written: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        }
        DebugOutput.println("Total Iterative Pipeline Time: " + (System.currentTimeMillis() - startTime) + "ms");
        return indexFile;
    }

    /**
     * After a cancel: the passes that finished keep their reports, and the index lists them. With none finished,
     * the run's folder is removed.
     */
    private static void keepFinishedPasses(File masterDir, List<DetectionReportGenerator.IterationSummary> summaries) {
        if (summaries.isEmpty()) {
            OutputFolders.deleteUnfinished(masterDir);
            return;
        }
        try {
            DetectionReportGenerator.exportIterativeIndexReport(masterDir, summaries, "The run was cancelled after "
                    + summaries.size() + (summaries.size() == 1 ? " pass" : " passes") + ". The passes listed here are complete.");
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not write the index of the finished passes in " + masterDir.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /** Builds the master map, then runs the passes; each finished pass adds its summary to {@code summaries}. */
    private void runPasses(DetectionConfig config,
                           FitsFileInformation[] cachedFileInfo,
                           ImageProcessing.DetectionSafetyPrompt safetyPrompt,
                           TransientEngineProgressListener progressListener,
                           int maxFramesLimit,
                           File masterDir,
                           List<DetectionReportGenerator.IterationSummary> summaries) throws Exception {
        int numFrames = cachedFileInfo.length;
        int targetMaxLimit = resolveTargetMaxLimit(maxFramesLimit, numFrames);

        DebugOutput.println("\n--- Extracting Timestamps & Exposures for Temporal Spacing ---");
        long[] frameTimestamps = new long[numFrames];
        long[] frameExposures = new long[numFrames];
        boolean hasValidTime = true;
        for (int i = 0; i < numFrames; i++) {
            frameTimestamps[i] = cachedFileInfo[i].getObservationTimestamp();
            frameExposures[i] = cachedFileInfo[i].getExposureDurationMillis();
            if (frameTimestamps[i] == -1) {
                hasValidTime = false;
            }
        }

        DebugOutput.println("\n--- Generating Global Master Map from " + targetMaxLimit + " globally spaced frames ---");
        TransientEngineProgressListener masterListener = (percent, msg) -> {
            if (progressListener != null) {
                progressListener.onProgressUpdate(percent / 5, "Global Master Map: " + msg);
            }
        };

        short[][] providedMasterStack;
        JTransientEngine globalEngine = new JTransientEngine();
        try {
            List<ImageFrame> masterFrames = loadFramesForIndices(
                    cachedFileInfo,
                    frameTimestamps,
                    frameExposures,
                    sampleFrameIndices(frameTimestamps, hasValidTime, numFrames, targetMaxLimit));
            providedMasterStack = globalEngine.generateMasterStack(masterFrames, config, masterListener);
            masterFrames.clear();
            System.gc();
        } finally {
            globalEngine.shutdown();
        }

        int totalIterations = (int) Math.ceil((targetMaxLimit - 4.0) / 5.0);
        int currentIteration = 0;

        for (int k = 5; k <= targetMaxLimit; k += 5) {
            DebugOutput.println("\n>>> RUNNING ITERATION: " + k + " Frames (Time-Spaced)");

            final int basePercent = 20 + (int) (((float) currentIteration / totalIterations) * 80);
            final int nextBasePercent = 20 + (int) (((float) (currentIteration + 1) / totalIterations) * 80);
            final int currentK = k;
            TransientEngineProgressListener scaledListener = (enginePercent, message) -> {
                if (progressListener != null) {
                    int scaledPercent = basePercent + (int) ((enginePercent / 100.0f) * (nextBasePercent - basePercent));
                    progressListener.onProgressUpdate(scaledPercent, "Pass " + currentK + " frames: " + message);
                }
            };

            if (progressListener != null) {
                scaledListener.onProgressUpdate(0, "Loading " + k + " frames for engine...");
            }

            List<ImageFrame> spacedSubset = loadFramesForIndices(
                    cachedFileInfo,
                    frameTimestamps,
                    frameExposures,
                    sampleFrameIndices(frameTimestamps, hasValidTime, numFrames, k));
            DetectionPipelineSupport.logPipelineFrameTimingPayload("Iterative pass " + k, spacedSubset, cachedFileInfo);

            DetectionConfig effectiveConfig = DetectionPipelineSupport.createEffectiveDetectionConfig(config, spacedSubset.size());
            // Photometry needs the full session; time-spaced subsets would only repeat it on partial data.
            effectiveConfig.enableVariableStarDetection = false;

            PipelineResult result;
            JTransientEngine engine = new JTransientEngine();
            try {
                // The engine takes 0-90% of the pass; generating its report follows at 95%.
                TransientEngineProgressListener engineListener =
                        (percent, message) -> scaledListener.onProgressUpdate((int) (percent * 0.9), message);
                result = engine.runPipeline(spacedSubset, effectiveConfig, engineListener, providedMasterStack);
            } finally {
                engine.shutdown();
            }

            ImageProcessing.DetectionSummary detectionSummary = DetectionPipelineSupport.summarizeDetections(result);
            if (safetyPrompt != null && !safetyPrompt.shouldProceed(detectionSummary)) {
                DebugOutput.println("Iteration " + k + " aborted by UI callback due to high detection count. Stopping further iterations.");
                break;
            }

            if (progressListener != null) {
                scaledListener.onProgressUpdate(95, "Generating report (on-demand disk reads)...");
            }

            File iterationDir = new File(masterDir, k + "_frames");
            iterationDir.mkdirs();

            String reportFailure = null;
            try {
                // The pass's report takes 95-98% of the pass.
                DetectionReportGenerator.exportTrackVisualizations(
                        result,
                        createOnDemandRawFramesForExport(cachedFileInfo),
                        cachedFileInfo,
                        iterationDir,
                        effectiveConfig,
                        appConfig,
                        detectionSummary.reportInclusion(),
                        (percent, message) -> scaledListener.onProgressUpdate(95 + percent / 25, message));
            } catch (IOException | RuntimeException e) {
                if (Cancellation.isCancellation(e)) {
                    OutputFolders.deleteUnfinished(iterationDir);
                    throw e;
                }
                // The other passes go on; the index says that this pass has no report, and why.
                reportFailure = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                System.err.println("Failed to export visualization for iteration " + k + ": " + reportFailure);
            }

            int anomalyCount = result.anomalies == null ? 0 : result.anomalies.size();
            summaries.add(new DetectionReportGenerator.IterationSummary(k, k + "_frames", result.tracks.size(), anomalyCount,
                    reportFailure));

            currentIteration++;
        }
    }

    static List<Integer> sampleFrameIndices(long[] times, boolean hasValidTime, int maxLimit, int k) {
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
                if (indices.size() == k) {
                    return indices;
                }
            }
        }

        indices.clear();
        for (int i = 0; i < k; i++) {
            int index = (int) Math.round(i * (maxLimit - 1) / (double) (k - 1));
            indices.add(index);
        }
        return indices;
    }

    private static File createIterativeOutputDirectory(FitsFileInformation[] cachedFileInfo) {
        File parentDir = new File(cachedFileInfo[0].getFilePath()).getParentFile();
        if (parentDir == null) {
            parentDir = new File(System.getProperty("user.dir"));
        }
        return OutputFolders.createNew(parentDir, "iterative_detections_");
    }

    private static int resolveTargetMaxLimit(int maxFramesLimit, int numFrames) {
        int targetMaxLimit = (maxFramesLimit > 0 && maxFramesLimit < numFrames) ? maxFramesLimit : numFrames;
        if (targetMaxLimit < 5) {
            targetMaxLimit = 5;
        }
        return targetMaxLimit;
    }

    private static List<ImageFrame> loadFramesForIndices(FitsFileInformation[] cachedFileInfo,
                                                         long[] frameTimestamps,
                                                         long[] frameExposures,
                                                         List<Integer> indices) throws Exception {
        List<ImageFrame> frames = new ArrayList<>();
        for (int index : indices) {
            frames.add(loadFrame(cachedFileInfo, frameTimestamps, frameExposures, index));
        }
        return frames;
    }

    private static ImageFrame loadFrame(FitsFileInformation[] cachedFileInfo,
                                        long[] frameTimestamps,
                                        long[] frameExposures,
                                        int index) throws Exception {
        File currentFile = new File(cachedFileInfo[index].getFilePath());
        try (Fits fitsFile = new Fits(currentFile)) {
            BasicHDU<?> hdu = ImageProcessing.getImageHDU(fitsFile);
            Object kernel = hdu.getKernel();
            if (!(kernel instanceof short[][])) {
                throw new IOException("Cannot process: Expected short[][] but found " + kernel.getClass() + " in file " + currentFile.getName());
            }
            return new ImageFrame(index, currentFile.getName(), (short[][]) kernel, frameTimestamps[index], frameExposures[index]);
        }
    }

    private static List<short[][]> createOnDemandRawFramesForExport(FitsFileInformation[] cachedFileInfo) {
        return new AbstractList<short[][]>() {
            @Override
            public short[][] get(int index) {
                try {
                    File currentFile = new File(cachedFileInfo[index].getFilePath());
                    try (Fits fitsFile = new Fits(currentFile)) {
                        BasicHDU<?> hdu = ImageProcessing.getImageHDU(fitsFile);
                        Object kernel = hdu.getKernel();
                        if (!(kernel instanceof short[][])) {
                            throw new IOException("Cannot process: Expected short[][] but found " + kernel.getClass() + " in file " + currentFile.getName());
                        }
                        return (short[][]) kernel;
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to read frame " + index + " on demand: " + e.getMessage(), e);
                }
            }

            @Override
            public int size() {
                return cachedFileInfo.length;
            }
        };
    }
}
