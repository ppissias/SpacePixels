/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.api;

/**
 * Public entrypoint for running the SpacePixels standard detection pipeline from Java code.
 * <p>
 * This API accepts an input directory plus optional detection configuration, input preparation,
 * auto-tuning, progress reporting, and report-export options. It always returns the in-memory
 * pipeline result and can optionally export the standard HTML report alongside it.
 *
 * <p>When report generation is enabled, callers should use the returned
 * {@link SpacePixelsPipelineResult#getExportDirectory()} and
 * {@link SpacePixelsPipelineResult#getReportFile()} values rather than assuming a hard-coded
 * output path.
 */
public interface SpacePixelsPipelineApi {
    /**
     * Executes the standard SpacePixels detection pipeline for the supplied request.
     *
     * @param request pipeline request describing the input directory and optional execution settings
     * @return pipeline execution result, including the effective configuration and raw pipeline output
     * @throws SpacePixelsPipelineException if input preparation, auto-tuning, validation, pipeline
     *                                      execution, or report generation fails
     */
    SpacePixelsPipelineResult run(SpacePixelsPipelineRequest request) throws SpacePixelsPipelineException;
}
