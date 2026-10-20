# High-Level Design

Reviewed against the source tree: 2026-10-19. This document describes implemented behavior; future work is tracked separately in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md).

Purpose:
- Describe the current SpacePixels architecture at subsystem and facade level.
- Record the main runtime flows for GUI, API, CLI, reporting, and plate solving.
- Keep stable design context separate from user-facing behavior in `MANUAL.md` and proposed work in `DESIGN-IMPROVEMENTS.md`.

Related documents:
- User workflows and operational behavior: [MANUAL.md](MANUAL.md)
- Public embedding surface: [API.md](API.md)
- Improvement status, sequencing and acceptance criteria: [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md)
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
  - GUI event bus, being retired job by job (DESIGN-IMPROVEMENTS S9)
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
- `NegativeValueHandling`

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
- `SpacePixelsSettingsFiles` (Save on the detection settings tab)
- `DetectionSettingsRules` (finite numbers and the ordered pairs of settings; checked by the detection settings tab, not yet by profile loading or the API)

The detection profile wraps JTransient `DetectionConfig` plus SpacePixels' Auto-Tune candidate-frame limit. Visualization preferences are intentionally separate because they do not affect detection results.

### `eu.startales.spacepixels.events`

Small EventBus message types for GUI/task coordination.

These events carry progress, lifecycle, and result notifications across background tasks and Swing panels. They are not part of the public API. Jobs that move to the job coordinator lose their events. Only import and Blink still use events.

### `eu.startales.spacepixels.jobs`

The job coordinator, with no Swing or `gui` dependency. A `Job` runs on a worker and returns a `JobOutcome`: completed with its result, declined by the user, or failed with a reason.

`JobCoordinator` behaves as follows:
- **Admission:** it admits one job at a time, on the UI thread, before the worker starts. It refuses a job while another runs, or while a task still on the event bus holds the window.
- **The window's lock:** its listener, in `MainApplicationPanel`, locks the window and shows the progress dialog on admission, and unlocks the window when the job ends.
- **Progress:** it reaches the window only while the job is current.
- **Outcome:** it is delivered exactly once, on the UI thread, after the unlock.
- **Failures:** an exception or `Error` becomes a failure. Running out of memory leaves the worker for the application's uncaught-exception handler (`JobFailures`).

Each `JobHandle` carries the session the job was started for. Until there is a session object, that is the `ImageProcessing` instance.

### `eu.startales.spacepixels.gui`

Swing UI shell, panels, dialogs, viewers, and UI-side workflow control.

Main classes:

