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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns the iterative slow-mover detection workflow that re-runs the engine on progressively larger
 * time-spaced subsets while keeping a shared master stack.
 */
final class IterativeDetectionPipelineService {

    private static final DateTimeFormatter ITERATIVE_DIRECTORY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

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
            System.out.println("Not enough frames for iterative detection. Falling back to standard pipeline.");
            return standardDetectionPipelineService.detectObjects(config, cachedFileInfo, safetyPrompt, progressListener);
        }

        System.out.println("\n--- Starting ITERATIVE PIPELINE for " + numFrames + " FITS files (On-Demand Loading) ---");

        File masterDir = createIterativeOutputDirectory(cachedFileInfo);
        int targetMaxLimit = resolveTargetMaxLimit(maxFramesLimit, numFrames);

        System.out.println("\n--- Extracting Timestamps & Exposures for Temporal Spacing ---");
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

        System.out.println("\n--- Generating Global Master Map from " + targetMaxLimit + " globally spaced frames ---");
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
        List<DetectionReportGenerator.IterationSummary> summaries = new ArrayList<>();

        for (int k = 5; k <= targetMaxLimit; k += 5) {
            System.out.println("\n>>> RUNNING ITERATION: " + k + " Frames (Time-Spaced)");

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

            PipelineResult result;
            JTransientEngine engine = new JTransientEngine();
            try {
                result = engine.runPipeline(spacedSubset, effectiveConfig, scaledListener, providedMasterStack);
            } finally {
                engine.shutdown();
            }

            ImageProcessing.DetectionSummary detectionSummary = DetectionPipelineSupport.summarizeDetections(result);
            if (safetyPrompt != null && !safetyPrompt.shouldProceed(detectionSummary)) {
                System.out.println("Iteration " + k + " aborted by UI callback due to high detection count. Stopping further iterations.");
                break;
            }

            if (progressListener != null) {
                scaledListener.onProgressUpdate(95, "Generating report (on-demand disk reads)...");
            }

            File iterationDir = new File(masterDir, k + "_frames");
            iterationDir.mkdirs();

            try {
                DetectionReportGenerator.exportTrackVisualizations(
                        result,
                        createOnDemandRawFramesForExport(cachedFileInfo),
                        cachedFileInfo,
                        iterationDir,
                        effectiveConfig,
                        appConfig);
            } catch (IOException e) {
                System.err.println("Failed to export visualization for iteration " + k + ": " + e.getMessage());
            }

            int anomalyCount = result.anomalies == null ? 0 : result.anomalies.size();
            summaries.add(new DetectionReportGenerator.IterationSummary(k, k + "_frames", result.tracks.size(), anomalyCount));

            currentIteration++;
        }

        if (progressListener != null) {
            progressListener.onProgressUpdate(100, "All iterative passes complete!");
        }

        File indexFile = new File(masterDir, "index.html");
        try {
            DetectionReportGenerator.exportIterativeIndexReport(masterDir, summaries);
            System.out.println("Total Iterative Pipeline Time: " + (System.currentTimeMillis() - startTime) + "ms");
            return indexFile;
        } catch (IOException e) {
            System.err.println("Failed to write iterative index report: " + e.getMessage());
        }

        System.out.println("Total Iterative Pipeline Time: " + (System.currentTimeMillis() - startTime) + "ms");
        return masterDir;
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
        File masterDir = new File(parentDir, "iterative_detections_" + LocalDateTime.now().format(ITERATIVE_DIRECTORY_TIMESTAMP));
        if (!masterDir.exists()) {
            masterDir.mkdirs();
        }
        return masterDir;
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
