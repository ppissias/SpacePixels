# High-Level Design

Snapshot date: 2026-04-26

Purpose:
- Capture the current stable application structure at a facade and subsystem level
- Describe how the main flows move through the codebase today
- Keep architectural context separate from the tactical work tracked in `REFACTOR.md`

Related document:
- Refactor sequencing and migration notes live in [REFACTOR.md](REFACTOR.md)

## Package-level boundaries

- `eu.startales.spacepixels.gui`
  - Swing application shell, panels, dialogs, and top-level user workflows
- `eu.startales.spacepixels.tasks`
  - Background task runners launched by the GUI
- `eu.startales.spacepixels.util`
  - Processing facade plus FITS, WCS, visualization, and pipeline support services
- `eu.startales.spacepixels.util.reporting`
  - Detection report export subsystem, report section writers, and astrometry/report lookup support
- `eu.startales.spacepixels.api`
  - Headless/public pipeline API for non-Swing callers

## Main facades

### 1. `ApplicationWindow`

Role:
- Desktop application shell and top-level composition root for the Swing application

Owns or coordinates:
- `MainApplicationPanel`
- `DetectionConfigurationPanel`
- `ConfigurationPanel`
- `StretchPanel`
- `BlinkFrame`
- `ImageProcessing`
- `ReportLookupProxyServer`
- Guava `EventBus`

### 2. `MainApplicationPanel`

Role:
- Main desktop workflow facade for import, solve, preview, blink, and detection actions

Depends on:
- `ApplicationWindow`
- `ImageProcessing`
- `DetectionConfigurationPanel`
- `StretchPanel`
- `ProcessingProgressDialog`
- `DetectionSequenceFrame`

Launches background tasks:
- `FitsImportTask`
- `BatchConvertMonoTask`
- `BlinkImagesTask`
- `ManualTransientInspectionTask`
- `DetectionTask`
- `IterativeDetectionTask`
- `PlateSolveTask`

### 3. `DetectionConfigurationPanel`

Role:
- Detection-settings facade for UI binding, persistence, preview, and auto-tune control

Depends on:
- `ApplicationWindow`
- `TuningPreviewManager`
- `DetectionConfig`
- `SpacePixelsDetectionProfile`
- `SpacePixelsDetectionProfileIO`
- `SpacePixelsVisualizationPreferences`
- `SpacePixelsVisualizationPreferencesIO`
- `AutoTuneTask`
- `JTransientAutoTuner`

Also controls runtime export settings through:
- `DetectionReportGenerator`

### 4. `ImageProcessing`

Role:
- Backend processing facade for FITS loading, conversion, preview/stretch, plate solving, and detection/report handoff

Coordinates these services:
- `StandardDetectionPipelineService`
- `IterativeDetectionPipelineService`
- `DetectionPipelineSupport`
- `PlateSolveService`
- `FitsVisualizationRenderer`

Relies on these supporting classes:
- `FitsPixelConverter`
- `FitsFileInformation`
- `FitsFormatChecker`
- `XisfImageConverter`
- `WcsCoordinateTransformer`
- `WcsSolutionResolver`
- `AppConfig`
- `SpacePixelsAppConfigIO`

Hands report export to:
- `DetectionReportGenerator`

### 5. `DetectionReportGenerator`

Role:
- Reporting facade that exports the HTML report and all companion visualization assets

Coordinates report models and document structure:
- `DetectionReportContext`
- `DetectionReportSummary`
- `ExportVisualizationSettings`
- `DetectionReportDocumentWriter`
- `ReportClientScriptWriter`

Coordinates section writers:
- `PipelineDiagnosticsSectionWriter`
- `TargetVisualizationSectionWriter`
- `DeepStackReportSectionWriter`
- `ResidualReviewSectionWriter`
- `GlobalMapsSectionWriter`

Uses rendering/export helpers:
- `TrackVisualizationRenderer`
- `TrackCropGeometry`
- `GifSequenceWriter`
- `CreativeTributeRenderer`
- `DisplayImageRenderer`

