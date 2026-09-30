# High-Level Design

Snapshot date: 2026-09-23

Purpose:
- Describe the current SpacePixels architecture at subsystem and facade level.
- Record the main runtime flows for GUI, API, CLI, reporting, and plate solving.
- Keep stable design context separate from user-facing behavior in `MANUAL.md` and tactical work in `REFACTOR.md`.

Related documents:
- User workflows and operational behavior: [MANUAL.md](MANUAL.md)
- Public embedding surface: [API.md](API.md)
- Refactor sequencing and migration notes: [REFACTOR.md](REFACTOR.md)
- JTransient algorithm, pipeline, config, and Auto-Tune details live in the JTransient project docs.

## 1. System Summary

SpacePixels is a single Gradle Java application with three entry surfaces:

- Swing desktop application: `eu.startales.spacepixels.gui.ApplicationWindow`
- Public Java embedding API: `eu.startales.spacepixels.api`
- Command-line tools: `eu.startales.spacepixels.tools`

SpacePixels owns:

- FITS/XISF import and preparation
- GUI workflow orchestration
- persisted application and detection settings
- plate-solving integration and WCS header updates
- report generation and exported visual assets
- public API and batch CLI wrapping

SpacePixels delegates:

- core transient extraction, frame rejection, tracking, slow-mover analysis, anomaly rescue, residual analysis, and Auto-Tune to JTransient
- local/online plate solving primitives to JPlateSolve
- FITS parsing and writing to nom-tam-fits

The central architectural rule is that JTransient remains the detector and SpacePixels remains the application, preparation, visualization, and integration layer.

## 2. External Dependencies

Primary runtime dependencies:

- `io.github.ppissias.jtransient:jtransient`
  - detection engine, configuration object, Auto-Tune, pipeline result model
- `io.github.ppissias.jplatesolve:jplatesolve`
  - ASTAP and Astrometry.net solving integration
- `gov.nasa.gsfc.heasarc:nom-tam-fits`
  - FITS read/write and compressed FITS support
- `com.google.guava:guava`
  - GUI event bus
- `com.google.code.gson:gson`
  - JSON configuration and report lookup payloads
- `com.formdev:flatlaf`
  - Swing look and feel

## 3. Package Boundaries

### `eu.startales.spacepixels.api`

Supported public API for external JVM consumers.

Public contract:

- `SpacePixelsPipelineApi`
- `DefaultSpacePixelsPipelineApi`
- `SpacePixelsPipelineRequest`
- `SpacePixelsPipelineResult`
- `SpacePixelsPipelineException`
- `SpacePixelsProgressListener`
- `InputPreparationMode`

Everything outside this package is internal unless explicitly exposed through the API result/request types.

### `eu.startales.spacepixels.config`

Persistence models and JSON IO for application-level and detection-level settings.

Owns:

- `AppConfig`
- `SpacePixelsAppConfigIO`
- `SpacePixelsDetectionProfile`
- `SpacePixelsDetectionProfileIO`
- `SpacePixelsVisualizationPreferences`
- `SpacePixelsVisualizationPreferencesIO`

The detection profile wraps JTransient `DetectionConfig` plus SpacePixels' Auto-Tune candidate-frame limit. Visualization preferences are intentionally separate because they do not affect detection results.

### `eu.startales.spacepixels.events`

Small EventBus message types for GUI/task coordination.

These events carry progress, lifecycle, and result notifications across background tasks and Swing panels. They are not part of the public API.

### `eu.startales.spacepixels.gui`

Swing UI shell, panels, dialogs, viewers, and UI-side workflow control.

Main classes:

- `ApplicationWindow`
- `MainApplicationPanel`
- `ConfigurationPanel`
- `StretchPanel`
- `DetectionConfigurationPanel`
- `DetectionSequenceFrame`
- `TransientInspectionFrame`
- `TuningPreviewManager`
- `ProcessingProgressDialog`

The GUI layer currently combines view and controller behavior in several panels. That is an accepted current state and a refactor boundary.

