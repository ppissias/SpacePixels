/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.session.SessionFrames;
import eu.startales.spacepixels.util.reporting.DetectionReportGenerator;
import eu.startales.spacepixels.util.reporting.ExportVisualizationSettings;
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
    private final SessionFrames state;

    StandardDetectionPipelineService(AppConfig appConfig, SessionFrames state) {
        this.appConfig = appConfig;
        this.state = state;
    }

    record RunResult(File report, ImageProcessing.DetectionSummary summary) {}

    File detectObjects(DetectionConfig config,
                       FitsFileInformation[] cachedFileInfo,
                       ImageProcessing.DetectionSafetyPrompt safetyPrompt,
                       TransientEngineProgressListener progressListener) throws Exception {
        return run(config, cachedFileInfo, safetyPrompt, progressListener, null, null).report();
    }

    RunResult run(DetectionConfig config, FitsFileInformation[] cachedFileInfo,
                  ImageProcessing.DetectionSafetyPrompt safetyPrompt, TransientEngineProgressListener progressListener,
                  File outputDirectory, ExportVisualizationSettings visualization) throws Exception {
        // Detection takes 0-90% of the bar; exporting the report the rest.
        ImageProcessing.PipelineExecutionData executionData = runDetectionPipeline(config, cachedFileInfo,
                progressListener == null ? null : (percent, message) -> progressListener.onProgressUpdate((int) (percent * 0.9), message));
        state.setOverlays(TrackOverlay.from(executionData.getPipelineResult()),
                VariableStarOverlay.from(executionData.getPipelineResult(), cachedFileInfo));

        ReportInclusion reportInclusion = ReportInclusion.all();
        ImageProcessing.DetectionSummary detectionSummary = DetectionPipelineSupport.summarizeDetections(executionData.getPipelineResult());
        if (safetyPrompt != null) {
            if (!safetyPrompt.shouldProceed(detectionSummary)) {
                DebugOutput.println("Report generation aborted by UI callback. Detection count: " + detectionSummary.totalDetections);
                return new RunResult(null, detectionSummary);
            }
            reportInclusion = detectionSummary.reportInclusion();
        }

        DebugOutput.println("\n--- PHASE 5: Exporting Visualizations ---");
        if (progressListener != null) {
            progressListener.onProgressUpdate(90, "Exporting tracks and generating HTML report...");
        }

        // Null means only that the user declined the report; a failed export says so with its reason.
        File reportFile;
        try {
            // Writing the report takes 90-99%.
            reportFile = exportDetectionReport(executionData, reportInclusion, progressListener == null ? null
                    : (percent, message) -> progressListener.onProgressUpdate(90 + percent / 10, message), outputDirectory, visualization);
        } catch (IOException | RuntimeException e) {
            if (Cancellation.isCancellation(e)) {
                throw e;
            }
            throw new IOException("The detection finished, but the report could not be written: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        }
        if (reportFile == null) {
            throw new IOException("The detection finished, but there were no frames to write a report for.");
        }
        if (progressListener != null) {
            progressListener.onProgressUpdate(100, "Finished!");
        }
        return new RunResult(reportFile, detectionSummary);
    }

    ImageProcessing.PipelineExecutionData runDetectionPipeline(DetectionConfig config,
                                                               FitsFileInformation[] cachedFileInfo,
                                                               TransientEngineProgressListener progressListener) throws Exception {
        long startTime = System.currentTimeMillis();
        int numFrames = cachedFileInfo.length;

        DebugOutput.println("\n--- Loading " + numFrames + " FITS files for JTransient Engine ---");

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

        DebugOutput.println("\n--- Passing data to JTransient Engine ---");
        if (progressListener != null) {
            progressListener.onProgressUpdate(20, "Initializing JTransient Engine...");
        }

        DetectionConfig effectiveConfig = DetectionPipelineSupport.createEffectiveDetectionConfig(config, framesForLibrary.size());

        JTransientEngine engine = new JTransientEngine();
        DebugOutput.applyToEngine();

        PipelineResult result;
        try {
            // Loading took 0-20%; the engine reports 0-100%, shown as 20-100%.
            result = engine.runPipeline(framesForLibrary, effectiveConfig, progressListener == null ? null
                    : (percent, message) -> progressListener.onProgressUpdate(20 + (int) (percent * 0.8), message));
        } finally {
            engine.shutdown();
        }
        result = DetectionPipelineSupport.suppressLatePhaseOutputsWhenTooFewFramesRemain(result);

        long pipelineDuration = System.currentTimeMillis() - startTime;
        DebugOutput.println("Total Pipeline Time: " + pipelineDuration + "ms");

        return new ImageProcessing.PipelineExecutionData(result, effectiveConfig, cachedFileInfo, rawFramesForExport, pipelineDuration);
    }

    File exportDetectionReport(ImageProcessing.PipelineExecutionData executionData,
                               TransientEngineProgressListener progressListener) throws IOException {
        return exportDetectionReport(executionData, ReportInclusion.all(), progressListener);
    }

    /**
     * Writes the report into a new {@code detections_} folder; {@code progressListener} (which may be null) hears
     * 0-99%. When it stops the report with a cancel ({@link Cancellation}), the unfinished folder is removed.
     */
    File exportDetectionReport(ImageProcessing.PipelineExecutionData executionData, ReportInclusion inclusion,
                               TransientEngineProgressListener progressListener) throws IOException {
        return exportDetectionReport(executionData, inclusion, progressListener, null, null);
    }

    private File exportDetectionReport(ImageProcessing.PipelineExecutionData executionData, ReportInclusion inclusion,
                               TransientEngineProgressListener progressListener, File outputDirectory,
                               ExportVisualizationSettings visualization) throws IOException {
        if (executionData == null) {
            throw new IllegalArgumentException("executionData must not be null");
        }

        FitsFileInformation[] filesInformation = executionData.getFilesInformation();
        if (filesInformation.length == 0) {
            return null;
        }

        File exportDir = outputDirectory == null
                ? ImageProcessing.createDetectionsDirectory(new File(filesInformation[0].getFilePath())) : outputDirectory;
        java.nio.file.Files.createDirectories(exportDir.toPath());
        try {
            DetectionReportGenerator.exportTrackVisualizations(
                    executionData.getPipelineResult(),
                    executionData.getRawFramesForExport(),
                    filesInformation,
                    exportDir,
                    executionData.getEffectiveConfig(),
                    appConfig,
                    inclusion,
                    progressListener, visualization);
        } catch (IOException | RuntimeException e) {
            if (Cancellation.isCancellation(e)) {
                OutputFolders.deleteUnfinished(exportDir);
            }
            throw e;
        }
        return new File(exportDir, DetectionReportGenerator.detectionReportName);
    }
}
