# SpacePixels Java API

This guide describes the supported SpacePixels Java API. Consult [release notes](RELEASE_NOTES.md) when upgrading an existing integration. The supported API runs the standard pipeline synchronously in another JVM application; it does not expose the desktop UI, iterative detection or the Siril calibration/registration workflow.


[Dependency](#dependency) · [First example](#example-basic-pipeline-run-with-report-export) · [Input preparation](#input-preparation-modes) · [Results](#result-object-overview) · [Errors](#error-handling)

## Dependency

Choose a version that has actually been published to Maven Central; a desktop release or local build does not establish Maven availability. Use the JTransient and JPlateSolve dependencies declared by the selected SpacePixels artifact; do not replace them with arbitrary older JARs. Add Maven Central to your repositories and replace `<version>` with the published SpacePixels version:

```groovy
repositories { mavenCentral() }
dependencies {
    implementation 'eu.startales.spacepixels:spacepixels:<version>'
}
```

`spacepixels` is the only Maven publication: the public API plus the headless configuration, session, FITS/preparation, processing, catalogue and reporting implementation/resources it needs. It also includes the `BatchDetectionCli` and `ArtificialStarInjector` command-line entrypoints, usable with a dependency-resolved Java classpath. Matching sources and Javadocs cover the same headless code. It contains no SpacePixels GUI, jobs/tasks, GUI icons or FlatLaf dependency.

JTransient is an `api` dependency because its configuration and result types appear in the supported API. FITS, Gson and JPlateSolve are `implementation` dependencies: consumers receive them transitively at runtime, but they are not exposed on the compile classpath. The supported detection API does not expose plate-solver types.

The desktop application is built separately by `run`/`installDist`, using an unpublished desktop companion JAR and GUI-only dependencies. A Maven API JAR is not a standalone desktop application or a shaded dependency bundle. Headless AWT image/report rendering still requires the JDK's `java.desktop` module; run servers with `-Djava.awt.headless=true` rather than removing that module. Java 21 is the compilation baseline.

`gradlew.bat apiConsumerTest` compiles and runs a separate Java project against the packaged API and dependency JARs. It exercises preparation, detection, Auto-Tune, report generation and the command-line tools, and checks that report resources and transitive dependencies are included without GUI dependencies. See [PUBLISHING.md](PUBLISHING.md) for publishing and packaging checks.

## What the API does

The API runs the standard SpacePixels detection pipeline on a directory of aligned FITS files, or an XISF-only directory when input preparation is enabled, and returns:

- the original and prepared input directories
- validated FITS metadata
- the base and effective detection configuration
- optional Auto-Tune telemetry
- the raw JTransient `PipelineResult`; its `tooFewFrames` is true when quality control kept fewer than three frames and the engine stopped there
- optional HTML report/export paths

When `generateReport(true)` is enabled, SpacePixels writes the report into a new
`detections_<timestamp>` directory under the directory that was actually processed.
That means:

- with `FAIL_IF_NOT_READY`, the report is written next to the original input files
- with `AUTO_PREPARE_TO_16BIT_MONO`, the report is written under the prepared working directory if input preparation created one

Library consumers should use `getExportDirectory()` and `getReportFile()` rather than guessing the output path.

The report directory also contains `report-data.json` (schema version 1): generation time, application/engine versions, included groups, rejected frame numbers and findings. A finding has a report-local `id`, `category`, HTML `anchor`, pixel position, optional sky position, observed `frames`, and optional variable-star `starId`/`tier`. Frame numbers in this file are one-based. Empty frame lists on maximum-stack candidates mean per-frame evidence is unavailable; they do not mean zero support. IDs are not persistent identities across reports or iterations. Input paths and observing-site coordinates are not included in this JSON. Photometry CSV exports include detection report labels and identification columns.

Report cards, maps, CSV labels and viewer overlays share category-owned identity rules. Track grouping retains engine order for multi-frame tracks and frame/file/position order for single-frame streaks. Variable IDs follow exported candidate order, not presentation sorting or card limits; candidates without a card link to the variable table.

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
- `NegativeValueHandling`

Other `eu.startales.spacepixels.*` packages should be treated as internal implementation details and may change between releases. The current public request/result model also exposes the following types. Their use here is part of the request/result contract; it does not make every other type in their packages a supported API:

- `io.github.ppissias.jtransient.config.DetectionConfig`
- `io.github.ppissias.jtransient.engine.AutoTuneProfile`
- `io.github.ppissias.jtransient.engine.PipelineResult`
- `eu.startales.spacepixels.fits.FitsFileInformation`
- `eu.startales.spacepixels.config.DetectionRunOptions`
- `eu.startales.spacepixels.config.VisualizationSettings`
- `eu.startales.spacepixels.config.VariableStarReportOptions`
- `eu.startales.spacepixels.fits.ImageScaling.OutlierHandling` (conversion policy); `ImageScaling.OutlierException` and its summary provide refusal diagnostics

### Frame metadata (`FitsFileInformation`)

`eu.startales.spacepixels.fits.FitsFileInformation` represents immutable metadata for an imported frame:

- **Immutability:** `FitsFileInformation` is `final` and deeply immutable. `getFitsHeader()` returns an unmodifiable map.
- **Construction:** Use `new FitsFileInformation(filePath, fileName, monochrome, sizeWidth, sizeHeight, fitsHeader)` to construct frame descriptors. The constructor freezes the header map into an unmodifiable map (preserving null commentary card values) and normalises ISO-8601 observation timestamps at construction time.
- **Copy with updated header:** Use `fileInfo.withFitsHeader(updatedHeaderMap)` to produce an updated descriptor (for example when applying a newly computed WCS solution) without mutating existing descriptors.
- **Container protection:** Array getters in results (`result.getFilesInformation()`) clone the array container to protect ordering, while individual descriptors remain shared and immutable.

## Run options and isolation

`SpacePixelsPipelineRequest.Builder.runOptions(DetectionRunOptions)` supplies the observing-site fallback, the immutable `VisualizationSettings` and the Auto-Tune candidate-frame limit. The `autoTuneMaxCandidateFrames` and `variableStarReportOptions` setters override those values when called afterwards.

`VisualizationSettings` (package `eu.startales.spacepixels.config`) holds every choice about how report pictures are drawn: the display stretch, the GIF speed, crop sizes and target circles, the detection marker sizes, the most animated detections, and the variable-star presentation. None of them changes a detection result. Start from `VisualizationSettings.defaults()` and replace values with its `with…` methods, for example `VisualizationSettings.defaults().withAutoStretchWhiteSigma(4.0).withGifBlinkSpeedMs(200)`, then pass the value to `DetectionRunOptions.withVisualization(..)`. `DetectionRunOptions.capture(AppConfig, int)` takes only the observing site from the `AppConfig` and uses the built-in visualization settings. Site latitude/longitude strings keep the application's decimal-degree/cardinal syntax; FITS site cards still take priority. Invalid or absent configured coordinates retain the existing no-site behavior.

The request defaults to `FAIL_IF_NOT_READY`, `ADD_OFFSET`, outlier policy `REQUIRE_DECISION`, report generation enabled, Auto-Tune disabled and calibration/alignment warnings recorded without rejection.

API requests use built-in rendering defaults and no configured observing site unless supplied explicitly. They do not load user-home preferences or modify desktop rendering/tuning defaults. The batch CLI reads the saved visualization preferences once at startup and passes them in its run options. Callers needing a particular site or presentation should supply run options.

After input preparation, each run captures FITS metadata, the active plate correction and the matching saved catalogue before pixel loading/detection. Report export uses that captured context. Later settings edits or catalogue/correction-file replacements affect subsequent runs.

## Input preparation modes

**Preparation converts file representation; it does not calibrate or star-register images.** Supply aligned, linear exposures in either mode. Detection needs at least three frames retained by quality control; Auto-Tune needs at least four input frames.

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
were bias- or dark-subtracted without a pedestal contain. `SpacePixelsPipelineRequest.Builder.negativeValueHandling(NegativeValueHandling)` chooses what
`AUTO_PREPARE_TO_16BIT_MONO` does with them:

- `ADD_OFFSET` (default, recommended): the same offset is added to every pixel of every
  frame so the lowest retained value becomes 0. Normal negative values and the sky noise are kept. The zero fill that
  registration leaves at the frame edges stays at 0, so the detection still recognises it, and the
  converted files record the pedestal in output ADU in the `SPOFFSET` keyword, which the report subtracts for the sky
  brightness.
- `CLIP_TO_ZERO`: values below zero become 0. Where the sky is close to zero this cuts off the low
  side of the noise; when more than half of each frame is at 0, every frame counts as a failed
  registration and the run fails.

### Conversion headroom and outliers

FITS physical values are decoded using `BZERO`/`BSCALE`; floating-point FITS use a nominal `[0,1]` scale and integers use ADU. XISF declared bounds are interpreted before the same scaling policy is applied (missing floating bounds currently default to `[0,1]`). Conversion uses one linear transform for the whole sequence, including a common offset for retained negatives. It preserves the nominal level spacing while the actual range fits in 16 bits, and reduces the multiplier only when necessary. No individual-frame normalization or nonlinear stretch is applied. Existing ready mono16 inputs are not converted again.

The accepted normalized headroom is `[-0.5,1.5]`. The optional request input `outlierHandling(ImageScaling.OutlierHandling)` applies to `AUTO_PREPARE_TO_16BIT_MONO`:

| Policy | Behaviour outside the accepted range |
|---|---|
| `REQUIRE_DECISION` **(default)** | Rejects conversion before writing the converted sequence. No GUI is opened and no clipping or precision reduction for extremes is silently selected. |
| `CLIP_EXTREMES` | Explicitly clips only values outside the headroom, then plans one common transform. The separate negative-value policy still applies to ordinary negative values. |
| `PRESERVE_ALL` | Keeps every finite sample and expands the common range as necessary, accepting reduced 16-bit precision. |

Nonfinite samples are rejected under every policy. A default refusal arrives as a `SpacePixelsPipelineException` with an `ImageScaling.OutlierException` cause; `summary()` exposes the observed range, counts and per-frame diagnostics. Callers can inspect it and submit a new request with an explicit policy. Output headers record the transform, clipping selection and outlier counts. A subsequent conversion creates a separate output directory and preserves previous results.

```java
import eu.startales.spacepixels.fits.ImageScaling;

var request = SpacePixelsPipelineRequest.builder(inputDirectory)
    .inputPreparationMode(InputPreparationMode.AUTO_PREPARE_TO_16BIT_MONO)
    .outlierHandling(ImageScaling.OutlierHandling.PRESERVE_ALL) // explicit precision trade-off
    .build();
```

`InputPreparationMode` controls how strictly the API treats the input directory:

- `FAIL_IF_NOT_READY`
  Use this when the directory is already an uncompressed 16-bit monochrome FITS sequence stored as
  unsigned values (`BZERO` 32768, `BSCALE` 1). Anything else is refused with a message.
- `AUTO_PREPARE_TO_16BIT_MONO`
  Use this when the input may be an XISF-only directory, color FITS, or supported FITS data that is not already 16-bit monochrome, and you want SpacePixels to normalize it into a new detection-ready directory first.

## Sampled calibration and alignment warnings

The prepared sequence is checked for sharp sensor defects and coherent star displacement before Auto-Tune or detection. The default request policy keeps processing and records any warning in `SpacePixelsPipelineResult.getCalibrationWarning()` (`null` when none was found); `hasCalibrationWarning()` tests its presence. The batch command prints this warning as well as the report location.

Use `.failIfUncalibratedOrMisaligned(true)` on the request builder to reject a flagged sequence with `SpacePixelsPipelineException` before tuning, detection or report export. The default is `false`. An unreadable sample fails inspection; cancellation remains cancellation. No desktop dialog is created by the API.

The inspector samples the first, middle and last frames. It requires a coherent displacement of at least three optical stars before reporting a registration shift; dimming or missing stars alone are not a measured displacement. A clean result means no sampled warning was found, not that every frame is certified calibrated and aligned. The check does not edit input pixels, engine settings or findings.

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
import io.github.ppissias.jtransient.engine.AutoTuneProfile;

import java.io.File;

SpacePixelsPipelineResult result = new DefaultSpacePixelsPipelineApi().run(
        SpacePixelsPipelineRequest.builder(new File("C:/astro/sequence"))
                .detectionConfig(new DetectionConfig())
                .inputPreparationMode(InputPreparationMode.FAIL_IF_NOT_READY)
                .autoTuneProfile(AutoTuneProfile.MEDIUM)
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

Profiles: `LOW`, `MEDIUM`, `HIGH` and `MAXIMUM` (as sensitive as possible; on large sensors it leaves many candidates, so it is meant for small sensors or targeted searches). Auto-Tune measures false detections and sensitivity on the session and picks the most sensitive settings within the profile's budget.

`run(...)` blocks until processing and any requested export finish. Progress callbacks are not dispatched to a GUI event thread; keep them quick and marshal UI updates yourself. Progress is a phase indicator, not an estimate of remaining time. Callbacks receive percentages from 0 to 100: input preparation up to 15%, Auto-Tune 15-35% when enabled, and the detection pipeline up to 90% when a report is requested or 100% otherwise. Report generation occupies 90-100%.

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
- `getCalibrationWarning()`
- `hasCalibrationWarning()`

Typical usage patterns:

- inspect `getPipelineResult()` for downstream integration with JTransient-level results
- inspect `getEffectiveConfig()` to see the final config actually used
- inspect `getPreparedInputDirectory()` when `AUTO_PREPARE_TO_16BIT_MONO` is enabled
- inspect `getReportFile()` when `generateReport(true)` is enabled

JTransient's raw `PipelineResult` exposes slow-mover candidates as maximum-stack morphology footprints, not confirmed tracks. `slowMoverAnalysis.maximumStackData` is the shared maximum stack, and `slowMoverAnalysis.medianMask` is the exact mask of independently extracted median-stack source pixels. Candidate overlap is the fraction of candidate raw pixels inside that mask.

Each slow-mover candidate's diagnostics include `frameSupportPercentage` (usable frames with localized signal inside its footprint) and `stationaryLikelihoodPercentage` (supported positions clustered near one location), plus supported/usable frame counts and availability flags. Check `frameSupportAvailable` and `stationaryLikelihoodAvailable` before displaying a percentage: a numeric frame-support value can exist even when too few frames make it unavailable. Stationary likelihood is a heuristic, not a calibrated probability or motion confirmation. `DetectionConfig.slowMoverMinFrameSupport` defaults to `40.0` and `slowMoverMaxStationaryLikelihood` to `60.0`: a candidate is rejected when its frame support is below 40 % or its stationary likelihood is above 60 %. Each gate applies only when its value is available, and `0.0` and `100.0` turn the gates off; `PipelineTelemetry.SlowMoverTelemetry` also exposes effective thresholds, rejection counts, and accepted-candidate score lists.

## Detection diagnostics

The raw pipeline result also exposes `telemetry.stageTimeMs`, an ordered map from `PipelineTelemetry.Stage` to elapsed wall-clock milliseconds for completed major phases. Disabled phases are absent. `telemetry.processingTimeMs` covers the complete engine run, including photometry, and excludes application image loading, Auto-Tune and report export. Parallel phases measure elapsed time rather than summed worker CPU time. `telemetry.trackerTelemetry.faintStreakFragmentTimeMs` is already included in tracking; -1 means the pass did not run. When photometry ran, `telemetry.photometryTelemetry.framesExcludedSpatial` counts its spatial-residual frame exclusions. The generated report shows these timings under Diagnostics and the spatial count in its photometry diagnostics.

Common-motion filtering precedes optional local rescue and activity clustering and has its own switch. Its groups are retained in `residualTransientAnalysis.commonMotionGroups` even with residual analysis disabled. Their observations remain in `allTransients` but are removed from `unclassifiedTransients`; local rescue candidates still reference observations in that latter list.

## Error handling

`SpacePixelsPipelineApi.run(...)` throws `SpacePixelsPipelineException` when input preparation, validation, Auto-Tune, pipeline execution, or report generation fails.

Request construction snapshots an explicitly supplied `DetectionConfig` with JTransient's `DetectionConfigRules.Operation.CONFIGURATION`. Non-finite numbers, including hidden/disabled settings, throw `DetectionConfigRules.InvalidConfigurationException` from `build()`, before `run`; its `issues()` contain rule, stage and field identifiers. Profile loading uses the same validation and wraps this exception as an `IOException` cause. Profiles and sentinels are not silently aligned or rejected.

Call `DetectionConfigRules.validate(config, operation[, timing])` for advisory issues, or explicitly request `align` and inspect its adjustments before building a request. Engine execution validates only the captured operation's read set; GUI spinner ranges are not extra API restrictions. SpacePixels uses detached `tune` results, including unsuccessful tuning fallbacks. Configuration warnings are available from the shared validator. See JTransient's [configuration contract](https://github.com/ppissias/JTransient/blob/main/PIPELINE.md#configuration-snapshots-and-shared-rules).

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
- Config getters and FITS metadata array getters return defensive copies. Individual `FitsFileInformation` elements are immutable. `getPipelineResult()` returns the raw engine result, not a deep defensive copy.
- The API is quiet by default; problems are thrown or logged as `java.util.logging` warnings. Engine/tuner diagnostics capture `spacepixels.debug` per run through a scoped sink; an explicit caller `DiagnosticContext` policy takes precedence. No shared engine/tuner debug flags or mutable tuning defaults are changed.
- The public API artifact is headless and contains no GUI classes.
- Auto-Tune uses independent per-operation options. Use the engine/solver versions selected for this SpacePixels release; arbitrary older replacement JARs are not supported.


### Variable-star report presentation

`SpacePixelsPipelineRequest.Builder.variableStarReportOptions(...)` accepts immutable SpacePixels presentation choices, independently of `DetectionConfig`:

```java
.variableStarReportOptions(new eu.startales.spacepixels.config.VariableStarReportOptions(
        60, eu.startales.spacepixels.config.VariableStarReportOptions.SortOrder.SHORTEST_PERIOD))
```

The default is 40 detailed cards and `OBSERVED_CHANGES`. Other orders are `LARGEST_VARIATION` and `SIGNIFICANCE`; high confidence always comes before possible. The valid card range is 1–1,000. Known periods come from the saved session catalogue; this does not enable period estimation. IDs and measured CSV values are independent of presentation order, and every candidate remains in the table/maps/CSV exports.

The batch CLI reads `variableStarMaxCandidateCards` and `variableStarSortOrder` from the flat SpacePixels detection profile. Profiles that omit these fields use the defaults. These are application fields, not JTransient detection settings. For example:

```json
{
  "variableStarMaxCandidateCards": 60,
  "variableStarSortOrder": "SHORTEST_PERIOD"
}
```