### `eu.startales.spacepixels.tasks`

Background runnables launched by the GUI.

Tasks are the boundary between Swing actions and longer-running work. They post lifecycle and progress events back to the GUI through the Guava `EventBus`.

Main tasks:

- `FitsImportTask`
- `BatchConvertMonoTask`
- `BatchStretchTask`
- `BlinkImagesTask`
- `DetectionTask`
- `IterativeDetectionTask`
- `ManualTransientInspectionTask`
- `PlateSolveTask`
- `AutoTuneTask`
- `TuningPreviewTask`
- preview/full-size generation tasks

### `eu.startales.spacepixels.tools`

Command-line utilities.

Current tools:

- `BatchDetectionCli`
  - headless standard detection with optional Auto-Tune
- `ArtificialStarInjector`
  - synthetic moving-star injection for testing and validation

### `eu.startales.spacepixels.util`

Internal backend facade and processing services.

Important classes:

- `ImageProcessing`
- `DetectionInputPreparation`
- `StandardDetectionPipelineService`
- `IterativeDetectionPipelineService`
- `DetectionPipelineSupport`
- `AutoTuneCandidatePoolBuilder`
- `PlateSolveService`
- `FitsFileInformation`
- `FitsFormatChecker`
- `FitsPixelConverter`
- `FitsVisualizationRenderer`
- `DisplayImageRenderer`
- `RawImageAnnotator`
- `WcsCoordinateTransformer`
- `WcsSolutionResolver`
- `XisfImageConverter`

`ImageProcessing` is still the main internal backend facade. New work should prefer extracting focused services behind it instead of expanding it further.

### `eu.startales.spacepixels.util.reporting`

Report export, report-side rendering, WCS-aware report context, and live report lookup support.

Main facade:

- `DetectionReportGenerator`

Supporting groups:

- document and model:
  - `DetectionReportContext`
  - `DetectionReportSummary`
  - `ExportVisualizationSettings`
  - `DetectionReportDocumentWriter`
  - `ReportClientScriptWriter`
- section writers:
  - `PipelineDiagnosticsSectionWriter`
  - `TargetVisualizationSectionWriter`
  - `DeepStackReportSectionWriter`
  - `ResidualReviewSectionWriter`
  - `GlobalMapsSectionWriter`
- rendering helpers:
  - `TrackVisualizationRenderer`
  - `TrackCropGeometry`
  - `GifSequenceWriter`
  - `CreativeTributeRenderer`
- astrometry and lookup:
  - `DetectionReportAstrometry`
  - `AstrometryContextFactory`
  - `SolarSystemQueryTargetFactory`
  - `SatCheckerQueryTargetFactory`
  - URL and HTML builders
  - `ReportLookupProxyServer`
  - report lookup cache and upstream client classes

## 4. Main Facades

### `ApplicationWindow`

Desktop composition root.

Owns:

- main Swing frame and tab composition
- `MainApplicationPanel`
- `ConfigurationPanel`
- `StretchPanel`
- `DetectionConfigurationPanel`
- `BlinkFrame`
- Guava `EventBus`
- current `ImageProcessing` instance after import
- `ReportLookupProxyServer` lifecycle

Responsibilities:

- start the application
- wire the four top-level tabs
- launch import from the File menu
- enable tabs after successful import
- listen for import and progress events
- stop the report lookup proxy on window close

### `MainApplicationPanel`

Primary GUI workflow facade.

Owns:

- FITS metadata table
- plate solve controls
- blink controls
- batch conversion and stretch controls
- single-frame preview and manual transient inspection actions
- standard and iterative detection launch actions
- progress state and modal progress dialog

Launches most GUI background tasks.

### `DetectionConfigurationPanel`

Detection settings, Auto-Tune, preview, and persistence facade.

Owns:

- UI binding for JTransient `DetectionConfig`
- `SpacePixelsDetectionProfile` load/save
- `SpacePixelsVisualizationPreferences` load/save
- current Auto-Tune candidate-frame limit
- tuning profile selector
- detection preview action through `TuningPreviewManager`
- Auto-Tune launch through `AutoTuneTask`

