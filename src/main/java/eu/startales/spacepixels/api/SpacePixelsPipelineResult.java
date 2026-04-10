/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.api;

import eu.startales.spacepixels.util.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import io.github.ppissias.jtransient.engine.PipelineResult;

import java.io.File;

/**
 * Result returned by the public SpacePixels pipeline API.
 * <p>
 * This object exposes the original and prepared input locations, the configuration actually used,
 * optional Auto-Tune telemetry, the raw JTransient {@link PipelineResult}, and optional report
 * export paths.
 */
public final class SpacePixelsPipelineResult {
    private final File originalInputDirectory;
    private final File preparedInputDirectory;
    private final boolean inputWasPrepared;
    private final FitsFileInformation[] filesInformation;
    private final DetectionConfig baseConfig;
    private final DetectionConfig effectiveConfig;
    private final boolean autoTuneApplied;
    private final JTransientAutoTuner.AutoTuneProfile autoTuneProfileUsed;
    private final String autoTuneTelemetryReport;
    private final PipelineResult pipelineResult;
    private final File exportDirectory;
    private final File reportFile;

    public SpacePixelsPipelineResult(File originalInputDirectory,
                                     File preparedInputDirectory,
                                     boolean inputWasPrepared,
                                     FitsFileInformation[] filesInformation,
                                     DetectionConfig baseConfig,
                                     DetectionConfig effectiveConfig,
                                     boolean autoTuneApplied,
                                     JTransientAutoTuner.AutoTuneProfile autoTuneProfileUsed,
                                     String autoTuneTelemetryReport,
                                     PipelineResult pipelineResult,
                                     File exportDirectory,
                                     File reportFile) {
        this.originalInputDirectory = originalInputDirectory;
        this.preparedInputDirectory = preparedInputDirectory;
        this.inputWasPrepared = inputWasPrepared;
        this.filesInformation = filesInformation == null ? new FitsFileInformation[0] : filesInformation.clone();
        this.baseConfig = baseConfig == null ? null : baseConfig.clone();
        this.effectiveConfig = effectiveConfig == null ? null : effectiveConfig.clone();
        this.autoTuneApplied = autoTuneApplied;
        this.autoTuneProfileUsed = autoTuneProfileUsed;
        this.autoTuneTelemetryReport = autoTuneTelemetryReport;
        this.pipelineResult = pipelineResult;
        this.exportDirectory = exportDirectory;
        this.reportFile = reportFile;
    }

    /**
     * Returns the input directory originally supplied to the request.
     *
     * @return original input directory
     */
    public File getOriginalInputDirectory() {
        return originalInputDirectory;
    }

    /**
     * Returns the directory actually processed by the pipeline.
     *
     * @return prepared input directory, which may be the same as the original directory
     */
    public File getPreparedInputDirectory() {
        return preparedInputDirectory;
    }

    /**
     * Returns whether the API created a prepared working directory before running the pipeline.
     *
     * @return {@code true} when input preparation created a new detection-ready directory
     */
    public boolean isInputWasPrepared() {
        return inputWasPrepared;
    }

    /**
     * Returns validated FITS metadata for the processed sequence.
     *
     * @return defensive copy of the processed FITS metadata array
     */
    public FitsFileInformation[] getFilesInformation() {
        return filesInformation.clone();
    }

    /**
     * Returns the base detection configuration before Auto-Tune adjusted it.
     *
     * @return defensive copy of the base configuration, or {@code null}
     */
    public DetectionConfig getBaseConfig() {
        return baseConfig == null ? null : baseConfig.clone();
    }

    /**
     * Returns the effective detection configuration used to run the pipeline.
     *
     * @return defensive copy of the effective configuration, or {@code null}
     */
    public DetectionConfig getEffectiveConfig() {
        return effectiveConfig == null ? null : effectiveConfig.clone();
    }

    /**
     * Returns whether Auto-Tune was applied before pipeline execution.
     *
     * @return {@code true} when Auto-Tune optimized the effective configuration
     */
    public boolean isAutoTuneApplied() {
        return autoTuneApplied;
    }

    /**
     * Returns the Auto-Tune profile used for this run.
     *
     * @return Auto-Tune profile, or {@code null} when Auto-Tune was disabled
     */
    public JTransientAutoTuner.AutoTuneProfile getAutoTuneProfileUsed() {
        return autoTuneProfileUsed;
    }

    /**
     * Returns the textual Auto-Tune telemetry report, when available.
     *
     * @return Auto-Tune telemetry report, or {@code null}
     */
    public String getAutoTuneTelemetryReport() {
        return autoTuneTelemetryReport;
    }

    /**
     * Returns the raw JTransient pipeline result for downstream inspection or integration.
     *
     * @return raw pipeline result
     */
    public PipelineResult getPipelineResult() {
        return pipelineResult;
    }

    /**
     * Returns the export directory created for report output.
     *
     * <p>When report generation is enabled, this directory is the generated
     * {@code detections_<timestamp>} folder under the directory that was actually processed.
     *
     * @return export directory, or {@code null} when report generation was disabled
     */
    public File getExportDirectory() {
        return exportDirectory;
    }

    /**
     * Returns the generated HTML report file.
     *
     * <p>This file lives under {@link #getExportDirectory()} when report generation is enabled.
     *
     * @return HTML report file, or {@code null} when report generation was disabled
     */
    public File getReportFile() {
        return reportFile;
    }
}
