/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util;

import eu.startales.spacepixels.util.reporting.ReportRunContext;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.PipelineResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a finished detection hands to its report export: the engine result, the settings it ran with, the frames'
 * metadata, the frame buffers and the report context captured at the start of the run. It is returned after the
 * JTransient engine completes and before any report is written. It keeps the frame buffers by reference, so a caller
 * keeps it only until the export ends; the viewers keep a {@link DetectionOverlays} instead.
 */
public final class PipelineExecutionData {
    private final ReportRunContext reportContext;
    private final PipelineResult pipelineResult;
    private final DetectionConfig effectiveConfig;
    private final FitsFileInformation[] filesInformation;
    private final List<short[][]> rawFramesForExport;

    /**
     * Captures a finished run. The settings are cloned and the metadata and frame lists copied; the frame buffers
     * themselves and the engine result are shared, never deep-copied.
     */
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

    /** The engine's result for this run, shared with the caller. */
    public PipelineResult getPipelineResult() {
        return pipelineResult;
    }

    /** A copy of the settings the engine ran with, or null when none were recorded. */
    public DetectionConfig getEffectiveConfig() {
        return effectiveConfig == null ? null : effectiveConfig.clone();
    }

    /** Snapshots of the run's frame metadata, in frame order; changing them does not change this run. */
    public FitsFileInformation[] getFilesInformation() {
        return filesInformation.clone();
    }

    /** The frame buffers the report draws from, in frame order: an unmodifiable list of the shared buffers. */
    public List<short[][]> getRawFramesForExport() {
        return rawFramesForExport;
    }
}