Design note:

- detection fields and visualization fields are intentionally stored in separate JSON files even though the same panel edits both.

### `ImageProcessing`

Internal backend facade for a selected/imported sequence directory.

Owns or coordinates:

- sequence metadata loading
- GUI import-time FITS validation and prompting
- decompression and 32-bit/color conversion helpers
- XISF import redirection through `XisfImageConverter`
- batch mono conversion and batch stretch
- plate solving through `PlateSolveService`
- standard detection through `StandardDetectionPipelineService`
- iterative detection through `IterativeDetectionPipelineService`
- final report handoff
- application config load/save

Design note:

- `ImageProcessing.getInstance(directory)` binds processing state to the active imported directory.
- For new backend behavior, prefer a focused helper/service and delegate from `ImageProcessing`.

### `StandardDetectionPipelineService`

Standard JTransient run orchestrator.

Responsibilities:

- load validated FITS frames into JTransient `ImageFrame` objects
- preserve raw frame arrays needed by report export
- create an effective config through `DetectionPipelineSupport`
- call `JTransientEngine.runPipeline(...)`
- suppress late-phase outputs when too few frames survive quality control
- optionally run the UI detection-count safety prompt before report generation
- export the standard report through `DetectionReportGenerator`

### `IterativeDetectionPipelineService`

Iterative large-dataset/slow-target workflow orchestrator.

Responsibilities:

- create an iterative output directory
- sample frames by timestamp when available, otherwise by sequence position
- generate one shared master stack from globally spaced frames
- run multiple `JTransientEngine.runPipeline(..., providedMasterStack)` passes
- export a report for each pass
- write an iterative index report linking the pass reports

### `DefaultSpacePixelsPipelineApi`

Public headless pipeline facade.

Responsibilities:

- optionally normalize input through `DetectionInputPreparation`
- validate metadata with the headless import path
- optionally run SpacePixels Auto-Tune candidate-pool selection plus JTransient Auto-Tune
- run the standard pipeline without Swing
- optionally export the HTML report
- return original/prepared directories, configs, metadata, raw `PipelineResult`, telemetry, and report paths

Design constraint:

- Auto-Tune-enabled API calls should be serialized in a single JVM because the implementation temporarily adjusts shared JTransient Auto-Tune sample-size state.

### `DetectionReportGenerator`

Report export facade.

Responsibilities:

- snapshot current visualization preferences into `ExportVisualizationSettings`
- generate PNG/GIF assets
- build global maps, masks, crops, shape maps, and diagnostic imagery
- coordinate section writers
- generate standard `detection_report.html`
- generate iterative index reports

Consumes:

- JTransient `PipelineResult`
- raw frame arrays or on-demand frame access
- `FitsFileInformation[]`
- effective `DetectionConfig`
- `AppConfig`

### `DetectionReportAstrometry`

Report-side astrometry facade.

Responsibilities:

- derive report astrometry context from FITS metadata and app config
- transform pixel detections into sky coordinates when WCS exists
- build JPL/SatChecker/Sky Viewer links
- generate report-ready identification markup

### `ReportLookupProxyServer`

Optional localhost helper for live report enrichment.

Responsibilities:

- listen on loopback port `47831`
- proxy trusted JPL and SatChecker lookup requests for static HTML reports
- normalize and cache JSON lookup responses
- persist lookup responses back into report-side cache data

The report remains a static disk artifact; this server only supports live enrichment when the desktop app is running.

## 5. Runtime Flows

### Desktop Import Flow

```text
ApplicationWindow
-> FitsImportTask
-> XisfImageConverter.prepareDirectoryForFitsImport(...)
-> ImageProcessing.getInstance(importDirectory)
-> ImageProcessing.getFitsfileInformation()
-> FitsImportFinishedEvent
-> ApplicationWindow enables tabs and refreshes UI
```

GUI import may prompt for:

