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
- `io.github.ppissias.jtransient.engine.JTransientAutoTuner.AutoTuneProfile`
- `eu.startales.spacepixels.util.AutoTunerRunner.Algorithm` (auto-tuner choice: `CALIBRATED`, the default, or `LEGACY`)
- `io.github.ppissias.jtransient.engine.PipelineResult`
- `eu.startales.spacepixels.util.FitsFileInformation`

## Dependency

When the artifact is published to Maven Central, depend on it as:

```groovy
implementation 'eu.startales.spacepixels:spacepixels:<version>'
```

The published artifact is the current single-module SpacePixels JAR, so it contains both the public API and the desktop application code. For library consumers, the supported surface is still only `eu.startales.spacepixels.api`.

## What the API does

The API runs the standard SpacePixels detection pipeline on a directory of aligned FITS files, or an XISF-only directory when input preparation is enabled, and returns:

- the original and prepared input directories
- validated FITS metadata
- the base and effective detection configuration
- optional Auto-Tune telemetry
- the raw JTransient `PipelineResult`
- optional HTML report/export paths

When `generateReport(true)` is enabled, SpacePixels writes the report into a new
`detections_<timestamp>` directory under the directory that was actually processed.
That means:

- with `FAIL_IF_NOT_READY`, the report is written next to the original input files
- with `AUTO_PREPARE_TO_16BIT_MONO`, the report is written under the prepared working directory if input preparation created one

Library consumers should use `getExportDirectory()` and `getReportFile()` rather than guessing the output path.

The report directory also contains `report-data.json` (schema version 1): generation time, application/engine versions, included groups, rejected frame numbers and findings. A finding has a report-local `id`, `category`, HTML `anchor`, pixel position, optional sky position, observed `frames`, and optional variable-star `starId`/`tier`. Frame numbers in this file are one-based. Empty frame lists on maximum-stack candidates mean per-frame evidence is unavailable; they do not mean zero support. IDs are not persistent identities across reports or iterations. Input paths and observing-site coordinates are not included in this JSON. Existing photometry CSV columns are retained; report labels and identification columns are appended.

## Input preparation modes

All imported images must use the same extension (case-insensitive), so FITS and XISF files cannot
be mixed. FITS frames must also share their BITPIX storage type, `BZERO`/`BSCALE` sample scaling,
mono/RGB layout, dimensions and compression state. XISF frames must share their dimensions, channel
count, colour space and sample format. These checks run before automatic FITS or XISF conversion,
so conversion cannot hide a mixed sequence. Compressed suffixes such as `.fit.fz` and `.fits.fz`
are treated as different extensions.

Timing must be consistent: every frame must have a parseable observation timestamp, or none may
have one. Timed sequences sort by timestamp, with filename as a tie breaker; untimed sequences
sort by filename. Malformed timestamps count as unavailable.

Exposure times (`EXPTIME`, or `EXPOSURE`) must match as well. Exposures within 2 % of each other (at
least 0.1 s) count as the same, because many cameras record the measured exposure (60.0, 60.1 and
60.2 s in one sequence). A sequence with several exposure times, or with only some frames carrying
one, fails with a `SpacePixelsPipelineException` whose message lists the groups and names the odd
frames; its cause, `MixedExposureException`, has the groups as `getGroups()`, largest first. The API
does not pick a group. The SpacePixels window offers to copy the largest group into a subfolder
such as `_exposure_60s` and import that.

FITS normalization applies the original `BSCALE` and `BZERO` in double precision and stores the
result as unsigned 16-bit (`BZERO` 32768, `BSCALE` 1). Signed 16-bit frames (`BZERO` 0) and frames
with other scaling count as not detection-ready, because read as they are their pixels would be at
the wrong brightness level. The API closes its import executor on both success and failure.

Unsigned 16-bit storage cannot hold values below zero, which signed or floating-point frames that
were bias- or dark-subtracted without a pedestal contain. `NegativeValueHandling` chooses what
`AUTO_PREPARE_TO_16BIT_MONO` does with them:

