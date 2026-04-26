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
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the standard JTransient detection path and exports the resulting report artifacts.
 *
 * <p>This service owns the non-iterative load-run-export flow so {@link ImageProcessing} can stay
 * focused on FITS lifecycle concerns, iterative workflows, and the broader facade API.</p>
 */
final class StandardDetectionPipelineService {

    private final AppConfig appConfig;

    StandardDetectionPipelineService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    File detectObjects(DetectionConfig config,
                       FitsFileInformation[] cachedFileInfo,
                       ImageProcessing.DetectionSafetyPrompt safetyPrompt,
                       TransientEngineProgressListener progressListener) throws Exception {
        ImageProcessing.PipelineExecutionData executionData = runDetectionPipeline(config, cachedFileInfo, progressListener);

        if (safetyPrompt != null) {
            ImageProcessing.DetectionSummary detectionSummary = DetectionPipelineSupport.summarizeDetections(executionData.getPipelineResult());
            if (!safetyPrompt.shouldProceed(detectionSummary)) {
                System.out.println("Report generation aborted by UI callback. Detection count: " + detectionSummary.totalDetections);
                return null;
            }
        }

        System.out.println("\n--- PHASE 5: Exporting Visualizations ---");
        if (progressListener != null) {
            progressListener.onProgressUpdate(90, "Exporting tracks and generating HTML report...");
        }

        try {
            File reportFile = exportDetectionReport(executionData);
            if (progressListener != null) {
                progressListener.onProgressUpdate(100, "Finished!");
            }
            return reportFile;
        } catch (IOException e) {
            System.err.println("Failed to export visualizations: " + e.getMessage());
        }
        return null;
    }

    ImageProcessing.PipelineExecutionData runDetectionPipeline(DetectionConfig config,
                                                               FitsFileInformation[] cachedFileInfo,
                                                               TransientEngineProgressListener progressListener) throws Exception {
        long startTime = System.currentTimeMillis();
        int numFrames = cachedFileInfo.length;

        System.out.println("\n--- Loading " + numFrames + " FITS files for JTransient Engine ---");

        List<ImageFrame> framesForLibrary = new ArrayList<>();
        List<short[][]> rawFramesForExport = new ArrayList<>();

        for (int i = 0; i < numFrames; i++) {
            if (progressListener != null) {
                int percent = (int) (((float) i / numFrames) * 20);
                progressListener.onProgressUpdate(percent, "Loading frame " + (i + 1) + " of " + numFrames + "...");
            }

            File currentFile = new File(cachedFileInfo[i].getFilePath());
            try (Fits fitsFile = new Fits(currentFile)) {
                BasicHDU<?> hdu = ImageProcessing.getImageHDU(fitsFile);
                Object kernel = hdu.getKernel();

                if (!(kernel instanceof short[][])) {
                    throw new IOException("Cannot process: Expected short[][] but found " + kernel.getClass());
                }

                short[][] imageData = (short[][]) kernel;
                long timestamp = cachedFileInfo[i].getObservationTimestamp();
                long exposure = cachedFileInfo[i].getExposureDurationMillis();
                framesForLibrary.add(new ImageFrame(i, currentFile.getName(), imageData, timestamp, exposure));
                rawFramesForExport.add(imageData);
            }
        }

        DetectionPipelineSupport.logPipelineFrameTimingPayload("Standard pipeline", framesForLibrary, cachedFileInfo);

        System.out.println("\n--- Passing data to JTransient Engine ---");
        if (progressListener != null) {
            progressListener.onProgressUpdate(20, "Initializing JTransient Engine...");
        }

        DetectionConfig effectiveConfig = DetectionPipelineSupport.createEffectiveDetectionConfig(config, framesForLibrary.size());

        JTransientEngine engine = new JTransientEngine();
        JTransientEngine.DEBUG = true;

        PipelineResult result;
        try {
            result = engine.runPipeline(framesForLibrary, effectiveConfig, progressListener);
        } finally {
            engine.shutdown();
        }
        result = DetectionPipelineSupport.suppressLatePhaseOutputsWhenTooFewFramesRemain(result);

        long pipelineDuration = System.currentTimeMillis() - startTime;
        System.out.println("Total Pipeline Time: " + pipelineDuration + "ms");

        return new ImageProcessing.PipelineExecutionData(result, effectiveConfig, cachedFileInfo, rawFramesForExport, pipelineDuration);
    }

    File exportDetectionReport(ImageProcessing.PipelineExecutionData executionData) throws IOException {
        if (executionData == null) {
            throw new IllegalArgumentException("executionData must not be null");
        }

        FitsFileInformation[] filesInformation = executionData.getFilesInformation();
        if (filesInformation.length == 0) {
            return null;
        }

        File exportDir = ImageProcessing.createDetectionsDirectory(new File(filesInformation[0].getFilePath()));
        DetectionReportGenerator.exportTrackVisualizations(
                executionData.getPipelineResult(),
                executionData.getRawFramesForExport(),
                filesInformation,
                exportDir,
                executionData.getEffectiveConfig(),
                appConfig);
        return new File(exportDir, DetectionReportGenerator.detectionReportName);
    }
}