- compressed FITS decompression
- XISF-to-FITS conversion
- 32-bit-to-16-bit standardization
- color-to-mono conversion through the main UI

The GUI path is interactive and may redirect the active import directory to a generated working directory.

### Headless/API Input Flow

```text
DefaultSpacePixelsPipelineApi
-> DetectionInputPreparation.prepareInputDirectory(...)
-> ImageProcessing.getInstance(preparedDirectory)
-> ImageProcessing.getFitsfileInformationHeadless()
```

`FAIL_IF_NOT_READY` keeps the original directory and requires detection-ready files.

`AUTO_PREPARE_TO_16BIT_MONO` can create a new detection-ready FITS directory from supported FITS or XISF inputs.

### Standard Detection Flow

```text
GUI/API/CLI
-> ImageProcessing.runDetectionPipeline(...) or detectObjects(...)
-> StandardDetectionPipelineService
-> DetectionPipelineSupport.createEffectiveDetectionConfig(...)
-> JTransientEngine.runPipeline(...)
-> PipelineResult
-> DetectionReportGenerator
```

The effective config is a clone of the caller config. JTransient builds the slow-mover maximum stack from quality-filtered frames.

### Auto-Tune Flow

```text
DetectionConfigurationPanel or DefaultSpacePixelsPipelineApi
-> AutoTuneCandidatePoolBuilder
-> JTransientAutoTuner.tune(...)
-> AutoTunerResult.optimizedConfig
-> standard pipeline effective config
```

SpacePixels owns candidate-pool preparation:

- selected GUI frames when enough are selected
- otherwise the full imported sequence
- for large sets, a deterministic mix of best-quality, median-quality, and evenly spaced frames

JTransient owns the actual tuning search and validation.

### Iterative Detection Flow

```text
MainApplicationPanel
-> IterativeDetectionTask
-> ImageProcessing.detectSlowObjectsIterative(...)
-> IterativeDetectionPipelineService
-> JTransientEngine.generateMasterStack(...)
-> repeated JTransientEngine.runPipeline(..., providedMasterStack)
-> per-pass reports
-> DetectionReportGenerator.exportIterativeIndexReport(...)
```

This flow trades one full-baseline run for several temporally spaced passes and reuses a shared master stack.

### Plate Solving and WCS Flow

```text
MainApplicationPanel
-> PlateSolveTask
-> ImageProcessing.solve(...)
-> PlateSolveService
-> JPlateSolve
-> ImageProcessing.updateFitsHeaderWithWCS(...) / applyWCSHeader(...)
-> WCS-aware viewers and reports
```

Report export later resolves WCS through:

```text
DetectionReportGenerator
-> DetectionReportAstrometry
-> WcsSolutionResolver
-> WcsCoordinateTransformer
```

### Inspection and Preview Flow

Detection sequence viewer:

```text
MainApplicationPanel
-> DetectionSequenceFrame
-> DetectionTask in quick-detection mode
-> JTransient SourceExtractor.extractSources(...)
-> annotated image displayed in DetectionSequenceFrame
```

Detection settings preview:

```text
DetectionConfigurationPanel
-> TuningPreviewManager
-> TuningPreviewTask
-> JTransient SourceExtractor.extractSources(...)
-> annotated preview image
```

Manual transient inspection:

```text
MainApplicationPanel
-> ManualTransientInspectionTask
-> ImageProcessing
-> JTransient transient filtering path
-> TransientInspectionFrame
```

### Report Export Flow

```text
PipelineResult + raw frames + metadata + config + app config
-> DetectionReportGenerator
-> DetectionReportContext / DetectionReportSummary
-> section writers
-> renderers and GIF writer
-> detection_report.html plus assets
```

Report categories are driven by JTransient result data:

- moving-object tracks
- streak tracks
- suspected same-frame streak tracks
- anomalies
- slow-mover candidates
- residual local rescue candidates
- residual local activity clusters
- diagnostic transients, masks, drift, and telemetry

## 6. Main Data Contracts

### Input metadata