- `ADD_OFFSET` (default, recommended): the same whole number of ADU is added to every pixel of every
  frame so the lowest value becomes 0. All values and the sky noise are kept. The zero fill that
  registration leaves at the frame edges stays at 0, so the detection still recognises it, and the
  converted files record the offset in the `SPOFFSET` keyword, which the report subtracts for the sky
  brightness.
- `CLIP_TO_ZERO`: values below zero become 0. Where the sky is close to zero this cuts off the low
  side of the noise; when more than half of each frame is at 0, every frame counts as a failed
  registration and the run fails.

`InputPreparationMode` controls how strictly the API treats the input directory:

- `FAIL_IF_NOT_READY`
  Use this when the directory is already an uncompressed 16-bit monochrome FITS sequence stored as
  unsigned values (`BZERO` 32768, `BSCALE` 1). Anything else is refused with a message.
- `AUTO_PREPARE_TO_16BIT_MONO`
  Use this when the input may be an XISF-only directory, color FITS, or supported FITS data that is not already 16-bit monochrome, and you want SpacePixels to normalize it into a new detection-ready directory first.

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
System.out.println("Track count: " + result.getPipelineResult().tracks.size());
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
import eu.startales.spacepixels.util.AutoTunerRunner;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;

import java.io.File;

SpacePixelsPipelineResult result = new DefaultSpacePixelsPipelineApi().run(
        SpacePixelsPipelineRequest.builder(new File("C:/astro/sequence"))
                .detectionConfig(new DetectionConfig())
                .inputPreparationMode(InputPreparationMode.FAIL_IF_NOT_READY)
                .autoTuneProfile(JTransientAutoTuner.AutoTuneProfile.MEDIUM)
                .autoTuneAlgorithm(AutoTunerRunner.Algorithm.CALIBRATED)
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

Profiles: `LOW`, `MEDIUM`, `HIGH` and `MAXIMUM` (as sensitive as possible; on large sensors it leaves many candidates, so it is meant for small sensors or targeted searches). `autoTuneAlgorithm(...)` selects the calibrated tuner (default), which measures false detections and sensitivity on the session, or the legacy score-based tuner, which treats `MAXIMUM` like `HIGH`.

Progress is reported from 0 to 100% and only moves forward: input preparation up to 15%, Auto-Tune 15-35% when enabled, the detection pipeline up to 90%, and report generation 90-100%.

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

JTransient's raw `PipelineResult` exposes slow-mover candidates as maximum-stack morphology footprints, not confirmed tracks. `slowMoverAnalysis.maximumStackData` is the shared maximum stack, and `slowMoverAnalysis.medianMask` is the exact mask of independently extracted median-stack source pixels. Candidate overlap is the fraction of candidate raw pixels inside that mask. The compatibility fields `slowMoverStackData`, `slowMoverMedianVetoMask`, and `SlowMoverAnalysis.medianVetoMask` refer to those same products despite their older names.

Each slow-mover candidate's diagnostics include `frameSupportPercentage` (usable frames with localized signal inside its footprint) and `stationaryLikelihoodPercentage` (supported positions clustered near one location), plus supported/usable frame counts and availability flags. Check `frameSupportAvailable` and `stationaryLikelihoodAvailable` before displaying a percentage: a numeric frame-support value can exist even when too few frames make it unavailable. Stationary likelihood is a heuristic, not a calibrated probability or motion confirmation. `DetectionConfig.slowMoverMinFrameSupport` defaults to `40.0` and `slowMoverMaxStationaryLikelihood` to `60.0`: a candidate is rejected when its frame support is below 40 % or its stationary likelihood is above 60 %. Each gate applies only when its value is available, and `0.0` and `100.0` turn the gates off; `PipelineTelemetry.SlowMoverTelemetry` also exposes effective thresholds, rejection counts, and accepted-candidate score lists.

## Detection diagnostics

The raw pipeline result also exposes `telemetry.stageTimeMs`, an ordered map from `PipelineTelemetry.Stage` to elapsed wall-clock milliseconds for completed major phases. Disabled phases are absent. `telemetry.processingTimeMs` covers the complete engine run, including photometry, and excludes application image loading, Auto-Tune and report export. Parallel phases measure elapsed time rather than summed worker CPU time. `telemetry.trackerTelemetry.faintStreakFragmentTimeMs` is already included in tracking; -1 means the pass did not run. When photometry ran, `telemetry.photometryTelemetry.framesExcludedSpatial` counts its spatial-residual frame exclusions. The generated report shows these timings under Diagnostics and the spatial count in its photometry diagnostics.

Common-motion filtering precedes optional local rescue and activity clustering and has its own switch. Its groups are retained in `residualTransientAnalysis.commonMotionGroups` even with residual analysis disabled. Their observations remain in `allTransients` but are removed from `unclassifiedTransients`; local rescue candidates still reference observations in that latter list.

## Error handling

`SpacePixelsPipelineApi.run(...)` throws `SpacePixelsPipelineException` when input preparation, validation, Auto-Tune, pipeline execution, or report generation fails.

Request construction snapshots an explicitly supplied `DetectionConfig` with JTransient's `DetectionConfigRules.Operation.CONFIGURATION`. Non-finite numbers, including hidden/disabled settings, throw `DetectionConfigRules.InvalidConfigurationException` from `build()`, before `run`; its `issues()` contain rule, stage and field identifiers. Profile loading uses the same validation and wraps this exception as an `IOException` cause. Finite legacy profiles and sentinels are not silently aligned or newly rejected.

Call `DetectionConfigRules.validate(config, operation[, timing])` for advisory issues, or explicitly request `align` and inspect its adjustments before building a request. Engine execution validates only the captured operation's read set; GUI spinner ranges are not extra API restrictions. SpacePixels uses detached `tuneSnapshot` results, including unsuccessful tuning fallbacks. Configuration warnings are available from the shared validator, not a new pipeline-result warnings property. See JTransient's [configuration contract](../JTransient/PIPELINE.md#configuration-snapshots-and-shared-rules).

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
- The API prints nothing to the console; problems are thrown or logged as `java.util.logging` warnings. Each run sets JTransient's `JTransientEngine.DEBUG` and `JTransientAutoTuner.DEBUG` from the `spacepixels.debug` system property, so start the JVM with `-Dspacepixels.debug=true` for the per-frame diagnostics on standard output. The Auto-Tune telemetry is in the result (`getAutoTuneTelemetryReport()`), not printed.
- The public artifact currently contains GUI code as well, but GUI classes are not part of the supported API contract.
- Do not run multiple API calls with the legacy auto-tuner concurrently in the same JVM. It temporarily adjusts shared JTransient Auto-Tune state while a tuning run is active. The calibrated tuner (the default) does not use shared state.


### Variable-star report presentation

`SpacePixelsPipelineRequest.Builder.variableStarReportOptions(...)` accepts immutable SpacePixels presentation choices, independently of `DetectionConfig`:

```java
.variableStarReportOptions(new eu.startales.spacepixels.config.VariableStarReportOptions(
        60, eu.startales.spacepixels.config.VariableStarReportOptions.SortOrder.SHORTEST_PERIOD))
```

The default is 40 detailed cards and `OBSERVED_CHANGES`. Other orders are `LARGEST_VARIATION` and `SIGNIFICANCE`; high confidence always comes before possible. The valid card range is 1–1,000. Known periods come from the saved session catalogue; this does not enable period estimation. IDs and measured CSV values are independent of presentation order, and every candidate remains in the table/maps/CSV exports.

The batch CLI reads `variableStarMaxCandidateCards` and `variableStarSortOrder` from the flat SpacePixels detection profile. Older profiles use the defaults. These are application fields, not JTransient detection settings. For example:

```json
{
  "variableStarMaxCandidateCards": 60,
  "variableStarSortOrder": "SHORTEST_PERIOD"
}
```
