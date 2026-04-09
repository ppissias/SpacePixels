/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */

/**
 * Official supported Java API for embedding SpacePixels pipeline execution.
 *
 * <p>The supported entrypoints for external consumers are the types in this package:
 * {@link eu.startales.spacepixels.api.SpacePixelsPipelineApi},
 * {@link eu.startales.spacepixels.api.DefaultSpacePixelsPipelineApi},
 * {@link eu.startales.spacepixels.api.SpacePixelsPipelineRequest},
 * {@link eu.startales.spacepixels.api.SpacePixelsPipelineResult},
 * {@link eu.startales.spacepixels.api.SpacePixelsPipelineException},
 * {@link eu.startales.spacepixels.api.SpacePixelsProgressListener}, and
 * {@link eu.startales.spacepixels.api.InputPreparationMode}.
 *
 * <p>Consumers should treat other {@code eu.startales.spacepixels.*} packages as internal
 * implementation details unless a type is explicitly exposed through this package. The current
 * request/result contract also exposes
 * {@link eu.startales.spacepixels.util.FitsFileInformation},
 * {@link io.github.ppissias.jtransient.config.DetectionConfig}, and
 * {@link io.github.ppissias.jtransient.engine.PipelineResult}.
 */
package eu.startales.spacepixels.api;