`FitsFileInformation` is SpacePixels' per-file metadata record. It carries:

- file path and display name
- image format and color-space facts
- dimensions and bit-depth facts
- observation timestamp and diagnostics
- exposure duration
- location and solved state metadata

This is the bridge between FITS import, GUI table display, JTransient `ImageFrame` construction, WCS/report logic, and API results.

### Detection configuration

JTransient `DetectionConfig` is the engine configuration contract.

SpacePixels wraps it in `SpacePixelsDetectionProfile` to add:

- `autoTuneMaxCandidateFrames`

Visualization preferences are separate and must not be treated as detection inputs.

### Pipeline result

JTransient `PipelineResult` is the raw detection result contract.

SpacePixels uses it for:

- report generation
- detection-count safety summaries
- API return values
- iterative pass summaries

SpacePixels should not reinterpret `PipelineResult` by duplicating engine logic. It should aggregate, render, and expose it.

### Effective config

Pipeline runs return or export the effective config actually used. This may differ from the base config because:

- Auto-Tune may produce an optimized clone.
- SpacePixels may apply runtime-safe adjustments before engine execution.

## 7. Persistence and Outputs

User-level JSON files under `user.home`:

- `spacepixels_app.json`
  - ASTAP path and observer/astrometry metadata
- `spacepixels_detection_profile.json`
  - JTransient `DetectionConfig` plus `autoTuneMaxCandidateFrames`
- `spacepixels_jtransient.json`
  - legacy detection-profile filename read for compatibility
- `spacepixels_visualization.json`
  - report and visualization preferences

Generated working directories:

- XISF conversion output
- decompressed FITS output
- prepared mono-16 FITS output for API auto-preparation
- 32-bit-to-16-bit conversion output
- mono conversion output
- stretch output

Detection report output:

- `detections_YYYYMMDD_HHMMSS/detection_report.html`
- image and GIF assets beside the report
- iterative root directory containing per-pass report directories plus `index.html`

## 8. Threading and Progress Model

Desktop:

- long work runs in explicit background `Thread`s around task `Runnable`s
- tasks post EventBus messages back to the UI
- Swing updates are scheduled back onto the EDT by subscribers where required

Headless/API:

- progress is reported through `SpacePixelsProgressListener`
- pipeline progress is scaled across preparation, Auto-Tune, engine execution, and report export phases

JTransient:

- the engine performs parallel extraction internally
- SpacePixels treats engine lifecycle as run/shutdown scoped per pipeline pass

## 9. Design Constraints

- Keep `eu.startales.spacepixels.api` small and stable.
- Treat non-API packages as internal implementation details.
- Keep JTransient algorithm details in JTransient; do not fork detector behavior into SpacePixels.
- Keep report generation as rendering and aggregation over `PipelineResult`.
- Prefer focused services behind `ImageProcessing` for new backend capabilities.
- Keep detection config and visualization preferences separate.
- Keep GUI-only prompting out of headless/API paths.
- Do not add concurrent Auto-Tune API execution without removing the shared JTransient sample-size mutation.

## 10. Current Design Debt and Refactor Boundaries

Accepted current debt:

- `ImageProcessing` is still broad and stateful.
- `DetectionConfigurationPanel` and `MainApplicationPanel` still combine Swing view logic and workflow control.
- `DetectionReportGenerator` still contains substantial static rendering/helper behavior even though major sections have been split into writers.
- Some visualization settings are static runtime values captured at report-export time.
- GUI tasks launch raw threads instead of using a centralized executor abstraction.

Preferred refactor direction:

- extract behavior behind existing facades first
- avoid changing user workflows while moving internals
- preserve `SpacePixelsPipelineApi` request/result semantics
- preserve report output shape unless the change is explicitly user-facing
- update `MANUAL.md`, `API.md`, and this document when architectural behavior changes

Use this document for:

- stable facade inventory
- subsystem ownership
- runtime flow reference
- integration boundaries

Use `REFACTOR.md` for:

- current refactor priorities
- extraction order
- migration constraints
- verification expectations