- `ApplicationWindow`
- `MainApplicationPanel`
- `ConfigurationPanel`
- `StretchPanel` (display stretch, remembered in Java preferences; whole-frame preview with `ZoomableImageView` and a histogram)
- `StretchPreviewRenderer` (renders the `StretchPanel` previews one request at a time and shows a result only while its request is the newest; a change of frame or stretch, hiding the tab or another import makes a render under way stale)
- `DetectionConfigurationPanel`
- `AutoTuneOverviewPanel`
- `SettingsNavigator`, `SettingRow`
- `CheckedNumberEditor` (the editor of every number setting: only text that is one complete number within the setting's range becomes its value; other text stays in the field, outlined. It shows every decimal of its value, so committing the shown text never rounds it)
- `WrapLayout`
- `WindowPlacement`
- `StarMaskExplorerFrame`
- `DetectionSequenceFrame`
- `TransientInspectionFrame`
- `TuningPreviewManager`
- `ProcessingProgressDialog`

The GUI layer currently combines view and controller behavior in several panels. That is an accepted current state and a refactor boundary.

### `eu.startales.spacepixels.tasks`

Background runnables launched by the GUI.

Tasks are the boundary between Swing actions and longer-running work. They run in one of two ways:
- **Jobs under the `JobCoordinator`:** `DetectionTask`, `IterativeDetectionTask`, `ManualTransientInspectionTask`, `StarMaskStackTask`, `BatchConvertMonoTask`, `BatchStretchTask`, `PlateSolveTask` and `AutoTuneTask`. They return their result, and the window opens what follows (the report question, the inspection view, the Star Mask Explorer, the offer to import the mono folder, the offer to write the WCS solution, the tuned settings on the Overview).
- **Through the Guava `EventBus`:** the others post lifecycle and progress events to the GUI.

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
- `StarMaskStackTask`
- `GeneratePreviewsTask`, which renders the Image Stretch previews of one frame and returns them to `StretchPreviewRenderer`; full-size sequence viewers load frames through `SequenceViewerFrame` and `BlinkSequence`

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
- `AutoTunerRunner`
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
- `StagedFile` (safe replacement of a file; see section 7)

`ImageProcessing` is still the main internal backend facade. New work should prefer extracting focused services behind it instead of expanding it further.

### `eu.startales.spacepixels.util.reporting`

Report export, report-side rendering, WCS-aware report context, and live report lookup support.

Main facade:

- `DetectionReportGenerator`

Supporting groups:

- document and model:
  - `DetectionReportContext`
  - `DetectionReportSummary`
  - `ReportData` (stable finding identities and JSON/CSV exports)
  - `AtomicReportFile`
  - `ExportVisualizationSettings`
  - `DetectionReportDocumentWriter`
  - `ReportClientScriptWriter`
- section writers:
  - `SessionPictureSectionWriter` (median stack, data area, detections and catalogue layers)
  - `NightEquipmentSectionWriter`
  - `PipelineDiagnosticsSectionWriter`
  - `TargetVisualizationSectionWriter`
  - `DeepStackReportSectionWriter`
  - `ResidualReviewSectionWriter` (local rescue and activity clusters with the results; camera-fixed and common-motion groups in the collapsed Likely Artifacts group)
  - `GlobalMapsSectionWriter`
  - `PhotometryReportSectionWriter` (variable-star photometry: readiness, noise model, per-frame diagnostics, candidate light curves, CSV exports, VSX lookup buttons)
  - `CatalogueComparisonWriter` and the embedded `catalogue-comparison.js` (read-only catalogue comparison notes for saved/live matches, separate from detection confidence; see [catalogue comparison technical note](docs/features/catalogue-comparison.md))
- rendering helpers:
  - `TrackVisualizationRenderer`
  - `PhotometrySvgChart`
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

- main Swing frame and tab composition (resizable; size and position from `WindowPlacement`: screen-based default, restored from Java preferences, saved on close)
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
- launch import (the Import Aligned Frames… button in the Main tab or a folder dropped on the window; there is no menu bar), and show the "new version" notice in the Main tab's status bar
- enable tabs after successful import
- listen for import and progress events
- stop the report lookup proxy on window close

### `MainApplicationPanel`

Primary GUI workflow facade.

Owns:

- a workflow strip of four groups (1 Prepare, 2 Astrometry, 3 Inspect, 4 Detect) laid out by `WrapLayout`; each group has a status line
- FITS metadata table, with a right-click menu for the frame actions
- plate solve controls
- blink controls
- batch conversion and stretch controls
- single-frame preview and manual transient inspection actions
- standard and iterative detection launch actions
- tooltips that explain why a button is disabled, and keyboard shortcuts
- progress state and modal progress dialog

Launches most GUI background tasks.

### `DetectionConfigurationPanel`

Detection settings, Auto-Tune, preview, and persistence facade.

Owns:

- UI binding for JTransient `DetectionConfig`
- `SpacePixelsDetectionProfile` load/save
- `SpacePixelsVisualizationPreferences` load/save
- current Auto-Tune candidate-frame limit
- the Overview page (`AutoTuneOverviewPanel`) and the page navigation (`SettingsNavigator`)
- the saved value of every setting row (`SettingRow`) and of the Overview core settings, taken after loading and after each Save; changes are marked against it
- detection preview action through `TuningPreviewManager`
- Auto-Tune launch through `AutoTuneTask`
- applying every edit to the session right away, the unsaved-changes indicator, and Revert to the last saved state
- keeping the pairs of `DetectionSettingsRules` in order while applying an edit, and saying in the footer which setting it moved
- `checkedSettings(...)`, the one step behind Save and every run: Detect Moving Targets, Detect Iteratively, Manual Transient Inspection, Preview Frame, Auto-Tune and Test Star Mask

`checkedSettings` returns a copy of the session `DetectionConfig` (hidden settings included), so editing the tab while a run works cannot change that run. It returns null instead, and nothing starts, while a field holds invalid text or the settings break a rule (for example a hidden setting loaded as NaN). The tab then comes forward, the footer names the action that did not start and what to enter, and the cursor goes into the field; `SettingsNavigator.reveal` shows a setting hidden by the filters or the expert switch with a search for its title. A setting whose feature is switched off is hidden and unused, so its text does not stop a run.

Design notes:

- detection fields and visualization fields are intentionally stored in separate JSON files even though the same panel edits both.
- the settings spinners stay owned by `DetectionConfigurationPanel`; `AutoTuneOverviewPanel` only lays out the core ones and talks back through a small `Host` interface.
- the visualization settings are still static runtime values, applied with each edit and read when the report is written; they are not part of the run's copy.

### `AutoTuneOverviewPanel`

The Overview page of Detection Settings.

Owns:

- the tuner and profile choice and the run button
- the profile table, built from `AutoTunerResult.calibration` (one calibrated run covers all profiles; `CalibratedAutoTuner.configFor(...)` applies a chosen row)
- the expected false detections for the imported session (measured rate × megapixels × frames)
- the eight core fields: detection sigma/grow/min pixels, master sigma/grow/min pixels, mask overlap and jitter; the calibrated tuner supplies all eight, while the legacy tuner supplies only the three per-frame values, overlap and jitter
- markers showing which displayed values still equal the applied tune; applying a legacy result preserves the current master settings
- the settings summary shown in the main window's Detect group

A tune result belongs to the imported session and is cleared when another dataset is imported.

### `StarMaskExplorerFrame`

The Star Mask Explorer, opened from the Overview.

- `StarMaskStackTask` loads the frames (with `SessionFrameLoader`, shared with Manual Transient Inspection) and calls `JTransientEngine.generateMasterStackWithDetails(...)`, so the stack comes from the pipeline's own frame selection.
- `DetectionConfigurationPanel` keeps the stack for the session. The key is the imported files plus every setting except the star mask values.
- For trial values the frame calls `MasterReferenceAnalyzer.analyzeFromMasterStack(...)` and `MasterVetoMask.build(...)`, the same code as the pipeline, in a background worker. It shows the mask as a one-bit overlay on `ZoomableImageView`.

### `SettingsNavigator` and `SettingRow`

`SettingsNavigator` replaces the settings tabs with:

- a grouped page list and the pages themselves (a `CardLayout`);
- a search box and a "show only unsaved changes" filter that work across all pages;
- a "Show expert settings" switch. Expert sections are hidden while it is off, but search and the unsaved-changes filter still find them, and each page says how many expert settings it hides.

Expert sections are found from the header labels that `DetectionConfigurationPanel` marks with a client property.

`SettingRow` is one setting on a page: its input, its saved value and a `Reset` button. It marks itself when its value differs from the saved configuration and tells the navigator to refresh the filters and counts.

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

- API calls with the legacy auto-tuner should be serialized in a single JVM because it temporarily adjusts shared JTransient Auto-Tune sample-size state. The calibrated tuner (the default) has no shared state.

### `DetectionReportGenerator`

Report export facade.

Responsibilities:

- snapshot current visualization preferences into `ExportVisualizationSettings`
- generate PNG/GIF assets
- build global maps, masks, crops, shape maps, and diagnostic imagery
- coordinate section writers
- generate standard `detection_report.html`
- generate iterative index reports
- build `ReportData`: report-local finding IDs/anchors, included variables and common-motion groups, and one shared quality-filtered frame selection
- export versioned `report-data.json` without input paths or observing-site coordinates
- publish HTML, JSON and CSV text artifacts through `AtomicReportFile` (built on `StagedFile`), checking write errors before replacing final files
- emit testable `report-lookups.js` and `report-interactions.js` resources; GIF exports include PNG posters for pause/reduced-motion controls

`ReportData` describes report presentation; it never changes the JTransient result. `DetectionReportContext` loads its catalogue once per export. `ExportVisualizationSettings` applies throughout image rendering. Long moving/streak GIFs stream images one at a time. Motion/lookup calculations use exposure midpoint times, while observing-span duration remains a separate metric. Detection-quality rejections are shared by all inspection animations.

The HTML places the results first. After the variable stars, a collapsed Likely Artifacts group holds the camera-fixed groups and then the common-motion groups, in pipeline order; local rescue candidates stay with the results. Diagnostics also starts collapsed, and links into a collapsed group open it. The HTML links maps to object cards, separates measured variable candidates from catalogue layers, and exposes navigation, filters and animation controls. Photometry exports append report labels and sky/catalogue identification columns. Lookup responses are validated before normalization; only successful responses are cached, refresh bypasses saved results, and save failures remain visible.

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
- proxy trusted JPL, SatChecker and AAVSO VSX (through the CDS VizieR TAP service) lookup requests for static HTML reports
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
- signed/scaled 16-bit or 32-bit to unsigned-16-bit standardization
- treatment of negative converted pixels: one sequence-wide offset (recommended) or clipping to zero
- importing the largest exposure group into a working subdirectory when exposure times differ
- color-to-mono conversion through the main UI

`FitsSequenceValidator` and `XisfImageConverter.validateSequence` check headers before conversion: uniform dimensions, channel layout/sample type, all-or-none usable timestamps, and one exposure group. FITS also compares `BZERO`/`BSCALE`. Exposure durations may differ within 2% (at least 0.1 s); missing durations form a separate group. The GUI can copy the largest exposure group to a working subdirectory; headless runs reject the mixed sequence.

The engine receives unsigned-16-bit-equivalent pixels (`BZERO` 32768, `BSCALE` 1), represented in signed Java shorts. A conversion offset is shared across frames and recorded in `SPOFFSET`, while row-end zero padding stays zero. The GUI path is interactive and may redirect the active import directory to a generated working directory.

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
DetectionConfigurationPanel (AutoTuneTask), DefaultSpacePixelsPipelineApi or BatchDetectionCli
-> AutoTuneCandidatePoolBuilder
-> AutoTunerRunner.run(..., profile, algorithm, ...)
   -> CalibratedAutoTuner.tune(...)   (CALIBRATED, the default)
   -> JTransientAutoTuner.tune(...)   (LEGACY)
-> AutoTunerResult.optimizedConfig, summary, telemetryReport
-> standard pipeline effective config
```

SpacePixels owns candidate-pool preparation:

- selected GUI frames when enough are selected
- otherwise the full imported sequence
- for large sets, a deterministic mix of best-quality, median-quality, and evenly spaced frames

JTransient owns the actual tuning search and validation. `AutoTunerRunner` only selects the tuner (the `Tuner` box in the GUI, `--tuner` in the CLI, `autoTuneAlgorithm(...)` in the API) and the profile (`LOW`, `MEDIUM`, `HIGH`, `MAXIMUM`; the earlier names remain parsing aliases).

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

Writing a solution into a frame changes the user's own file. `PlateSolveService` writes the updated frame to a temporary file beside it, reads it back to check the image size and that all of its data is present, and only then replaces the original in one step. A failure leaves the original unchanged.

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
-> DetectionSequenceFrame (a SequenceViewerFrame; prepares one frame at a time on a SwingWorker)
-> JTransient SourceExtractor.extractSources(...)
-> detection markers drawn by FrameView
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
-> SessionFrameLoader (shared with the Star Mask Explorer)
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
- anomalies
- slow-mover candidates
- camera-fixed groups (`FC`), removed from point detections and `allTransients` before linking
- variable-star candidates and photometric readiness
- common-motion groups, filtered from unclassified observations before optional residual analysis
- residual local rescue candidates
- residual local activity clusters
- diagnostic transients, masks, drift, and telemetry

Same-frame groups of rescued anomalies are offered to confirmed-streak consolidation; an unmerged group remains separate anomalies. There is no suspected-streak result category.

`AnomalySectionWriter` presents these as unconfirmed single-frame events: exposure timing and coordinates, plain-language detection reasons, a static before/event/after comparison and a shareable GIF. Both views use the same event-derived display stretch and nearest kept neighbours; labels sit outside the sky crops. Raw measurements, footprints and mask overlap are collapsed under Detection details. Anomaly SatChecker queries use the event position and the whole exposure, with metadata fallback from the FITS frame; they do not use the shortened streak-query window. They reuse the report's live lookup and cache/sidecar flow and present satellite results as possible associations without changing detection classifications.

An anomaly is marked as likely part of a moving-object track when all of these hold:

- The track has no detection in the anomaly's frame, and that frame lies within the track's frames or just before or after them.
- The track's predicted position lies within `predictionTolerance` of the anomaly. The prediction interpolates between the neighbouring detections at the exposure midpoints, or uses frame order without timestamps. Next to the track it extends the first or last step.
- The anomaly passes the linker's profile check (`maxFwhmRatio`, `maxSurfaceBrightnessRatio`) against the track's nearest detection.

The card gets a title badge and a note, both linking to the track card. `DeepStackReportSectionWriter` marks a slow-mover candidate the same way when the detections of a reported track lie on its smear. These marks change only the presentation: the findings keep their categories, numbers and JSON entries.

Camera-fixed filtering (`enableCameraFixedRejection`) uses registrations inferred from fill edges. At least three frames must map detections within 2 px of the same camera position while their registered-frame positions spread at least 4 px. Unusable registrations are excluded. `ReportInclusion.cameraFixed` controls presentation, independently of the engine filter.

Common-motion filtering has its own switch and runs before local rescue and activity clustering. It assigns each observation once, preserves ambiguous associations for review, and removes accepted groups from the residual input while retaining them in the report. The residual-analysis switch controls only local rescue and clustering.

`PipelineTelemetry.stageTimeMs` provides ordered, non-overlapping major-phase wall-clock durations, and `processingTimeMs` includes the full engine run through photometry. `TrackerTelemetry.faintStreakFragmentTimeMs` is a substep already included in tracking (-1 when not run). `PipelineDiagnosticsSectionWriter` displays these durations under Diagnostics. `PhotometryTelemetry.framesExcludedSpatial` supplies the spatial-exclusion summary and diagnostic count. Engine timing excludes application image loading, Auto-Tune and report export.

### Sky catalogue and plate correction

- `SkyCatalogueClient` queries SIMBAD, VSX and Gaia for the solved field. `SkyCatalogueStore` persists results in `sky_catalogue.json`; restore requires a matching field. Catalogue errors are retained as problems, and fetching runs off the EDT with its own cancellation/progress UI.
- `SkyCatalogueModel.shared()` currently shares catalogue/correction state between viewers. `SkyAnnotations` controls annotations and layers without fetching data. `FrameView` clips and thins marks to manage density.
- `WcsCoordinateTransformer` handles TAN-SIP distortion. `PlateCorrectionFitter` matches isolated Gaia/extracted stars, fits a global displacement of order 1–3 plus per-frame terms, and uses held-out frames to choose the lowest order within 3% of the best. It skips already-good header solutions (90% within 0.5 px) or solutions already within 3% of the best fit.
- A correction is saved in `plate_correction.json`, starts disabled, and retains its enabled state on refit. `WcsSolutionResolver.resolve` applies only a compatible correction with the same header-solution identity and dimensions. A concrete frame uses its per-frame correction; report-wide resolution uses the global part.
- `SessionCatalogue` supplies field-matched report context. Saved and live VSX matches enrich reports without changing engine confidence. The detailed comparison rules, provenance exceptions and validation are in [catalogue comparison](docs/features/catalogue-comparison.md).

### Shared frame viewers

Blink, Preview Frame, Manual Transient Inspection and Show full size share `FrameView`, `StretchControls` and `ViewerSupport`. `SequenceViewerFrame` prepares one frame at a time on a `SwingWorker`, rejects obsolete worker results, and releases listeners on disposal. `BlinkSequence` holds 16-bit frames, histograms and half-size display copies. Stretch controls share their models with the main stretch panel and must detach on disposal. Detection overlays use the last standard-run results, including variable-star positions when available.

The shared **Stretch display** button model selects stretched or full-range linear display, defaults to on, and is saved as `stretch.enabled` in the existing Java preferences node. Off disables the stretch controls while retaining their values; viewers use `DisplayStretch.linearLookupTable`, preserving RGB channels even when Extreme is selected. Preview requests capture this flag so obsolete stretched results cannot replace a newer linear request. **Enable batch stretch** remains a separate disk-output option and uses the chosen algorithm/parameters independently of the display flag.

Settings rows apply valid edits to the loaded config in place so hidden fields survive. Core fields live on Overview; basic fields are visible by default; expert sections disclose through the navigator, search or unsaved-changes filter. Strictness presets set the five frame-outlier limits together and show Custom for other combinations. Unknown JSON keys are ignored by Gson, and key-set differences trigger the backed-up migration offer. No edge/void fields remain; single-frame preview, tuning-pool and correction extraction use `DataArea.ofFrame`.

### Report diagnostics

`DataAreaPresentation` draws the kept-frame intersection on the Overview picture and explains where sky/star measurements are made. `StarShape`, `NightEquipment` and `FieldDepth` measure diagnostics during export; these calculations currently sit in the reporting layer.

`NightEquipment` measures isolated stars across kept frames for seeing, relative transparency, field shape/brightness maps, and sky estimates. It accounts for `SPOFFSET`, uses PIERSIDE to orient minority-side frames, and requires catalogue/linearity support for calibrated sky estimates. Optical interpretations remain diagnostic hints. `FieldDepth` compares Gaia stars with detections in catalogue depth patches, using the run's thresholds and cut-out data areas; crowded or inadequately recovered patches are excluded. These measurements do not change engine detections.

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
- effective settings in `detection_config.json`, stable finding data in `report-data.json` and CSV exports
- session catalogue/correction files (`sky_catalogue.json`, `plate_correction.json`) in the imported dataset folder, guarded by field and plate-solution identity
- iterative root directory containing per-pass report directories plus `index.html`

Replacing files:

- The settings files, the session files, report text files, plate-solved frames and the converted FITS copies are written through `util.StagedFile`. (Report images, which go into a new folder per run, and plate-solver downloads are written directly.) The new contents are written to a temporary file in the same folder (`.<name>.<number>.tmp`) and moved over the target in one step only once complete. The previous file is never deleted first, and a failed write or move removes the temporary file and leaves the previous file as it was.
- Without an atomic move (not expected for a file in the same folder), the previous file is renamed aside and put back if the new one cannot take its place.
- Save on the detection settings tab writes both settings files completely before replacing either. The two files are independent, so if the second replacement fails the new profile stays saved; the error names the file that was not saved, and the tab keeps showing unsaved changes.

## 8. Threading and Progress Model

Desktop:

- **All long jobs except import and Blink** run under `JobCoordinator`:
  - admission and locking happen on the EDT before the worker starts;
  - progress and the single outcome come back on the EDT.
- **Other long work** runs in explicit background `Thread`s around task `Runnable`s that post EventBus messages. Their subscribers move Swing updates onto the EDT.

Headless/API:

- progress is reported through `SpacePixelsProgressListener`
- pipeline progress is scaled across preparation, Auto-Tune, engine execution, and report export phases, and only moves forward

Progress ranges:

- JTransient `runPipeline(...)` reports 0-100%: extraction up to 40%, frame rejection 42%, master stack 45-49%, track linking 50-90%, common-motion filtering and optional residual analysis 92%, photometry 93-99%
- the standard pipeline shows frame loading at 0-20% and the engine at 20-100% of the detection run; the GUI shows the detection run as 0-90% and report export as 90-100%
- the API shows input preparation up to 15%, Auto-Tune 15-35%, the detection run up to 90% and report export 90-100%
- the iterative pipeline gives each pass an equal share; within a pass the engine takes 0-90% and report generation 95%

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
- Replace files through `StagedFile`; never delete a user's file before its replacement is complete.
- Start every GUI run and save from `DetectionConfigurationPanel.checkedSettings(...)`; never hand a task the panel's live `DetectionConfig`.
- Do not add concurrent Auto-Tune API execution without removing the shared JTransient sample-size mutation.

## 10. Current Design Debt and Refactor Boundaries

Accepted current debt:

- `ImageProcessing` is still broad and stateful.
- `DetectionConfigurationPanel` and `MainApplicationPanel` still combine Swing view logic and workflow control.
- `DetectionReportGenerator` still contains substantial static rendering/helper behavior even though major sections have been split into writers.
- Some visualization settings are static runtime values captured at report-export time.
- GUI tasks still on the event bus launch raw threads; all other long jobs run under the job coordinator.

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

Use [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md) for:

- current refactor priorities
- extraction order
- migration constraints
- verification expectations