Depends on astrometry/report lookup support:
- `DetectionReportAstrometry`

Consumes upstream pipeline/domain models:
- `PipelineResult`
- `DetectionConfig`
- `FitsFileInformation`
- `SourceExtractor`
- `TrackLinker`
- `ResidualTransientAnalysis`
- `SlowMoverAnalysis`
- `SlowMoverCandidateResult`
- `SlowMoverSummaryTelemetry`
- `PipelineTelemetry`
- `TrackerTelemetry`

### 6. `DetectionReportAstrometry`

Role:
- Report-side astrometry facade for WCS-based sky identification, lookup query construction, and report-ready identification markup

Coordinates context and WCS support:
- `AstrometryContextFactory`
- `WcsSolutionResolver`
- `WcsCoordinateTransformer`

Coordinates query-target builders:
- `SolarSystemQueryTarget`
- `SolarSystemQueryTargetFactory`
- `SatCheckerQueryTargetFactory`

Coordinates report-facing HTML and URL builders:
- `AstrometryIdentificationHtmlBuilder`
- `JplSbIdentUrlBuilder`
- `SatCheckerUrlBuilder`
- `SkyViewerHtmlBuilder`

Consumes:
- `FitsFileInformation`
- `AppConfig`
- `SourceExtractor`
- `TrackLinker`

### 7. `DefaultSpacePixelsPipelineApi`

Role:
- Headless/public facade for running the SpacePixels pipeline without the Swing UI

Coordinates:
- `DetectionInputPreparation`
- `ImageProcessing`
- `AutoTuneCandidatePoolBuilder`
- `JTransientAutoTuner`

Consumes or returns:
- `SpacePixelsPipelineRequest`
- `SpacePixelsPipelineResult`
- `DetectionConfig`
- `FitsFileInformation`

## Main execution paths

### 1. Desktop standard detection flow

`ApplicationWindow`
-> `MainApplicationPanel`
-> `DetectionTask`
-> `ImageProcessing`
-> `StandardDetectionPipelineService`
-> `DetectionReportGenerator`

### 2. Desktop iterative detection flow

`ApplicationWindow`
-> `MainApplicationPanel`
-> `IterativeDetectionTask`
-> `ImageProcessing`
-> `IterativeDetectionPipelineService`
-> iterative report/index export

### 3. Headless/API flow

`DefaultSpacePixelsPipelineApi`
-> `DetectionInputPreparation`
-> `ImageProcessing`
-> `StandardDetectionPipelineService`
-> optional `DetectionReportGenerator`

### 4. Plate-solving and WCS flow

GUI or API caller
-> `ImageProcessing`
-> `PlateSolveService`
-> FITS WCS header update/cleanup
-> `DetectionReportAstrometry` consumes resolved WCS during report export

### 5. Report export flow

`ImageProcessing`
-> `DetectionReportGenerator`
-> `DetectionReportAstrometry`
-> report section writers
-> HTML document plus image/GIF assets

### 6. Detection-settings flow

`DetectionConfigurationPanel`
-> UI widgets
-> `DetectionConfig`
-> profile/preferences persistence
-> preview and auto-tune tasks
-> runtime export setting updates

## Current design notes

- `ImageProcessing` remains the main backend facade even though several responsibilities have already been extracted behind it.
- `DetectionReportGenerator` is now mostly an orchestration facade, while detailed report sections live in dedicated writer classes.
- `DetectionReportAstrometry` is still a concentrated boundary between WCS context, lookup strategy, and report-facing identification output.
- `DetectionConfigurationPanel` and `MainApplicationPanel` still combine view and controller behavior, which is why they remain refactor targets.
- `DefaultSpacePixelsPipelineApi` is the main non-GUI entry point and should stay aligned with the same backend services used by the desktop application.

## Refactor boundary

Use this document for:
- stable facade inventory
- subsystem boundaries
- runtime flow reference

Use `REFACTOR.md` for:
- current refactor priorities
- extraction order
- migration constraints
- verification expectations
