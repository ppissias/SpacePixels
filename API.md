# SpacePixels Java API

This document describes the supported Java API for embedding SpacePixels pipeline execution in another JVM application.

## Official API surface

The official supported API is the [`eu.startales.spacepixels.api`](src/main/java/eu/startales/spacepixels/api) package.

Supported public types:

- `SpacePixelsPipelineApi`
- `DefaultSpacePixelsPipelineApi`
- `SpacePixelsPipelineRequest`
- `SpacePixelsPipelineResult`
- `SpacePixelsPipelineException`
- `SpacePixelsProgressListener`
- `InputPreparationMode`

Other `eu.startales.spacepixels.*` packages should be treated as internal implementation details and may change between releases. The current public request/result model also exposes:

- `io.github.ppissias.jtransient.config.DetectionConfig`
- `io.github.ppissias.jtransient.engine.PipelineResult`
- `eu.startales.spacepixels.util.FitsFileInformation`

## Dependency

When the artifact is published to Maven Central, depend on it as:

```groovy
implementation 'eu.startales.spacepixels:spacepixels:<version>'
```

The published artifact is the current single-module SpacePixels JAR, so it contains both the public API and the desktop application code. For library consumers, the supported surface is still only `eu.startales.spacepixels.api`.

## What the API does

The API runs the standard SpacePixels detection pipeline on a directory of aligned FITS or XISF files and returns:

- the original and prepared input directories
- validated FITS metadata
- the base and effective detection configuration
- optional Auto-Tune telemetry
- the raw JTransient `PipelineResult`
- optional HTML report/export paths

## Input preparation modes

`InputPreparationMode` controls how strictly the API treats the input directory:

- `FAIL_IF_NOT_READY`
  Use this when the directory is already an uncompressed 16-bit monochrome FITS sequence.
- `AUTO_PREPARE_TO_16BIT_MONO`
  Use this when the input may be XISF, color FITS, or unsupported bit depth and you want SpacePixels to normalize it into a new detection-ready directory first.

## Example: basic pipeline run with report export

```java
import eu.startales.spacepixels.api.DefaultSpacePixelsPipelineApi;
import eu.startales.spacepixels.api.InputPreparationMode;
import eu.startales.spacepixels.api.SpacePixelsPipelineApi;
import eu.startales.spacepixels.api.SpacePixelsPipelineRequest;
import eu.startales.spacepixels.api.SpacePixelsPipelineResult;
import io.github.ppissias.jtransient.config.DetectionConfig;

import java.io.File;

DetectionConfig config = new DetectionConfig();

SpacePixelsPipelineApi api = new DefaultSpacePixelsPipelineApi();
SpacePixelsPipelineResult result = api.run(
        SpacePixelsPipelineRequest.builder(new File("C:/astro/sequence"))
                .detectionConfig(config)
                .inputPreparationMode(InputPreparationMode.FAIL_IF_NOT_READY)
                .generateReport(true)
                .build());

System.out.println("Prepared input directory: " + result.getPreparedInputDirectory());
System.out.println("Report file: " + result.getReportFile());
System.out.println("Track count: " + result.getPipelineResult().linkedTracks.size());
```

Use this mode when your input directory is already detection-ready and you want the standard HTML report on disk.

## Example: auto-prepare inputs and return raw results only

```java
import eu.startales.spacepixels.api.DefaultSpacePixelsPipelineApi;
import eu.startales.spacepixels.api.InputPreparationMode;
import eu.startales.spacepixels.api.SpacePixelsPipelineRequest;
import eu.startales.spacepixels.api.SpacePixelsPipelineResult;
import io.github.ppissias.jtransient.config.DetectionConfig;

import java.io.File;

SpacePixelsPipelineResult result = new DefaultSpacePixelsPipelineApi().run(
        SpacePixelsPipelineRequest.builder(new File("C:/astro/color-or-xisf-sequence"))
                .detectionConfig(new DetectionConfig())
                .inputPreparationMode(InputPreparationMode.AUTO_PREPARE_TO_16BIT_MONO)
                .generateReport(false)
                .build());

System.out.println("Input was prepared: " + result.isInputWasPrepared());
System.out.println("Prepared directory: " + result.getPreparedInputDirectory());
System.out.println("Files validated: " + result.getFilesInformation().length);
System.out.println("Report generated: " + (result.getReportFile() != null));
```

Use this mode when you want SpacePixels to normalize the data first and you only need the in-memory pipeline output.

## Example: Auto-Tune with progress updates

```java
import eu.startales.spacepixels.api.DefaultSpacePixelsPipelineApi;
import eu.startales.spacepixels.api.InputPreparationMode;
import eu.startales.spacepixels.api.SpacePixelsPipelineRequest;
import eu.startales.spacepixels.api.SpacePixelsPipelineResult;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;

import java.io.File;

SpacePixelsPipelineResult result = new DefaultSpacePixelsPipelineApi().run(
        SpacePixelsPipelineRequest.builder(new File("C:/astro/sequence"))
                .detectionConfig(new DetectionConfig())
                .inputPreparationMode(InputPreparationMode.FAIL_IF_NOT_READY)
                .autoTuneProfile(JTransientAutoTuner.AutoTuneProfile.BALANCED)
                .autoTuneMaxCandidateFrames(12)
                .progressListener((percentage, message) ->
                        System.out.println(percentage + "% " + message))
                .generateReport(true)
                .build());

System.out.println("Auto-Tune applied: " + result.isAutoTuneApplied());
System.out.println("Auto-Tune profile used: " + result.getAutoTuneProfileUsed());
System.out.println("Telemetry report available: " + (result.getAutoTuneTelemetryReport() != null));
```

Use this when you want SpacePixels to optimize the effective detection configuration before running the standard pipeline.

## Result object overview

`SpacePixelsPipelineResult` gives access to:

- `getOriginalInputDirectory()`
- `getPreparedInputDirectory()`
- `isInputWasPrepared()`
- `getFilesInformation()`
- `getBaseConfig()`
- `getEffectiveConfig()`
- `isAutoTuneApplied()`
- `getAutoTuneProfileUsed()`
- `getAutoTuneTelemetryReport()`
- `getPipelineResult()`
- `getExportDirectory()`
- `getReportFile()`

Typical usage patterns:

- inspect `getPipelineResult()` for downstream integration with JTransient-level results
- inspect `getEffectiveConfig()` to see the final config actually used
- inspect `getPreparedInputDirectory()` when `AUTO_PREPARE_TO_16BIT_MONO` is enabled
- inspect `getReportFile()` when `generateReport(true)` is enabled

## Error handling

`SpacePixelsPipelineApi.run(...)` throws `SpacePixelsPipelineException` when input preparation, validation, Auto-Tune, pipeline execution, or report generation fails.

Example:

```java
try {
    SpacePixelsPipelineResult result = new DefaultSpacePixelsPipelineApi().run(request);
    // consume result
} catch (eu.startales.spacepixels.api.SpacePixelsPipelineException e) {
    System.err.println("SpacePixels pipeline failed: " + e.getMessage());
    e.printStackTrace();
}
```

## Notes for API consumers

- The API is designed for aligned astronomical image sequences.
- Best results come from calibrated inputs with a consistent frame size.
- `FAIL_IF_NOT_READY` is stricter and is best for automated production workflows.
- `AUTO_PREPARE_TO_16BIT_MONO` is more forgiving and is best when input format may vary.
- The API returns a defensive copy for exposed configs and FITS metadata arrays.
- The public artifact currently contains GUI code as well, but GUI classes are not part of the supported API contract.
